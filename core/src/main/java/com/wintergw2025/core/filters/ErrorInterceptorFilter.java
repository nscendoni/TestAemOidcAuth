package com.wintergw2025.core.filters;

import java.io.IOException;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.sling.api.SlingHttpServletRequest;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.http.whiteboard.HttpWhiteboardConstants;
import org.osgi.service.http.whiteboard.Preprocessor;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Custom preprocessor filter to intercept OAuth authentication errors
 * and redirect users to a custom error page.
 * Implements Preprocessor like CORSFilter to run before authentication.
 */
@Component(
    service = {
        Preprocessor.class,
        Filter.class
    },
    property = {
        "osgi.http.whiteboard.filter.regex=/.*",
        HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_SELECT + "=(" + HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME + "=*)",
        "service.ranking:Integer=" + Integer.MAX_VALUE,
        "service.description:String=OAuth Error Interceptor"
    }
)
@Designate(ocd = ErrorInterceptorFilter.Config.class)
public class ErrorInterceptorFilter implements Preprocessor, Filter {

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

        // Get request path - works for both Sling and non-Sling requests
        String requestPath = null;
        if (request instanceof SlingHttpServletRequest) {
            SlingHttpServletRequest slingRequest = (SlingHttpServletRequest) request;
            requestPath = slingRequest.getRequestPathInfo().getResourcePath();
        } else if (request instanceof HttpServletRequest) {
            HttpServletRequest httpRequest = (HttpServletRequest) request;
            requestPath = httpRequest.getRequestURI();
        }
        
        // Log filter execution for debugging
        logger.info("ErrorInterceptorFilter.doFilter() called for path: {}, enabled: {}, pattern: {}", 
                   requestPath, enabled, pathPattern);

        if (!enabled) {
            logger.debug("Filter is disabled, passing through");
            filterChain.doFilter(request, response);
            return;
        }

        HttpServletResponse httpResponse = (HttpServletResponse) response;

        try {
            // Continue with the filter chain - wrapped in try-catch to intercept OAuth errors
            filterChain.doFilter(request, response);

        } catch (IllegalStateException e) {
            // Check if this is the OAuth authentication error
            String exceptionMessage = getFullExceptionMessage(e);
            logger.info("IllegalStateException caught for path {}: {}", requestPath, exceptionMessage);

            // Check if the path matches our pattern AND it's an OAuth-related error
            // Handle both cases: "No cookies found" (not authenticated) and the configured error message (authenticated)
            boolean isOAuthError = exceptionMessage.contains(errorMessage) ||
                                   exceptionMessage.contains("No cookies found");

            if (requestPath != null && requestPath.startsWith(pathPattern) && isOAuthError) {
                logger.info("OAuth authentication error detected, redirecting from {} to {}",
                           requestPath, redirectPath);

                if (!httpResponse.isCommitted()) {
                    httpResponse.sendRedirect(redirectPath);
                } else {
                    logger.warn("Response already committed, cannot redirect from path: {}", requestPath);
                    throw e;
                }
            } else {
                // Not the error we're looking for, re-throw
                logger.debug("Exception doesn't match our criteria, re-throwing");
                throw e;
            }
        } catch (Exception e) {
            logger.debug("Unexpected exception type in ErrorInterceptorFilter for path: {}", requestPath);
            throw e;
        }
    }

    /**
     * Extracts the full exception message including all causes
     */
    private String getFullExceptionMessage(Throwable exception) {
        StringBuilder sb = new StringBuilder();
        Throwable current = exception;

        while (current != null) {
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            if (current.getMessage() != null) {
                sb.append(current.getMessage());
            }
            current = current.getCause();
        }

        return sb.toString();
    }

    @Override
    public void init(FilterConfig filterConfig) {
        logger.info("ErrorInterceptorFilter.init() called");
    }

    @Override
    public void destroy() {
        // No cleanup needed
    }
}
