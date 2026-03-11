#!/bin/bash
# infra/phase13/apply-sonarqube-gate.sh
# Creates a custom SonarQube quality gate via the Web API
#
# PREREQUISITES:
#   - SonarQube is running and accessible
#   - Admin credentials or token with admin permissions
#
# USAGE:
#   export SONARQUBE_URL="https://sonarqube-devsecops-tools.apps.cluster-pmqwq.pmqwq.sandbox270.opentlc.com"
#   export SONARQUBE_TOKEN="sqa_..."
#   bash apply-sonarqube-gate.sh
#
# WHAT THIS DOES:
#   1. Creates a quality gate named "DevSecOps-Gate"
#   2. Adds conditions: 0 bugs, 0 vulns, >=80% coverage, <3% duplication
#   3. Sets it as the default quality gate
#   4. Associates it with the sampleapi project (if exists)

set -euo pipefail

# --- Configuration ---
SONARQUBE_URL="${SONARQUBE_URL:-https://sonarqube-devsecops-tools.apps.cluster-pmqwq.pmqwq.sandbox270.opentlc.com}"
SONARQUBE_TOKEN="${SONARQUBE_TOKEN:?ERROR: SONARQUBE_TOKEN must be set}"
PROJECT_KEY="${PROJECT_KEY:-sampleapi}"
GATE_NAME="${GATE_NAME:-DevSecOps-Gate}"

# SonarQube API uses token as username with empty password
AUTH="${SONARQUBE_TOKEN}:"

echo "============================================"
echo "SonarQube Quality Gate Configuration"
echo "============================================"
echo "URL:          ${SONARQUBE_URL}"
echo "Gate Name:    ${GATE_NAME}"
echo "Project Key:  ${PROJECT_KEY}"
echo ""

# --- Helper: API call with error checking ---
sq_api() {
  local method="$1"
  local endpoint="$2"
  shift 2
  local response
  response=$(curl -sk -w "\n%{http_code}" \
    -X "${method}" \
    -u "${AUTH}" \
    "${SONARQUBE_URL}/api/${endpoint}" \
    "$@")
  local http_code
  http_code=$(echo "${response}" | tail -1)
  local body
  body=$(echo "${response}" | sed '$d')
  if [[ "${http_code}" -ge 400 ]]; then
    echo "  WARNING: HTTP ${http_code} — ${body}" >&2
    echo "${body}"
    return 1
  fi
  echo "${body}"
}

# --- Step 1: Check if gate already exists ---
echo "Step 1: Checking for existing quality gate..."
existing=$(sq_api GET "qualitygates/list" 2>/dev/null || echo "{}")
gate_id=$(echo "${existing}" | python3 -c "
import json, sys
data = json.load(sys.stdin)
for gate in data.get('qualitygates', []):
    if gate['name'] == '${GATE_NAME}':
        print(gate['id'])
        break
" 2>/dev/null || true)

if [[ -n "${gate_id}" ]]; then
  echo "  Gate '${GATE_NAME}' already exists (ID: ${gate_id})"
  echo "  Deleting existing gate to recreate..."
  sq_api POST "qualitygates/destroy" -d "id=${gate_id}" >/dev/null 2>&1 || true
fi

# --- Step 2: Create quality gate ---
echo "Step 2: Creating quality gate '${GATE_NAME}'..."
create_response=$(sq_api POST "qualitygates/create" -d "name=${GATE_NAME}")
gate_id=$(echo "${create_response}" | python3 -c "
import json, sys
data = json.load(sys.stdin)
print(data.get('id', ''))
" 2>/dev/null || true)

if [[ -z "${gate_id}" ]]; then
  echo "  ERROR: Failed to create quality gate"
  echo "  Response: ${create_response}"
  exit 1
fi
echo "  Created gate ID: ${gate_id}"

# --- Step 3: Add conditions ---
echo "Step 3: Adding quality gate conditions..."

# Condition format: metric, operator, error threshold
# Operators: GT (greater than), LT (less than)
declare -a CONDITIONS=(
  "new_bugs|GT|0|No new bugs"
  "new_vulnerabilities|GT|0|No new vulnerabilities"
  "new_security_hotspots_reviewed|LT|100|All security hotspots reviewed"
  "new_coverage|LT|80|Minimum 80% coverage on new code"
  "new_duplicated_lines_density|GT|3|Maximum 3% duplication on new code"
  "new_code_smells|GT|10|Maximum 10 new code smells"
  "new_reliability_rating|GT|1|Reliability rating A on new code"
  "new_security_rating|GT|1|Security rating A on new code"
)

for condition in "${CONDITIONS[@]}"; do
  IFS='|' read -r metric op error desc <<< "${condition}"
  echo "  Adding: ${desc} (${metric} ${op} ${error})"
  sq_api POST "qualitygates/create_condition" \
    -d "gateName=${GATE_NAME}" \
    -d "metric=${metric}" \
    -d "op=${op}" \
    -d "error=${error}" >/dev/null 2>&1 || echo "    WARNING: Failed to add condition ${metric}"
done

# --- Step 4: Set as default quality gate ---
echo "Step 4: Setting '${GATE_NAME}' as default..."
sq_api POST "qualitygates/set_as_default" -d "id=${gate_id}" >/dev/null 2>&1 || \
  echo "  WARNING: Failed to set as default"
echo "  Done"

# --- Step 5: Associate with project (if it exists) ---
echo "Step 5: Associating with project '${PROJECT_KEY}'..."
sq_api POST "qualitygates/select" \
  -d "gateName=${GATE_NAME}" \
  -d "projectKey=${PROJECT_KEY}" >/dev/null 2>&1 || \
  echo "  WARNING: Project '${PROJECT_KEY}' may not exist yet (will apply when created)"
echo "  Done"

# --- Step 6: Verify ---
echo ""
echo "============================================"
echo "Verification"
echo "============================================"
echo "Quality gate conditions:"
sq_api GET "qualitygates/show?name=${GATE_NAME}" 2>/dev/null | \
  python3 -m json.tool 2>/dev/null || echo "  (run manually to verify)"

echo ""
echo "Done. Quality gate '${GATE_NAME}' configured."
echo ""
echo "To verify in SonarQube UI:"
echo "  ${SONARQUBE_URL}/quality_gates"
