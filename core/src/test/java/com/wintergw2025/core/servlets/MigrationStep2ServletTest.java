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
import javax.jcr.Value;
import javax.jcr.ValueFactory;
import javax.servlet.ServletException;
import java.io.IOException;
import java.util.Arrays;
import java.util.Calendar;
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
class MigrationStep2ServletTest {

    @Mock
    private SlingRepository repository;

    @Mock
    private JackrabbitSession serviceSession;

    @Mock
    private UserManager userManager;

    @Mock
    private ValueFactory valueFactory;

    @Mock
    private User user;

    @Mock
    private Group group1;

    @Mock
    private Group group2;

    @Mock
    private Group everyoneGroup;

    @Mock
    private Value mockValue;

    private MigrationStep2Servlet fixture;

    @BeforeEach
    void setUp() throws RepositoryException {
        fixture = new MigrationStep2Servlet();
        
        // Use reflection to inject the repository mock
        try {
            java.lang.reflect.Field field = MigrationStep2Servlet.class.getDeclaredField("repository");
            field.setAccessible(true);
            field.set(fixture, repository);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // Setup common mock behaviors
        when(repository.loginService(eq("group-provisioner"), any())).thenReturn(serviceSession);
        when(serviceSession.getUserManager()).thenReturn(userManager);
        when(serviceSession.getValueFactory()).thenReturn(valueFactory);
        when(serviceSession.getUserID()).thenReturn("group-provisioner");
        when(serviceSession.isLive()).thenReturn(true);
        when(valueFactory.createValue(anyString())).thenReturn(mockValue);
        when(valueFactory.createValue(any(Calendar.class))).thenReturn(mockValue);
    }

    @Test
    void testDoGetReturnsServletInfo(AemContext context) throws ServletException, IOException {
        MockSlingHttpServletRequest request = context.request();
        MockSlingHttpServletResponse response = context.response();

        fixture.doGet(request, response);

        assertTrue(response.getContentType().startsWith("application/json"));
        assertEquals("UTF-8", response.getCharacterEncoding());
        String output = response.getOutputAsString();
        assertTrue(output.contains("MigrationStep2Servlet"));
        assertTrue(output.contains("Assigns dynamic groups to user"));
    }

    @Test
    void testDoPostWithoutUserId(AemContext context) throws ServletException, IOException {
        MockSlingHttpServletRequest request = context.request();
        MockSlingHttpServletResponse response = context.response();

        fixture.doPost(request, response);

        assertEquals(400, response.getStatus());
        assertTrue(response.getOutputAsString().contains("userId parameter is required"));
    }

    @Test
    void testDoPostWithoutIdpName(AemContext context) throws ServletException, IOException {
        MockSlingHttpServletRequest request = context.request();
        request.addRequestParameter("userId", "testuser");
        MockSlingHttpServletResponse response = context.response();

        fixture.doPost(request, response);

        assertEquals(400, response.getStatus());
        assertTrue(response.getOutputAsString().contains("idpName parameter is required"));
    }

    @Test
    void testDoPostWithNonExistentUser(AemContext context) throws ServletException, IOException, RepositoryException {
        when(userManager.getAuthorizable("nonexistent")).thenReturn(null);

        MockSlingHttpServletRequest request = context.request();
        request.addRequestParameter("userId", "nonexistent");
        request.addRequestParameter("idpName", "saml-idp");
        MockSlingHttpServletResponse response = context.response();

        fixture.doPost(request, response);

        assertEquals(404, response.getStatus());
        assertTrue(response.getOutputAsString().contains("User not found"));
    }

    @Test
    void testDoPostWithGroupInsteadOfUser(AemContext context) throws ServletException, IOException, RepositoryException {
        when(userManager.getAuthorizable("groupId")).thenReturn(group1);
        when(group1.isGroup()).thenReturn(true);

        MockSlingHttpServletRequest request = context.request();
        request.addRequestParameter("userId", "groupId");
        request.addRequestParameter("idpName", "saml-idp");
        MockSlingHttpServletResponse response = context.response();

        fixture.doPost(request, response);

        assertEquals(400, response.getStatus());
        assertTrue(response.getOutputAsString().contains("is a group, not a user"));
    }

    @Test
    void testDoPostSuccessfullyAssignsDynamicGroups(AemContext context) throws ServletException, IOException, RepositoryException {
        String userId = "testuser";
        String idpName = "saml-idp";

        // Setup user with external ID
        when(userManager.getAuthorizable(userId)).thenReturn(user);
        when(user.isGroup()).thenReturn(false);
        when(user.getID()).thenReturn(userId);
        when(user.getPath()).thenReturn("/home/users/t/testuser");
        when(user.getProperty("rep:externalId")).thenReturn(new Value[]{mockValue});
        when(user.getProperty("rep:externalPrincipalNames")).thenReturn(null);

        // Setup group memberships
        when(group1.getID()).thenReturn("marketing");
        when(group2.getID()).thenReturn("sales");
        Iterator<Group> groupIterator = Arrays.asList(group1, group2).iterator();
        when(user.declaredMemberOf()).thenReturn(groupIterator);

        MockSlingHttpServletRequest request = context.request();
        request.addRequestParameter("userId", userId);
        request.addRequestParameter("idpName", idpName);
        MockSlingHttpServletResponse response = context.response();

        fixture.doPost(request, response);

        assertEquals(200, response.getStatus());
        String output = response.getOutputAsString();
        assertTrue(output.contains("\"success\": true"));
        assertTrue(output.contains("\"principalsAdded\": 2"));
        assertTrue(output.contains("\"groupMembershipsChecked\": 2"));
        
        verify(user).setProperty(eq("rep:externalPrincipalNames"), any(Value[].class));
        verify(user).setProperty(eq("rep:lastDynamicSync"), any(Value.class));
        verify(user).setProperty(eq("rep:lastSynced"), any(Value.class));
        verify(serviceSession).save();
        verify(serviceSession).logout();
    }

    @Test
    void testDoPostConvertsLocalUserToExternal(AemContext context) throws ServletException, IOException, RepositoryException {
        String userId = "localuser";
        String idpName = "saml-idp";

        // Setup user without external ID
        when(userManager.getAuthorizable(userId)).thenReturn(user);
        when(user.isGroup()).thenReturn(false);
        when(user.getID()).thenReturn(userId);
        when(user.getPath()).thenReturn("/home/users/l/localuser");
        when(user.getProperty("rep:externalId")).thenReturn(null); // No external ID
        when(user.getProperty("rep:externalPrincipalNames")).thenReturn(null);
        when(user.declaredMemberOf()).thenReturn(Collections.emptyIterator());

        MockSlingHttpServletRequest request = context.request();
        request.addRequestParameter("userId", userId);
        request.addRequestParameter("idpName", idpName);
        MockSlingHttpServletResponse response = context.response();

        fixture.doPost(request, response);

        assertEquals(200, response.getStatus());
        String output = response.getOutputAsString();
        assertTrue(output.contains("\"success\": true"));
        assertTrue(output.contains("\"userConverted\": true"));
        
        verify(user).setProperty(eq("rep:externalId"), any(Value.class));
        verify(serviceSession).save();
    }

    @Test
    void testDoPostSkipsEveryoneGroup(AemContext context) throws ServletException, IOException, RepositoryException {
        String userId = "testuser";
        String idpName = "saml-idp";

        // Setup user
        when(userManager.getAuthorizable(userId)).thenReturn(user);
        when(user.isGroup()).thenReturn(false);
        when(user.getID()).thenReturn(userId);
        when(user.getPath()).thenReturn("/home/users/t/testuser");
        when(user.getProperty("rep:externalId")).thenReturn(new Value[]{mockValue});
        when(user.getProperty("rep:externalPrincipalNames")).thenReturn(null);

        // Setup group memberships including "everyone"
        when(group1.getID()).thenReturn("marketing");
        when(everyoneGroup.getID()).thenReturn("everyone");
        Iterator<Group> groupIterator = Arrays.asList(group1, everyoneGroup).iterator();
        when(user.declaredMemberOf()).thenReturn(groupIterator);

        MockSlingHttpServletRequest request = context.request();
        request.addRequestParameter("userId", userId);
        request.addRequestParameter("idpName", idpName);
        MockSlingHttpServletResponse response = context.response();

        fixture.doPost(request, response);

        assertEquals(200, response.getStatus());
        String output = response.getOutputAsString();
        assertTrue(output.contains("\"success\": true"));
        assertTrue(output.contains("\"principalsAdded\": 1")); // Only marketing, not everyone
        assertTrue(output.contains("\"systemGroupsSkipped\": 1"));
    }

    @Test
    void testDoPostWithExistingPrincipals(AemContext context) throws ServletException, IOException, RepositoryException {
        String userId = "testuser";
        String idpName = "saml-idp";
        String existingPrincipal = "marketing;" + idpName;

        // Setup user with existing principals
        when(userManager.getAuthorizable(userId)).thenReturn(user);
        when(user.isGroup()).thenReturn(false);
        when(user.getID()).thenReturn(userId);
        when(user.getPath()).thenReturn("/home/users/t/testuser");
        when(user.getProperty("rep:externalId")).thenReturn(new Value[]{mockValue});
        
        Value existingValue = mock(Value.class);
        when(existingValue.getString()).thenReturn(existingPrincipal);
        when(user.getProperty("rep:externalPrincipalNames")).thenReturn(new Value[]{existingValue});

        // Setup group membership that already has a principal
        when(group1.getID()).thenReturn("marketing");
        Iterator<Group> groupIterator = Collections.singletonList(group1).iterator();
        when(user.declaredMemberOf()).thenReturn(groupIterator);

        MockSlingHttpServletRequest request = context.request();
        request.addRequestParameter("userId", userId);
        request.addRequestParameter("idpName", idpName);
        MockSlingHttpServletResponse response = context.response();

        fixture.doPost(request, response);

        assertEquals(200, response.getStatus());
        String output = response.getOutputAsString();
        assertTrue(output.contains("\"success\": true"));
        assertTrue(output.contains("\"principalsAdded\": 0")); // Already exists
        assertTrue(output.contains("\"principalsSkipped\": 1"));
    }

    @Test
    void testDoPostWithRepositoryException(AemContext context) throws ServletException, IOException, RepositoryException {
        when(userManager.getAuthorizable(anyString())).thenThrow(new RepositoryException("Test exception"));

        MockSlingHttpServletRequest request = context.request();
        request.addRequestParameter("userId", "testuser");
        request.addRequestParameter("idpName", "saml-idp");
        MockSlingHttpServletResponse response = context.response();

        fixture.doPost(request, response);

        assertEquals(500, response.getStatus());
        assertTrue(response.getOutputAsString().contains("Repository error"));
        verify(serviceSession).logout();
    }

    @Test
    void testDoPostSessionClosedProperly(AemContext context) throws ServletException, IOException, RepositoryException {
        when(userManager.getAuthorizable("testuser")).thenReturn(user);
        when(user.isGroup()).thenReturn(false);
        when(user.getID()).thenReturn("testuser");
        when(user.getPath()).thenReturn("/home/users/t/testuser");
        when(user.getProperty("rep:externalId")).thenReturn(new Value[]{mockValue});
        when(user.getProperty("rep:externalPrincipalNames")).thenReturn(null);
        when(user.declaredMemberOf()).thenReturn(Collections.emptyIterator());

        MockSlingHttpServletRequest request = context.request();
        request.addRequestParameter("userId", "testuser");
        request.addRequestParameter("idpName", "saml-idp");
        MockSlingHttpServletResponse response = context.response();

        fixture.doPost(request, response);

        verify(serviceSession).logout();
    }
}
