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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.security.Principal;
import java.util.Collections;

import javax.jcr.Session;
import javax.jcr.Value;

import org.apache.commons.lang3.StringUtils;
import org.apache.jackrabbit.api.security.user.Authorizable;
import org.apache.jackrabbit.api.security.user.Group;
import org.apache.jackrabbit.api.security.user.UserManager;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import com.day.cq.wcm.api.Page;
import com.wintergw2025.core.testcontext.AppAemContext;

import io.wcm.testing.mock.aem.junit5.AemContext;
import io.wcm.testing.mock.aem.junit5.AemContextExtension;

/**
 * JUnit test verifying the HelloWorldModel
 */
@ExtendWith({AemContextExtension.class, MockitoExtension.class})
class HelloWorldModelTest {

    private final AemContext context = AppAemContext.newAemContext();

    private HelloWorldModel hello;

    private Page page;
    private Resource resource;

    private static final String TEST_USER_ID = "testuser";
    private static final String TEST_USER_PATH = "/home/users/test/testuser";
    private static final String TEST_GIVEN_NAME = "John";
    private static final String TEST_FAMILY_NAME = "Doe";
    private static final String TEST_GROUP_NAME = "testgroup";
    private static final String TEST_GROUP_PATH = "/home/groups/test/testgroup";

    @BeforeEach
    public void setup() throws Exception {
        // Create mocks for JCR security
        Session mockSession = mock(Session.class);
        UserManager mockUserManager = mock(UserManager.class);
        Authorizable mockUser = mock(Authorizable.class);
        Principal mockPrincipal = mock(Principal.class);
        Group mockGroup = mock(Group.class);

        // Setup session mock
        when(mockSession.getUserID()).thenReturn(TEST_USER_ID);

        // Setup user mock
        when(mockUser.getPath()).thenReturn(TEST_USER_PATH);
        when(mockUser.getPrincipal()).thenReturn(mockPrincipal);
        when(mockPrincipal.getName()).thenReturn(TEST_USER_ID);

        // Setup user properties (given_name and family_name)
        Value mockGivenNameValue = mock(Value.class);
        when(mockGivenNameValue.getString()).thenReturn(TEST_GIVEN_NAME);
        when(mockUser.getProperty("profile/given_name")).thenReturn(new Value[]{mockGivenNameValue});

        Value mockFamilyNameValue = mock(Value.class);
        when(mockFamilyNameValue.getString()).thenReturn(TEST_FAMILY_NAME);
        when(mockUser.getProperty("profile/family_name")).thenReturn(new Value[]{mockFamilyNameValue});

        // Setup group mock
        Principal mockGroupPrincipal = mock(Principal.class);
        when(mockGroupPrincipal.getName()).thenReturn(TEST_GROUP_NAME);
        when(mockGroup.getPrincipal()).thenReturn(mockGroupPrincipal);
        when(mockGroup.getPath()).thenReturn(TEST_GROUP_PATH);
        when(mockUser.memberOf()).thenReturn(Collections.singletonList(mockGroup).iterator());

        // Setup UserManager mock
        when(mockUserManager.getAuthorizable(TEST_USER_ID)).thenReturn(mockUser);

        // Register adapters for Session and UserManager
        context.registerAdapter(ResourceResolver.class, Session.class, mockSession);
        context.registerAdapter(ResourceResolver.class, UserManager.class, mockUserManager);

        // Prepare a page with a test resource
        page = context.create().page("/content/mypage");
        resource = context.create().resource(page, "hello",
            "sling:resourceType", "wintergw2025/components/helloworld");

        // Create sling model
        hello = resource.adaptTo(HelloWorldModel.class);
    }

    @Test
    void testGetMessage() throws Exception {
        // Verify model was created
        assertNotNull(hello, "HelloWorldModel should not be null");

        // Get the message
        String msg = hello.getMessage();

        // Verify message is not null
        assertNotNull(msg, "Message should not be null");

        // Verify message contains expected content
        assertTrue(StringUtils.contains(msg, "Hello World!"), "Message should contain 'Hello World!'");
        assertTrue(StringUtils.contains(msg, resource.getResourceType()), "Message should contain resource type");
        assertTrue(StringUtils.contains(msg, page.getPath()), "Message should contain page path");
    }

    @Test
    void testMessageContainsUserPath() throws Exception {
        assertNotNull(hello, "HelloWorldModel should not be null");
        String msg = hello.getMessage();

        assertTrue(StringUtils.contains(msg, TEST_USER_PATH), 
            "Message should contain user path: " + TEST_USER_PATH);
    }

    @Test
    void testMessageContainsPrincipalName() throws Exception {
        assertNotNull(hello, "HelloWorldModel should not be null");
        String msg = hello.getMessage();

        assertTrue(StringUtils.contains(msg, "PrincipalName: " + TEST_USER_ID), 
            "Message should contain principal name");
    }

    @Test
    void testMessageContainsGivenName() throws Exception {
        assertNotNull(hello, "HelloWorldModel should not be null");
        String msg = hello.getMessage();

        assertTrue(StringUtils.contains(msg, TEST_GIVEN_NAME), 
            "Message should contain given name: " + TEST_GIVEN_NAME);
    }

    @Test
    void testMessageContainsFamilyName() throws Exception {
        assertNotNull(hello, "HelloWorldModel should not be null");
        String msg = hello.getMessage();

        assertTrue(StringUtils.contains(msg, TEST_FAMILY_NAME), 
            "Message should contain family name: " + TEST_FAMILY_NAME);
    }

    @Test
    void testMessageContainsGroups() throws Exception {
        assertNotNull(hello, "HelloWorldModel should not be null");
        String msg = hello.getMessage();

        assertTrue(StringUtils.contains(msg, "Groups:"), "Message should contain 'Groups:' section");
        assertTrue(StringUtils.contains(msg, TEST_GROUP_NAME), 
            "Message should contain group name: " + TEST_GROUP_NAME);
        assertTrue(StringUtils.contains(msg, TEST_GROUP_PATH), 
            "Message should contain group path: " + TEST_GROUP_PATH);
    }

    @Test
    void testMessageContainsResourceType() throws Exception {
        assertNotNull(hello, "HelloWorldModel should not be null");
        String msg = hello.getMessage();

        assertTrue(StringUtils.contains(msg, "wintergw2025/components/helloworld"), 
            "Message should contain the resource type");
    }
}
