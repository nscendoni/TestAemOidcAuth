package com.wintergw2025.core.filters;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;

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
        //"service.ranking:Integer=" + Integer.MAX_VALUE,
        "service.description:String=OAuth Error Interceptor"
    }
)
@Designate(ocd = ErrorInterceptorFilter.Config.class)
public class ErrorInterceptorFilter implements Preprocessor, Filter {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private String pathPattern;
    private String redirectPath;
    private String[] errorMessages;
    private boolean enabled;

    @ObjectClassDefinition(name = "SDCERA - Error Interceptor Filter",
                          description = "Intercepts OAuth authentication errors and redirects to custom error page")
    public @interface Config {
        
        @AttributeDefinition(name = "Enabled",
                           description = "Enable or disable the error interceptor filter")
        boolean enabled() default true;
        
        @AttributeDefinition(name = "Path Pattern",
                           description = "Path pattern to intercept (e.g., /content/sdcera/us/en/dashboard-oidc)")
        String path_pattern() default "/content/sdcera/us/en/dashboard-oidc/j_security_check";

        @AttributeDefinition(name = "Redirect Path",
                           description = "Path to redirect to on error (e.g., /content/sdcera/us/en/errors/500.html)")
        String redirect_path() default "/content/sdcera/us/en/errors/500.html";

        @AttributeDefinition(name = "Error Messages",
                           description = "Array of error messages to look for when identifying OAuth errors")
        String[] error_messages() default {
            "No request cookie named sling.oauth-request-key found",
            "No cookies found",
            "oauth",
            "OAuth"
        };
    }

    @Activate
    protected void activate(Config config) {
        this.enabled = config.enabled();
        this.pathPattern = config.path_pattern();
        this.redirectPath = config.redirect_path();
        this.errorMessages = config.error_messages();
        logger.debug("ErrorInterceptorFilter activated - enabled: {}, pattern: {}, redirect: {}, errorMessages: {}",
                    enabled, pathPattern, redirectPath, String.join(", ", errorMessages));
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
        logger.debug("ErrorInterceptorFilter.doFilter() called for path: {}, enabled: {}, pattern: {}", 
                   requestPath, enabled, pathPattern);

        if (!enabled) {
            logger.debug("Filter is disabled, passing through");
            filterChain.doFilter(request, response);
            return;
        }

        HttpServletResponse httpResponse = (HttpServletResponse) response;

        // Only wrap response for OAuth paths to avoid overhead
        boolean shouldIntercept = requestPath != null && requestPath.startsWith(pathPattern);

        if (!shouldIntercept) {
            // Not our path, proceed normally
            filterChain.doFilter(request, response);
            return;
        }

        // Wrap the response to buffer output and prevent early commitment
        logger.debug("Wrapping response for OAuth path: {}", requestPath);
        BufferedResponseWrapper wrappedResponse = new BufferedResponseWrapper(httpResponse);

        try {
            // Continue with the filter chain using wrapped response
            filterChain.doFilter(request, wrappedResponse);

            // Check if error status was set
            int status = wrappedResponse.getStatus();
            String errorMsg = wrappedResponse.getErrorMessage();
            logger.debug("After filterChain.doFilter() - status: {}, errorMsg: '{}', isCommitted: {}, path: {}",
                       status, errorMsg, wrappedResponse.isCommitted(), requestPath);

            // Debug: Check buffer content
//            byte[] bufferContent = wrappedResponse.getBufferContent();
//            if (bufferContent.length > 0) {
//                logger.debug("Buffer has {} bytes of content", bufferContent.length);
//            } else {
//                logger.debug("Buffer is empty");
//            }

            // Handle error status
            if (status >= 500) {
                // Check if there's an exception set in request attributes
                Throwable exception = (Throwable) request.getAttribute("javax.servlet.error.exception");
                if (exception != null) {
                    String exceptionMsg = getFullExceptionMessage(exception);
                    logger.debug("Found exception in request attributes: {}", exceptionMsg);

                    // Check if exception message indicates OAuth error
                    if (isOAuthError(exceptionMsg)) {
                        logger.warn("OAuth error detected from exception (status: {}, exception: '{}'), redirecting to {}",
                                   status, exceptionMsg, redirectPath);
                        wrappedResponse.reset();
                        httpResponse.sendRedirect(redirectPath);
                        return;
                    }
                }

                // Check if sendError message indicates OAuth error
                boolean isOAuth = isOAuthError(errorMsg);

                if (isOAuth) {
                    logger.info("OAuth error detected from error message (status: {}, message: '{}'), redirecting to {}",
                               status, errorMsg, redirectPath);
                    // Reset the wrapped response and redirect using the original response
                    wrappedResponse.reset();
                    httpResponse.sendRedirect(redirectPath);
                    return;
                } else {
                    logger.info("Error status {} but message doesn't match OAuth patterns (message: '{}'), propagating error",
                               status, errorMsg);
                    // Not OAuth error, propagate the error status
                    httpResponse.sendError(status, errorMsg != null ? errorMsg : "Internal Server Error");
                    return;
                }
            }

            // No error, flush the buffered content to actual response
            logger.debug("No error detected (status: {}), flushing buffered response", status);
            wrappedResponse.flushBuffer();

        } catch (IllegalStateException e) {
            // Check if this is the OAuth authentication error
            logger.debug("IllegalStateException caught, checking if OAuth error");
            String exceptionMessage = getFullExceptionMessage(e);
            logger.debug("Exception for path {}: {}", requestPath, exceptionMessage);

            if (isOAuthError(exceptionMessage)) {
                logger.debug("OAuth authentication error detected, redirecting from {} to {}", requestPath, redirectPath);
                // Reset buffered response and redirect
                wrappedResponse.reset();
                httpResponse.sendRedirect(redirectPath);
                return;  // IMPORTANT: Stop processing after redirect
            } else {
                // Not the error we're looking for, re-throw
                logger.debug("Exception doesn't match OAuth error criteria, re-throwing");
                throw e;
            }
        }
//        } catch (ServletException e) {
//            // Check if ServletException wraps IllegalStateException
//            logger.debug("ServletException caught, checking for wrapped OAuth error");
//            Throwable rootCause = e.getRootCause();
//
//            if (rootCause instanceof IllegalStateException) {
//                String exceptionMessage = getFullExceptionMessage(rootCause);
//                if (isOAuthError(exceptionMessage)) {
//                    logger.debug("OAuth error found in wrapped exception, redirecting to {}", redirectPath);
//                    wrappedResponse.reset();
//                    httpResponse.sendRedirect(redirectPath);
//                    return;
//                }
//            }
//
//            // Re-throw if not handled
//            logger.debug("ServletException not handled, re-throwing");
//            throw e;
//        } catch (IOException e) {
//            logger.debug("IOException in ErrorInterceptorFilter for path: {}", requestPath);
//            throw e;
//        }
    }

    /**
     * Checks if the given message indicates an OAuth authentication error
     * by matching against configured error messages
     */
    private boolean isOAuthError(String message) {
        if (message == null || errorMessages == null || errorMessages.length == 0) {
            return false;
        }

        for (String errorMsg : errorMessages) {
            if (message.contains(errorMsg)) {
                return true;
            }
        }
        return false;
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
        logger.debug("Exception text: {}", sb.toString());
        return sb.toString();
    }

    @Override
    public void init(FilterConfig filterConfig) {
        logger.debug("ErrorInterceptorFilter.init() called");
    }

    @Override
    public void destroy() {
        // No cleanup needed
    }

    /**
     * Response wrapper that buffers the output stream to prevent early response commitment.
     * This allows us to intercept error status codes and redirect before content is sent to the client.
     */
    private static class BufferedResponseWrapper extends HttpServletResponseWrapper {

        private final Logger wrapperLogger = LoggerFactory.getLogger(BufferedResponseWrapper.class);
        private final ByteArrayOutputStream buffer;
        private final ServletOutputStream outputStream;
        private PrintWriter writer;
        private boolean outputStreamCalled = false;

        private int status = SC_OK;
        private String errorMessage;

        public BufferedResponseWrapper(HttpServletResponse response) {
            super(response);
            this.buffer = new ByteArrayOutputStream();
            this.outputStream = new ServletOutputStream() {
                @Override
                public void write(int b) throws IOException {
                    buffer.write(b);
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setWriteListener(WriteListener writeListener) {
                    // Not used
                }
            };
            wrapperLogger.debug("BufferedResponseWrapper created");
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public byte[] getBufferContent() {
            return buffer.toByteArray();
        }

        @Override
        public ServletOutputStream getOutputStream() throws IOException {
            if (writer != null) {
                throw new IllegalStateException("getWriter() has already been called");
            }
            wrapperLogger.debug("getOutputStream called");
            outputStreamCalled = true;
            return outputStream;
        }

        @Override
        public PrintWriter getWriter() throws IOException {
            if (outputStreamCalled) {
                throw new IllegalStateException("getOutputStream() has already been called");
            }
            if (writer == null) {
                wrapperLogger.debug("getWriter called - creating new PrintWriter");
                writer = new PrintWriter(new OutputStreamWriter(buffer, getCharacterEncoding()));
            }
            return writer;
        }

        @Override
        public void setStatus(int sc) {
            wrapperLogger.debug("setStatus called with: {}", sc);
            this.status = sc;
            // Don't call super.setStatus() - we're buffering
        }

        @Override
        @Deprecated
        public void setStatus(int sc, String sm) {
            wrapperLogger.debug("setStatus(deprecated) called with: {} message: {}", sc, sm);
            this.status = sc;
            this.errorMessage = sm;
            // Don't call super.setStatus() - we're buffering
        }

        @Override
        public void sendError(int sc) throws IOException {
            wrapperLogger.debug("sendError called with status: {}", sc);
            this.status = sc;
            this.errorMessage = "Error " + sc;
            // Don't call super.sendError() - we're buffering
        }

        @Override
        public void sendError(int sc, String msg) throws IOException {
            wrapperLogger.debug("sendError called with status: {} message: {}", sc, msg);

            // Log stack trace to see where this is coming from
            if (sc >= 500) {
                wrapperLogger.debug("sendError stack trace:", new Exception("Stack trace for sendError"));
            }

            this.status = sc;
            this.errorMessage = msg;
            // Don't call super.sendError() - we're buffering
        }

        @Override
        public int getStatus() {
            return this.status;
        }

        @Override
        public void flushBuffer() throws IOException {
            wrapperLogger.debug("flushBuffer called, buffer size: {}, status: {}", buffer.size(), status);
            if (writer != null) {
                writer.flush();
            }
            if (outputStream != null) {
                outputStream.flush();
            }

            // Write the buffered content to the actual response
            byte[] content = buffer.toByteArray();
            if (content.length > 0) {
                HttpServletResponse response = (HttpServletResponse) getResponse();
                response.setContentLength(content.length);
                response.getOutputStream().write(content);
                response.getOutputStream().flush();
                wrapperLogger.debug("Flushed {} bytes to actual response", content.length);
            } else {
                wrapperLogger.debug("No content to flush");
            }
        }

        @Override
        public void reset() {
            wrapperLogger.debug("reset called");
            super.reset();
            buffer.reset();
            this.status = SC_OK;
            this.errorMessage = null;
            this.writer = null;
            this.outputStreamCalled = false;
        }

        @Override
        public boolean isCommitted() {
            // Response is only committed when we flush the buffer
            return false;
        }
    }
}
