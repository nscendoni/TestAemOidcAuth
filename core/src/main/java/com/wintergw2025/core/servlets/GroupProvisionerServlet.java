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
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;

/**
 * Servlet that adds external principal names to a user for dynamic group membership.
 * Accessible at: /bin/wintergw2025/group-provisioner
 * 
 * This servlet opens an Oak session with the "group-provisioner" service user and adds
 * a principalName to the rep:externalPrincipalNames attribute of a specified user.
 * 
 * The rep:externalPrincipalNames property on a user enables dynamic group membership
 * where the user becomes a member of groups that have matching external principal names.
 * 
 * If the user doesn't exist, it will be created as an external user with rep:externalId.
 * If the group (using principalName as groupId) doesn't exist, it will be created as 
 * an external group with rep:externalId.
 * 
 * Usage:
 *   POST /bin/wintergw2025/group-provisioner?userId=<userId>&principalName=<principalName>
 *   
 * If no userId is provided, it uses the currently logged-in user.
 */
@Component(service = { Servlet.class })
@SlingServletPaths("/bin/wintergw2025/group-provisioner")
@ServiceDescription("Group Provisioner Servlet - Adds external principal names to users for dynamic group membership")
public class GroupProvisionerServlet extends SlingAllMethodsServlet {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LoggerFactory.getLogger(GroupProvisionerServlet.class);
    
    private static final String SERVICE_USER = "group-provisioner";
    private static final String DEFAULT_IDP_NAME = "saml-idp";
    private static final String DEFAULT_PRINCIPAL_NAME = "marketing:saml-idp";
    private static final String REP_EXTERNAL_PRINCIPAL_NAMES = "rep:externalPrincipalNames";
    private static final String REP_EXTERNAL_ID = "rep:externalId";
    
    // Authorized technical account from Adobe Developer Console Server-to-Server integration
    private static final String ALLOWED_TECHNICAL_ACCOUNT = "4FFECF71-3760-49F2-A883-B741AA1893C6@TECHACCT.ADOBE.COM";
    @Reference
    private SlingRepository repository;

    @Override
    protected void doPost(final SlingHttpServletRequest request,
                          final SlingHttpServletResponse response) throws ServletException, IOException {
        
        LOG.info("=== GroupProvisionerServlet.doPost() STARTED ===");
        LOG.info("Request URI: {}", request.getRequestURI());
        LOG.info("Remote User: {}", request.getRemoteUser());
        LOG.info("Auth Type: {}", request.getAuthType());
        
        Session session = request.getResourceResolver().adaptTo(Session.class);
        LOG.info("Session User ID: {}", session != null ? session.getUserID() : "NULL SESSION");
        
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        PrintWriter writer = response.getWriter();
        
        // Check if the caller is the authorized technical account
        if (!isAuthorizedCaller(request, response, writer)) {
            return;
        }
        
        // Get the userId parameter, or use the currently logged-in user
        String userId = request.getParameter("userId");
        if (userId == null || userId.trim().isEmpty()) {
            // Get the current user from the request
            Session requestSession = request.getResourceResolver().adaptTo(Session.class);
            if (requestSession != null) {
                userId = requestSession.getUserID();
            }
        }
        
        if (userId == null || userId.trim().isEmpty() || "anonymous".equals(userId)) {
            response.setStatus(SlingHttpServletResponse.SC_BAD_REQUEST);
            writer.write("{\"success\": false, \"error\": \"No userId provided and no authenticated user found\"}");
            writer.flush();
            return;
        }
        
        // Get the principalName parameter - this is used both as the external principal name 
        // and as the groupId for creating the external group
        String principalName = request.getParameter("principalName");
        if (principalName == null || principalName.trim().isEmpty()) {
            principalName = DEFAULT_PRINCIPAL_NAME;
        }
        
        // Get the idpName parameter for the external user/group's rep:externalId
        String idpName = request.getParameter("idpName");
        if (idpName == null || idpName.trim().isEmpty()) {
            idpName = DEFAULT_IDP_NAME;
        }
        
        LOG.info("GroupProvisionerServlet: Adding external principal '{}' to user '{}'", 
                principalName, userId);
        
        Session serviceSession = null;
        try {
            // Login as the service user using SlingRepository.loginService()
            serviceSession = repository.loginService(SERVICE_USER, null);
            UserManager userManager = ((JackrabbitSession) serviceSession).getUserManager();
            ValueFactory valueFactory = serviceSession.getValueFactory();
            
            LOG.info("Service session opened with user: {}", serviceSession.getUserID());
            
            // Ensure the group exists as an external group (using principalName as groupId)
            boolean groupCreated = ensureExternalGroupExists(userManager, valueFactory, principalName, idpName);
            
            // Ensure the user exists as an external user
            UserResult userResult = ensureExternalUserExists(userManager, valueFactory, userId, idpName);
            User user = userResult.user;
            boolean userCreated = userResult.created;
            
            // Get existing external principal names
            Value[] existingValues = user.getProperty(REP_EXTERNAL_PRINCIPAL_NAMES);
            List<String> principalNames = new ArrayList<>();
            
            if (existingValues != null) {
                for (Value value : existingValues) {
                    principalNames.add(value.getString());
                }
            }
            
            // Check if the principal name already exists
            boolean alreadyExists = principalNames.contains(principalName);
            if (!alreadyExists) {
                // Add the new principal name
                principalNames.add(principalName);
                
                // Create new Value array
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


            // Add the user to the group
            // Save the session
            serviceSession.save();
            
            StringBuilder message = new StringBuilder();
            if (userCreated) {
                message.append("Created external user '").append(escapeJson(userId)).append("'. ");
            }
            if (groupCreated) {
                message.append("Created external group '").append(escapeJson(principalName)).append("'. ");
            }
            if (alreadyExists) {
                message.append("External principal '").append(escapeJson(principalName))
                       .append("' already exists on user '").append(escapeJson(userId)).append("'.");
            } else {
                message.append("Successfully added external principal '").append(escapeJson(principalName))
                       .append("' to user '").append(escapeJson(userId)).append("'.");
            }
            
            LOG.info(message.toString());
            
            writer.write("{\"success\": true, \"message\": \"" + message.toString() + 
                    "\", \"userCreated\": " + userCreated +
                    ", \"groupCreated\": " + groupCreated + 
                    ", \"principalName\": \"" + escapeJson(principalName) +
                    "\", \"allPrincipals\": " + toJsonArray(principalNames) + "}");
            writer.flush();
            
        } catch (RepositoryException e) {
            LOG.error("Repository error while updating user", e);
            response.setStatus(SlingHttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            writer.write("{\"success\": false, \"error\": \"Repository error: " + 
                    escapeJson(e.getMessage()) + "\"}");
            writer.flush();
        } finally {
            if (serviceSession != null && serviceSession.isLive()) {
                serviceSession.logout();
            }
        }
    }
    
    /**
     * Result holder for user creation/lookup.
     */
    private static class UserResult {
        final User user;
        final boolean created;
        
        UserResult(User user, boolean created) {
            this.user = user;
            this.created = created;
        }
    }
    
    /**
     * Ensures that the external user exists. If it doesn't, creates it with rep:externalId.
     * 
     * @param userManager the UserManager
     * @param valueFactory the ValueFactory for creating values
     * @param userId the user ID
     * @param idpName the identity provider name for rep:externalId
     * @return UserResult containing the user and whether it was created
     * @throws RepositoryException if there's an error accessing the repository
     */
    private UserResult ensureExternalUserExists(UserManager userManager, ValueFactory valueFactory, 
            String userId, String idpName) throws RepositoryException {
        
        Authorizable existing = userManager.getAuthorizable(userId);
        
        if (existing != null) {
            if (!existing.isGroup()) {
                LOG.info("User '{}' already exists", userId);
                return new UserResult((User) existing, false);
            } else {
                throw new RepositoryException("An authorizable with ID '" + userId + "' exists but is a group, not a user");
            }
        }
        
        // Create the user with a Principal
        final String finalUserId = userId;
        Principal userPrincipal = new Principal() {
            @Override
            public String getName() {
                return finalUserId;
            }
        };
        
        User user = userManager.createUser(userId, null, userPrincipal, null);
        LOG.info("Created user '{}' at path '{}'", userId, user.getPath());
        
        // Set the rep:externalId property to mark it as an external user
        // Format: userId;idpName (same format as ExternalIdentityRef.getString())
        ExternalIdentityRef externalRef = new ExternalIdentityRef(userId, idpName);
        String externalId = externalRef.getString();
        
        user.setProperty(REP_EXTERNAL_ID, valueFactory.createValue(externalId));
        LOG.info("Set rep:externalId='{}' on user '{}'", externalId, userId);
        
        return new UserResult(user, true);
    }
    
    /**
     * Ensures that the external group exists. If it doesn't, creates it with rep:externalId.
     * 
     * @param userManager the UserManager
     * @param valueFactory the ValueFactory for creating values
     * @param groupId the group ID (same as principalName)
     * @param idpName the identity provider name for rep:externalId
     * @return true if the group was created, false if it already existed
     * @throws RepositoryException if there's an error accessing the repository
     */
    private boolean ensureExternalGroupExists(UserManager userManager, ValueFactory valueFactory, 
            String groupId, String idpName) throws RepositoryException {
        
        Authorizable existing = userManager.getAuthorizable(groupId);
        
        if (existing != null) {
            if (existing.isGroup()) {
                LOG.info("Group '{}' already exists", groupId);
                return false;
            } else {
                throw new RepositoryException("An authorizable with ID '" + groupId + "' exists but is not a group");
            }
        }
        
        // Create the group with a Principal
        final String finalGroupId = groupId;
        Principal groupPrincipal = new Principal() {
            @Override
            public String getName() {
                return finalGroupId;
            }
        };
        
        Group group = userManager.createGroup(groupPrincipal);
        LOG.info("Created group '{}' at path '{}'", groupId, group.getPath());
        
        // Set the rep:externalId property to mark it as an external group
        // Format: groupId;idpName (same format as ExternalIdentityRef.getString())
        ExternalIdentityRef externalRef = new ExternalIdentityRef(groupId, idpName);
        String externalId = externalRef.getString();
        
        group.setProperty(REP_EXTERNAL_ID, valueFactory.createValue(externalId));
        LOG.info("Set rep:externalId='{}' on group '{}'", externalId, groupId);
        
        return true;
    }
    
    @Override
    protected void doGet(final SlingHttpServletRequest request,
                         final SlingHttpServletResponse response) throws ServletException, IOException {
        
        LOG.info("=== GroupProvisionerServlet.doGet() STARTED ===");
        LOG.info("Request URI: {}", request.getRequestURI());
        LOG.info("Remote User: {}", request.getRemoteUser());
        LOG.info("Auth Type: {}", request.getAuthType());
        
        Session session = request.getResourceResolver().adaptTo(Session.class);
        LOG.info("Session User ID: {}", session != null ? session.getUserID() : "NULL SESSION");
        
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        PrintWriter writer = response.getWriter();
        
        // Check if the caller is the authorized technical account
        if (!isAuthorizedCaller(request, response, writer)) {
            return;
        }
        
        // Get the userId parameter
        String userId = request.getParameter("userId");
        if (userId == null || userId.trim().isEmpty()) {
            response.setStatus(SlingHttpServletResponse.SC_BAD_REQUEST);
            writer.write("{\"success\": false, \"error\": \"No userId provided\"}");
            writer.flush();
            return;
        }
        
        Session serviceSession = null;
        try {
            // Login as the service user using SlingRepository.loginService()
            serviceSession = repository.loginService(SERVICE_USER, null);
            UserManager userManager = ((JackrabbitSession) serviceSession).getUserManager();
            
            Authorizable authorizable = userManager.getAuthorizable(userId);
            
            if (authorizable == null) {
                response.setStatus(SlingHttpServletResponse.SC_NOT_FOUND);
                writer.write("{\"success\": false, \"error\": \"User '" + escapeJson(userId) + "' not found\"}");
                writer.flush();
                return;
            }
            
            if (authorizable.isGroup()) {
                response.setStatus(SlingHttpServletResponse.SC_BAD_REQUEST);
                writer.write("{\"success\": false, \"error\": \"'" + escapeJson(userId) + "' is a group, not a user\"}");
                writer.flush();
                return;
            }
            
            User user = (User) authorizable;
            Value[] existingValues = user.getProperty(REP_EXTERNAL_PRINCIPAL_NAMES);
            List<String> principalNames = new ArrayList<>();
            
            if (existingValues != null) {
                for (Value value : existingValues) {
                    principalNames.add(value.getString());
                }
            }
            
            writer.write("{\"success\": true, \"userId\": \"" + escapeJson(userId) + 
                    "\", \"externalPrincipalNames\": " + toJsonArray(principalNames) + "}");
            writer.flush();
            
        } catch (RepositoryException e) {
            LOG.error("Repository error", e);
            response.setStatus(SlingHttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            writer.write("{\"success\": false, \"error\": \"Repository error: " + 
                    escapeJson(e.getMessage()) + "\"}");
            writer.flush();
        } finally {
            if (serviceSession != null && serviceSession.isLive()) {
                serviceSession.logout();
            }
        }
    }
    
    /**
     * Checks if the caller is the authorized technical account.
     * 
     * @param request the HTTP request
     * @param response the HTTP response
     * @param writer the response writer
     * @return true if authorized, false otherwise (response is already written)
     */
    private boolean isAuthorizedCaller(SlingHttpServletRequest request, 
                                       SlingHttpServletResponse response, 
                                       PrintWriter writer) {
        Session session = request.getResourceResolver().adaptTo(Session.class);
        String callerId = session != null ? session.getUserID() : null;
        
        LOG.info("isAuthorizedCaller - Caller ID from session: '{}', Expected: '{}'", callerId, ALLOWED_TECHNICAL_ACCOUNT);
        
        if (!ALLOWED_TECHNICAL_ACCOUNT.equals(callerId)) {
            LOG.warn("Unauthorized access attempt by user: '{}' (expected: '{}')", callerId, ALLOWED_TECHNICAL_ACCOUNT);
            response.setStatus(SlingHttpServletResponse.SC_FORBIDDEN);
            writer.write("{\"success\": false, \"error\": \"Access denied. Only authorized technical accounts can access this endpoint. Caller: " + escapeJson(callerId) + "\"}");
            writer.flush();
            return false;
        }
        
        LOG.info("Authorized access by technical account: {}", callerId);
        return true;
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
