#!/bin/bash
# infra/phase5/acs-init-bundle-generate.sh
# Generates an init bundle for SecuredCluster ↔ Central authentication
# The init bundle creates TLS secrets that allow Sensor to trust Central
#
# Prerequisites:
#   - Central is running and accessible
#   - roxctl CLI is installed (or use the Central UI)
#   - Admin password is available
#
# Usage: bash acs-init-bundle-generate.sh

set -euo pipefail

# --- Configuration ---
# Central route (auto-detected from OpenShift)
CENTRAL_ROUTE=$(oc get route central -n stackrox -o jsonpath='{.spec.host}' 2>/dev/null || echo "")
if [[ -z "${CENTRAL_ROUTE}" ]]; then
    echo "ERROR: Cannot find Central route. Is Central deployed in 'stackrox' namespace?"
    exit 1
fi

CENTRAL_URL="https://${CENTRAL_ROUTE}"
BUNDLE_NAME="local-cluster-init-bundle"
BUNDLE_OUTPUT="init-bundle-${BUNDLE_NAME}.yaml"

echo "=== ACS Init Bundle Generator ==="
echo "Central URL: ${CENTRAL_URL}"
echo "Bundle name: ${BUNDLE_NAME}"
echo ""

# --- Get admin password ---
# Try to extract from the auto-generated secret first
ADMIN_PASSWORD=$(oc get secret central-htpasswd -n stackrox -o jsonpath='{.data.password}' 2>/dev/null | base64 -d || echo "")
if [[ -z "${ADMIN_PASSWORD}" ]]; then
    echo "Could not auto-detect admin password."
    echo -n "Enter ACS Central admin password: "
    read -rs ADMIN_PASSWORD
    echo ""
fi

# --- Generate init bundle via roxctl ---
echo "Generating init bundle..."

# Check if roxctl is available
if command -v roxctl &>/dev/null; then
    roxctl -e "${CENTRAL_URL}:443" \
        --password "${ADMIN_PASSWORD}" \
        --insecure-skip-tls-verify \
        central init-bundles generate "${BUNDLE_NAME}" \
        --output-secrets "${BUNDLE_OUTPUT}"

    echo "Init bundle generated: ${BUNDLE_OUTPUT}"
    echo ""
    echo "Applying init bundle secrets to stackrox namespace..."
    oc apply -f "${BUNDLE_OUTPUT}" -n stackrox

    echo ""
    echo "=== Init bundle applied successfully ==="
    echo "You can now apply the SecuredCluster CR:"
    echo "  oc apply -f infra/phase5/acs-secured-cluster.yaml"
else
    echo "roxctl CLI not found. Generate the init bundle via the Central UI instead:"
    echo ""
    echo "1. Open: ${CENTRAL_URL}"
    echo "2. Login as admin"
    echo "3. Navigate to: Platform Configuration → Integrations → Cluster Init Bundle"
    echo "4. Click 'Generate Bundle'"
    echo "5. Name: ${BUNDLE_NAME}"
    echo "6. Download the Kubernetes secrets YAML"
    echo "7. Apply: oc apply -f <downloaded-bundle>.yaml -n stackrox"
    echo ""
    echo "Then apply the SecuredCluster CR:"
    echo "  oc apply -f infra/phase5/acs-secured-cluster.yaml"
fi
