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
    protected void init() throws RepositoryException {
        PageManager pageManager = resourceResolver.adaptTo(PageManager.class);
        String currentPagePath = Optional.ofNullable(pageManager)
                .map(pm -> pm.getContainingPage(currentResource))
                .map(Page::getPath).orElse("");

        Session session = resourceResolver.adaptTo(Session.class);
        UserManager um = resourceResolver.adaptTo(UserManager.class);
        Authorizable user = um.getAuthorizable(session.getUserID());
        
        message = "Hello World!\n"
                + "Resource type is: " + resourceType + "\n"
                + "Current page is:  " + currentPagePath + "\n"
                + "Logged User:\n" +
                "Path: " + user.getPath() + "\n" +
                "PrincipalName: " + user.getPrincipal().getName() + "\n" +
                "givenName:" + user.getProperty("profile/given_name") + "\n" +
                "surname:" + user.getProperty("profile/family_name") + "\n" +
                "Groups: \n";
        message += "<ul>\n";
        Iterator<Group> memberOf = user.memberOf();
        while (memberOf.hasNext()) {
            message = message + "<li>" + memberOf.next().getPath() + "\n";
        }
        message += "</ul>\n";
    }

    public String getMessage() {
        return message;
    }

}
