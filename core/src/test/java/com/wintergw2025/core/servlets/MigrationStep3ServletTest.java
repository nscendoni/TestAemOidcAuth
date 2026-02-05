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
import org.apache.sling.jcr.api.SlingRepository;
import org.apache.sling.testing.mock.sling.servlet.MockSlingHttpServletRequest;
import org.apache.sling.testing.mock.sling.servlet.MockSlingHttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.wcm.testing.mock.aem.junit5.AemContext;
import io.wcm.testing.mock.aem.junit5.AemContextExtension;

import javax.jcr.RepositoryException;
import javax.servlet.ServletException;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith({AemContextExtension.class, MockitoExtension.class})
@MockitoSettings(strictness = Strictness.LENIENT)
class MigrationStep3ServletTest {

    @Mock
    private SlingRepository repository;

    @Mock
    private JackrabbitSession serviceSession;

    @Mock
    private UserManager userManager;

    @Mock
    private Group localGroup;

    @Mock
    private Group childGroup;

    @Mock
    private User user1;

    @Mock
    private User user2;

    private MigrationStep3Servlet fixture;

    @BeforeEach
    void setUp() throws RepositoryException {
        fixture = new MigrationStep3Servlet();
        
        // Use reflection to inject the repository mock
        try {
            java.lang.reflect.Field field = MigrationStep3Servlet.class.getDeclaredField("repository");
            field.setAccessible(true);
            field.set(fixture, repository);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // Setup common mock behaviors
        when(repository.loginService(eq("group-provisioner"), any())).thenReturn(serviceSession);
        when(serviceSession.getUserManager()).thenReturn(userManager);
        when(serviceSession.getUserID()).thenReturn("group-provisioner");
        when(serviceSession.isLive()).thenReturn(true);
    }

    @Test
    void testDoGetReturnsServletInfo(AemContext context) throws ServletException, IOException {
        MockSlingHttpServletRequest request = context.request();
        MockSlingHttpServletResponse response = context.response();

        fixture.doGet(request, response);

        assertTrue(response.getContentType().startsWith("application/json"));
        assertEquals("UTF-8", response.getCharacterEncoding());
        String output = response.getOutputAsString();
        assertTrue(output.contains("MigrationStep3Servlet"));
        assertTrue(output.contains("Removes user members from a local group"));
    }

    @Test
    void testDoPostWithoutGroupPath(AemContext context) throws ServletException, IOException {
        MockSlingHttpServletRequest request = context.request();
        MockSlingHttpServletResponse response = context.response();

        fixture.doPost(request, response);

        assertEquals(400, response.getStatus());
        assertTrue(response.getOutputAsString().contains("groupPath parameter is required"));
    }

    @Test
    void testDoPostWithNonExistentGroup(AemContext context) throws ServletException, IOException, RepositoryException {
        when(userManager.getAuthorizableByPath("/home/groups/nonexistent")).thenReturn(null);

        MockSlingHttpServletRequest request = context.request();
        request.addRequestParameter("groupPath", "/home/groups/nonexistent");
        MockSlingHttpServletResponse response = context.response();

        fixture.doPost(request, response);

        assertEquals(404, response.getStatus());
        assertTrue(response.getOutputAsString().contains("Group not found"));
    }

    @Test
    void testDoPostWithNonGroupAuthorizable(AemContext context) throws ServletException, IOException, RepositoryException {
        when(userManager.getAuthorizableByPath("/home/users/testuser")).thenReturn(user1);
        when(user1.isGroup()).thenReturn(false);

        MockSlingHttpServletRequest request = context.request();
        request.addRequestParameter("groupPath", "/home/users/testuser");
        MockSlingHttpServletResponse response = context.response();

        fixture.doPost(request, response);

        assertEquals(400, response.getStatus());
        assertTrue(response.getOutputAsString().contains("is not a group"));
    }

    @Test
    void testDoPostSuccessfullyRemovesUserMembers(AemContext context) throws ServletException, IOException, RepositoryException {
        String groupPath = "/home/groups/marketing";
        String groupId = "marketing";

        // Setup group
        when(userManager.getAuthorizableByPath(groupPath)).thenReturn(localGroup);
        when(localGroup.isGroup()).thenReturn(true);
        when(localGroup.getID()).thenReturn(groupId);

        // Setup user members
        when(user1.isGroup()).thenReturn(false);
        when(user1.getID()).thenReturn("user1");
        when(user2.isGroup()).thenReturn(false);
        when(user2.getID()).thenReturn("user2");

        Iterator<Authorizable> members = Arrays.asList((Authorizable) user1, (Authorizable) user2).iterator();
        when(localGroup.getDeclaredMembers()).thenReturn(members);
        when(localGroup.removeMember(user1)).thenReturn(true);
        when(localGroup.removeMember(user2)).thenReturn(true);

        MockSlingHttpServletRequest request = context.request();
        request.addRequestParameter("groupPath", groupPath);
        MockSlingHttpServletResponse response = context.response();

        fixture.doPost(request, response);

        assertEquals(200, response.getStatus());
        String output = response.getOutputAsString();
        assertTrue(output.contains("\"success\": true"));
        assertTrue(output.contains("\"usersRemoved\": 2"));
        
        verify(localGroup).removeMember(user1);
        verify(localGroup).removeMember(user2);
        verify(serviceSession).save();
        verify(serviceSession).logout();
    }

    @Test
    void testDoPostPreservesGroupMembers(AemContext context) throws ServletException, IOException, RepositoryException {
        String groupPath = "/home/groups/marketing";
        String groupId = "marketing";

        // Setup group
        when(userManager.getAuthorizableByPath(groupPath)).thenReturn(localGroup);
        when(localGroup.isGroup()).thenReturn(true);
        when(localGroup.getID()).thenReturn(groupId);

        // Setup mixed members: user and group
        when(user1.isGroup()).thenReturn(false);
        when(user1.getID()).thenReturn("user1");
        when(childGroup.isGroup()).thenReturn(true);
        when(childGroup.getID()).thenReturn("child-group");

        Iterator<Authorizable> members = Arrays.asList((Authorizable) user1, (Authorizable) childGroup).iterator();
        when(localGroup.getDeclaredMembers()).thenReturn(members);
        when(localGroup.removeMember(user1)).thenReturn(true);

        MockSlingHttpServletRequest request = context.request();
        request.addRequestParameter("groupPath", groupPath);
        MockSlingHttpServletResponse response = context.response();

        fixture.doPost(request, response);

        assertEquals(200, response.getStatus());
        String output = response.getOutputAsString();
        assertTrue(output.contains("\"success\": true"));
        assertTrue(output.contains("\"usersRemoved\": 1"));
        assertTrue(output.contains("\"groupMembersPreserved\": 1"));
        
        verify(localGroup).removeMember(user1);
        verify(localGroup, never()).removeMember(childGroup); // Group should not be removed
        verify(serviceSession).save();
    }

    @Test
    void testDoPostWithNoUserMembers(AemContext context) throws ServletException, IOException, RepositoryException {
        String groupPath = "/home/groups/marketing";
        String groupId = "marketing";

        // Setup group with only group members
        when(userManager.getAuthorizableByPath(groupPath)).thenReturn(localGroup);
        when(localGroup.isGroup()).thenReturn(true);
        when(localGroup.getID()).thenReturn(groupId);

        when(childGroup.isGroup()).thenReturn(true);
        when(childGroup.getID()).thenReturn("child-group");

        Iterator<Authorizable> members = Collections.singletonList((Authorizable) childGroup).iterator();
        when(localGroup.getDeclaredMembers()).thenReturn(members);

        MockSlingHttpServletRequest request = context.request();
        request.addRequestParameter("groupPath", groupPath);
        MockSlingHttpServletResponse response = context.response();

        fixture.doPost(request, response);

        assertEquals(200, response.getStatus());
        String output = response.getOutputAsString();
        assertTrue(output.contains("\"success\": true"));
        assertTrue(output.contains("\"usersRemoved\": 0"));
        assertTrue(output.contains("No user members found to remove"));
        
        verify(localGroup, never()).removeMember(any(User.class));
        verify(serviceSession).save();
    }

    @Test
    void testDoPostWithEmptyGroup(AemContext context) throws ServletException, IOException, RepositoryException {
        String groupPath = "/home/groups/empty";
        String groupId = "empty";

        // Setup empty group
        when(userManager.getAuthorizableByPath(groupPath)).thenReturn(localGroup);
        when(localGroup.isGroup()).thenReturn(true);
        when(localGroup.getID()).thenReturn(groupId);
        when(localGroup.getDeclaredMembers()).thenReturn(Collections.emptyIterator());

        MockSlingHttpServletRequest request = context.request();
        request.addRequestParameter("groupPath", groupPath);
        MockSlingHttpServletResponse response = context.response();

        fixture.doPost(request, response);

        assertEquals(200, response.getStatus());
        String output = response.getOutputAsString();
        assertTrue(output.contains("\"success\": true"));
        assertTrue(output.contains("\"usersRemoved\": 0"));
    }

    @Test
    void testDoPostWithRepositoryException(AemContext context) throws ServletException, IOException, RepositoryException {
        when(userManager.getAuthorizableByPath(anyString())).thenThrow(new RepositoryException("Test exception"));

        MockSlingHttpServletRequest request = context.request();
        request.addRequestParameter("groupPath", "/home/groups/test");
        MockSlingHttpServletResponse response = context.response();

        fixture.doPost(request, response);

        assertEquals(500, response.getStatus());
        assertTrue(response.getOutputAsString().contains("Repository error"));
        verify(serviceSession).logout();
    }

    @Test
    void testDoPostSessionClosedProperly(AemContext context) throws ServletException, IOException, RepositoryException {
        when(userManager.getAuthorizableByPath(anyString())).thenReturn(localGroup);
        when(localGroup.isGroup()).thenReturn(true);
        when(localGroup.getID()).thenReturn("test");
        when(localGroup.getDeclaredMembers()).thenReturn(Collections.emptyIterator());

        MockSlingHttpServletRequest request = context.request();
        request.addRequestParameter("groupPath", "/home/groups/test");
        MockSlingHttpServletResponse response = context.response();

        fixture.doPost(request, response);

        verify(serviceSession).logout();
    }
}
