#!/bin/bash
# infra/phase25/keycloak-tls-generate.sh
# Generate self-signed TLS certificate for Keycloak and create the k8s secret
#
# Usage: ./keycloak-tls-generate.sh <cluster-domain>
# Example: ./keycloak-tls-generate.sh apps.muhrahma-cluster.vmware.tamlab.rdu2.redhat.com

set -euo pipefail

CLUSTER_DOMAIN="${1:?Usage: $0 <cluster-apps-domain>}"
KEYCLOAK_HOSTNAME="keycloak.${CLUSTER_DOMAIN}"
NAMESPACE="rhbok-operator"

echo "Generating TLS certificate for ${KEYCLOAK_HOSTNAME}..."

# Generate self-signed certificate
openssl req -x509 -nodes -days 365 \
  -newkey rsa:2048 \
  -keyout /tmp/keycloak-tls.key \
  -out /tmp/keycloak-tls.crt \
  -subj "/CN=${KEYCLOAK_HOSTNAME}/O=Demo" \
  -addext "subjectAltName=DNS:${KEYCLOAK_HOSTNAME}"

# Create the TLS secret in OpenShift
oc create secret tls keycloak-tls-secret \
  --cert=/tmp/keycloak-tls.crt \
  --key=/tmp/keycloak-tls.key \
  -n "${NAMESPACE}" \
  --dry-run=client -o yaml | oc apply -f -

# Also store the CA cert as a ConfigMap for other namespaces to trust
oc create configmap keycloak-tls-ca \
  --from-file=ca.crt=/tmp/keycloak-tls.crt \
  -n "${NAMESPACE}" \
  --dry-run=client -o yaml | oc apply -f -

echo "TLS secret 'keycloak-tls-secret' created in ${NAMESPACE}"
echo "CA ConfigMap 'keycloak-tls-ca' created in ${NAMESPACE}"

# Copy CA to namespaces that need it (RHTPA)
for NS in test-app trusted-artifact-signer devsecops-tools; do
  oc create configmap keycloak-tls-ca \
    --from-file=ca.crt=/tmp/keycloak-tls.crt \
    -n "${NS}" \
    --dry-run=client -o yaml | oc apply -f - 2>/dev/null || echo "Skipped ${NS} (namespace may not exist yet)"
done

# Cleanup
rm -f /tmp/keycloak-tls.key /tmp/keycloak-tls.crt
echo "Done."
