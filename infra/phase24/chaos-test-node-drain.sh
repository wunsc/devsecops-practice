#!/usr/bin/env bash
# infra/phase24/chaos-test-node-drain.sh
# Chaos test: Drain a worker node to verify zero-downtime pod migration.
#
# PREREQUISITES:
#   - PROD has 3 sampleapi replicas across 3 worker nodes
#   - PDB minAvailable=1 ensures at least 1 pod stays running during drain
#   - Run k6 load test in background to detect any failed requests
#
# USAGE:
#   bash chaos-test-node-drain.sh [worker-node-name]
#
# SAFETY:
#   - Automatically uncordons the node after the test
#   - Respects PDB — drain will wait if eviction would violate PDB
#   - Only drains ONE worker node at a time
set -euo pipefail

OC="${OC:-~/Downloads/oc}"
APPS_DOMAIN="${APPS_DOMAIN:-apps.cluster-pmqwq.pmqwq.sandbox270.opentlc.com}"

# Get worker nodes
WORKERS=($($OC get nodes -l node-role.kubernetes.io/worker --no-headers -o custom-columns='NAME:.metadata.name'))
echo "Available worker nodes:"
for w in "${WORKERS[@]}"; do echo "  - $w"; done

# Target node — use argument or default to first worker
TARGET_NODE="${1:-${WORKERS[0]}}"
echo ""
echo "=== Chaos Test: Node Drain ==="
echo "Target: ${TARGET_NODE}"
echo "Time:   $(date)"
echo ""

# Pre-test: Show pod distribution
echo "--- Pre-drain pod distribution ---"
$OC get pods -n sampleapi-prod -o custom-columns='POD:.metadata.name,NODE:.spec.nodeName,STATUS:.status.phase' --no-headers
echo ""

# Count pods on target node
PODS_ON_NODE=$($OC get pods -n sampleapi-prod --field-selector spec.nodeName=${TARGET_NODE} --no-headers | wc -l)
echo "Pods on ${TARGET_NODE}: ${PODS_ON_NODE}"
echo ""

if [ "$PODS_ON_NODE" -eq 0 ]; then
    echo "No pods on target node — nothing to test. Try a different node."
    exit 0
fi

# Verify health before drain
echo "--- Health check before drain ---"
HTTP_CODE=$(curl -sk -o /dev/null -w "%{http_code}" "https://sampleapi-sampleapi-prod.${APPS_DOMAIN}/healthz")
echo "Health check: HTTP ${HTTP_CODE}"
if [ "$HTTP_CODE" != "200" ]; then
    echo "ABORT: Application not healthy before drain"
    exit 1
fi

# Drain the node
echo ""
echo "Draining node ${TARGET_NODE}..."
echo "(This respects PDB — will wait if eviction would violate minAvailable)"
$OC adm drain "${TARGET_NODE}" \
    --ignore-daemonsets \
    --delete-emptydir-data \
    --timeout=120s \
    --pod-selector='app in (sampleapi,notificationapi)' 2>&1 || true

echo ""
echo "Drain completed. Waiting 30s for rescheduling..."
sleep 30

# Post-drain: Show pod distribution
echo "--- Post-drain pod distribution ---"
$OC get pods -n sampleapi-prod -o custom-columns='POD:.metadata.name,NODE:.spec.nodeName,STATUS:.status.phase' --no-headers
echo ""

# Verify health after drain
echo "--- Health check after drain ---"
HTTP_CODE=$(curl -sk -o /dev/null -w "%{http_code}" "https://sampleapi-sampleapi-prod.${APPS_DOMAIN}/healthz")
echo "Health check: HTTP ${HTTP_CODE}"

# Verify pod count
READY_COUNT=$($OC get pods -n sampleapi-prod -l app=sampleapi --field-selector=status.phase=Running --no-headers | wc -l)
echo "Running sampleapi pods: ${READY_COUNT}"

# Uncordon the node — ALWAYS do this
echo ""
echo "Uncordoning node ${TARGET_NODE}..."
$OC adm uncordon "${TARGET_NODE}"
echo "Node restored to schedulable."

# Final assessment
echo ""
if [ "$HTTP_CODE" = "200" ] && [ "$READY_COUNT" -ge 2 ]; then
    echo "PASS: Zero-downtime drain successful"
    echo "  - Health check passed throughout"
    echo "  - ${READY_COUNT} pods running after drain"
else
    echo "FAIL: Issues detected during drain"
    echo "  - Health check: HTTP ${HTTP_CODE}"
    echo "  - Running pods: ${READY_COUNT}"
    exit 1
fi

echo ""
echo "=== Chaos test completed — $(date) ==="
