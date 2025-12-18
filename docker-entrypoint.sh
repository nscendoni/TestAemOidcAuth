#!/bin/bash
#
# Docker entrypoint for AEM with SAML/Keycloak setup
# Certificates are generated at runtime, not stored in repository
#

set -e

AEM_DIR="/opt/aem"
CONTENT_PATH="/var/cache/contents"
ENV_FILE="/opt/aem/saml.env"

# Default values (can be overridden by environment variables)
MODE="${MODE:-publish}"
PORT="${PORT:-4503}"
DEBUG_PORT="${DEBUG_PORT:-5006}"

# =============================================================================
# HELPER FUNCTIONS
# =============================================================================

start_aem() {
    echo "Starting AEM..."
    cd ${AEM_DIR}
    
    # Source environment file if it exists (contains SAML_ALIAS after setup)
    if [ -f "${ENV_FILE}" ]; then
        echo "Loading environment from ${ENV_FILE}"
        source "${ENV_FILE}"
        export SAML_ALIAS
    fi
    
    java \
        -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:${DEBUG_PORT} \
        -jar ${AEM_DIR}/cq-quickstart.jar \
        -nointeractive \
        -r ${MODE} \
        -p ${PORT} \
        -Dadmin.password.file=password \
        -nofork &
    
    AEM_PID=$!
    echo "AEM started with PID: ${AEM_PID}"
}

wait_for_aem() {
    echo "Waiting for AEM to be ready..."
    local count=0
    
    # Wait for AEM product info (indicates AEM is running)
    while true; do
        if curl -u admin:admin -s -L \
            -A "Mozilla/5.0 (X11; Linux x86_64; rv:60.0) Gecko/20100101 Firefox/81.0" \
            "http://localhost:${PORT}/system/console/productinfo" 2>/dev/null | grep -q "6.6.0"; then
            break
        fi
        
        count=$((count + 1))
        if [ $count -gt 240 ]; then
            echo "ERROR: AEM did not start in time"
            exit 1
        fi
        echo "  Waiting for AEM to be ready... (attempt $count/240)"
        sleep 5
    done

    # Wait for login page (indicates Felix console is accessible)
    count=0
    while true; do
        if curl -s -L \
            -A "Mozilla/5.0 (X11; Linux x86_64; rv:60.0) Gecko/20100101 Firefox/81.0" \
            "http://localhost:${PORT}/system/console" 2>/dev/null | grep -q "Sign In"; then
            break
        fi
        
        count=$((count + 1))
        if [ $count -gt 240 ]; then
            echo "ERROR: AEM login page did not become available"
            exit 1
        fi
        echo "  Waiting for AEM login page... (attempt $count/240)"
        sleep 5
    done

    echo "AEM is ready!"
}

stop_aem() {
    echo "Stopping AEM..."
    
    # Try graceful shutdown first using pkill with SIGTERM
    if pgrep -f "cq-quickstart.jar" > /dev/null 2>&1; then
        echo "Sending SIGTERM to AEM process..."
        pkill -TERM -f "cq-quickstart.jar" || true
    fi
    
    # Wait for AEM to stop
    local count=0
    while pgrep -f "cq-quickstart.jar" > /dev/null 2>&1; do
        count=$((count + 1))
        if [ $count -gt 30 ]; then
            echo "Force killing AEM with SIGKILL..."
            pkill -9 -f "cq-quickstart.jar" || true
            sleep 2
            break
        fi
        echo "  Waiting for AEM to stop... (attempt $count/30)"
        sleep 2
    done
    
    # Clean up pid file if it exists
    rm -f "${AEM_DIR}/crx-quickstart/conf/cq.pid" 2>/dev/null || true
    
    echo "AEM stopped"
}

deploy_packages() {
    echo "============================================="
    echo "  Deploying Content Packages"
    echo "============================================="
    cd ${CONTENT_PATH}

    if [ -f "pom.xml" ]; then
        if [ -f "${MAVEN_REPOSITORY}/settings.xml" ]; then
            mvn -s ${MAVEN_REPOSITORY}/settings.xml \
                -Dmaven.repo.local=${MAVEN_REPOSITORY}/repository \
                clean install -DskipTests=true -PautoInstallSinglePackagePublish
        else
            mvn clean install -DskipTests=true -PautoInstallSinglePackagePublish
        fi
    else
        echo "No pom.xml found, skipping Maven build"
    fi
}

# =============================================================================
# MAIN EXECUTION
# =============================================================================

echo "============================================="
echo "  AEM Docker Container Starting"
echo "  Mode: ${MODE}"
echo "  Port: ${PORT}"
echo "============================================="

cd ${AEM_DIR}

# Create admin password file
echo "admin" > password

# Phase 1: Start AEM (first time)
start_aem
wait_for_aem

# Phase 2: Run SAML setup if in publish mode
if [ "$MODE" == "publish" ]; then
    echo "============================================="
    echo "  Running SAML Setup"
    echo "============================================="
    
    # Export variables for the SAML setup script
    export AEM_PORT="${PORT}"
    export AEM_PUBLISH_URL="http://localhost:${PORT}"
    export AEM_ADMIN_USER="admin"
    export AEM_ADMIN_PASS="admin"
    export KEYCLOAK_URL="${KEYCLOAK_URL:-http://keycloak:8080}"
    export KEYCLOAK_ADMIN="${KEYCLOAK_ADMIN:-admin}"
    export KEYCLOAK_ADMIN_PASSWORD="${KEYCLOAK_ADMIN_PASSWORD:-admin}"
    export KEYCLOAK_REALM="${KEYCLOAK_REALM:-sling}"
    export KEYCLOAK_SAML_CLIENT_ID="${KEYCLOAK_SAML_CLIENT_ID:-test-saml}"
    export CERT_DIR="${CERT_DIR:-/opt/aem/certs}"
    export OPENSSL_PASS="${OPENSSL_PASS:-admin}"
    

    # Run SAML setup (generates certificates at runtime)
    # This script will export SAML_ALIAS
    source ${AEM_DIR}/saml_setup.sh
    
    # Save environment variables to file for restart
    echo "============================================="
    echo "  Saving SAML Environment Variables"
    echo "============================================="
    echo "export SAML_ALIAS=\"${SAML_ALIAS}\"" > "${ENV_FILE}"
    echo "Saved SAML_ALIAS=${SAML_ALIAS} to ${ENV_FILE}"
    
    # Phase 3: Deploy packages with SAML configuration BEFORE restart
    # This ensures OSGi configs with SAML_ALIAS are in place
    deploy_packages
    
    # Phase 4: Restart AEM to apply configuration with new environment
    echo "============================================="
    echo "  Restarting AEM to Apply Configuration"
    echo "============================================="
    stop_aem
    sleep 5
    start_aem
    wait_for_aem
    
else
    # Non-publish mode: just deploy packages
    deploy_packages
fi

echo ""
echo "*****************************************"
echo "*            AEM STARTED               *"
echo "*****************************************"
echo ""
echo "  Mode:       ${MODE}"
echo "  Port:       ${PORT}"
echo "  SAML_ALIAS: ${SAML_ALIAS:-N/A}"
echo ""

# Keep container running and tail the error log
tail -f ${AEM_DIR}/crx-quickstart/logs/error.log
