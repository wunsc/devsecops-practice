#!/bin/bash
# infra/phase12/setup-webhooks.sh
# Creates GitLab webhooks for all service repos -> Jenkins pipeline jobs
#
# ARCHITECTURE: Service-parameterized webhooks
#   Each service repo gets 3 webhooks (T1 MR, T2 Merge, T3 Tag).
#   The app-gitops repo gets 1 webhook (T4 Promotion).
#
# ADDING A NEW SERVICE:
#   1. Add an entry to the SERVICES array below:
#      "service_name:gitlab_project_id"
#   2. Ensure the Jenkins jobs exist ({service}-mr, {service}-merge, {service}-tag)
#   3. Run this script
#
# USAGE:
#   export GITLAB_TOKEN="glpat-..."
#   bash setup-webhooks.sh
#
# CLEANUP: To delete existing webhooks before creating new ones:
#   CLEANUP_EXISTING=true bash setup-webhooks.sh
#
# KNOWN FIXES:
#   - GitLab webhooks hit /project/<job> as anonymous user
#   - Jenkins must grant Overall/Read + Job/Read + Job/Build to anonymous
#   - ssl_verification must be false for self-signed certificates
#   - Tag push webhook uses push_events=false + tag_push_events=true

set -euo pipefail

# ═══════════════════════════════════════════════════════════════
# CONFIGURATION — adjust these for your environment
# ═══════════════════════════════════════════════════════════════

GITLAB_URL="${GITLAB_URL:-https://gitlab-devsecops-gitlab.apps.cluster-pmqwq.pmqwq.sandbox270.opentlc.com}"
GITLAB_TOKEN="${GITLAB_TOKEN:?ERROR: GITLAB_TOKEN must be set (personal access token with api scope)}"
JENKINS_URL="${JENKINS_URL:-https://jenkins-devsecops-tools.apps.cluster-pmqwq.pmqwq.sandbox270.opentlc.com}"

# ═══════════════════════════════════════════════════════════════
# SERVICE REGISTRY — "service_name:gitlab_project_id"
#
# TO ADD A NEW SERVICE: Add a line here.
# The project ID is the GitLab project ID for that service's repo.
# ═══════════════════════════════════════════════════════════════
SERVICES=(
  "sampleapi:1"           # devsecops/app-source (Project ID 1)
  "notificationapi:5"     # devsecops/notificationapi-source (Project ID 5)
)

# App-gitops project ID for T4 promotion webhook
GITOPS_PROJECT_ID="${GITOPS_PROJECT_ID:-4}"

# ═══════════════════════════════════════════════════════════════
# HELPER FUNCTIONS
# ═══════════════════════════════════════════════════════════════

create_webhook() {
  local name="$1"
  local job_name="$2"
  local project_id="$3"
  local push_events="$4"
  local mr_events="$5"
  local tag_events="$6"
  local branch_filter="${7:-}"

  local webhook_url="${JENKINS_URL}/project/${job_name}"

  echo "  Creating: ${name}"
  echo "    URL: ${webhook_url}"
  echo "    Push: ${push_events} | MR: ${mr_events} | Tag: ${tag_events}"

  local payload
  payload=$(cat <<JSONEOF
{
  "url": "${webhook_url}",
  "push_events": ${push_events},
  "merge_requests_events": ${mr_events},
  "tag_push_events": ${tag_events},
  "enable_ssl_verification": false,
  "push_events_branch_filter": "${branch_filter}"
}
JSONEOF
)

  local response
  response=$(curl -sk -w "\n%{http_code}" \
    -X POST \
    -H "PRIVATE-TOKEN: ${GITLAB_TOKEN}" \
    -H "Content-Type: application/json" \
    -d "${payload}" \
    "${GITLAB_URL}/api/v4/projects/${project_id}/hooks")

  local http_code
  http_code=$(echo "${response}" | tail -1)

  if [[ "${http_code}" == "201" ]]; then
    local hook_id
    hook_id=$(echo "${response}" | sed '$d' | grep -o '"id":[0-9]*' | head -1 | cut -d: -f2)
    echo "    OK (webhook ID: ${hook_id})"
  else
    echo "    ERROR: HTTP ${http_code}"
    echo "    $(echo "${response}" | sed '$d' | head -1)"
  fi
}

cleanup_webhooks() {
  local project_id="$1"
  local project_label="$2"

  echo "  Cleaning up existing webhooks for ${project_label} (project ${project_id})..."
  local existing
  existing=$(curl -sk \
    -H "PRIVATE-TOKEN: ${GITLAB_TOKEN}" \
    "${GITLAB_URL}/api/v4/projects/${project_id}/hooks")

  for hook_id in $(echo "${existing}" | grep -o '"id":[0-9]*' | cut -d: -f2); do
    curl -sk -X DELETE \
      -H "PRIVATE-TOKEN: ${GITLAB_TOKEN}" \
      "${GITLAB_URL}/api/v4/projects/${project_id}/hooks/${hook_id}" >/dev/null
    echo "    Deleted webhook ID: ${hook_id}"
  done
}

# ═══════════════════════════════════════════════════════════════
# MAIN
# ═══════════════════════════════════════════════════════════════

echo "============================================"
echo "GitLab Webhook Setup — Service Pipelines"
echo "============================================"
echo "GitLab:  ${GITLAB_URL}"
echo "Jenkins: ${JENKINS_URL}"
echo "Services: ${#SERVICES[@]}"
echo ""

# --- Create webhooks for each service ---
for entry in "${SERVICES[@]}"; do
  service_name="${entry%%:*}"
  project_id="${entry##*:}"

  echo "--- Service: ${service_name} (GitLab project ID: ${project_id}) ---"

  # Optional cleanup
  if [[ "${CLEANUP_EXISTING:-false}" == "true" ]]; then
    cleanup_webhooks "${project_id}" "${service_name}"
  fi

  # T1: MR Validation — triggered by MR events
  create_webhook \
    "T1 MR Validation" \
    "${service_name}-mr" \
    "${project_id}" \
    "false" "true" "false"

  # T2: Merge to Main — triggered by push to main
  create_webhook \
    "T2 Merge to Main" \
    "${service_name}-merge" \
    "${project_id}" \
    "true" "false" "false" \
    "main"

  # T3: Tag Release — triggered by tag push
  create_webhook \
    "T3 Tag Release" \
    "${service_name}-tag" \
    "${project_id}" \
    "false" "false" "true"

  echo ""
done

# --- T4: Promotion webhook on app-gitops repo ---
echo "--- Shared: app-gitops (GitLab project ID: ${GITOPS_PROJECT_ID}) ---"

if [[ "${CLEANUP_EXISTING:-false}" == "true" ]]; then
  cleanup_webhooks "${GITOPS_PROJECT_ID}" "app-gitops"
fi

create_webhook \
  "T4 GitOps Promotion" \
  "sampleapi-promote" \
  "${GITOPS_PROJECT_ID}" \
  "true" "false" "false" \
  "main"

echo ""

# --- Summary ---
echo "============================================"
echo "Summary"
echo "============================================"
echo ""
echo "Webhooks created per service:"
for entry in "${SERVICES[@]}"; do
  service_name="${entry%%:*}"
  project_id="${entry##*:}"
  echo "  ${service_name} (project ${project_id}):"
  echo "    T1 MR:    ${JENKINS_URL}/project/${service_name}-mr"
  echo "    T2 Merge: ${JENKINS_URL}/project/${service_name}-merge"
  echo "    T3 Tag:   ${JENKINS_URL}/project/${service_name}-tag"
done
echo ""
echo "  app-gitops (project ${GITOPS_PROJECT_ID}):"
echo "    T4 Promote: ${JENKINS_URL}/project/sampleapi-promote"
echo ""
echo "Next steps:"
echo "  1. Verify Jenkins anonymous access (Overall/Read, Job/Read, Job/Build)"
echo "  2. Test each trigger type per service"
echo ""
echo "To add a new service:"
echo "  1. Create GitLab repo: devsecops/{service}-source"
echo "  2. Add entry to SERVICES array in this script"
echo "  3. Add {service}-mr/merge/tag jobs to JCasC (copy existing block)"
echo "  4. Add configureForService() case in PipelineConfig.groovy"
echo "  5. Re-run this script"
