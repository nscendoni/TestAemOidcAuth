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
import javax.servlet.Servlet;
import javax.servlet.ServletException;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Migration Step 3 Servlet - Removes user members from a local group.
 * Accessible at: /bin/wintergw2025/migration-step3
 * 
 * This servlet performs the following operations:
 * 1. For a local group passed as input parameter:
 *    - For each member of the group, if the member is a user, remove it from the group
 * 
 * This is typically used after users have been set up with dynamic group membership
 * via external principals, so direct user memberships are no longer needed.
 * 
 * Usage:
 *   POST /bin/wintergw2025/migration-step3?groupPath=<groupPath>
 */
@Component(service = { Servlet.class })
@SlingServletPaths("/bin/wintergw2025/migration-step3")
@ServiceDescription("Migration Step 3 Servlet - Removes user members from a local group")
public class MigrationStep3Servlet extends SlingAllMethodsServlet {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LoggerFactory.getLogger(MigrationStep3Servlet.class);
    
    private static final String SERVICE_USER = "group-provisioner";

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
        
        LOG.info("MigrationStep3Servlet: Processing group at path '{}'", groupPath);
        
        Session serviceSession = null;
        try {
            // Login as the service user using SlingRepository.loginService()
            serviceSession = repository.loginService(SERVICE_USER, null);
            UserManager userManager = ((JackrabbitSession) serviceSession).getUserManager();
            
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
            
            // Process each member of the local group
            Iterator<Authorizable> members = localGroup.getDeclaredMembers();
            List<String> removedUsers = new ArrayList<>();
            List<String> skippedGroups = new ArrayList<>();
            int usersRemoved = 0;
            
            while (members.hasNext()) {
                Authorizable member = members.next();
                
                // Skip if member is a group, keep group memberships
                if (member.isGroup()) {
                    skippedGroups.add(member.getID());
                    LOG.info("Skipping group member '{}' (group memberships are preserved)", member.getID());
                    continue;
                }
                
                // Remove user from group
                User user = (User) member;
                String userId = user.getID();
                
                boolean removed = localGroup.removeMember(member);
                if (removed) {
                    removedUsers.add(userId);
                    usersRemoved++;
                    LOG.info("Removed user '{}' from group '{}'", userId, localGroupId);
                } else {
                    LOG.warn("Failed to remove user '{}' from group '{}'", userId, localGroupId);
                }
            }
            
            // Save the session
            serviceSession.save();
            
            StringBuilder message = new StringBuilder();
            message.append("Successfully processed group '").append(escapeJson(localGroupId)).append("'. ");
            if (usersRemoved > 0) {
                message.append("Removed ").append(usersRemoved).append(" user members from the group. ");
            } else {
                message.append("No user members found to remove. ");
            }
            if (!skippedGroups.isEmpty()) {
                message.append("Preserved ").append(skippedGroups.size()).append(" group memberships.");
            }
            
            LOG.info(message.toString());
            
            writer.write("{\"success\": true, \"message\": \"" + message.toString() + 
                    "\", \"localGroupId\": \"" + escapeJson(localGroupId) +
                    "\", \"usersRemoved\": " + usersRemoved +
                    ", \"groupMembersPreserved\": " + skippedGroups.size() +
                    ", \"removedUsers\": " + toJsonArray(removedUsers) +
                    ", \"preservedGroups\": " + toJsonArray(skippedGroups) + "}");
            
        } catch (RepositoryException e) {
            LOG.error("Repository error while processing migration step 3", e);
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
        
        writer.write("{\"servlet\": \"MigrationStep3Servlet\", " +
                "\"description\": \"Removes user members from a local group\", " +
                "\"usage\": \"POST /bin/wintergw2025/migration-step3?groupPath=<groupPath>\", " +
                "\"parameters\": {" +
                "\"groupPath\": \"Path to the local group (required)\"" +
                "}, " +
                "\"operations\": [" +
                "\"1. Get all members of the local group\", " +
                "\"2. Remove user members from the group (preserve group memberships)\"" +
                "], " +
                "\"note\": \"This is typically used after dynamic group membership has been set up via external principals.\"}");
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
