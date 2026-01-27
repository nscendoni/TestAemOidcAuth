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
import javax.jcr.ValueFactory;
import javax.servlet.Servlet;
import javax.servlet.ServletException;
import java.io.IOException;
import java.io.PrintWriter;
import java.security.Principal;

/**
 * Migration Step 1 Servlet - Creates external group and adds it to local group.
 * Accessible at: /bin/wintergw2025/migration-step1
 * 
 * This servlet performs the following operations:
 * 1. Create a corresponding external group with principalName: <local group name>;<idpName>
 * 2. Add the external group as a member of the local group
 * 
 * Usage:
 *   POST /bin/wintergw2025/migration-step1?groupPath=<groupPath>&idpName=<idpName>
 */
@Component(service = { Servlet.class })
@SlingServletPaths("/bin/wintergw2025/migration-step1")
@ServiceDescription("Migration Step 1 Servlet - Creates external group and adds it to local group")
public class MigrationStep1Servlet extends SlingAllMethodsServlet {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LoggerFactory.getLogger(MigrationStep1Servlet.class);
    
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
        
        // Get the idpName parameter
        String idpName = request.getParameter("idpName");
        if (idpName == null || idpName.trim().isEmpty()) {
            response.setStatus(SlingHttpServletResponse.SC_BAD_REQUEST);
            writer.write("{\"success\": false, \"error\": \"idpName parameter is required\"}");
            return;
        }
        
        LOG.info("MigrationStep1Servlet: Processing group at path '{}' with idpName '{}'", 
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
            
            // Step 1: Create the external group name: <local group>;<idpName>
            String externalGroupPrincipalName = localGroupId + ";" + idpName;
            LOG.info("Creating external group with principal name: '{}'", externalGroupPrincipalName);
            
            // Ensure the external group exists
            Group externalGroup = ensureExternalGroupExists(userManager, valueFactory, 
                    externalGroupPrincipalName, localGroupId, idpName);
            
            // Step 2: Add the external group as a member of the local group
            boolean groupAdded = localGroup.addMember(externalGroup);
            if (groupAdded) {
                LOG.info("Added external group '{}' as member of local group '{}'", 
                        externalGroupPrincipalName, localGroupId);
            } else {
                LOG.info("External group '{}' was already a member of local group '{}'", 
                        externalGroupPrincipalName, localGroupId);
            }
            
            // Save the session
            serviceSession.save();
            
            String message = "Successfully processed group '" + escapeJson(localGroupId) +
                    "'. Created external group '" + escapeJson(externalGroupPrincipalName) +
                    "' and added it to the local group.";
            
            LOG.info(message);
            
            writer.write("{\"success\": true, \"message\": \"" + message + 
                    "\", \"localGroupId\": \"" + escapeJson(localGroupId) +
                    "\", \"externalGroupPrincipalName\": \"" + escapeJson(externalGroupPrincipalName) +
                    "\", \"externalGroupAdded\": " + groupAdded + "}");
            
        } catch (RepositoryException e) {
            LOG.error("Repository error while processing migration step 1", e);
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
     * @param externalGroupPrincipalName the external group principal name (format: localGroupId;idpName)
     * @param localGroupId the original local group ID
     * @param idpName the identity provider name for rep:externalId
     * @return the external Group
     * @throws RepositoryException if there's an error accessing the repository
     */
    private Group ensureExternalGroupExists(UserManager userManager, ValueFactory valueFactory, 
            String externalGroupPrincipalName, String localGroupId, String idpName) throws RepositoryException {
        
        Authorizable existing = userManager.getAuthorizable(externalGroupPrincipalName);
        
        if (existing != null) {
            if (existing.isGroup()) {
                LOG.info("External group '{}' already exists", externalGroupPrincipalName);
                return (Group) existing;
            } else {
                throw new RepositoryException("An authorizable with ID '" + externalGroupPrincipalName + 
                        "' exists but is not a group");
            }
        }
        
        // Create the group with a Principal
        final String finalGroupId = externalGroupPrincipalName;
        Principal groupPrincipal = new Principal() {
            @Override
            public String getName() {
                return finalGroupId;
            }
        };
        
        Group group = userManager.createGroup(groupPrincipal);
        LOG.info("Created external group '{}' at path '{}'", externalGroupPrincipalName, group.getPath());
        
        // Set the rep:externalId property to mark it as an external group
        // Use the localGroupId as the external user ID part
        ExternalIdentityRef externalRef = new ExternalIdentityRef(localGroupId, idpName);
        String externalId = externalRef.getString();
        
        group.setProperty("rep:externalId", valueFactory.createValue(externalId));
        LOG.info("Set rep:externalId='{}' on external group '{}'", externalId, externalGroupPrincipalName);
        
        return group;
    }
    
    @Override
    protected void doGet(final SlingHttpServletRequest request,
                         final SlingHttpServletResponse response) throws ServletException, IOException {
        
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        PrintWriter writer = response.getWriter();
        
        writer.write("{\"servlet\": \"MigrationStep1Servlet\", " +
                "\"description\": \"Creates external group and adds it to local group\", " +
                "\"usage\": \"POST /bin/wintergw2025/migration-step1?groupPath=<groupPath>&idpName=<idpName>\", " +
                "\"parameters\": {" +
                "\"groupPath\": \"Path to the local group (required)\", " +
                "\"idpName\": \"Identity provider name (required)\"" +
                "}, " +
                "\"operations\": [" +
                "\"1. Create external group with principalName: <local group name>;<idpName>\", " +
                "\"2. Add the external group as a member of the local group\"" +
                "]}");
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
