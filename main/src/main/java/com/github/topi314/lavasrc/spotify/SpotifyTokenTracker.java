package com.github.topi314.lavasrc.spotify;

import com.github.topi314.lavasrc.LavaSrcTools;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.message.BasicNameValuePair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SpotifyTokenTracker {
	private static final Logger log = LoggerFactory.getLogger(SpotifyTokenTracker.class);
	
	private static final String SPOTIFY_PARTNER_API_URL = "https://api-partner.spotify.com/pathfinder/v2/query";
	private static final String TOKEN_REFRESH_MARGIN_MS = "300000"; // 5 minutes in milliseconds
	
	// Query definitions for Partner API
	private static final Map<String, QueryDefinition> QUERIES = new HashMap<>();
	
	static {
		QUERIES.put("getTrack", new QueryDefinition(
			"getTrack",
			"612585ae06ba435ad26369870deaae23b5c8800a256cd8a57e08eddc25a37294"
		));
		QUERIES.put("getAlbum", new QueryDefinition(
			"getAlbum",
			"b9bfabef66ed756e5e13f68a942deb60bd4125ec1f1be8cc42769dc0259b4b10"
		));
		QUERIES.put("getPlaylist", new QueryDefinition(
			"fetchPlaylist",
			"bb67e0af06e8d6f52b531f97468ee4acd44cd0f82b988e15c2ea47b1148efc77"
		));
		QUERIES.put("getArtist", new QueryDefinition(
			"queryArtistOverview",
			"35648a112beb1794e39ab931365f6ae4a8d45e65396d641eeda94e4003d41497"
		));
		QUERIES.put("searchDesktop", new QueryDefinition(
			"searchDesktop",
			"fcad5a3e0d5af727fb76966f06971c19cfa2275e6ff7671196753e008611873c"
		));
	}

	private final SpotifySourceManager sourceManager;

	private String clientId;
	private String clientSecret;
	private String accessToken;
	private Instant expires;

	private String partnerToken;
	private Instant partnerTokenExpiry;

	private String spDc;
	private String accountAccessToken;
	private Instant accountAccessTokenExpire;

	public SpotifyTokenTracker(SpotifySourceManager source, String clientId, String clientSecret, String spDc) {
		this(source, clientId, clientSecret, spDc, null);
	}

	public SpotifyTokenTracker(SpotifySourceManager source, String clientId, String clientSecret, String spDc, String customTokenEndpoint) {
		this.sourceManager = source;
		this.clientId = clientId;
		this.clientSecret = clientSecret;

		if (!hasValidCredentials()) {
			log.debug("Missing/invalid credentials, will use partner token if available.");
		}

		this.spDc = spDc;

		if (!hasValidAccountCredentials()) {
			log.debug("Missing/invalid account credentials");
		}
	}

	public void setClientIDS(String clientId, String clientSecret) {
		this.clientId = clientId;
		this.clientSecret = clientSecret;
		this.accessToken = null;
		this.expires = null;
	}

	private boolean hasValidCredentials() {
		return clientId != null && !clientId.isEmpty() && clientSecret != null && !clientSecret.isEmpty();
	}

	public String getAccessToken(boolean preferPartnerToken) throws IOException {
		if (preferPartnerToken || !hasValidCredentials()) {
			return this.getPartnerToken();
		}
		if (this.accessToken == null || this.expires == null || this.expires.isBefore(Instant.now())) {
			synchronized (this) {
				if (accessToken == null || this.expires == null || this.expires.isBefore(Instant.now())) {
					log.debug("Access token is invalid or expired, refreshing token...");
					this.refreshAccessToken();
				}
			}
		}
		return this.accessToken;
	}

	private void refreshAccessToken() throws IOException {
		var request = new HttpPost("https://accounts.spotify.com/api/token");
		request.addHeader("Authorization", "Basic " + Base64.getEncoder().encodeToString((this.clientId + ":" + this.clientSecret).getBytes(StandardCharsets.UTF_8)));
		request.setEntity(new UrlEncodedFormEntity(List.of(new BasicNameValuePair("grant_type", "client_credentials")), StandardCharsets.UTF_8));

		var json = LavaSrcTools.fetchResponseAsJson(sourceManager.getHttpInterface(), request);
		if (json == null) {
			throw new RuntimeException("No response from Spotify API");
		}
		if (!json.get("error").isNull()) {
			var error = json.get("error").text();
			throw new RuntimeException("Error while fetching access token: " + error);
		}
		accessToken = json.get("access_token").text();
		expires = Instant.now().plusSeconds(json.get("expires_in").asLong(0));
	}

	/**
	 * Get Partner Token - replaces the old anonymous token system
	 * This uses the Spotify Partner API for token retrieval
	 */
	public String getPartnerToken() throws IOException {
		if (this.partnerToken == null || this.partnerTokenExpiry == null || 
		    this.partnerTokenExpiry.isBefore(Instant.now().plusMillis(Long.parseLong(TOKEN_REFRESH_MARGIN_MS)))) {
			synchronized (this) {
				if (this.partnerToken == null || this.partnerTokenExpiry == null || 
				    this.partnerTokenExpiry.isBefore(Instant.now().plusMillis(Long.parseLong(TOKEN_REFRESH_MARGIN_MS)))) {
					log.debug("Partner token is invalid or expired, refreshing token...");
					this.refreshPartnerToken();
				}
			}
		}
		return this.partnerToken;
	}

	/**
	 * Refresh Partner Token using the client token endpoint
	 * This is a simpler approach that doesn't require secret extraction
	 */
	private void refreshPartnerToken() throws IOException {
		// Use the client token endpoint - no authentication required
		var request = new HttpGet("https://clienttoken.spotify.com/v1/clienttoken");
		request.addHeader("Accept", "application/json");
		
		var json = LavaSrcTools.fetchResponseAsJson(sourceManager.getHttpInterface(), request);
		if (json == null) {
			throw new RuntimeException("No response from Spotify Partner API while fetching partner token.");
		}
		
		// Check if we got a granted_token response
		if (!json.get("granted_token").isNull()) {
			var grantedToken = json.get("granted_token");
			partnerToken = grantedToken.get("token").text();
			
			// Calculate expiry time
			long expiresInSeconds = grantedToken.get("expires_after_seconds").asLong(3600);
			partnerTokenExpiry = Instant.now().plusSeconds(expiresInSeconds);
			
			log.debug("Partner token refreshed successfully. Expires in {} seconds", expiresInSeconds);
			return;
		}
		
		// Fallback to old format if available
		if (!json.get("error").isNull()) {
			var error = json.get("error").text();
			throw new RuntimeException("Error while fetching partner token: " + error);
		}

		throw new RuntimeException("Unable to parse partner token response");
	}

	public void setSpDc(String spDc) {
		this.spDc = spDc;
		this.accountAccessToken = null;
		this.accountAccessTokenExpire = null;
	}

	public String getAccountAccessToken() throws IOException {
		if (this.accountAccessToken == null || this.accountAccessTokenExpire == null || this.accountAccessTokenExpire.isBefore(Instant.now())) {
			synchronized (this) {
				if (this.accountAccessToken == null || this.accountAccessTokenExpire == null || this.accountAccessTokenExpire.isBefore(Instant.now())) {
					log.debug("Account token is invalid or expired, refreshing token...");
					this.refreshAccountAccessToken();
				}
			}
		}
		return this.accountAccessToken;
	}

	public void refreshAccountAccessToken() throws IOException {
		var request = new HttpGet("https://open.spotify.com/get_access_token?reason=transport&productType=web_player");
		request.addHeader("App-Platform", "WebPlayer");
		request.addHeader("Cookie", "sp_dc=" + this.spDc);

		try {
			var json = LavaSrcTools.fetchResponseAsJson(this.sourceManager.getHttpInterface(), request);
			if (json == null) {
				throw new RuntimeException("No response from Spotify API while fetching account access token.");
			}
			if (!json.get("error").isNull()) {
				var error = json.get("error").text();
				log.error("Error while fetching account token: {}", error);
				throw new RuntimeException("Error while fetching account access token: " + error);
			}
			this.accountAccessToken = json.get("accessToken").text();
			this.accountAccessTokenExpire = Instant.ofEpochMilli(json.get("accessTokenExpirationTimestampMs").asLong(0));
		} catch (IOException e) {
			log.error("Account token refreshing failed.", e);
			throw new RuntimeException("Account token refreshing failed", e);
		}
	}

	public boolean hasValidAccountCredentials() {
		return this.spDc != null && !this.spDc.isEmpty();
	}

	/**
	 * Make a request to the Spotify Partner API
	 * This replaces the need for the internal API requests with secret extraction
	 */
	public JsonBrowser makePartnerApiRequest(String queryName, Map<String, Object> variables) throws IOException {
		QueryDefinition query = QUERIES.get(queryName);
		if (query == null) {
			throw new IllegalArgumentException("Unknown query: " + queryName);
		}

		String token = getPartnerToken();
		
		var request = new HttpPost(SPOTIFY_PARTNER_API_URL);
		request.addHeader("Authorization", "Bearer " + token);
		request.addHeader("App-Platform", "WebPlayer");
		request.addHeader("Spotify-App-Version", "1.2.81.104.g225ec0e6");
		request.addHeader("Content-Type", "application/json; charset=utf-8");
		
		// Build request body
		Map<String, Object> body = new HashMap<>();
		body.put("variables", variables);
		body.put("operationName", query.name);
		
		Map<String, Object> extensions = new HashMap<>();
		Map<String, Object> persistedQuery = new HashMap<>();
		persistedQuery.put("version", 1);
		persistedQuery.put("sha256Hash", query.hash);
		extensions.put("persistedQuery", persistedQuery);
		body.put("extensions", extensions);
		
		// Convert to JSON string
		String jsonBody = buildJsonString(body);
		request.setEntity(new org.apache.http.entity.StringEntity(jsonBody, StandardCharsets.UTF_8));
		
		var json = LavaSrcTools.fetchResponseAsJson(sourceManager.getHttpInterface(), request);
		if (json == null) {
			throw new RuntimeException("No response from Spotify Partner API");
		}
		
		if (!json.get("errors").isNull()) {
			var errors = json.get("errors");
			throw new RuntimeException("Partner API error: " + errors.format());
		}
		
		return json.get("data");
	}

	/**
	 * Simple JSON string builder for the request body
	 */
	private String buildJsonString(Map<String, Object> map) {
		StringBuilder sb = new StringBuilder("{");
		boolean first = true;
		
		for (Map.Entry<String, Object> entry : map.entrySet()) {
			if (!first) sb.append(",");
			first = false;
			
			sb.append("\"").append(entry.getKey()).append("\":");
			
			Object value = entry.getValue();
			if (value instanceof String) {
				sb.append("\"").append(value).append("\"");
			} else if (value instanceof Map) {
				sb.append(buildJsonString((Map<String, Object>) value));
			} else if (value instanceof Number) {
				sb.append(value);
			} else {
				// For other types, use toString
				sb.append("\"").append(value.toString()).append("\"");
			}
		}
		
		sb.append("}");
		return sb.toString();
	}

	/**
	 * Query definition for Partner API
	 */
	private static class QueryDefinition {
		final String name;
		final String hash;

		QueryDefinition(String name, String hash) {
			this.name = name;
			this.hash = hash;
		}
	}
}