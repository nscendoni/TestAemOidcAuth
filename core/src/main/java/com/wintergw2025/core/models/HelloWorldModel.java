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
package com.wintergw2025.core.models;

import static org.apache.sling.api.resource.ResourceResolver.PROPERTY_RESOURCE_TYPE;

import javax.annotation.PostConstruct;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Value;

import org.apache.jackrabbit.api.security.user.Authorizable;
import org.apache.jackrabbit.api.security.user.Group;
import org.apache.jackrabbit.api.security.user.UserManager;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.models.annotations.Default;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.InjectionStrategy;
import org.apache.sling.models.annotations.injectorspecific.SlingObject;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;

import com.day.cq.wcm.api.Page;
import com.day.cq.wcm.api.PageManager;

import java.util.Iterator;
import java.util.Optional;

@Model(adaptables = Resource.class)
public class HelloWorldModel {

    @ValueMapValue(name=PROPERTY_RESOURCE_TYPE, injectionStrategy=InjectionStrategy.OPTIONAL)
    @Default(values="No resourceType")
    protected String resourceType;

    @SlingObject
    private Resource currentResource;
    @SlingObject
    private ResourceResolver resourceResolver;

    private String message;

    @PostConstruct
    protected void init() {
        PageManager pageManager = resourceResolver.adaptTo(PageManager.class);
        String currentPagePath = Optional.ofNullable(pageManager)
                .map(pm -> pm.getContainingPage(currentResource))
                .map(Page::getPath).orElse("");
        
        message = "Hello World!\n"
                + "Resource type is: " + resourceType + "\n"
                + "Current page is:  " + currentPagePath + "\n\n";

        try {
            Session session = resourceResolver.adaptTo(Session.class);
            UserManager um = resourceResolver.adaptTo(UserManager.class);
            
            if (session == null || um == null) {
                message += "⚠️ You are browsing as ANONYMOUS\n\n"
                        + "User information is not available.\n"
                        + "Please log in to see your profile details.";
                return;
            }
            
            String userId = session.getUserID();
            
            // Check if user is anonymous
            if ("anonymous".equals(userId)) {
                message += "⚠️ You are browsing as ANONYMOUS\n\n"
                        + "User ID: " + userId + "\n\n"
                        + "You are not logged in.\n"
                        + "Please authenticate to see your profile details.";
                return;
            }
            
            Authorizable user = um.getAuthorizable(userId);
            
            if (user == null) {
                message += "Logged User: " + userId + " (user details not available)";
                return;
            }
            
            message += "✅ Logged User:\n" +
                    "User ID: " + userId + "\n" +
                "Path: " + user.getPath() + "\n" +
                "PrincipalName: " + user.getPrincipal().getName() + "\n";
            
            // Print all properties of the user
            // Print properties under the user's profile node (not .profile)
            javax.jcr.Node userNode = null;
            try {
                userNode = session.getNode(user.getPath());
            } catch (RepositoryException e) {
                message += "Unable to retrieve user node for profile properties: " + e.getMessage() + "\n";
            }

            if (userNode != null) {
                if (userNode.hasNode("profile")) {
                    try {
                        javax.jcr.Node profileNode = userNode.getNode("profile");
                        message += "User Profile Properties (profile):\n";
                        javax.jcr.PropertyIterator pi = profileNode.getProperties();
                        while (pi.hasNext()) {
                            javax.jcr.Property prop = pi.nextProperty();
                            // Skip jcr:primaryType and other protected properties if needed
                            String propName = prop.getName();
                            if ("jcr:primaryType".equals(propName)) {
                                continue;
                            }
                            message += propName + ": ";
                            if (prop.isMultiple()) {
                                javax.jcr.Value[] values = prop.getValues();
                                for (int i = 0; i < values.length; i++) {
                                    message += values[i].getString();
                                    if (i < values.length - 1) {
                                        message += ", ";
                                    }
                                }
                            } else {
                                message += prop.getValue().getString();
                            }
                            message += "\n";
                        }
                    } catch (RepositoryException e) {
                        message += "Error getting profile properties: " + e.getMessage() + "\n";
                    }
                } else {
                    message += "No profile node found for user.\n";
                }
            }
            
            message += "\nGroups:\n";
            Iterator<Group> memberOf = user.memberOf();
            while (memberOf.hasNext()) {
                Group group = memberOf.next();
                String groupName = group.getPrincipal().getName();
                String groupPath = group.getPath();
                message += "- " + groupName + " (" + groupPath + ")\n";
            }
        } catch (RepositoryException e) {
            message += "Error retrieving user information: " + e.getMessage();
        }
    }

    public String getMessage() {
        return message;
    }

}
