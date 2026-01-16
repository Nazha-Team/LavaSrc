package com.github.topi314.lavasrc.spotify;

import com.github.topi314.lavasrc.LavaSrcTools;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Base64;

public class SpotifyPartnerTokenTracker {
	private static final Logger log = LoggerFactory.getLogger(SpotifyPartnerTokenTracker.class);
	private static final String PARTNER_TOKEN_URL = "https://accounts.spotify.com/api/token";
	
	private final SpotifySourceManager sourceManager;
	private final String clientId;
	private final String clientSecret;
	
	private String accessToken;
	private long tokenExpiryTime;
	
	public SpotifyPartnerTokenTracker(SpotifySourceManager sourceManager, String clientId, String clientSecret) {
		this.sourceManager = sourceManager;
		this.clientId = clientId;
		this.clientSecret = clientSecret;
	}
	
	public String getPartnerAccessToken() throws IOException {
		if (accessToken == null || System.currentTimeMillis() > tokenExpiryTime - 30000) {
			refreshPartnerToken();
		}
		return accessToken;
	}
	
	private synchronized void refreshPartnerToken() throws IOException {
		try {
			String credentials = Base64.getEncoder().encodeToString((clientId + ":" + clientSecret).getBytes());
			
			HttpPost request = new HttpPost(PARTNER_TOKEN_URL);
			request.setHeader("Authorization", "Basic " + credentials);
			request.setHeader("Content-Type", "application/x-www-form-urlencoded");
			
			StringEntity params = new StringEntity("grant_type=client_credentials");
			request.setEntity(params);
			
			JsonBrowser json = LavaSrcTools.fetchResponseAsJson(sourceManager.getHttpInterface(), request);
			
			if (json == null || json.get("access_token").isNull()) {
				throw new IOException("Failed to get partner access token");
			}
			
			this.accessToken = json.get("access_token").text();
			long expiresIn = json.get("expires_in").asLong(3600) * 1000;
			this.tokenExpiryTime = System.currentTimeMillis() + expiresIn;
			
			log.info("Refreshed Spotify Partner API token, expires in {} seconds", expiresIn / 1000);
		} catch (Exception e) {
			log.error("Failed to refresh partner token", e);
			throw new IOException("Failed to refresh partner token", e);
		}
	}
}