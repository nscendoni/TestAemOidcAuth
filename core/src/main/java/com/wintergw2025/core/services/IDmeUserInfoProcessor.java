/*
 *  Copyright 2015 Adobe Systems Incorporated
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.wintergw2025.core.services;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.apache.http.HttpHeaders;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.apache.sling.auth.oauth_client.spi.OidcAuthCredentials;
import org.apache.sling.auth.oauth_client.spi.UserInfoProcessor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

/**
 * IDme UserInfoProcessor implementation that fetches user info from ID.me API
 * and processes it to create OIDC authentication credentials.
 * 
 * Endpoint: https://api.idmelabs.com/api/public/v3/userinfo
 */
@Component(
        service = UserInfoProcessor.class,
        property = {"service.ranking:Integer=100"})
@Designate(ocd = IDmeUserInfoProcessor.Config.class, factory = true)
public class IDmeUserInfoProcessor implements UserInfoProcessor {

    private static final Logger logger = LoggerFactory.getLogger(IDmeUserInfoProcessor.class);

    private static final String DEFAULT_USERINFO_ENDPOINT = "https://api.idmelabs.com/api/public/v3/userinfo";

    @ObjectClassDefinition(
            name = "ID.me UserInfo Processor",
            description = "Processes user info from ID.me identity provider")
    @interface Config {

        @AttributeDefinition(
                name = "Connection Name",
                description = "OIDC Connection Name that this processor handles")
        String connection();

        @AttributeDefinition(
                name = "UserInfo Endpoint",
                description = "ID.me UserInfo API endpoint URL")
        String userInfoEndpoint() default DEFAULT_USERINFO_ENDPOINT;

        @AttributeDefinition(
                name = "Store Access Token",
                description = "Store access token under user node")
        boolean storeAccessToken() default false;

        @AttributeDefinition(
                name = "Add IDP suffix to principals",
                description = "Add IDP name suffix to username and groups")
        boolean idpNameInPrincipals() default false;
    }

    private final String connection;
    private final String userInfoEndpoint;
    private final boolean storeAccessToken;
    private final boolean idpNameInPrincipals;

    @Activate
    public IDmeUserInfoProcessor(Config config) {
        if (config.connection() == null || config.connection().isEmpty()) {
            throw new IllegalArgumentException("Connection name must not be null or empty");
        }
        this.connection = config.connection();
        this.userInfoEndpoint = config.userInfoEndpoint() != null && !config.userInfoEndpoint().isEmpty() 
                ? config.userInfoEndpoint() 
                : DEFAULT_USERINFO_ENDPOINT;
        this.storeAccessToken = config.storeAccessToken();
        this.idpNameInPrincipals = config.idpNameInPrincipals();
        
        logger.info("IDmeUserInfoProcessor activated for connection: {}, endpoint: {}", connection, userInfoEndpoint);
    }

    @Override
    public @NotNull OidcAuthCredentials process(
            @Nullable String userInfo,
            @NotNull String tokenResponse,
            @NotNull String oidcSubject,
            @NotNull String idp) throws RuntimeException {

        logger.debug("Processing user info for subject: {}, idp: {}", oidcSubject, idp);

        // Parse token response to get access token
        String accessToken = extractAccessToken(tokenResponse);
        
        // Fetch user info from ID.me API
        JsonObject idmeUserInfo = fetchIDmeUserInfo(accessToken);
        
        // Create credentials with subject
        String userId = oidcSubject + (idpNameInPrincipals ? ";" + idp : "");
        OidcAuthCredentials credentials = new OidcAuthCredentials(userId, idp);
        credentials.setAttribute(".token", "");

        // Map ID.me fields to user profile attributes
        if (idmeUserInfo != null) {
            mapUserInfoToCredentials(idmeUserInfo, credentials);
        }

        // Optionally store access token
        if (storeAccessToken && accessToken != null) {
            credentials.setAttribute("oauth_access_token", accessToken);
        }

        logger.debug("Created credentials for user: {}", userId);
        return credentials;
    }

    @Override
    public @NotNull String connection() {
        return connection;
    }

    /**
     * Extract access token from token response JSON
     */
    private String extractAccessToken(String tokenResponse) {
        try {
            JsonObject json = JsonParser.parseString(tokenResponse).getAsJsonObject();
            if (json.has("access_token")) {
                return json.get("access_token").getAsString();
            }
        } catch (Exception e) {
            logger.error("Failed to extract access token from token response", e);
        }
        return null;
    }

    /**
     * Fetch user info from ID.me API using the access token.
     * ID.me returns a JWT instead of plain JSON, so we need to decode it.
     */
    private JsonObject fetchIDmeUserInfo(String accessToken) {
        if (accessToken == null) {
            logger.warn("Access token is null, cannot fetch ID.me user info");
            return null;
        }

        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpGet request = new HttpGet(userInfoEndpoint);
            request.setHeader(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
            request.setHeader(HttpHeaders.ACCEPT, "application/json");

            logger.debug("Fetching user info from: {}", userInfoEndpoint);

            try (CloseableHttpResponse response = httpClient.execute(request)) {
                int statusCode = response.getStatusLine().getStatusCode();
                
                if (statusCode == 200) {
                    String responseBody = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
                    logger.debug("ID.me user info response: {}", responseBody);
                    
                    // ID.me returns a JWT, need to decode the payload
                    return parseUserInfoResponse(responseBody);
                } else {
                    logger.error("Failed to fetch ID.me user info. Status: {}", statusCode);
                }
            }
        } catch (IOException e) {
            logger.error("Error fetching ID.me user info", e);
        }
        
        return null;
    }

    /**
     * Parse the user info response which can be either:
     * - A JWT token (ID.me returns this)
     * - Plain JSON
     */
    private JsonObject parseUserInfoResponse(String responseBody) {
        if (responseBody == null || responseBody.isEmpty()) {
            return null;
        }

        // Remove quotes if the response is wrapped in quotes
        String cleanResponse = responseBody.trim();
        if (cleanResponse.startsWith("\"") && cleanResponse.endsWith("\"")) {
            cleanResponse = cleanResponse.substring(1, cleanResponse.length() - 1);
        }

        // Check if it's a JWT (has 3 parts separated by dots)
        if (isJwt(cleanResponse)) {
            logger.debug("Response is a JWT, decoding payload...");
            return decodeJwtPayload(cleanResponse);
        }

        // Try to parse as plain JSON
        try {
            return JsonParser.parseString(cleanResponse).getAsJsonObject();
        } catch (JsonSyntaxException e) {
            logger.error("Failed to parse user info response as JSON: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Check if the string is a JWT (3 base64 parts separated by dots)
     */
    private boolean isJwt(String token) {
        if (token == null) {
            return false;
        }
        String[] parts = token.split("\\.");
        return parts.length == 3;
    }

    /**
     * Decode the payload (middle part) of a JWT token.
     * JWT format: header.payload.signature
     * The payload is Base64URL encoded JSON.
     */
    private JsonObject decodeJwtPayload(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length != 3) {
                logger.error("Invalid JWT format: expected 3 parts, got {}", parts.length);
                return null;
            }

            // Decode the payload (second part)
            // JWT uses Base64URL encoding, need to handle padding
            String payload = parts[1];
            
            // Add padding if necessary (Base64URL doesn't require padding, but Java decoder might need it)
            int paddingNeeded = (4 - payload.length() % 4) % 4;
            payload = payload + "====".substring(0, paddingNeeded);
            
            // Replace URL-safe characters with standard Base64 characters
            payload = payload.replace('-', '+').replace('_', '/');
            
            byte[] decodedBytes = Base64.getDecoder().decode(payload);
            String decodedPayload = new String(decodedBytes, StandardCharsets.UTF_8);
            
            logger.debug("Decoded JWT payload: {}", decodedPayload);
            
            return JsonParser.parseString(decodedPayload).getAsJsonObject();
        } catch (IllegalArgumentException e) {
            logger.error("Failed to decode JWT payload: {}", e.getMessage());
            return null;
        } catch (JsonSyntaxException e) {
            logger.error("Failed to parse decoded JWT payload as JSON: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Map ID.me user info fields to OidcAuthCredentials attributes
     * 
     * ID.me JWT payload format (decoded from UserInfo endpoint response):
     * {
     *   "iss": "https://api.idmelabs.com/oidc",
     *   "sub": "c8d8e0cc0d7c4f6ca9a23cb75886cb04",
     *   "aud": "9c174b9fb56c8f425ba383bb96ff292d",
     *   "exp": 1765980060,
     *   "iat": 1765962024,
     *   "nonce": "...",
     *   "birth_date": "1942-07-09",
     *   "email": "adbtest@id.mex",
     *   "given_name": "VERONICA",
     *   "name": "VERONICA C PERSINGER",
     *   "social": "454354354",
     *   "family_name": "PERSINGER",
     *   "phone": "18004920941",
     *   "zip": "55016",
     *   "credential_option_preverified": "Preverified",
     *   "uuid": "c8d8e0cc0d7c4f6ca9a23cb75886cb04"
     * }
     */
    private void mapUserInfoToCredentials(JsonObject idmeUserInfo, OidcAuthCredentials credentials) {
        // Map OIDC standard claims (these use standard OIDC naming)
        setAttributeIfPresent(idmeUserInfo, "email", credentials, "profile/email");
        setAttributeIfPresent(idmeUserInfo, "given_name", credentials, "profile/given_name");
        setAttributeIfPresent(idmeUserInfo, "family_name", credentials, "profile/family_name");
        setAttributeIfPresent(idmeUserInfo, "name", credentials, "profile/name");
        setAttributeIfPresent(idmeUserInfo, "sub", credentials, "profile/sub");
        setAttributeIfPresent(idmeUserInfo, "iss", credentials, "profile/iss");
        setAttributeIfPresent(idmeUserInfo, "aud", credentials, "profile/aud");
        
        // Map ID.me specific fields
        setAttributeIfPresent(idmeUserInfo, "phone", credentials, "profile/phone");
        setAttributeIfPresent(idmeUserInfo, "zip", credentials, "profile/zip");
        setAttributeIfPresent(idmeUserInfo, "birth_date", credentials, "profile/birth_date");
        setAttributeIfPresent(idmeUserInfo, "uuid", credentials, "profile/uuid");
        setAttributeIfPresent(idmeUserInfo, "social", credentials, "profile/social");
        setAttributeIfPresent(idmeUserInfo, "credential_option_preverified", credentials, "profile/credential_option_preverified");
        
        // Also try legacy field names (fname/lname) in case they're used
        if (!idmeUserInfo.has("given_name")) {
            setAttributeIfPresent(idmeUserInfo, "fname", credentials, "profile/given_name");
        }
        if (!idmeUserInfo.has("family_name")) {
            setAttributeIfPresent(idmeUserInfo, "lname", credentials, "profile/family_name");
        }

        logger.debug("Mapped ID.me user info to credentials: email={}, given_name={}, family_name={}",
                getStringOrNull(idmeUserInfo, "email"),
                getStringOrNull(idmeUserInfo, "given_name"),
                getStringOrNull(idmeUserInfo, "family_name"));
    }

    private void setAttributeIfPresent(JsonObject json, String sourceKey, OidcAuthCredentials credentials, String targetKey) {
        if (json.has(sourceKey) && !json.get(sourceKey).isJsonNull()) {
            String value = json.get(sourceKey).getAsString();
            if (value != null && !value.isEmpty()) {
                credentials.setAttribute(targetKey, value);
            }
        }
    }

    private String getStringOrNull(JsonObject json, String key) {
        if (json.has(key) && !json.get(key).isJsonNull()) {
            return json.get(key).getAsString();
        }
        return null;
    }
}

