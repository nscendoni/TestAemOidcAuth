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
import java.security.Principal;

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
class MigrationStep1ServletTest {

    @Mock
    private SlingRepository repository;

    @Mock
    private JackrabbitSession serviceSession;

    @Mock
    private UserManager userManager;

    @Mock
    private ValueFactory valueFactory;

    @Mock
    private Group localGroup;

    @Mock
    private Group externalGroup;

    @Mock
    private Value mockValue;

    private MigrationStep1Servlet fixture;

    @BeforeEach
    void setUp() throws RepositoryException {
        fixture = new MigrationStep1Servlet();
        
        // Use reflection to inject the repository mock
        try {
            java.lang.reflect.Field field = MigrationStep1Servlet.class.getDeclaredField("repository");
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
    }

    @Test
    void testDoGetReturnsServletInfo(AemContext context) throws ServletException, IOException {
        MockSlingHttpServletRequest request = context.request();
        MockSlingHttpServletResponse response = context.response();

        fixture.doGet(request, response);

        assertTrue(response.getContentType().startsWith("application/json"));
        assertEquals("UTF-8", response.getCharacterEncoding());
        String output = response.getOutputAsString();
        assertTrue(output.contains("MigrationStep1Servlet"));
        assertTrue(output.contains("Creates external group and adds it to local group"));
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
    void testDoPostWithoutIdpName(AemContext context) throws ServletException, IOException {
        MockSlingHttpServletRequest request = context.request();
        request.addRequestParameter("groupPath", "/home/groups/marketing");
        MockSlingHttpServletResponse response = context.response();

        fixture.doPost(request, response);

        assertEquals(400, response.getStatus());
        assertTrue(response.getOutputAsString().contains("idpName parameter is required"));
    }

    @Test
    void testDoPostWithNonExistentGroup(AemContext context) throws ServletException, IOException, RepositoryException {
        when(userManager.getAuthorizableByPath("/home/groups/nonexistent")).thenReturn(null);

        MockSlingHttpServletRequest request = context.request();
        request.addRequestParameter("groupPath", "/home/groups/nonexistent");
        request.addRequestParameter("idpName", "saml-idp");
        MockSlingHttpServletResponse response = context.response();

        fixture.doPost(request, response);

        assertEquals(404, response.getStatus());
        assertTrue(response.getOutputAsString().contains("Group not found"));
    }

    @Test
    void testDoPostSuccessfullyCreatesAndAddsExternalGroup(AemContext context) throws ServletException, IOException, RepositoryException {
        String groupPath = "/home/groups/marketing";
        String groupId = "marketing";
        String idpName = "saml-idp";
        String externalGroupId = groupId + ";" + idpName;

        // Setup mocks
        when(userManager.getAuthorizableByPath(groupPath)).thenReturn(localGroup);
        when(localGroup.isGroup()).thenReturn(true);
        when(localGroup.getID()).thenReturn(groupId);
        when(localGroup.addMember(any(Group.class))).thenReturn(true);

        // Mock external group doesn't exist
        when(userManager.getAuthorizable(externalGroupId)).thenReturn(null);
        when(userManager.createGroup(any(Principal.class))).thenReturn(externalGroup);
        when(externalGroup.getPath()).thenReturn("/home/groups/external/" + externalGroupId);

        MockSlingHttpServletRequest request = context.request();
        request.addRequestParameter("groupPath", groupPath);
        request.addRequestParameter("idpName", idpName);
        MockSlingHttpServletResponse response = context.response();

        fixture.doPost(request, response);

        assertEquals(200, response.getStatus());
        String output = response.getOutputAsString();
        assertTrue(output.contains("\"success\": true"));
        assertTrue(output.contains(groupId));
        assertTrue(output.contains(externalGroupId));
        assertTrue(output.contains("\"externalGroupAdded\": true"));
        
        verify(serviceSession).save();
        verify(serviceSession).logout();
    }

    @Test
    void testDoPostWithExistingExternalGroup(AemContext context) throws ServletException, IOException, RepositoryException {
        String groupPath = "/home/groups/marketing";
        String groupId = "marketing";
        String idpName = "saml-idp";
        String externalGroupId = groupId + ";" + idpName;

        // Setup mocks
        when(userManager.getAuthorizableByPath(groupPath)).thenReturn(localGroup);
        when(localGroup.isGroup()).thenReturn(true);
        when(localGroup.getID()).thenReturn(groupId);
        when(localGroup.addMember(any(Group.class))).thenReturn(false); // Already a member

        // Mock external group already exists
        when(userManager.getAuthorizable(externalGroupId)).thenReturn(externalGroup);
        when(externalGroup.isGroup()).thenReturn(true);

        MockSlingHttpServletRequest request = context.request();
        request.addRequestParameter("groupPath", groupPath);
        request.addRequestParameter("idpName", idpName);
        MockSlingHttpServletResponse response = context.response();

        fixture.doPost(request, response);

        assertEquals(200, response.getStatus());
        String output = response.getOutputAsString();
        assertTrue(output.contains("\"success\": true"));
        assertTrue(output.contains("\"externalGroupAdded\": false"));
        
        verify(userManager, never()).createGroup(any(Principal.class));
        verify(serviceSession).save();
    }

    @Test
    void testDoPostWithNonGroupAuthorizable(AemContext context) throws ServletException, IOException, RepositoryException {
        User user = mock(User.class);
        when(userManager.getAuthorizableByPath("/home/users/testuser")).thenReturn(user);
        when(user.isGroup()).thenReturn(false);

        MockSlingHttpServletRequest request = context.request();
        request.addRequestParameter("groupPath", "/home/users/testuser");
        request.addRequestParameter("idpName", "saml-idp");
        MockSlingHttpServletResponse response = context.response();

        fixture.doPost(request, response);

        assertEquals(400, response.getStatus());
        assertTrue(response.getOutputAsString().contains("is not a group"));
    }

    @Test
    void testDoPostWithRepositoryException(AemContext context) throws ServletException, IOException, RepositoryException {
        when(userManager.getAuthorizableByPath(anyString())).thenThrow(new RepositoryException("Test exception"));

        MockSlingHttpServletRequest request = context.request();
        request.addRequestParameter("groupPath", "/home/groups/test");
        request.addRequestParameter("idpName", "saml-idp");
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
        when(userManager.getAuthorizable(anyString())).thenReturn(externalGroup);
        when(externalGroup.isGroup()).thenReturn(true);

        MockSlingHttpServletRequest request = context.request();
        request.addRequestParameter("groupPath", "/home/groups/test");
        request.addRequestParameter("idpName", "saml-idp");
        MockSlingHttpServletResponse response = context.response();

        fixture.doPost(request, response);

        verify(serviceSession).logout();
    }
}
