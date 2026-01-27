# Custom Error Handling in Dispatcher

## Overview

This project includes custom error handling for the Dispatcher to provide a better user experience when errors occur.

## 500 Error Page for OAuth2 Authenticated Content

### Configuration Details

When a 500 Internal Server Error occurs in the `/content/wintergw2025/us/en/oauth2-authenticated` context, the Dispatcher will serve a custom error page instead of the default AEM error response.

### Components

1. **Static Error Page** (`dispatcher/src/htdocs/error-500.html`)
   - A standalone HTML file served directly by Apache
   - User-friendly error message with modern styling
   - Link to return to home page
   - Does not require AEM to be functioning
   - Mounted at `/var/www/default/error-500.html` in the container

2. **Custom VHost Configuration** (`dispatcher/src/conf.d/available_vhosts/wintergw2025.vhost`)
   - Extends the default publish virtualhost
   - Sets `DispatcherPassError 1` globally to allow Apache to handle errors
   - Includes `LocationMatch` directive for `/content/wintergw2025/us/en/oauth2-authenticated.*`
   - Defines `ErrorDocument 500 /error-500.html` for the matched location
   - Disables Dispatcher handler for `/error-500.html` using `SetHandler none`

3. **Docker Volume Mount** (`docker-compose.yml`)
   - Mounts `./dispatcher/src/htdocs/error-500.html` to `/var/www/default/error-500.html`
   - Ensures the error page is available to Apache

4. **Cache Rules** (`dispatcher/src/conf.dispatcher.d/cache/rules.any`)
   - Error pages matching pattern `/content/*/error-*.html` are allowed to be cached
   - Improves performance when serving error pages from AEM (not used for static error page)

### How It Works

1. A request is made to `/content/wintergw2025/us/en/oauth2-authenticated/*`
2. If AEM returns a 500 error:
   - Because `DispatcherPassError 1` is set globally, Dispatcher passes the error to Apache
   - The `LocationMatch` directive detects the path matches `^/content/wintergw2025/us/en/oauth2-authenticated.*`
   - Apache's `ErrorDocument 500` directive serves `/error-500.html`
   - The `SetHandler none` directive ensures Apache serves the static file directly
3. The static error page is returned to the user with status code 500
4. The error page loads instantly without any AEM dependencies

### Deployment

#### Docker Compose Environment

The error page is automatically configured when running `docker-compose up`. The volumes are mounted and the configuration is applied.

To verify:

```bash
# Check that the error page file exists
ls -l dispatcher/src/htdocs/error-500.html

# Verify the container mount
docker exec dispatcher ls -l /var/www/default/error-500.html

# Check the vhost configuration
docker exec dispatcher cat /etc/httpd/conf.d/enabled_vhosts/default.vhost | grep -A 5 "LocationMatch"
```

#### Standalone Dispatcher

1. Ensure the error page is in the htdocs directory:
   ```bash
   cp dispatcher/src/htdocs/error-500.html /path/to/dispatcher/htdocs/
   ```

2. Verify the Dispatcher configuration:
   ```bash
   # Check symlink is correct
   ls -l dispatcher/src/conf.d/enabled_vhosts/
   
   # Should show: wintergw2025.vhost -> ../available_vhosts/wintergw2025.vhost
   ```

3. Restart Apache/Dispatcher after configuration changes

### Testing

To test the error handling:

1. Trigger a 500 error in the OAuth2 authenticated flow (e.g., authentication failure)
2. Access: `http://localhost:8085/content/wintergw2025/us/en/oauth2-authenticated/j_security_check` with invalid parameters
3. Verify the custom error page is displayed with proper styling
4. Click the "Return to Home" button to verify navigation works

Alternatively, you can test the error page directly:
```bash
curl -i http://localhost:8085/error-500.html
```

### Extending Error Handling

To add error handling for other HTTP status codes:

1. Create additional static HTML files (e.g., `error-404.html`, `error-403.html`) in `dispatcher/src/htdocs/`
2. Add volume mounts in `docker-compose.yml`:
   ```yaml
   - ./dispatcher/src/htdocs/error-404.html:/var/www/default/error-404.html
   - ./dispatcher/src/htdocs/error-403.html:/var/www/default/error-403.html
   ```
3. Add corresponding `ErrorDocument` directives in the vhost:
   ```apache
   <LocationMatch "^/content/wintergw2025/us/en/oauth2-authenticated.*">
       ErrorDocument 500 /error-500.html
       ErrorDocument 404 /error-404.html
       ErrorDocument 403 /error-403.html
   </LocationMatch>
   ```
4. Add `SetHandler none` directives for each error page:
   ```apache
   <Location "/error-404.html">
       <IfModule disp_apache2.c>
           SetHandler none
       </IfModule>
   </Location>
   ```

To apply error handling to other paths:

1. Add new `LocationMatch` directives in `wintergw2025.vhost`:
   ```apache
   <LocationMatch "^/content/wintergw2025/us/en/saml-authenticated.*">
       ErrorDocument 500 /error-500.html
   </LocationMatch>
   ```

### Important Notes on DispatcherPassError

- `DispatcherPassError 1` is set **globally** in the VirtualHost configuration
- This means all paths will have error handling enabled by Apache
- If you need error handling only for specific paths, consider:
  - Using the default `DispatcherPassError 0` globally
  - Creating separate VirtualHost configurations for different paths
  - Or accepting that error handling is global and using `LocationMatch` to customize error pages per path

## Notes

- `DispatcherPassError 1` is set globally, meaning Apache handles errors for all paths (not just OAuth2 authenticated)
- Static error pages are preferred over AEM-rendered pages because they work even when AEM is completely down
- Error pages should be simple and not depend on external resources that might also be unavailable
- The error page uses inline CSS to avoid external dependencies
- Consider adding monitoring/alerting when the error page is served frequently
- For production, ensure error pages don't expose sensitive information about the system
