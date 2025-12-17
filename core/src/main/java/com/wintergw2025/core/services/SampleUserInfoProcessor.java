/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.wintergw2025.core.services;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

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

/**
 * Sample UserInfoProcessor showing how to:
 * - Extract access_token and id_token from the token response
 * - Return hardcoded user attributes
 * - Return hardcoded group memberships
 */
@Component(service = UserInfoProcessor.class, property = {"service.ranking:Integer=50"})
@Designate(ocd = SampleUserInfoProcessor.Config.class, factory = true)
public class SampleUserInfoProcessor implements UserInfoProcessor {

    private static final Logger logger = LoggerFactory.getLogger(SampleUserInfoProcessor.class);

    @ObjectClassDefinition(name = "Sample UserInfo Processor")
    @interface Config {
        @AttributeDefinition(name = "Connection Name", description = "OIDC Connection Name")
        String connection();
    }

    private final String connection;

    @Activate
    public SampleUserInfoProcessor(Config config) {
        this.connection = config.connection();
        logger.info("SampleUserInfoProcessor activated for connection: {}", connection);
    }

    @Override
    public @NotNull OidcAuthCredentials process(
            @Nullable String userInfo,
            @NotNull String tokenResponse,
            @NotNull String oidcSubject,
            @NotNull String idp) {

        // === Extract tokens from token response ===
        JsonObject tokenJson = JsonParser.parseString(tokenResponse).getAsJsonObject();
        String accessToken = tokenJson.has("access_token") ? tokenJson.get("access_token").getAsString() : null;
        String idToken = tokenJson.has("id_token") ? tokenJson.get("id_token").getAsString() : null;

        logger.info("Access Token: {}", accessToken != null ? accessToken.substring(0, Math.min(30, accessToken.length())) + "..." : "null");
        logger.info("ID Token: {}", idToken != null ? idToken.substring(0, Math.min(30, idToken.length())) + "..." : "null");

        // === Decode ID Token to see claims (optional) ===
        if (idToken != null) {
            JsonObject claims = decodeJwtPayload(idToken);
            logger.info("ID Token claims: {}", claims);
        }

        // === Create credentials ===
        OidcAuthCredentials credentials = new OidcAuthCredentials(oidcSubject, idp);
        credentials.setAttribute(".token", "");

        // === Set hardcoded user attributes ===
        // These will be synced to user node based on DefaultSyncHandler propertyMapping
        credentials.setAttribute("profile/given_name", "John");
        credentials.setAttribute("profile/family_name", "Doe");
        credentials.setAttribute("profile/email", "john.doe@example.com");
        credentials.setAttribute("profile/department", "Engineering");

        // === Add hardcoded group memberships ===
        // These groups will be created/synced based on DefaultSyncHandler configuration
        credentials.addGroup("authenticated-users");
        credentials.addGroup("premium-members");
        credentials.addGroup("content-authors");

        return credentials;
    }

    @Override
    public @NotNull String connection() {
        return connection;
    }

    /** Decode JWT payload (middle part) */
    private JsonObject decodeJwtPayload(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length != 3) return null;
            
            String payload = parts[1];
            payload = payload + "====".substring(0, (4 - payload.length() % 4) % 4);
            payload = payload.replace('-', '+').replace('_', '/');
            
            return JsonParser.parseString(new String(Base64.getDecoder().decode(payload), StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }
}
