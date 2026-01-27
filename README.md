# AEM OIDC & SAML Authentication Demo

This is a demo AEM project showcasing how to set up **OIDC (OpenID Connect)** and **SAML** authentication with **Keycloak** as the Identity Provider (IdP). It provides a complete Docker-based development environment with AEM, Keycloak, and Dispatcher pre-configured for authentication testing.

## Quick Start with Docker Compose

The easiest way to run this demo is using Docker Compose, which orchestrates AEM, Keycloak, and Dispatcher containers with automatic SAML certificate exchange.

### Prerequisites

- Docker and Docker Compose installed
- AEM Quickstart JAR file (6.6.0-SNAPSHOT or compatible)
- ~8GB RAM available for containers

### Setup

1. **Create a `.env` file** in the project root with the path to your AEM quickstart JAR:

   ```bash
   AEM_QUICKSTART=/path/to/your/cq-quickstart-6.6.0-SNAPSHOT.jar
   ```

2. **Start all services:**

   ```bash
   docker-compose up
   ```

   This will:
   - Start Keycloak with the pre-configured `sling` realm
   - Wait for Keycloak to be healthy
   - Start AEM Publish instance
   - Automatically generate and exchange SAML certificates between AEM and Keycloak
   - Deploy the project packages to AEM
   - Start the Dispatcher

3. **Access the services:**

   | Service    | URL                        |
   |------------|----------------------------|
   | AEM Publish | http://localhost:4503     |
   | Keycloak   | http://localhost:8081      |
   | Dispatcher | http://localhost:8085      |

### Test User

To test authentication, use the following credentials:

| Username | Password |
|----------|----------|
| `test`   | `test`   |

### How It Works

#### SAML Authentication Flow

1. User accesses a protected page on AEM
2. AEM redirects to Keycloak's SAML endpoint
3. User authenticates with Keycloak (e.g., `test`/`test`)
4. Keycloak sends a SAML assertion back to AEM
5. AEM validates the assertion and creates a user session

#### Automatic Certificate Exchange

The `saml_setup.sh` script runs automatically at container startup and:

1. **Generates AEM certificates** - Creates RSA key pair and self-signed certificate for the AEM Service Provider (SP)
2. **Retrieves Keycloak certificate** - Downloads the IdP signing certificate from Keycloak's SAML descriptor
3. **Uploads AEM certificate to Keycloak** - Configures the `test-saml` client in Keycloak with AEM's public certificate
4. **Configures AEM truststore** - Imports Keycloak's certificate so AEM can validate SAML assertions
5. **Configures AEM keystore** - Sets up the `authentication-service` user's keystore with AEM's private key

#### OIDC Authentication

The project also includes OIDC configuration for OAuth2-based authentication. This can be used as an alternative or in addition to SAML.

### Access Control Lists (ACLs) for External Groups

This demo uses **repoinit** to configure ACLs that restrict access to protected pages based on **external group membership** from Keycloak. The groups are synchronized from Keycloak to AEM during authentication.

#### Configuration

The ACL configuration is defined in:
`ui.config/src/main/content/jcr_root/apps/wintergw2025/osgiconfig/config.publish/org.apache.sling.jcr.repoinit.RepositoryInitializer~saml-access.cfg.json`

#### Protected Pages and Required Groups

| Page | Path | Required External Group | Notes |
|------|------|------------------------|-------|
| **SAML Authenticated** | `/content/wintergw2025/us/en/saml-authenticated` | `offline_access;saml-idp` | Accessible to users with `offline_access` role via SAML |
| **OAuth2 Authenticated** | `/content/wintergw2025/us/en/oauth2-authenticated` | `test-group` | Accessible to users in `test-group` via OIDC |

#### How It Works

1. **Deny Everyone**: By default, `jcr:read` is denied for `everyone` on both protected pages
2. **Allow External Groups**: Specific external groups are granted `jcr:read` permission using the `ACLOptions=ignoreMissingPrincipal` option (required since the group may not exist until a user authenticates)

#### External Group Naming Convention

- **SAML groups**: Use the format `{group-name};{idp-suffix}` (e.g., `offline_access;saml-idp`)
- **OIDC groups**: Use the group name as-is (e.g., `test-group`)

#### Keycloak Group Membership

The `test` user in Keycloak has the following group memberships:
- `offline_access` - Sent in SAML assertions, allowing access to the SAML page
- **Note**: If you authenticate via SAML and try to access the OAuth2 page, you will get a 404 because `test-group` membership is not sent in SAML assertions by default
- `test-group` - Sent when autehnticate via OIDC
- **Note**: If you authenticate via OIDC and try to access the Saml page, you will get a 404 because `offline_access` membership is not sent via OIDC

### Environment Variables

The following environment variables can be customized in your `.env` file:

| Variable | Default | Description |
|----------|---------|-------------|
| `AEM_QUICKSTART` | (required) | Path to AEM quickstart JAR |
| `KEYCLOAK_ADMIN` | `admin` | Keycloak admin username |
| `KEYCLOAK_ADMIN_PASSWORD` | `admin` | Keycloak admin password |
| `KEYCLOAK_REALM` | `sling` | Keycloak realm name |
| `KEYCLOAK_SAML_CLIENT_ID` | `test-saml` | SAML client ID in Keycloak |
| `OPENSSL_PASS` | `admin` | Password for generated certificates |

### Stopping the Environment

```bash
docker-compose down
```

To completely reset (including Keycloak database):

```bash
docker-compose down
rm -rf keycloak/h2 keycloak/transaction-logs
```

---

## Project Modules

This is a project template for AEM-based applications. It is intended as a best-practice set of examples as well as a potential starting point to develop your own functionality.

## Modules

The main parts of the template are:

* [core:](core/README.md) Java bundle containing all core functionality like OSGi services, listeners or schedulers, as well as component-related Java code such as servlets or request filters.
* [it.tests:](it.tests/README.md) Java based integration tests
* [ui.apps:](ui.apps/README.md) contains the /apps (and /etc) parts of the project, ie JS&CSS clientlibs, components, and templates
* [ui.content:](ui.content/README.md) contains sample content using the components from the ui.apps
* ui.config: contains runmode specific OSGi configs for the project
* [ui.frontend:](ui.frontend.general/README.md) an optional dedicated front-end build mechanism (Angular, React or general Webpack project)
* [ui.tests:](ui.tests/README.md) Cypress based UI tests (for other frameworks check [aem-test-samples](https://github.com/adobe/aem-test-samples) repository
* all: a single content package that embeds all of the compiled modules (bundles and content packages) including any vendor dependencies
* analyse: this module runs analysis on the project which provides additional validation for deploying into AEMaaCS

## How to build

To build all the modules run in the project root directory the following command with Maven 3:

    mvn clean install

To build all the modules and deploy the `all` package to a local instance of AEM, run in the project root directory the following command:

    mvn clean install -PautoInstallSinglePackage

Or to deploy it to a publish instance, run

    mvn clean install -PautoInstallSinglePackagePublish

Or alternatively

    mvn clean install -PautoInstallSinglePackage -Daem.port=4503

Or to deploy only the bundle to the author, run

    mvn clean install -PautoInstallBundle

Or to deploy only a single content package, run in the sub-module directory (i.e `ui.apps`)

    mvn clean install -PautoInstallPackage

## Documentation

The build process also generates documentation in the form of README.md files in each module directory for easy reference. Depending on the options you select at build time, the content may be customized to your project.

## Testing

There are three levels of testing contained in the project:

### Unit tests

This show-cases classic unit testing of the code contained in the bundle. To
test, execute:

    mvn clean test

### Integration tests

This allows running integration tests that exercise the capabilities of AEM via
HTTP calls to its API. To run the integration tests, run:

    mvn clean verify -Plocal

Test classes must be saved in the `src/main/java` directory (or any of its
subdirectories), and must be contained in files matching the pattern `*IT.java`.

The configuration provides sensible defaults for a typical local installation of
AEM. If you want to point the integration tests to different AEM author and
publish instances, you can use the following system properties via Maven's `-D`
flag.

| Property              | Description                                         | Default value           |
|-----------------------|-----------------------------------------------------|-------------------------|
| `it.author.url`       | URL of the author instance                          | `http://localhost:4502` |
| `it.author.user`      | Admin user for the author instance                  | `admin`                 |
| `it.author.password`  | Password of the admin user for the author instance  | `admin`                 |
| `it.publish.url`      | URL of the publish instance                         | `http://localhost:4503` |
| `it.publish.user`     | Admin user for the publish instance                 | `admin`                 |
| `it.publish.password` | Password of the admin user for the publish instance | `admin`                 |

The integration tests in this archetype use the [AEM Testing
Clients](https://github.com/adobe/aem-testing-clients) and showcase some
recommended [best
practices](https://github.com/adobe/aem-testing-clients/wiki/Best-practices) to
be put in use when writing integration tests for AEM.

## Static Analysis

The `analyse` module performs static analysis on the project for deploying into AEMaaCS. It is automatically
run when executing

    mvn clean install

from the project root directory. Additional information about this analysis and how to further configure it
can be found here https://github.com/adobe/aemanalyser-maven-plugin

### UI tests

They will test the UI layer of your AEM application using Cypress framework.

Check README file in `ui.tests` module for more details.

Examples of UI tests in different frameworks can be found here: https://github.com/adobe/aem-test-samples

## ClientLibs

The frontend module is made available using an [AEM ClientLib](https://helpx.adobe.com/experience-manager/6-5/sites/developing/using/clientlibs.html). When executing the NPM build script, the app is built and the [`aem-clientlib-generator`](https://github.com/wcm-io-frontend/aem-clientlib-generator) package takes the resulting build output and transforms it into such a ClientLib.

A ClientLib will consist of the following files and directories:

- `css/`: CSS files which can be requested in the HTML
- `css.txt` (tells AEM the order and names of files in `css/` so they can be merged)
- `js/`: JavaScript files which can be requested in the HTML
- `js.txt` (tells AEM the order and names of files in `js/` so they can be merged
- `resources/`: Source maps, non-entrypoint code chunks (resulting from code splitting), static assets (e.g. icons), etc.

## Maven settings

The project comes with the auto-public repository configured. To setup the repository in your Maven settings, refer to:

    http://helpx.adobe.com/experience-manager/kb/SetUpTheAdobeMavenRepository.html

# How to test with dispatcher

We assume that you run AEM on port 4053.
Download the sdk in the project root directory from [Software Distribution](https://experience.adobe.com/downloads) and unpack it and the run:
```bash
cd dispatcher-sdk-2.0.258
rm -rf cache/html/content
./bin/docker_run.sh ../dispatcher/src docker.for.mac.localhost:4503 8085
```
You can find all the options, including enabling trace logs by running: `./bin/docker_run.sh --help`

Remark: to deploy dispatcher configuration you need to create a Web Tier Config pipeline. You also need to configure the Code Location to: `dispatcher/src`. Documentation on how to debug is [here](https://experienceleague.adobe.com/en/docs/experience-manager-cloud-service/content/implementing/content-delivery/validation-debug)

## Custom Error Pages in Dispatcher

This project includes a custom 500 error page that is served by the AEM when errors occur in the OAuth2 authenticated context.

For more details, see [dispatcher/ERROR_HANDLING.md](dispatcher/ERROR_HANDLING.md).

## How to run dispatcher validation
```bash
cd dispatcher-sdk-2.0.258
./bin/validate.sh src/dispatcher
```

# How to run Keycloak (Standalone)

> **Note:** For the full demo experience with automatic SAML setup, use `docker-compose up` instead (see Quick Start above).

To run Keycloak standalone:

```bash
docker run --rm \
  --volume $(pwd)/keycloak:/opt/keycloak/data \
  -p 8081:8080 \
  -e KEYCLOAK_ADMIN=admin \
  -e KEYCLOAK_ADMIN_PASSWORD=admin \
  --name keycloak \
  quay.io/keycloak/keycloak:26.4.6 start-dev --import-realm
```

## How to export Keycloak configuration

To export the realm configuration, run inside the Keycloak container:

```bash
# Using kcadm.sh (while Keycloak is running)
docker exec -it keycloak /opt/keycloak/bin/kcadm.sh config credentials \
  --server http://localhost:8080 --realm master --user admin
docker exec -it keycloak /opt/keycloak/bin/kcadm.sh get realms/sling \
  > keycloak/export/sling-export.json

# Or using kc.sh export (requires restart)
docker run --rm \
  --volume $(pwd)/keycloak:/opt/keycloak/data \
  -e KEYCLOAK_ADMIN=admin \
  -e KEYCLOAK_ADMIN_PASSWORD=admin \
  quay.io/keycloak/keycloak:26.4.6 export --dir /opt/keycloak/data/export
```

# Environment variables that can be customized for different idp
OIDC_BASE_URL
OIDC_CLIENT_ID
OIDC_CLIENT_SECRET

SAML_IDP_REFERRER
SAML_IDP_URL
SAML_ALIAS
SAML_LOGOUT_URL

# How to deploy to cloud
Set in Cloud Manager the variable OIDC_CALLBACK. For example:
```
OIDC_CALLBACK=https://publish-p148861-e340062-cmstg.adobeaemcloud.com
```