#!/usr/bin/env bash
#
# SAML Setup Script for AEM + Keycloak (Docker version)
# This script configures SAML authentication between AEM (SP) and Keycloak (IdP)
# Certificates are generated at runtime - not stored in repository
#
# This script is called by docker-entrypoint.sh after AEM is running
#

set -e

# =============================================================================
# CONFIGURATION (from environment variables)
# =============================================================================

AEM_PORT="${AEM_PORT:-4503}"
AEM_PUBLISH_URL="${AEM_PUBLISH_URL:-http://localhost:${AEM_PORT}}"
AEM_ADMIN_USER="${AEM_ADMIN_USER:-admin}"
AEM_ADMIN_PASS="${AEM_ADMIN_PASS:-admin}"

KEYCLOAK_URL="${KEYCLOAK_URL:-http://keycloak:8080}"
KEYCLOAK_ADMIN="${KEYCLOAK_ADMIN:-admin}"
KEYCLOAK_ADMIN_PASSWORD="${KEYCLOAK_ADMIN_PASSWORD:-admin}"
KEYCLOAK_REALM="${KEYCLOAK_REALM:-sling}"
KEYCLOAK_SAML_CLIENT_ID="${KEYCLOAK_SAML_CLIENT_ID:-test-saml}"

CERT_DIR="${CERT_DIR:-/opt/aem/certs}"
OPENSSL_PASS="${OPENSSL_PASS:-admin}"

# =============================================================================
# HELPER FUNCTIONS
# =============================================================================

log_section() {
    echo ""
    echo "============================================="
    echo "  $1"
    echo "============================================="
}

log_step() {
    echo ">>> $1"
}

log_success() {
    echo "✓ $1"
}

log_error() {
    echo "✗ ERROR: $1" >&2
}

# =============================================================================
# CERTIFICATE FUNCTIONS
# =============================================================================

create_aem_certificates() {
    log_section "CREATING AEM CERTIFICATES"
    
    mkdir -p "${CERT_DIR}"
    cd "${CERT_DIR}"
    
    log_step "Generating RSA key pair and self-signed certificate..."
    openssl req -x509 -sha256 -days 365 \
        -newkey rsa:4096 \
        -keyout aem-private.key \
        -out aem-public.crt \
        -subj "/CN=aem" \
        -passout pass:${OPENSSL_PASS}
    
    log_step "Converting private key to DER format..."
    openssl rsa -in aem-private.key \
        -outform der \
        -out aem-private.der \
        -passin pass:${OPENSSL_PASS}
    
    log_step "Converting to PKCS8 format..."
    openssl pkcs8 -topk8 -inform der -nocrypt \
        -in aem-private.der \
        -outform der \
        -out aem-private-pkcs8.der
    
    log_success "AEM certificates created in ${CERT_DIR}"
    cd - > /dev/null
}

retrieve_keycloak_certificate() {
    log_section "RETRIEVING KEYCLOAK CERTIFICATE"
    
    log_step "Downloading SAML descriptor from Keycloak..."
    curl -s "${KEYCLOAK_URL}/realms/${KEYCLOAK_REALM}/protocol/saml/descriptor" \
        | xmllint --xpath "string(//*[local-name()='KeyDescriptor' and @use='signing']//*[local-name()='X509Certificate'])" - \
        | tr -d '[:space:]' \
        | fold -w 64 \
        | awk 'BEGIN {print "-----BEGIN CERTIFICATE-----"} {print} END {print "-----END CERTIFICATE-----"}' \
        > "${CERT_DIR}/keycloak.pem"
    
    log_success "Keycloak certificate saved to ${CERT_DIR}/keycloak.pem"
}

# =============================================================================
# KEYCLOAK CONFIGURATION
# =============================================================================

upload_certificate_to_keycloak() {
    log_section "UPLOADING AEM CERTIFICATE TO KEYCLOAK"
    
    log_step "Getting Keycloak admin access token..."
    local access_token
    access_token=$(curl -s -X POST "${KEYCLOAK_URL}/realms/master/protocol/openid-connect/token" \
        -H "Content-Type: application/x-www-form-urlencoded" \
        -d "username=${KEYCLOAK_ADMIN}" \
        -d "password=${KEYCLOAK_ADMIN_PASSWORD}" \
        -d "grant_type=password" \
        -d "client_id=admin-cli" | jq -r '.access_token')
    
    if [ -z "${access_token}" ] || [ "${access_token}" == "null" ]; then
        log_error "Failed to get Keycloak access token"
        return 1
    fi
    log_success "Access token obtained"
    
    log_step "Extracting certificate content..."
    local cert_content
    cert_content=$(grep -v "BEGIN CERTIFICATE\|END CERTIFICATE" "${CERT_DIR}/aem-public.crt" | tr -d '\n')
    
    log_step "Looking up SAML client UUID..."
    local client_uuid
    client_uuid=$(curl -s -X GET "${KEYCLOAK_URL}/admin/realms/${KEYCLOAK_REALM}/clients?clientId=${KEYCLOAK_SAML_CLIENT_ID}" \
        -H "Authorization: Bearer ${access_token}" \
        -H "Content-Type: application/json" | jq -r '.[0].id')
    
    if [ -z "${client_uuid}" ] || [ "${client_uuid}" == "null" ]; then
        log_error "Could not find SAML client '${KEYCLOAK_SAML_CLIENT_ID}'"
        return 1
    fi
    log_success "Found SAML client: ${client_uuid}"
    
    log_step "Fetching current client configuration..."
    local client_config
    client_config=$(curl -s -X GET "${KEYCLOAK_URL}/admin/realms/${KEYCLOAK_REALM}/clients/${client_uuid}" \
        -H "Authorization: Bearer ${access_token}" \
        -H "Content-Type: application/json")
    
    log_step "Updating SAML client with AEM SP certificate..."
    local updated_config
    updated_config=$(echo "${client_config}" | jq --arg cert "${cert_content}" '.attributes["saml.signing.certificate"] = $cert')
    
    curl -s -X PUT "${KEYCLOAK_URL}/admin/realms/${KEYCLOAK_REALM}/clients/${client_uuid}" \
        -H "Authorization: Bearer ${access_token}" \
        -H "Content-Type: application/json" \
        -d "${updated_config}"
    
    log_success "AEM SP certificate uploaded to Keycloak"
}

# =============================================================================
# AEM CONFIGURATION
# =============================================================================

configure_aem_truststore() {
    log_section "CONFIGURING AEM TRUSTSTORE"
    
    # Check if truststore already exists
    local has_aliases
    has_aliases=$(curl -s "${AEM_PUBLISH_URL}/libs/granite/security/truststore.json" \
        --basic -u "${AEM_ADMIN_USER}:${AEM_ADMIN_PASS}" | jq 'has("aliases")')
    
    if [ "$has_aliases" == "true" ]; then
        log_step "Truststore already exists, skipping creation"
        export SAML_ALIAS=$(curl -s "${AEM_PUBLISH_URL}/libs/granite/security/truststore.json" \
            --basic -u "${AEM_ADMIN_USER}:${AEM_ADMIN_PASS}" | jq -r '.aliases[].alias')
        log_success "Using existing truststore (SAML_ALIAS=${SAML_ALIAS})"
        return 0
    fi
    
    log_step "Creating truststore..."
    curl -s "${AEM_PUBLISH_URL}/libs/granite/security/post/truststore" \
        --basic -u "${AEM_ADMIN_USER}:${AEM_ADMIN_PASS}" \
        -H 'Content-Type: application/x-www-form-urlencoded; charset=UTF-8' \
        --data "newPassword=${OPENSSL_PASS}&rePassword=${OPENSSL_PASS}&%3Aoperation=createStore"
    
    log_step "Uploading Keycloak IdP certificate to truststore..."
    curl -s "${AEM_PUBLISH_URL}/libs/granite/security/post/truststore" \
        --basic -u "${AEM_ADMIN_USER}:${AEM_ADMIN_PASS}" \
        -F "certificate=@${CERT_DIR}/keycloak.pem"
    
    log_step "Retrieving SAML IdP certificate alias..."
    export SAML_ALIAS=$(curl -s "${AEM_PUBLISH_URL}/libs/granite/security/truststore.json" \
        --basic -u "${AEM_ADMIN_USER}:${AEM_ADMIN_PASS}" | jq -r '.aliases[].alias')
    
    log_success "Truststore configured (SAML_ALIAS=${SAML_ALIAS})"
}

configure_aem_keystore() {
    log_section "CONFIGURING AEM KEYSTORE"
    
    log_step "Finding authentication-service user path..."
    local auth_user_path
    auth_user_path=$(curl -s "${AEM_PUBLISH_URL}/bin/querybuilder.json" \
        --basic -u "${AEM_ADMIN_USER}:${AEM_ADMIN_PASS}" \
        --data-urlencode "path=/home/users/system/cq:services/internal/security" \
        --data-urlencode "1_property=rep:principalName" \
        --data-urlencode "1_property.value=authentication-service" \
        --data-urlencode "1_property.operation=equal" \
        --data-urlencode "type=rep:SystemUser" \
        --data-urlencode "p.hits=full" | jq -r '.hits[0]."jcr:path"')
    
    if [ -z "${auth_user_path}" ] || [ "${auth_user_path}" == "null" ]; then
        log_error "Could not find authentication-service user"
        return 1
    fi
    log_success "Found authentication-service user: ${auth_user_path}"
    
    log_step "Creating keystore for authentication-service user..."
    curl -s "${AEM_PUBLISH_URL}${auth_user_path}.ks.html" \
        --basic -u "${AEM_ADMIN_USER}:${AEM_ADMIN_PASS}" \
        -H 'Content-Type: application/x-www-form-urlencoded; charset=UTF-8' \
        --data "newPassword=${OPENSSL_PASS}&rePassword=${OPENSSL_PASS}&%3Aoperation=createStore"
    
    log_step "Uploading SAML SP key and certificate..."
    curl -s "${AEM_PUBLISH_URL}${auth_user_path}.ks.html" \
        --basic -u "${AEM_ADMIN_USER}:${AEM_ADMIN_PASS}" \
        -F 'alias=aem-sp' \
        -F "pk=@${CERT_DIR}/aem-private-pkcs8.der" \
        -F "cert-chain=@${CERT_DIR}/aem-public.crt"
    
    log_success "Keystore configured"
}

# =============================================================================
# MAIN EXECUTION
# =============================================================================

main() {
    log_section "SAML SETUP STARTED"
    
    echo "Configuration:"
    echo "  AEM_PUBLISH_URL: ${AEM_PUBLISH_URL}"
    echo "  KEYCLOAK_URL: ${KEYCLOAK_URL}"
    echo "  KEYCLOAK_REALM: ${KEYCLOAK_REALM}"
    echo "  CERT_DIR: ${CERT_DIR}"
    echo "  KEYCLOAK_URL=${KEYCLOAK_URL}"
    echo "  KEYCLOAK_REALM=${KEYCLOAK_REALM}"
    echo "  KEYCLOAK_ADMIN=${KEYCLOAK_ADMIN}"
    echo "  KEYCLOAK_ADMIN_PASSWORD=${KEYCLOAK_ADMIN_PASSWORD}"
    echo "  KEYCLOAK_SAML_CLIENT_ID=${KEYCLOAK_SAML_CLIENT_ID}"
    echo "  CERT_DIR=${CERT_DIR}"
    echo "  OPENSSL_PASS=${OPENSSL_PASS}"
    echo "  MAVEN_REPOSITORY=${MAVEN_REPOSITORY}"
    echo "  CONTENT_PATH=${CONTENT_PATH}"
    echo "  ENV_FILE=${ENV_FILE}"
   
    # Generate certificates at runtime
    create_aem_certificates
    retrieve_keycloak_certificate
    
    # Upload AEM certificate to Keycloak
    upload_certificate_to_keycloak
    
    # Configure AEM
    configure_aem_truststore
    configure_aem_keystore
    
    log_section "SAML SETUP COMPLETE"
    echo ""
    echo "  SAML_ALIAS: ${SAML_ALIAS}"
    echo ""
}

# Run main function
main "$@"

