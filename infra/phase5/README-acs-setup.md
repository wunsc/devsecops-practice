# Red Hat ACS (StackRox) — Post-Deployment Setup Guide

## Prerequisites
- Phase 1 (namespaces) applied
- Cluster has access to Red Hat operator catalog (`redhat-operators`)

## Execution Order

```bash
# 1. Install the ACS Operator via OLM
oc apply -f infra/phase5/acs-operator-subscription.yaml

# 2. Wait for operator to be ready (CSV in Succeeded phase)
# This can take 2-5 minutes
echo "Waiting for ACS operator..."
while ! oc get csv -n rhacs-operator 2>/dev/null | grep -q rhacs; do
    sleep 10
    echo "  Still waiting..."
done
oc get csv -n rhacs-operator | grep rhacs
# Should show: rhacs-operator.v4.x.x   Succeeded

# 3. Deploy Central
oc apply -f infra/phase5/acs-central.yaml

# 4. Wait for Central to be ready (can take 5-10 minutes)
oc wait --for=condition=ready pod -l app=central -n stackrox --timeout=600s

# 5. Generate and apply init bundle (authenticates SecuredCluster → Central)
bash infra/phase5/acs-init-bundle-generate.sh

# 6. Deploy SecuredCluster (Sensor, Collector, Admission Controller)
oc apply -f infra/phase5/acs-secured-cluster.yaml

# 7. Wait for Sensor and Collector
oc wait --for=condition=ready pod -l app=sensor -n stackrox --timeout=300s
echo "Waiting for collector pods on each node..."
oc rollout status daemonset/collector -n stackrox --timeout=300s

# 8. Apply security policies (stored as ConfigMaps with policy JSON)
oc apply -f infra/phase5/acs-policies/
```

## Post-Deploy Manual Steps

### 1. Get Admin Password
```bash
# Auto-generated admin password (if not set manually)
oc get secret central-htpasswd -n stackrox -o jsonpath='{.data.password}' | base64 -d
echo ""
```

### 2. Access Central UI
```bash
# Get the route URL
oc get route central -n stackrox -o jsonpath='{.spec.host}'
# Open: https://<route-host>
# Login: admin / <password from step 1>
```

### 3. Import Security Policies
The policies are stored as ConfigMap JSON data. Import them via the ACS API:

```bash
CENTRAL_URL="https://$(oc get route central -n stackrox -o jsonpath='{.spec.host}')"
ADMIN_PASS="$(oc get secret central-htpasswd -n stackrox -o jsonpath='{.data.password}' | base64 -d)"

# Import each policy JSON from the ConfigMaps
for cm in acs-policy-block-critical-cves acs-policy-block-root-images acs-policy-block-untrusted-registries acs-policy-detect-runtime-threats; do
    echo "Importing policy from ConfigMap: ${cm}"
    # Extract all policy JSON keys from the ConfigMap
    for key in $(oc get configmap ${cm} -n stackrox -o jsonpath='{.data}' | python3 -c "import sys,json; print(' '.join(json.load(sys.stdin).keys()))"); do
        POLICY_JSON=$(oc get configmap ${cm} -n stackrox -o jsonpath="{.data.${key}}")
        curl -sk -u "admin:${ADMIN_PASS}" \
            -X POST "${CENTRAL_URL}/v1/policies" \
            -H "Content-Type: application/json" \
            -d "${POLICY_JSON}" && echo " -> OK" || echo " -> FAILED (may already exist)"
    done
done
```

### 4. Generate API Token for Jenkins
```bash
# Create an API token for Jenkins to use with roxctl
CENTRAL_URL="https://$(oc get route central -n stackrox -o jsonpath='{.spec.host}')"
ADMIN_PASS="$(oc get secret central-htpasswd -n stackrox -o jsonpath='{.data.password}' | base64 -d)"

# Generate a token with Continuous Integration role
ACS_TOKEN=$(curl -sk -u "admin:${ADMIN_PASS}" \
    -X POST "${CENTRAL_URL}/v1/apitokens/generate" \
    -H "Content-Type: application/json" \
    -d '{"name":"jenkins-ci","roles":["Continuous Integration"]}' \
    | python3 -c "import sys,json; print(json.load(sys.stdin)['token'])")

echo "ACS API Token: ${ACS_TOKEN}"

# Store in OpenShift as a secret for Jenkins
oc create secret generic acs-token \
    --from-literal=token="${ACS_TOKEN}" \
    -n devsecops-tools
oc label secret acs-token team=devsecops component=jenkins -n devsecops-tools
```

### 5. Configure Internal Registry Integration
ACS needs access to scan images in the internal registry:

```bash
# Via UI: Platform Configuration → Integrations → Image Integrations
# Add: OpenShift Container Registry
#   Name: OCP Internal Registry
#   Endpoint: image-registry.openshift-image-registry.svc:5000
#   Type: Registry
# The ACS scanner SA should have pull access automatically
```

## Verification

```bash
# Check all ACS components are running
oc get pods -n stackrox

# Expected pods:
# central-xxxxx                    1/1   Running
# central-db-xxxxx                 1/1   Running
# scanner-xxxxx                    1/1   Running
# scanner-db-xxxxx                 1/1   Running
# scanner-v4-indexer-xxxxx         1/1   Running
# scanner-v4-matcher-xxxxx         1/1   Running
# scanner-v4-db-xxxxx              1/1   Running
# sensor-xxxxx                     1/1   Running
# collector-xxxxx (per node)       3/3   Running
# admission-control-xxxxx          3/3   Running

# Test roxctl connectivity (if roxctl is installed)
# NOTE: roxctl has NO --token flag. Use ROX_API_TOKEN env var instead.
CENTRAL_URL="https://$(oc get route central -n stackrox -o jsonpath='{.spec.host}')"
export ROX_API_TOKEN=$(oc get secret acs-token -n devsecops-tools -o jsonpath='{.data.token}' | base64 -d)

roxctl -e "${CENTRAL_URL}:443" \
    --insecure-skip-tls-verify \
    central whoami

# Check policies are loaded
roxctl -e "${CENTRAL_URL}:443" \
    --insecure-skip-tls-verify \
    policy ls | grep -E "Block Critical|Block Root|Block Untrusted|Detect"

# Test image scan (example with a known image)
roxctl -e "${CENTRAL_URL}:443" \
    --insecure-skip-tls-verify \
    image check --image "registry.access.redhat.com/ubi9/ubi-minimal:latest"
```

## Troubleshooting

### Operator not installing
```bash
# Check OLM subscription status
oc get sub rhacs-operator -n rhacs-operator -o yaml | grep -A5 status

# Check install plan
oc get installplan -n rhacs-operator

# Check catalog source
oc get catalogsource -n openshift-marketplace | grep redhat
```

### Central pod not starting
```bash
# Check events
oc get events -n stackrox --sort-by='.lastTimestamp' | tail -20

# Check Central logs
oc logs deployment/central -n stackrox

# Common issue: PVC not binding (check storage class)
oc get pvc -n stackrox
```

### Sensor cannot connect to Central
```bash
# Verify init bundle was applied
oc get secret -n stackrox | grep init-bundle

# Check Sensor logs for connection errors
oc logs deployment/sensor -n stackrox | grep -i error

# Verify Central endpoint is correct in SecuredCluster CR
oc get securedcluster -n stackrox -o jsonpath='{.spec.centralEndpoint}'
# Expected: central.stackrox.svc:443 (NOT central-stackrox.stackrox.svc:443)
# Fix if wrong:
# oc patch securedcluster stackrox-secured-cluster-services -n stackrox --type merge \
#   -p '{"spec":{"centralEndpoint":"central.stackrox.svc:443"}}'
```

### ACS 401 from internal registry
ACS Central needs an image integration to authenticate with the internal registry.
Without it, `roxctl image scan` returns 401 errors for internal images.
The `scanACSImage.groovy` shared library handles this gracefully (reports 0 findings).

## Architecture Notes
- Central: Management plane (UI, API, policy engine, vuln DB) — single instance
- Scanner: Image vulnerability scanner — auto-scales 1-3 replicas
- Sensor: Per-cluster agent that monitors Kubernetes resources
- Collector: Per-node DaemonSet for runtime monitoring (eBPF mode)
- Admission Controller: Webhook that enforces deploy-time policies
- roxctl: CLI tool used by Jenkins pipeline for build-time image checks
- roxctl has NO `--token` flag — authentication is via `ROX_API_TOKEN` env var
- Known limitation (v4.5.x): `--force-print-all-violations` flag does not exist
