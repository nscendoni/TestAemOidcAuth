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
                message += "User information not available (anonymous access)";
                return;
            }
            
            String userId = session.getUserID();
            Authorizable user = um.getAuthorizable(userId);
            
            if (user == null) {
                message += "Logged User: " + userId + " (user details not available)";
                return;
            }
            
            message += "Logged User:\n" +
                    "Path: " + user.getPath() + "\n" +
                    "PrincipalName: " + user.getPrincipal().getName() + "\n";
            
            Value[] givenNameValues = user.getProperty("profile/given_name");
            if (givenNameValues != null && givenNameValues.length > 0) {
                message += "profile/given_name: " + givenNameValues[0].getString() + "\n";
            }
            
            Value[] familyNameValues = user.getProperty("profile/family_name");
            if (familyNameValues != null && familyNameValues.length > 0) {
                message += "profile/family_name: " + familyNameValues[0].getString() + "\n";
            }
            
            message += "\nGroups:\n";
            Iterator<Group> memberOf = user.memberOf();
            while (memberOf.hasNext()) {
                message += "- " + memberOf.next().getPath() + "\n";
            }
        } catch (RepositoryException e) {
            message += "Error retrieving user information: " + e.getMessage();
        }
    }

    public String getMessage() {
        return message;
    }

}
