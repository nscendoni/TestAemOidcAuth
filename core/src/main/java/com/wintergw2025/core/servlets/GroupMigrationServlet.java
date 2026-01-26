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
import java.util.Iterator;
import java.util.List;

/**
 * Servlet that migrates local groups to external groups.
 * Accessible at: /bin/wintergw2025/group-migration
 * 
 * This servlet performs the following operations:
 * 1. Login as service user "group-provisioner"
 * 2. For each member of the group, check if the user has rep:externalId and add it if it doesn't have it
 * 3. Create a new external group with the name: <local group>;<idpName>
 * 4. Add the new external group as member of the local group
 * 5. Add the new group principal to the rep:externalPrincipalNames attribute of each user
 * 
 * Usage:
 *   POST /bin/wintergw2025/group-migration?groupPath=<groupPath>&idpName=<idpName>
 */
@Component(service = { Servlet.class })
@SlingServletPaths("/bin/wintergw2025/group-migration")
@ServiceDescription("Group Migration Servlet - Migrates local groups to external groups")
public class GroupMigrationServlet extends SlingAllMethodsServlet {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LoggerFactory.getLogger(GroupMigrationServlet.class);
    
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
        
        // Get the groupPath parameter
        String groupPath = request.getParameter("groupPath");
        if (groupPath == null || groupPath.trim().isEmpty()) {
            response.setStatus(SlingHttpServletResponse.SC_BAD_REQUEST);
            writer.write("{\"success\": false, \"error\": \"groupPath parameter is required\"}");
            return;
        }
        
        // Get the idpName parameter
        String idpName = request.getParameter("idpName");
        if (idpName == null || idpName.trim().isEmpty()) {
            response.setStatus(SlingHttpServletResponse.SC_BAD_REQUEST);
            writer.write("{\"success\": false, \"error\": \"idpName parameter is required\"}");
            return;
        }
        
        LOG.info("GroupMigrationServlet: Migrating group at path '{}' with idpName '{}'", 
                groupPath, idpName);
        
        Session serviceSession = null;
        try {
            // Login as the service user using SlingRepository.loginService()
            serviceSession = repository.loginService(SERVICE_USER, null);
            UserManager userManager = ((JackrabbitSession) serviceSession).getUserManager();
            ValueFactory valueFactory = serviceSession.getValueFactory();
            
            LOG.info("Service session opened with user: {}", serviceSession.getUserID());
            
            // Get the local group by path
            Authorizable localGroupAuth = userManager.getAuthorizableByPath(groupPath);
            if (localGroupAuth == null) {
                response.setStatus(SlingHttpServletResponse.SC_NOT_FOUND);
                writer.write("{\"success\": false, \"error\": \"Group not found at path: " + 
                        escapeJson(groupPath) + "\"}");
                return;
            }
            
            if (!localGroupAuth.isGroup()) {
                response.setStatus(SlingHttpServletResponse.SC_BAD_REQUEST);
                writer.write("{\"success\": false, \"error\": \"Authorizable at path '" + 
                        escapeJson(groupPath) + "' is not a group\"}");
                return;
            }
            
            Group localGroup = (Group) localGroupAuth;
            String localGroupId = localGroup.getID();
            LOG.info("Found local group '{}' at path '{}'", localGroupId, groupPath);
            
            // Create the external group name: <local group>;<idpName>
            String externalGroupId = localGroupId + ";" + idpName;
            LOG.info("Creating external group with ID: '{}'", externalGroupId);
            
            // Ensure the external group exists
            Group externalGroup = ensureExternalGroupExists(userManager, valueFactory, 
                    externalGroupId, localGroupId, idpName);
            
            // Add the external group as a member of the local group
            boolean groupAdded = localGroup.addMember(externalGroup);
            if (groupAdded) {
                LOG.info("Added external group '{}' as member of local group '{}'", 
                        externalGroupId, localGroupId);
            } else {
                LOG.info("External group '{}' was already a member of local group '{}'", 
                        externalGroupId, localGroupId);
            }
            
            // Process each member of the local group
            Iterator<Authorizable> members = localGroup.getDeclaredMembers();
            List<String> processedUsers = new ArrayList<>();
            List<String> skippedGroups = new ArrayList<>();
            int usersUpdated = 0;
            int usersWithExternalIdAdded = 0;
            
            while (members.hasNext()) {
                Authorizable member = members.next();
                
                // Skip if member is a group
                if (member.isGroup()) {
                    skippedGroups.add(member.getID());
                    LOG.info("Skipping group member: '{}'", member.getID());
                    continue;
                }
                
                User user = (User) member;
                String userId = user.getID();
                processedUsers.add(userId);
                
                // Check if user has rep:externalId
                Value[] externalIdValues = user.getProperty(REP_EXTERNAL_ID);
                if (externalIdValues == null || externalIdValues.length == 0) {
                    // Add rep:externalId to the user
                    ExternalIdentityRef externalRef = new ExternalIdentityRef(userId, idpName);
                    String externalId = externalRef.getString();
                    user.setProperty(REP_EXTERNAL_ID, valueFactory.createValue(externalId));
                    LOG.info("Added rep:externalId='{}' to user '{}'", externalId, userId);
                    usersWithExternalIdAdded++;
                }
                
                // Add the external group principal to rep:externalPrincipalNames
                boolean principalAdded = addExternalPrincipalName(user, valueFactory, externalGroupId);
                if (principalAdded) {
                    usersUpdated++;
                    LOG.info("Added external principal '{}' to user '{}'", externalGroupId, userId);
                } else {
                    LOG.info("External principal '{}' already exists on user '{}'", externalGroupId, userId);
                }
            }
            
            // Save the session
            serviceSession.save();
            
            StringBuilder message = new StringBuilder();
            message.append("Successfully migrated group '").append(escapeJson(localGroupId))
                   .append("'. Created external group '").append(escapeJson(externalGroupId))
                   .append("' and processed ").append(processedUsers.size()).append(" users.");
            if (usersUpdated > 0) {
                message.append(" Updated ").append(usersUpdated).append(" users with external principal.");
            }
            if (usersWithExternalIdAdded > 0) {
                message.append(" Added rep:externalId to ").append(usersWithExternalIdAdded).append(" users.");
            }
            if (!skippedGroups.isEmpty()) {
                message.append(" Skipped ").append(skippedGroups.size()).append(" group members.");
            }
            
            LOG.info(message.toString());
            
            writer.write("{\"success\": true, \"message\": \"" + message.toString() + 
                    "\", \"localGroupId\": \"" + escapeJson(localGroupId) +
                    "\", \"externalGroupId\": \"" + escapeJson(externalGroupId) +
                    "\", \"usersProcessed\": " + processedUsers.size() +
                    ", \"usersUpdated\": " + usersUpdated +
                    ", \"usersWithExternalIdAdded\": " + usersWithExternalIdAdded +
                    ", \"groupMembersSkipped\": " + skippedGroups.size() +
                    ", \"processedUsers\": " + toJsonArray(processedUsers) +
                    ", \"skippedGroups\": " + toJsonArray(skippedGroups) + "}");
            
        } catch (RepositoryException e) {
            LOG.error("Repository error while migrating group", e);
            response.setStatus(SlingHttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            writer.write("{\"success\": false, \"error\": \"Repository error: " + 
                    escapeJson(e.getMessage()) + "\"}");
        } finally {
            if (serviceSession != null && serviceSession.isLive()) {
                serviceSession.logout();
            }
        }
    }
    
    /**
     * Ensures that the external group exists. If it doesn't, creates it with rep:externalId.
     * 
     * @param userManager the UserManager
     * @param valueFactory the ValueFactory for creating values
     * @param externalGroupId the external group ID (format: localGroupId;idpName)
     * @param localGroupId the original local group ID
     * @param idpName the identity provider name for rep:externalId
     * @return the external Group
     * @throws RepositoryException if there's an error accessing the repository
     */
    private Group ensureExternalGroupExists(UserManager userManager, ValueFactory valueFactory, 
            String externalGroupId, String localGroupId, String idpName) throws RepositoryException {
        
        Authorizable existing = userManager.getAuthorizable(externalGroupId);
        
        if (existing != null) {
            if (existing.isGroup()) {
                LOG.info("External group '{}' already exists", externalGroupId);
                return (Group) existing;
            } else {
                throw new RepositoryException("An authorizable with ID '" + externalGroupId + 
                        "' exists but is not a group");
            }
        }
        
        // Create the group with a Principal
        final String finalGroupId = externalGroupId;
        Principal groupPrincipal = new Principal() {
            @Override
            public String getName() {
                return finalGroupId;
            }
        };
        
        Group group = userManager.createGroup(groupPrincipal);
        LOG.info("Created external group '{}' at path '{}'", externalGroupId, group.getPath());
        
        // Set the rep:externalId property to mark it as an external group
        // Use the localGroupId as the external user ID part
        ExternalIdentityRef externalRef = new ExternalIdentityRef(localGroupId, idpName);
        String externalId = externalRef.getString();
        
        group.setProperty(REP_EXTERNAL_ID, valueFactory.createValue(externalId));
        LOG.info("Set rep:externalId='{}' on external group '{}'", externalId, externalGroupId);
        
        return group;
    }
    
    /**
     * Adds an external principal name to a user's rep:externalPrincipalNames property.
     * 
     * @param user the User
     * @param valueFactory the ValueFactory for creating values
     * @param principalName the principal name to add
     * @return true if the principal was added, false if it already existed
     * @throws RepositoryException if there's an error accessing the repository
     */
    private boolean addExternalPrincipalName(User user, ValueFactory valueFactory, 
            String principalName) throws RepositoryException {
        
        // Get existing external principal names
        Value[] existingValues = user.getProperty(REP_EXTERNAL_PRINCIPAL_NAMES);
        List<String> principalNames = new ArrayList<>();
        
        if (existingValues != null) {
            for (Value value : existingValues) {
                principalNames.add(value.getString());
            }
        }
        
        // Check if the principal name already exists
        if (principalNames.contains(principalName)) {
            return false;
        }
        
        // Add the new principal name
        principalNames.add(principalName);
        
        // Create new Value array
        Value[] newValues = new Value[principalNames.size()];
        for (int i = 0; i < principalNames.size(); i++) {
            newValues[i] = valueFactory.createValue(principalNames.get(i));
        }
        
        // Set the property on the user
        user.setProperty(REP_EXTERNAL_PRINCIPAL_NAMES, newValues);
        
        // Update sync timestamps
        long now = System.currentTimeMillis();
        user.setProperty("rep:lastDynamicSync", valueFactory.createValue(now));
        user.setProperty("rep:lastSynced", valueFactory.createValue(now));
        
        return true;
    }
    
    @Override
    protected void doGet(final SlingHttpServletRequest request,
                         final SlingHttpServletResponse response) throws ServletException, IOException {
        
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        PrintWriter writer = response.getWriter();
        
        writer.write("{\"servlet\": \"GroupMigrationServlet\", " +
                "\"description\": \"Migrates local groups to external groups\", " +
                "\"usage\": \"POST /bin/wintergw2025/group-migration?groupPath=<groupPath>&idpName=<idpName>\", " +
                "\"parameters\": {" +
                "\"groupPath\": \"Path to the local group to migrate (required)\", " +
                "\"idpName\": \"Identity provider name (required)\"" +
                "}}");
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
