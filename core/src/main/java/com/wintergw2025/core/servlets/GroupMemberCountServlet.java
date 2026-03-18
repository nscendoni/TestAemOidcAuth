package com.wintergw2025.core.servlets;

import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.jackrabbit.api.security.user.Authorizable;
import org.apache.jackrabbit.api.security.user.Group;
import org.apache.jackrabbit.api.security.user.UserManager;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.servlets.SlingSafeMethodsServlet;
import org.apache.sling.servlets.annotations.SlingServletPaths;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.propertytypes.ServiceDescription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.servlet.Servlet;
import javax.servlet.ServletException;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Iterator;

/**
 * Returns the number of members in a group.
 * Accessible at: GET /bin/wintergw2025/group-member-count?groupId=<groupId>
 */
@Component(service = { Servlet.class })
@SlingServletPaths("/bin/wintergw2025/group-member-count")
@ServiceDescription("Group Member Count Servlet")
public class GroupMemberCountServlet extends SlingSafeMethodsServlet {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LoggerFactory.getLogger(GroupMemberCountServlet.class);
    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response)
            throws ServletException, IOException {

        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        PrintWriter writer = response.getWriter();

        String groupId = request.getParameter("groupId");
        if (groupId == null || groupId.trim().isEmpty()) {
            response.setStatus(SlingHttpServletResponse.SC_BAD_REQUEST);
            writer.write("{\"error\": \"groupId parameter is required\"}");
            return;
        }

        try {
            Session session = request.getResourceResolver().adaptTo(Session.class);
            UserManager userManager = ((JackrabbitSession) session).getUserManager();

            Authorizable authorizable = userManager.getAuthorizable(groupId);
            if (authorizable == null || !authorizable.isGroup()) {
                response.setStatus(SlingHttpServletResponse.SC_NOT_FOUND);
                writer.write("{\"error\": \"Group '" + escapeJson(groupId) + "' not found\"}");
                return;
            }

            Group group = (Group) authorizable;
            Iterator<Authorizable> members = group.getMembers();
            int count = 0;
            while (members.hasNext()) {
                members.next();
                count++;
            }

            writer.write("{\"groupId\": \"" + escapeJson(groupId) + "\", \"memberCount\": " + count + "}");

        } catch (RepositoryException e) {
            LOG.error("Error reading group members for '{}'", groupId, e);
            response.setStatus(SlingHttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            writer.write("{\"error\": \"" + escapeJson(e.getMessage()) + "\"}");
        }
    }

    private String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}