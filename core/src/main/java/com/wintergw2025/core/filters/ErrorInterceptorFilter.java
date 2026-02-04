package com.wintergw2025.core.filters;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.engine.EngineConstants;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.propertytypes.ServiceRanking;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Custom filter to intercept 500 errors on OAuth2 authenticated content
 * and redirect users to a custom error page.
 */
@Component(service = Filter.class,
           property = {
                   EngineConstants.SLING_FILTER_SCOPE + "=" + EngineConstants.FILTER_SCOPE_REQUEST,
                   EngineConstants.SLING_FILTER_SCOPE + "=" + EngineConstants.FILTER_SCOPE_ERROR
           })
@Designate(ocd = ErrorInterceptorFilter.Config.class)
@ServiceRanking(1000) // Higher ranking to intercept early
public class ErrorInterceptorFilter implements Filter {

    private final Logger logger = LoggerFactory.getLogger(getClass());
    
    private String pathPattern;
    private String redirectPath;
    private String errorMessage;
    private boolean enabled;

    @ObjectClassDefinition(name = "WinterGW2025 - Error Interceptor Filter",
                          description = "Intercepts specific error messages and redirects to custom error page")
    public @interface Config {
        
        @AttributeDefinition(name = "Enabled",
                           description = "Enable or disable the error interceptor filter")
        boolean enabled() default true;
        
        @AttributeDefinition(name = "Path Pattern",
                           description = "Path pattern to intercept (e.g., /content/wintergw2025/us/en/oauth2-authenticated)")
        String path_pattern() default "/content/wintergw2025/us/en/oauth2-authenticated";
        
        @AttributeDefinition(name = "Redirect Path",
                           description = "Path to redirect to on error (e.g., /content/wintergw2025/us/en/error.html)")
        String redirect_path() default "/content/wintergw2025/us/en/error.html";
        
        @AttributeDefinition(name = "Error Message",
                           description = "Error message to look for in response body before redirecting")
        String error_message() default "No request cookie named sling.oauth-request-key found";
    }

    @Activate
    protected void activate(Config config) {
        this.enabled = config.enabled();
        this.pathPattern = config.path_pattern();
        this.redirectPath = config.redirect_path();
        this.errorMessage = config.error_message();
        logger.info("ErrorInterceptorFilter activated - enabled: {}, pattern: {}, redirect: {}, errorMessage: {}", 
                    enabled, pathPattern, redirectPath, errorMessage);
    }

    @Override
    public void doFilter(final ServletRequest request, final ServletResponse response,
                         final FilterChain filterChain) throws IOException, ServletException {

        final SlingHttpServletRequest slingRequest = (SlingHttpServletRequest) request;
        final SlingHttpServletResponse slingResponse = (SlingHttpServletResponse) response;
        
        String requestPath = slingRequest.getRequestPathInfo().getResourcePath();
        
        // Log filter execution for debugging
        logger.info("ErrorInterceptorFilter.doFilter() called for path: {}, enabled: {}, pattern: {}", 
                   requestPath, enabled, pathPattern);

        if (!enabled) {
            logger.debug("Filter is disabled, passing through");
            filterChain.doFilter(request, response);
            return;
        }
        
        // Check if the request path matches our pattern
        if (requestPath != null && requestPath.startsWith(pathPattern)) {
            logger.info("Path matches pattern, intercepting request for: {}", requestPath);
            
            // Wrap the response to capture both status and body content
            BufferingResponseWrapper responseWrapper = 
                new BufferingResponseWrapper(slingResponse);
            
            try {
                // Continue with the filter chain
                filterChain.doFilter(request, responseWrapper);
                
                // Get the response body content
                String responseBody = responseWrapper.getCapturedContent();
                int statusCode = responseWrapper.getStatus();
                
                // Check if it's a 500 error AND contains our specific error message
                if (statusCode == HttpServletResponse.SC_INTERNAL_SERVER_ERROR 
                    && responseBody != null 
                    && responseBody.contains(errorMessage)) {
                    
                    logger.info("500 error with message '{}' detected on path: {}, redirecting to: {}", 
                               errorMessage, requestPath, redirectPath);
                    
                    // Clear any content and redirect
                    if (!slingResponse.isCommitted()) {
                        slingResponse.reset();
                        slingResponse.sendRedirect(redirectPath);
                    } else {
                        logger.warn("Response already committed, cannot redirect from path: {}", 
                                   requestPath);
                        // Write the captured content since we can't redirect
                        responseWrapper.writeTo(slingResponse);
                    }
                } else {
                    // No match, write the captured content to the actual response
                    responseWrapper.writeTo(slingResponse);
                }
            } catch (Exception e) {
                logger.error("Error in ErrorInterceptorFilter for path: {}", requestPath, e);
                
                // On exception, check if error message is in the exception
                if (!slingResponse.isCommitted() && e.getMessage() != null 
                    && e.getMessage().contains(errorMessage)) {
                    slingResponse.reset();
                    slingResponse.sendRedirect(redirectPath);
                } else {
                    throw e;
                }
            }
        } else {
            // Path doesn't match, continue normally
            logger.debug("Path does not match pattern, passing through: {}", requestPath);
            filterChain.doFilter(request, response);
        }
    }

    @Override
    public void init(FilterConfig filterConfig) {
        logger.info("ErrorInterceptorFilter.init() called");
    }

    @Override
    public void destroy() {
        // No cleanup needed
    }

    /**
     * Response wrapper that buffers the response content and captures the HTTP status code
     */
    private static class BufferingResponseWrapper extends HttpServletResponseWrapper {
        
        private int status = HttpServletResponse.SC_OK;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private PrintWriter writer;
        private ServletOutputStream outputStream;

        public BufferingResponseWrapper(HttpServletResponse response) {
            super(response);
        }

        @Override
        public PrintWriter getWriter() throws IOException {
            if (writer == null) {
                writer = new PrintWriter(new OutputStreamWriter(buffer, 
                    getCharacterEncoding() != null ? getCharacterEncoding() : StandardCharsets.UTF_8.name()));
            }
            return writer;
        }

        @Override
        public ServletOutputStream getOutputStream() throws IOException {
            if (outputStream == null) {
                outputStream = new ServletOutputStream() {
                    @Override
                    public void write(int b) throws IOException {
                        buffer.write(b);
                    }

                    @Override
                    public boolean isReady() {
                        return true;
                    }

                    @Override
                    public void setWriteListener(WriteListener listener) {
                        // Not implemented
                    }
                };
            }
            return outputStream;
        }

        @Override
        public void setStatus(int sc) {
            this.status = sc;
            super.setStatus(sc);
        }

        @Override
        public void setStatus(int sc, String sm) {
            this.status = sc;
            super.setStatus(sc, sm);
        }

        @Override
        public void sendError(int sc) throws IOException {
            this.status = sc;
            // Don't call super.sendError() as we want to buffer the content
        }

        @Override
        public void sendError(int sc, String msg) throws IOException {
            this.status = sc;
            // Write the error message to our buffer
            if (msg != null) {
                getWriter().write(msg);
                getWriter().flush();
            }
        }

        public int getStatus() {
            return status;
        }

        /**
         * Get the captured response content as a String
         */
        public String getCapturedContent() {
            if (writer != null) {
                writer.flush();
            }
            try {
                return buffer.toString(getCharacterEncoding() != null 
                    ? getCharacterEncoding() 
                    : StandardCharsets.UTF_8.name());
            } catch (Exception e) {
                return buffer.toString(StandardCharsets.UTF_8);
            }
        }

        /**
         * Write the buffered content to the actual response
         */
        public void writeTo(HttpServletResponse response) throws IOException {
            if (writer != null) {
                writer.flush();
            }
            
            byte[] content = buffer.toByteArray();
            if (content.length > 0) {
                response.setContentLength(content.length);
                response.getOutputStream().write(content);
            }
        }
    }
}
