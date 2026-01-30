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
package com.wintergw2025.core.servlets;

import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.jackrabbit.api.security.user.Authorizable;
import org.apache.jackrabbit.api.security.user.Group;
import org.apache.jackrabbit.api.security.user.User;
import org.apache.jackrabbit.api.security.user.UserManager;
import org.apache.jackrabbit.oak.spi.security.authentication.external.ExternalIdentityRef;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.apache.sling.jcr.api.SlingRepository;
import org.apache.sling.servlets.annotations.SlingServletPaths;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.propertytypes.ServiceDescription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Value;
import javax.jcr.ValueFactory;
import javax.servlet.Servlet;
import javax.servlet.ServletException;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Migration Step 2 Servlet - Assigns dynamic groups to user based on local group memberships.
 * Accessible at: /bin/wintergw2025/migration-step2
 * 
 * This servlet performs the following operations:
 * 1. For a local user passed as input parameter:
 *    - Ensure the user has rep:externalId (convert to external user if needed)
 *    - For each group membership of the user (excluding system groups like 'everyone'), 
 *      assign the dynamic group corresponding to local group membership
 *    - Update the properties rep:lastDynamicSync and rep:lastSynced with the current timestamp
 * 
 * Usage:
 *   POST /bin/wintergw2025/migration-step2?userId=<userId>&idpName=<idpName>
 */
@Component(service = { Servlet.class })
@SlingServletPaths("/bin/wintergw2025/migration-step2")
@ServiceDescription("Migration Step 2 Servlet - Assigns dynamic groups to user based on group memberships")
public class MigrationStep2Servlet extends SlingAllMethodsServlet {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LoggerFactory.getLogger(MigrationStep2Servlet.class);
    
    private static final String SERVICE_USER = "group-provisioner";
    private static final String REP_EXTERNAL_PRINCIPAL_NAMES = "rep:externalPrincipalNames";
    private static final String REP_EXTERNAL_ID = "rep:externalId";

    @Reference
    private SlingRepository repository;

    @Override
    protected void doPost(final SlingHttpServletRequest request,
                          final SlingHttpServletResponse response) throws ServletException, IOException {
        
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        PrintWriter writer = response.getWriter();
        
        // Get the userId parameter
        String userId = request.getParameter("userId");
        if (userId == null || userId.trim().isEmpty()) {
            response.setStatus(SlingHttpServletResponse.SC_BAD_REQUEST);
            writer.write("{\"success\": false, \"error\": \"userId parameter is required\"}");
            return;
        }
        
        // Get the idpName parameter
        String idpName = request.getParameter("idpName");
        if (idpName == null || idpName.trim().isEmpty()) {
            response.setStatus(SlingHttpServletResponse.SC_BAD_REQUEST);
            writer.write("{\"success\": false, \"error\": \"idpName parameter is required\"}");
            return;
        }
        
        LOG.info("MigrationStep2Servlet: Processing user '{}' with idpName '{}'", userId, idpName);
        
        Session serviceSession = null;
        try {
            // Login as the service user using SlingRepository.loginService()
            serviceSession = repository.loginService(SERVICE_USER, null);
            UserManager userManager = ((JackrabbitSession) serviceSession).getUserManager();
            ValueFactory valueFactory = serviceSession.getValueFactory();
            
            LOG.info("Service session opened with user: {}", serviceSession.getUserID());
            
            // Get the user
            Authorizable userAuth = userManager.getAuthorizable(userId);
            if (userAuth == null) {
                response.setStatus(SlingHttpServletResponse.SC_NOT_FOUND);
                writer.write("{\"success\": false, \"error\": \"User not found: " + 
                        escapeJson(userId) + "\"}");
                return;
            }
            
            if (userAuth.isGroup()) {
                response.setStatus(SlingHttpServletResponse.SC_BAD_REQUEST);
                writer.write("{\"success\": false, \"error\": \"Authorizable '" + 
                        escapeJson(userId) + "' is a group, not a user\"}");
                return;
            }
            
            User user = (User) userAuth;
            LOG.info("Found user '{}' at path '{}'", userId, user.getPath());
            
            // Ensure user has rep:externalId (required before setting rep:externalPrincipalNames)
            Value[] externalIdValues = user.getProperty(REP_EXTERNAL_ID);
            boolean userConverted = false;
            if (externalIdValues == null || externalIdValues.length == 0) {
                // Convert local user to external user by adding rep:externalId
                ExternalIdentityRef externalRef = new ExternalIdentityRef(userId, idpName);
                String externalId = externalRef.getString();
                user.setProperty(REP_EXTERNAL_ID, valueFactory.createValue(externalId));
                userConverted = true;
                LOG.info("Converted local user '{}' to external user with rep:externalId='{}'", userId, externalId);
            } else {
                LOG.info("User '{}' already has rep:externalId='{}'", userId, externalIdValues[0].getString());
            }
            
            // Get existing external principal names
            Value[] existingValues = user.getProperty(REP_EXTERNAL_PRINCIPAL_NAMES);
            List<String> principalNames = new ArrayList<>();
            
            if (existingValues != null) {
                for (Value value : existingValues) {
                    principalNames.add(value.getString());
                }
            }
            
            LOG.info("User '{}' has {} existing external principal names", userId, principalNames.size());
            
            // Get all group memberships for the user
            Iterator<Group> groupIterator = user.declaredMemberOf();
            List<String> processedGroups = new ArrayList<>();
            List<String> addedPrincipals = new ArrayList<>();
            List<String> skippedPrincipals = new ArrayList<>();
            List<String> skippedSystemGroups = new ArrayList<>();
            int principalsAdded = 0;
            
            while (groupIterator.hasNext()) {
                Group group = groupIterator.next();
                String groupId = group.getID();
                
                // Skip the "everyone" system group
                if ("everyone".equals(groupId)) {
                    skippedSystemGroups.add(groupId);
                    LOG.info("Skipping system group '{}' for user '{}'", groupId, userId);
                    continue;
                }
                
                processedGroups.add(groupId);
                
                // Create the dynamic group principal name: <groupId>;<idpName>
                String dynamicGroupPrincipal = groupId + ";" + idpName;
                
                // Check if the principal name already exists
                if (principalNames.contains(dynamicGroupPrincipal)) {
                    skippedPrincipals.add(dynamicGroupPrincipal);
                    LOG.info("User '{}' already has external principal '{}' for group '{}'", 
                            userId, dynamicGroupPrincipal, groupId);
                } else {
                    // Add the new principal name
                    principalNames.add(dynamicGroupPrincipal);
                    addedPrincipals.add(dynamicGroupPrincipal);
                    principalsAdded++;
                    LOG.info("Added external principal '{}' to user '{}' for group membership '{}'", 
                            dynamicGroupPrincipal, userId, groupId);
                }
            }
            
            if (principalsAdded > 0) {
                // Create new Value array with all principal names
                Value[] newValues = new Value[principalNames.size()];
                for (int i = 0; i < principalNames.size(); i++) {
                    newValues[i] = valueFactory.createValue(principalNames.get(i));
                }
                
                // Set the property on the user
                user.setProperty(REP_EXTERNAL_PRINCIPAL_NAMES, newValues);
            }
            
            // Update sync timestamps to far future (workaround for OAK-12079)
            // Set to 10 years in the future to prevent premature cleanup of external group memberships
            // See: https://issues.apache.org/jira/browse/OAK-12079
            java.util.Calendar future = java.util.Calendar.getInstance();
            future.add(java.util.Calendar.YEAR, 10);
            user.setProperty("rep:lastDynamicSync", valueFactory.createValue(future));
            user.setProperty("rep:lastSynced", valueFactory.createValue(future));
            
            // Save the session
            serviceSession.save();
            
            StringBuilder message = new StringBuilder();
            message.append("Successfully processed user '").append(escapeJson(userId)).append("'. ");
            if (userConverted) {
                message.append("Converted user to external user. ");
            }
            message.append("Checked ").append(processedGroups.size()).append(" group memberships. ");
            if (!skippedSystemGroups.isEmpty()) {
                message.append("Skipped ").append(skippedSystemGroups.size()).append(" system groups. ");
            }
            if (principalsAdded > 0) {
                message.append("Added ").append(principalsAdded).append(" dynamic group principals. ");
            }
            if (!skippedPrincipals.isEmpty()) {
                message.append(skippedPrincipals.size()).append(" principals already existed. ");
            }
            message.append("Updated sync timestamps.");
            
            LOG.info(message.toString());
            
            writer.write("{\"success\": true, \"message\": \"" + message.toString() + 
                    "\", \"userId\": \"" + escapeJson(userId) +
                    "\", \"userConverted\": " + userConverted +
                    ", \"groupMembershipsChecked\": " + processedGroups.size() +
                    ", \"systemGroupsSkipped\": " + skippedSystemGroups.size() +
                    ", \"principalsAdded\": " + principalsAdded +
                    ", \"principalsSkipped\": " + skippedPrincipals.size() +
                    ", \"processedGroups\": " + toJsonArray(processedGroups) +
                    ", \"skippedSystemGroups\": " + toJsonArray(skippedSystemGroups) +
                    ", \"addedPrincipals\": " + toJsonArray(addedPrincipals) +
                    ", \"skippedPrincipals\": " + toJsonArray(skippedPrincipals) +
                    ", \"allExternalPrincipals\": " + toJsonArray(principalNames) + "}");
            
        } catch (RepositoryException e) {
            LOG.error("Repository error while processing migration step 2", e);
            response.setStatus(SlingHttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            writer.write("{\"success\": false, \"error\": \"Repository error: " + 
                    escapeJson(e.getMessage()) + "\"}");
        } finally {
            if (serviceSession != null && serviceSession.isLive()) {
                serviceSession.logout();
            }
        }
    }
    
    @Override
    protected void doGet(final SlingHttpServletRequest request,
                         final SlingHttpServletResponse response) throws ServletException, IOException {
        
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        PrintWriter writer = response.getWriter();
        
        writer.write("{\"servlet\": \"MigrationStep2Servlet\", " +
                "\"description\": \"Assigns dynamic groups to user based on local group memberships\", " +
                "\"usage\": \"POST /bin/wintergw2025/migration-step2?userId=<userId>&idpName=<idpName>\", " +
                "\"parameters\": {" +
                "\"userId\": \"User ID to process (required)\", " +
                "\"idpName\": \"Identity provider name (required)\"" +
                "}, " +
                "\"operations\": [" +
                "\"1. Ensure user has rep:externalId (convert to external user if needed)\", " +
                "\"2. Get all group memberships for the user (skips system group 'everyone')\", " +
                "\"3. For each group, assign the dynamic group principal: <groupId>;<idpName>\", " +
                "\"4. Update rep:lastDynamicSync and rep:lastSynced timestamps\"" +
                "]}");
    }
    
    private String toJsonArray(List<String> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append("\"").append(escapeJson(items.get(i))).append("\"");
        }
        sb.append("]");
        return sb.toString();
    }
    
    private String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");
    }
}
