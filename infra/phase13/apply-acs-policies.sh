#!/bin/bash
# infra/phase13/apply-acs-policies.sh
# Imports custom ACS (StackRox) security policies via the Central API
#
# PREREQUISITES:
#   - ACS Central is running and accessible
#   - ACS admin API token or admin password
#   - roxctl CLI installed (optional — script uses REST API as fallback)
#
# USAGE:
#   export ACS_CENTRAL_URL="https://central-stackrox.apps.cluster-pmqwq.pmqwq.sandbox270.opentlc.com"
#   export ACS_API_TOKEN="..."   # or ACS_ADMIN_PASSWORD
#   bash apply-acs-policies.sh
#
# WHAT THIS DOES:
#   1. Imports 6 custom security policies (build, deploy, runtime)
#   2. Verifies each policy was imported successfully
#   3. Lists all custom policies

set -euo pipefail

# --- Configuration ---
ACS_CENTRAL_URL="${ACS_CENTRAL_URL:-https://central-stackrox.apps.cluster-pmqwq.pmqwq.sandbox270.opentlc.com}"
ACS_API_TOKEN="${ACS_API_TOKEN:-}"
ACS_ADMIN_PASSWORD="${ACS_ADMIN_PASSWORD:-}"
POLICY_DIR="${POLICY_DIR:-$(dirname "$0")/acs-policies}"

echo "============================================"
echo "ACS Security Policy Import"
echo "============================================"
echo "Central URL:  ${ACS_CENTRAL_URL}"
echo "Policy Dir:   ${POLICY_DIR}"
echo ""

# --- Determine auth method ---
AUTH_HEADER=""
if [[ -n "${ACS_API_TOKEN}" ]]; then
  AUTH_HEADER="Authorization: Bearer ${ACS_API_TOKEN}"
  echo "Auth method:  API Token"
elif [[ -n "${ACS_ADMIN_PASSWORD}" ]]; then
  # Generate a short-lived token from admin credentials
  echo "Auth method:  Admin password (generating token...)"
  token_response=$(curl -sk -X POST \
    -u "admin:${ACS_ADMIN_PASSWORD}" \
    "${ACS_CENTRAL_URL}/v1/apitokens/generate" \
    -H "Content-Type: application/json" \
    -d '{"name":"policy-import-temp","role":"Admin"}')
  ACS_API_TOKEN=$(echo "${token_response}" | python3 -c "
import json, sys
data = json.load(sys.stdin)
print(data.get('token', ''))
" 2>/dev/null || true)
  if [[ -z "${ACS_API_TOKEN}" ]]; then
    echo "ERROR: Failed to generate API token from admin password"
    echo "Response: ${token_response}"
    exit 1
  fi
  AUTH_HEADER="Authorization: Bearer ${ACS_API_TOKEN}"
  echo "  Token generated successfully"
else
  echo "ERROR: Set ACS_API_TOKEN or ACS_ADMIN_PASSWORD"
  exit 1
fi
echo ""

# --- Helper: Import a policy JSON file ---
import_policy() {
  local file="$1"
  local filename
  filename=$(basename "${file}")
  local policy_name
  policy_name=$(python3 -c "
import json, sys
with open('${file}') as f:
    data = json.load(f)
policies = data.get('policies', [])
if policies:
    print(policies[0].get('name', 'Unknown'))
else:
    print('Unknown')
" 2>/dev/null || echo "Unknown")

  echo "--- Importing: ${filename} ---"
  echo "  Policy name: ${policy_name}"

  # Check if policy already exists (by name)
  existing=$(curl -sk \
    -H "${AUTH_HEADER}" \
    "${ACS_CENTRAL_URL}/v1/policies?query=Policy:${policy_name// /%20}" 2>/dev/null || echo "{}")

  existing_id=$(echo "${existing}" | python3 -c "
import json, sys
data = json.load(sys.stdin)
for p in data.get('policies', []):
    if p.get('name') == '${policy_name}':
        print(p['id'])
        break
" 2>/dev/null || true)

  if [[ -n "${existing_id}" ]]; then
    echo "  Policy already exists (ID: ${existing_id})"
    echo "  Deleting existing policy to reimport..."
    curl -sk -X DELETE \
      -H "${AUTH_HEADER}" \
      "${ACS_CENTRAL_URL}/v1/policies/${existing_id}" >/dev/null 2>&1 || true
  fi

  # Import via REST API
  local response
  response=$(curl -sk -w "\n%{http_code}" \
    -X POST \
    -H "${AUTH_HEADER}" \
    -H "Content-Type: application/json" \
    -d @"${file}" \
    "${ACS_CENTRAL_URL}/v1/policies/import")

  local http_code
  http_code=$(echo "${response}" | tail -1)
  local body
  body=$(echo "${response}" | sed '$d')

  if [[ "${http_code}" -ge 200 && "${http_code}" -lt 300 ]]; then
    echo "  SUCCESS: Policy imported"
  else
    echo "  WARNING: HTTP ${http_code}"
    # Try roxctl as fallback
    if command -v roxctl &>/dev/null; then
      echo "  Trying roxctl fallback..."
      ROX_API_TOKEN="${ACS_API_TOKEN}" roxctl -e "${ACS_CENTRAL_URL}" \
        policy import --file="${file}" 2>&1 | head -5 || true
    else
      echo "  Body: ${body}"
    fi
  fi
  echo ""
}

# --- Import all policy files ---
echo "============================================"
echo "Importing policies from ${POLICY_DIR}"
echo "============================================"
echo ""

if [[ ! -d "${POLICY_DIR}" ]]; then
  echo "ERROR: Policy directory not found: ${POLICY_DIR}"
  exit 1
fi

policy_count=0
for policy_file in "${POLICY_DIR}"/*.json; do
  if [[ -f "${policy_file}" ]]; then
    import_policy "${policy_file}"
    ((policy_count++))
  fi
done

echo "============================================"
echo "Summary: ${policy_count} policies processed"
echo "============================================"
echo ""

# --- Verify: List all custom policies ---
echo "Listing all custom policies..."
all_policies=$(curl -sk \
  -H "${AUTH_HEADER}" \
  "${ACS_CENTRAL_URL}/v1/policies?query=" 2>/dev/null || echo "{}")

echo "${all_policies}" | python3 -c "
import json, sys
data = json.load(sys.stdin)
custom = [p for p in data.get('policies', []) if not p.get('isDefault', True)]
print(f'Custom policies: {len(custom)}')
for p in custom:
    lifecycle = ', '.join(p.get('lifecycleStages', []))
    severity = p.get('severity', 'UNKNOWN').replace('_SEVERITY', '')
    disabled = ' (DISABLED)' if p.get('disabled', False) else ''
    print(f'  [{lifecycle}] [{severity}] {p[\"name\"]}{disabled}')
" 2>/dev/null || echo "  (manual verification required)"

echo ""
echo "Done. Verify in ACS Central UI:"
echo "  ${ACS_CENTRAL_URL}/main/policy-management/policies"
