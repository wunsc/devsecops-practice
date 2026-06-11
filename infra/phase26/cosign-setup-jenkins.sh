#!/bin/bash
# infra/phase26/cosign-setup-jenkins.sh
# Configures Jenkins to use RHTAS for image signing
#
# What this script does:
# 1. Extracts CTLog public key from RHTAS
# 2. Extracts Rekor public key from RHTAS
# 3. Creates ConfigMap in devsecops-tools namespace for Jenkins agent to use
# 4. Initializes cosign TUF root from RHTAS cli-server

set -euo pipefail

CLUSTER_DOMAIN="${1:?Usage: $0 <cluster-apps-domain>}"
TOOLS_NS="devsecops-tools"
TAS_NS="trusted-artifact-signer"

echo "=== Extracting RHTAS keys for Jenkins ==="

# Get Fulcio root CA cert
FULCIO_URL="https://fulcio-server-${TAS_NS}.${CLUSTER_DOMAIN}"
echo "Fulcio URL: ${FULCIO_URL}"

# Get Rekor public key
REKOR_URL="https://rekor-server-${TAS_NS}.${CLUSTER_DOMAIN}"
curl -sfk "${REKOR_URL}/api/v1/log/publicKey" > /tmp/rekor-public.pem
echo "Retrieved Rekor public key"

# Get CTLog public key from the RHTAS secret
CTL_SECRET=$(oc get secrets -n ${TAS_NS} -o name | grep ctlog-public-key | head -1)
if [ -n "${CTL_SECRET}" ]; then
  oc get ${CTL_SECRET} -n ${TAS_NS} -o jsonpath='{.data.public}' | base64 -d > /tmp/ctlog-public.pem
  echo "Retrieved CTLog public key"
else
  echo "WARNING: CTLog public key secret not found"
fi

# Create ConfigMap in Jenkins namespace with signing keys
oc create configmap rhtas-signing-keys \
  --from-file=rekor-public.pem=/tmp/rekor-public.pem \
  --from-file=ctlog-public.pem=/tmp/ctlog-public.pem \
  -n ${TOOLS_NS} \
  --dry-run=client -o yaml | oc apply -f -

echo "ConfigMap 'rhtas-signing-keys' created in ${TOOLS_NS}"

# Store RHTAS URLs as ConfigMap for pipeline use
oc create configmap rhtas-config \
  --from-literal=FULCIO_URL="${FULCIO_URL}" \
  --from-literal=REKOR_URL="${REKOR_URL}" \
  --from-literal=TUF_MIRROR="https://cli-server-${TAS_NS}.${CLUSTER_DOMAIN}" \
  --from-literal=OIDC_ISSUER="https://keycloak.${CLUSTER_DOMAIN}/realms/trustify" \
  -n ${TOOLS_NS} \
  --dry-run=client -o yaml | oc apply -f -

echo "ConfigMap 'rhtas-config' created in ${TOOLS_NS}"

# Cleanup
rm -f /tmp/rekor-public.pem /tmp/ctlog-public.pem

echo "Done. Jenkins signImage.groovy will use these ConfigMaps."
