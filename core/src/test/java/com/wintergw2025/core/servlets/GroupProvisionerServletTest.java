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
import org.apache.sling.api.resource.ResourceResolver;
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
import javax.jcr.Session;
import javax.jcr.Value;
import javax.jcr.ValueFactory;
import javax.servlet.ServletException;
import java.io.IOException;
import java.security.Principal;
import java.util.Calendar;

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
class GroupProvisionerServletTest {

    private static final String TECHNICAL_ACCOUNT = "4FFECF71-3760-49F2-A883-B741AA1893C6@TECHACCT.ADOBE.COM";

    @Mock
    private SlingRepository repository;

    @Mock
    private JackrabbitSession serviceSession;

    @Mock
    private JackrabbitSession requestSession;

    @Mock
    private UserManager userManager;

    @Mock
    private ValueFactory valueFactory;

    @Mock
    private User user;

    @Mock
    private Group group;

    @Mock
    private Value mockValue;

    @Mock
    private ResourceResolver resourceResolver;

    private GroupProvisionerServlet fixture;

    @BeforeEach
    void setUp() throws RepositoryException {
        fixture = new GroupProvisionerServlet();
        
        // Use reflection to inject the repository mock
        try {
            java.lang.reflect.Field field = GroupProvisionerServlet.class.getDeclaredField("repository");
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
    void testDoGetWithUnauthorizedUser(AemContext context) throws ServletException, IOException {
        context.registerAdapter(ResourceResolver.class, Session.class, requestSession);
        when(requestSession.getUserID()).thenReturn("unauthorizedUser");

        MockSlingHttpServletRequest request = context.request();
        request.addRequestParameter("userId", "testuser");
        MockSlingHttpServletResponse response = context.response();

        fixture.doGet(request, response);

        assertEquals(403, response.getStatus());
        assertTrue(response.getOutputAsString().contains("Access denied"));
    }

    @Test
    void testDoGetWithAuthorizedUser(AemContext context) throws ServletException, IOException, RepositoryException {
        context.registerAdapter(ResourceResolver.class, Session.class, requestSession);
        when(requestSession.getUserID()).thenReturn(TECHNICAL_ACCOUNT);

        when(userManager.getAuthorizable("testuser")).thenReturn(user);
        when(user.isGroup()).thenReturn(false);
        when(user.getProperty("rep:externalPrincipalNames")).thenReturn(new Value[]{mockValue});
        when(mockValue.getString()).thenReturn("marketing:saml-idp");

        MockSlingHttpServletRequest request = context.request();
        request.addRequestParameter("userId", "testuser");
        MockSlingHttpServletResponse response = context.response();

        fixture.doGet(request, response);

        assertEquals(200, response.getStatus());
        String output = response.getOutputAsString();
        assertTrue(output.contains("\"success\": true"));
        assertTrue(output.contains("testuser"));
    }

    @Test
    void testDoGetWithoutUserId(AemContext context) throws ServletException, IOException {
        context.registerAdapter(ResourceResolver.class, Session.class, requestSession);
        when(requestSession.getUserID()).thenReturn(TECHNICAL_ACCOUNT);

        MockSlingHttpServletRequest request = context.request();
        MockSlingHttpServletResponse response = context.response();

        fixture.doGet(request, response);

        assertEquals(400, response.getStatus());
        assertTrue(response.getOutputAsString().contains("No userId provided"));
    }

    @Test
    void testDoGetWithNonExistentUser(AemContext context) throws ServletException, IOException, RepositoryException {
        context.registerAdapter(ResourceResolver.class, Session.class, requestSession);
        when(requestSession.getUserID()).thenReturn(TECHNICAL_ACCOUNT);
        when(userManager.getAuthorizable("nonexistent")).thenReturn(null);

        MockSlingHttpServletRequest request = context.request();
        request.addRequestParameter("userId", "nonexistent");
        MockSlingHttpServletResponse response = context.response();

        fixture.doGet(request, response);

        assertEquals(404, response.getStatus());
        assertTrue(response.getOutputAsString().contains("not found"));
    }

    @Test
    void testDoGetWithGroupInsteadOfUser(AemContext context) throws ServletException, IOException, RepositoryException {
        context.registerAdapter(ResourceResolver.class, Session.class, requestSession);
        when(requestSession.getUserID()).thenReturn(TECHNICAL_ACCOUNT);
        when(userManager.getAuthorizable("groupId")).thenReturn(group);
        when(group.isGroup()).thenReturn(true);

        MockSlingHttpServletRequest request = context.request();
        request.addRequestParameter("userId", "groupId");
        MockSlingHttpServletResponse response = context.response();

        fixture.doGet(request, response);

        assertEquals(400, response.getStatus());
        assertTrue(response.getOutputAsString().contains("is a group, not a user"));
    }

    @Test
    void testDoPostWithUnauthorizedUser(AemContext context) throws ServletException, IOException {
        context.registerAdapter(ResourceResolver.class, Session.class, requestSession);
        when(requestSession.getUserID()).thenReturn("unauthorizedUser");

        MockSlingHttpServletRequest request = context.request();
        request.addRequestParameter("userId", "testuser");
        MockSlingHttpServletResponse response = context.response();

        fixture.doPost(request, response);

        assertEquals(403, response.getStatus());
        assertTrue(response.getOutputAsString().contains("Access denied"));
    }

    @Test
    void testDoPostWithoutUserIdParamFallsBackToSession(AemContext context) throws ServletException, IOException, RepositoryException {
        // When no userId param is provided, servlet will try to get user from session
        // Set up the adapter and mock the session to return the technical account
        context.registerAdapter(ResourceResolver.class, Session.class, requestSession);
        when(requestSession.getUserID()).thenReturn(TECHNICAL_ACCOUNT);

        // Mock the user lookup for TECHNICAL_ACCOUNT
        when(userManager.getAuthorizable(TECHNICAL_ACCOUNT)).thenReturn(user);
        when(user.isGroup()).thenReturn(false);
        when(user.getID()).thenReturn(TECHNICAL_ACCOUNT);
        when(user.getProperty("rep:externalPrincipalNames")).thenReturn(null);
        
        // Mock group exists
        when(userManager.getAuthorizable("marketing:saml-idp")).thenReturn(group);
        when(group.isGroup()).thenReturn(true);

        MockSlingHttpServletRequest request = context.request();
        // Don't provide userId parameter - should fallback to session user
        MockSlingHttpServletResponse response = context.response();

        fixture.doPost(request, response);

        // Should succeed with the session user
        assertEquals(200, response.getStatus());
        assertTrue(response.getOutputAsString().contains("\"success\": true"));
    }

    @Test
    void testDoPostSuccessfullyAddsExternalPrincipal(AemContext context) throws ServletException, IOException, RepositoryException {
        context.registerAdapter(ResourceResolver.class, Session.class, requestSession);
        when(requestSession.getUserID()).thenReturn(TECHNICAL_ACCOUNT);

        String userId = "testuser";
        String principalName = "marketing:saml-idp";
        String idpName = "saml-idp";

        // Mock user exists
        when(userManager.getAuthorizable(userId)).thenReturn(user);
        when(user.isGroup()).thenReturn(false);
        when(user.getID()).thenReturn(userId);
        when(user.getProperty("rep:externalPrincipalNames")).thenReturn(null);

        // Mock group doesn't exist - will be created
        when(userManager.getAuthorizable(principalName)).thenReturn(null);
        when(userManager.createGroup(any(Principal.class))).thenReturn(group);
        when(group.getPath()).thenReturn("/home/groups/" + principalName);

        MockSlingHttpServletRequest request = context.request();
        request.addRequestParameter("userId", userId);
        request.addRequestParameter("principalName", principalName);
        request.addRequestParameter("idpName", idpName);
        MockSlingHttpServletResponse response = context.response();

        fixture.doPost(request, response);

        assertEquals(200, response.getStatus());
        String output = response.getOutputAsString();
        assertTrue(output.contains("\"success\": true"));
        assertTrue(output.contains(principalName));
        
        verify(user).setProperty(eq("rep:externalPrincipalNames"), any(Value[].class));
        verify(serviceSession).save();
        verify(serviceSession).logout();
    }

    @Test
    void testDoPostWithExistingPrincipal(AemContext context) throws ServletException, IOException, RepositoryException {
        context.registerAdapter(ResourceResolver.class, Session.class, requestSession);
        when(requestSession.getUserID()).thenReturn(TECHNICAL_ACCOUNT);

        String userId = "testuser";
        String principalName = "marketing:saml-idp";

        // Mock user exists with principal already set
        when(userManager.getAuthorizable(userId)).thenReturn(user);
        when(user.isGroup()).thenReturn(false);
        when(user.getID()).thenReturn(userId);
        when(user.getProperty("rep:externalPrincipalNames")).thenReturn(new Value[]{mockValue});
        when(mockValue.getString()).thenReturn(principalName);

        // Mock group exists
        when(userManager.getAuthorizable(principalName)).thenReturn(group);
        when(group.isGroup()).thenReturn(true);

        MockSlingHttpServletRequest request = context.request();
        request.addRequestParameter("userId", userId);
        request.addRequestParameter("principalName", principalName);
        MockSlingHttpServletResponse response = context.response();

        fixture.doPost(request, response);

        assertEquals(200, response.getStatus());
        String output = response.getOutputAsString();
        assertTrue(output.contains("\"success\": true"));
        assertTrue(output.contains("already exists"));
    }

    @Test
    void testDoPostCreatesUserIfNotExists(AemContext context) throws ServletException, IOException, RepositoryException {
        context.registerAdapter(ResourceResolver.class, Session.class, requestSession);
        when(requestSession.getUserID()).thenReturn(TECHNICAL_ACCOUNT);

        String userId = "newuser";
        String principalName = "marketing:saml-idp";
        String idpName = "saml-idp";

        // Mock user doesn't exist - will be created
        when(userManager.getAuthorizable(userId)).thenReturn(null);
        when(userManager.createUser(eq(userId), any(), any(Principal.class), any())).thenReturn(user);
        when(user.getPath()).thenReturn("/home/users/" + userId);
        when(user.getID()).thenReturn(userId);
        when(user.getProperty("rep:externalPrincipalNames")).thenReturn(null);

        // Mock group doesn't exist - will be created
        when(userManager.getAuthorizable(principalName)).thenReturn(null);
        when(userManager.createGroup(any(Principal.class))).thenReturn(group);
        when(group.getPath()).thenReturn("/home/groups/" + principalName);

        MockSlingHttpServletRequest request = context.request();
        request.addRequestParameter("userId", userId);
        request.addRequestParameter("principalName", principalName);
        request.addRequestParameter("idpName", idpName);
        MockSlingHttpServletResponse response = context.response();

        fixture.doPost(request, response);

        assertEquals(200, response.getStatus());
        String output = response.getOutputAsString();
        assertTrue(output.contains("\"success\": true"));
        assertTrue(output.contains("\"userCreated\": true"));
        assertTrue(output.contains("\"groupCreated\": true"));
        
        verify(userManager).createUser(eq(userId), any(), any(Principal.class), any());
        verify(userManager).createGroup(any(Principal.class));
        verify(serviceSession).save();
    }

    @Test
    void testDoPostWithDefaultPrincipalName(AemContext context) throws ServletException, IOException, RepositoryException {
        context.registerAdapter(ResourceResolver.class, Session.class, requestSession);
        when(requestSession.getUserID()).thenReturn(TECHNICAL_ACCOUNT);

        String userId = "testuser";
        String defaultPrincipal = "marketing:saml-idp";

        // Mock user exists
        when(userManager.getAuthorizable(userId)).thenReturn(user);
        when(user.isGroup()).thenReturn(false);
        when(user.getID()).thenReturn(userId);
        when(user.getProperty("rep:externalPrincipalNames")).thenReturn(null);

        // Mock group exists
        when(userManager.getAuthorizable(defaultPrincipal)).thenReturn(group);
        when(group.isGroup()).thenReturn(true);

        MockSlingHttpServletRequest request = context.request();
        request.addRequestParameter("userId", userId);
        // No principalName parameter - should use default
        MockSlingHttpServletResponse response = context.response();

        fixture.doPost(request, response);

        assertEquals(200, response.getStatus());
        String output = response.getOutputAsString();
        assertTrue(output.contains("\"success\": true"));
        assertTrue(output.contains(defaultPrincipal));
    }

    @Test
    void testDoPostWithRepositoryException(AemContext context) throws ServletException, IOException, RepositoryException {
        context.registerAdapter(ResourceResolver.class, Session.class, requestSession);
        when(requestSession.getUserID()).thenReturn(TECHNICAL_ACCOUNT);

        when(userManager.getAuthorizable(anyString())).thenThrow(new RepositoryException("Test exception"));

        MockSlingHttpServletRequest request = context.request();
        request.addRequestParameter("userId", "testuser");
        MockSlingHttpServletResponse response = context.response();

        fixture.doPost(request, response);

        assertEquals(500, response.getStatus());
        assertTrue(response.getOutputAsString().contains("Repository error"));
        verify(serviceSession).logout();
    }

    @Test
    void testDoPostSessionClosedProperly(AemContext context) throws ServletException, IOException, RepositoryException {
        context.registerAdapter(ResourceResolver.class, Session.class, requestSession);
        when(requestSession.getUserID()).thenReturn(TECHNICAL_ACCOUNT);

        when(userManager.getAuthorizable("testuser")).thenReturn(user);
        when(user.isGroup()).thenReturn(false);
        when(user.getID()).thenReturn("testuser");
        when(user.getProperty("rep:externalPrincipalNames")).thenReturn(null);
        
        when(userManager.getAuthorizable("marketing:saml-idp")).thenReturn(group);
        when(group.isGroup()).thenReturn(true);

        MockSlingHttpServletRequest request = context.request();
        request.addRequestParameter("userId", "testuser");
        MockSlingHttpServletResponse response = context.response();

        fixture.doPost(request, response);

        verify(serviceSession).logout();
    }
}
