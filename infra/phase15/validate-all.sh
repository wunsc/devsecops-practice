#!/bin/bash
# infra/phase15/validate-all.sh
# End-to-end validation script for the DevSecOps workflow
#
# Runs all verification checks from the Execution Runbook (Phase 15).
# Reports PASS/FAIL for each validation item.
#
# USAGE:
#   export CLUSTER_DOMAIN="apps.cluster-pmqwq.pmqwq.sandbox270.opentlc.com"
#   export GITLAB_TOKEN="glpat-..."
#   bash infra/phase15/validate-all.sh
#
# NOTE: This script only validates — it does NOT deploy or modify anything.

set -uo pipefail

# --- Configuration ---
CLUSTER_DOMAIN="${CLUSTER_DOMAIN:-apps.cluster-pmqwq.pmqwq.sandbox270.opentlc.com}"
APP_NAME="${APP_NAME:-sampleapi}"
GITLAB_URL="${GITLAB_URL:-https://gitlab-devsecops-gitlab.${CLUSTER_DOMAIN}}"
JENKINS_URL="${JENKINS_URL:-https://jenkins-devsecops-tools.${CLUSTER_DOMAIN}}"
SONARQUBE_URL="${SONARQUBE_URL:-https://sonarqube-devsecops-tools.${CLUSTER_DOMAIN}}"
ACS_CENTRAL_URL="${ACS_CENTRAL_URL:-https://central-stackrox.${CLUSTER_DOMAIN}}"
ARGOCD_URL="${ARGOCD_URL:-https://openshift-gitops-server-openshift-gitops.${CLUSTER_DOMAIN}}"
GITLAB_TOKEN="${GITLAB_TOKEN:-}"

PASS=0
FAIL=0
SKIP=0

# --- Helper functions ---
check() {
  local name="$1"
  local result="$2"
  local expected="${3:-0}"

  if [[ "${result}" == "${expected}" ]]; then
    echo "  [PASS] ${name}"
    ((PASS++))
  else
    echo "  [FAIL] ${name} (got: ${result}, expected: ${expected})"
    ((FAIL++))
  fi
}

check_http() {
  local name="$1"
  local url="$2"
  local expected="${3:-200}"

  local status
  status=$(curl -sk -o /dev/null -w "%{http_code}" "${url}" 2>/dev/null || echo "000")
  check "${name}" "${status}" "${expected}"
}

skip() {
  local name="$1"
  local reason="$2"
  echo "  [SKIP] ${name} — ${reason}"
  ((SKIP++))
}

# --- Start Validation ---
echo "============================================"
echo "DevSecOps Workflow — Full Validation"
echo "============================================"
echo "Cluster: ${CLUSTER_DOMAIN}"
echo "Date:    $(date -Iseconds)"
echo ""

# =====================================================
# Section 1: Namespaces & RBAC (Phase 1)
# =====================================================
echo "--- Phase 1: Infrastructure Foundation ---"

for ns in devsecops-tools devsecops-gitlab ${APP_NAME}-dev ${APP_NAME}-sit ${APP_NAME}-uat ${APP_NAME}-prod; do
  result=$(oc get ns "${ns}" -o jsonpath='{.status.phase}' 2>/dev/null || echo "NotFound")
  check "Namespace: ${ns}" "${result}" "Active"
done

# Service accounts
sa_result=$(oc get sa jenkins-sa -n devsecops-tools -o name 2>/dev/null || echo "NotFound")
check "ServiceAccount: jenkins-sa" "$(echo ${sa_result} | grep -c jenkins-sa || true)" "1"

# Network policies
netpol_count=$(oc get netpol -n ${APP_NAME}-dev --no-headers 2>/dev/null | wc -l || echo 0)
check "NetworkPolicies in ${APP_NAME}-dev (>=2)" "$([ ${netpol_count} -ge 2 ] && echo 1 || echo 0)" "1"

# Quotas
quota_result=$(oc get quota -n ${APP_NAME}-dev --no-headers 2>/dev/null | wc -l || echo 0)
check "ResourceQuota in ${APP_NAME}-dev" "$([ ${quota_result} -ge 1 ] && echo 1 || echo 0)" "1"

echo ""

# =====================================================
# Section 2: GitLab (Phase 2)
# =====================================================
echo "--- Phase 2: GitLab CE ---"

# GitLab pods
for pod_label in gitlab-ce gitlab-postgresql gitlab-redis; do
  pod_ready=$(oc get pods -l app=${pod_label} -n devsecops-gitlab -o jsonpath='{.items[0].status.phase}' 2>/dev/null || echo "NotFound")
  check "Pod: ${pod_label}" "${pod_ready}" "Running"
done

# GitLab route
check_http "GitLab Route accessible" "${GITLAB_URL}/users/sign_in"

# GitLab repos (requires token)
if [[ -n "${GITLAB_TOKEN}" ]]; then
  for repo in app-source build-config jenkins-shared-lib app-gitops; do
    repo_exists=$(curl -sk -H "PRIVATE-TOKEN: ${GITLAB_TOKEN}" \
      "${GITLAB_URL}/api/v4/projects?search=${repo}" 2>/dev/null | grep -c "\"name\":\"${repo}\"" || echo 0)
    check "GitLab repo: ${repo}" "$([ ${repo_exists} -ge 1 ] && echo 1 || echo 0)" "1"
  done
else
  skip "GitLab repos" "GITLAB_TOKEN not set"
fi

echo ""

# =====================================================
# Section 3: SonarQube (Phase 3)
# =====================================================
echo "--- Phase 3: SonarQube CE ---"

sonar_pod=$(oc get pods -l app=sonarqube -n devsecops-tools -o jsonpath='{.items[0].status.phase}' 2>/dev/null || echo "NotFound")
check "Pod: sonarqube" "${sonar_pod}" "Running"

check_http "SonarQube Route accessible" "${SONARQUBE_URL}/api/system/status"

echo ""

# =====================================================
# Section 4: Registry (Phase 4)
# =====================================================
echo "--- Phase 4: Registry Configuration ---"

can_push=$(oc auth can-i create imagestreams --as=system:serviceaccount:devsecops-tools:jenkins-sa -n ${APP_NAME}-dev 2>/dev/null || echo "no")
check "Jenkins SA can push images" "${can_push}" "yes"

echo ""

# =====================================================
# Section 5: ACS / StackRox (Phase 5)
# =====================================================
echo "--- Phase 5: Red Hat ACS ---"

central_pod=$(oc get pods -l app=central -n stackrox -o jsonpath='{.items[0].status.phase}' 2>/dev/null || echo "NotFound")
check "Pod: central" "${central_pod}" "Running"

sensor_pod=$(oc get pods -l app=sensor -n stackrox -o jsonpath='{.items[0].status.phase}' 2>/dev/null || echo "NotFound")
check "Pod: sensor" "${sensor_pod}" "Running"

check_http "ACS Central Route accessible" "${ACS_CENTRAL_URL}/v1/ping"

echo ""

# =====================================================
# Section 6: ArgoCD (Phase 6)
# =====================================================
echo "--- Phase 6: OpenShift GitOps / ArgoCD ---"

argocd_pod=$(oc get pods -l app.kubernetes.io/name=openshift-gitops-server -n openshift-gitops -o jsonpath='{.items[0].status.phase}' 2>/dev/null || echo "NotFound")
check "Pod: argocd-server" "${argocd_pod}" "Running"

check_http "ArgoCD Route accessible" "${ARGOCD_URL}/healthz"

# ArgoCD applications
for env in dev sit uat prod; do
  app_exists=$(oc get application ${APP_NAME}-${env} -n openshift-gitops -o name 2>/dev/null | wc -l || echo 0)
  check "ArgoCD App: ${APP_NAME}-${env}" "$([ ${app_exists} -ge 1 ] && echo 1 || echo 0)" "1"
done

echo ""

# =====================================================
# Section 7: Jenkins (Phase 7)
# =====================================================
echo "--- Phase 7: Jenkins ---"

jenkins_pod=$(oc get pods -l app=jenkins -n devsecops-tools -o jsonpath='{.items[0].status.phase}' 2>/dev/null || echo "NotFound")
check "Pod: jenkins" "${jenkins_pod}" "Running"

check_http "Jenkins Route accessible" "${JENKINS_URL}/login"

# Agent image
agent_is=$(oc get is jenkins-agent-devsecops -n devsecops-tools -o name 2>/dev/null | wc -l || echo 0)
check "Agent ImageStream exists" "$([ ${agent_is} -ge 1 ] && echo 1 || echo 0)" "1"

echo ""

# =====================================================
# Section 8: Webhooks (Phase 12)
# =====================================================
echo "--- Phase 12: Webhooks ---"

if [[ -n "${GITLAB_TOKEN}" ]]; then
  webhook_count=$(curl -sk -H "PRIVATE-TOKEN: ${GITLAB_TOKEN}" \
    "${GITLAB_URL}/api/v4/projects/1/hooks" 2>/dev/null | grep -o '"id"' | wc -l || echo 0)
  check "GitLab webhooks (>=3)" "$([ ${webhook_count} -ge 3 ] && echo 1 || echo 0)" "1"
else
  skip "Webhooks" "GITLAB_TOKEN not set"
fi

# Jenkins jobs respond to webhook endpoint
for job in sampleapi-mr sampleapi-merge sampleapi-tag; do
  job_status=$(curl -sk -o /dev/null -w "%{http_code}" "${JENKINS_URL}/project/${job}" 2>/dev/null || echo "000")
  # 200 or 201 = job exists and accepts webhooks; 403 = auth issue
  check "Jenkins webhook endpoint: ${job}" "$([ ${job_status} -eq 200 -o ${job_status} -eq 201 ] && echo 1 || echo 0)" "1"
done

echo ""

# =====================================================
# Section 9: Security Policies (Phase 13)
# =====================================================
echo "--- Phase 13: Security Policies ---"

# SonarQube quality gate
if [[ -n "${SONARQUBE_TOKEN:-}" ]]; then
  gate_exists=$(curl -sk -u "${SONARQUBE_TOKEN}:" \
    "${SONARQUBE_URL}/api/qualitygates/list" 2>/dev/null | grep -c "DevSecOps-Gate" || echo 0)
  check "SonarQube quality gate: DevSecOps-Gate" "$([ ${gate_exists} -ge 1 ] && echo 1 || echo 0)" "1"
else
  skip "SonarQube quality gate" "SONARQUBE_TOKEN not set"
fi

echo ""

# =====================================================
# Section 10: Monitoring (Phase 14)
# =====================================================
echo "--- Phase 14: Monitoring ---"

uwm_pod=$(oc get pods -l app.kubernetes.io/name=prometheus -n openshift-user-workload-monitoring -o jsonpath='{.items[0].status.phase}' 2>/dev/null || echo "NotFound")
check "User Workload Monitoring" "${uwm_pod}" "Running"

sm_count=$(oc get servicemonitor -n ${APP_NAME}-dev --no-headers 2>/dev/null | wc -l || echo 0)
check "ServiceMonitor in ${APP_NAME}-dev" "$([ ${sm_count} -ge 1 ] && echo 1 || echo 0)" "1"

pr_count=$(oc get prometheusrule -n ${APP_NAME}-dev --no-headers 2>/dev/null | wc -l || echo 0)
check "PrometheusRule in ${APP_NAME}-dev" "$([ ${pr_count} -ge 1 ] && echo 1 || echo 0)" "1"

echo ""

# =====================================================
# Section 11: Application (DEV)
# =====================================================
echo "--- Application: DEV Environment ---"

dev_pod=$(oc get pods -l app=sampleapi -n ${APP_NAME}-dev -o jsonpath='{.items[0].status.phase}' 2>/dev/null || echo "NotFound")
check "SampleAPI pod in DEV" "${dev_pod}" "Running"

check_http "SampleAPI /healthz" "https://${APP_NAME}-${APP_NAME}-dev.${CLUSTER_DOMAIN}/healthz"
check_http "SampleAPI /api/info" "https://${APP_NAME}-${APP_NAME}-dev.${CLUSTER_DOMAIN}/api/info"

# ArgoCD sync status
dev_sync=$(oc get application ${APP_NAME}-dev -n openshift-gitops -o jsonpath='{.status.sync.status}' 2>/dev/null || echo "Unknown")
check "ArgoCD DEV sync status" "${dev_sync}" "Synced"

dev_health=$(oc get application ${APP_NAME}-dev -n openshift-gitops -o jsonpath='{.status.health.status}' 2>/dev/null || echo "Unknown")
check "ArgoCD DEV health status" "${dev_health}" "Healthy"

echo ""

# =====================================================
# Summary
# =====================================================
echo "============================================"
echo "VALIDATION SUMMARY"
echo "============================================"
TOTAL=$((PASS + FAIL + SKIP))
echo "  TOTAL:  ${TOTAL}"
echo "  PASS:   ${PASS}"
echo "  FAIL:   ${FAIL}"
echo "  SKIP:   ${SKIP}"
echo ""

if [[ ${FAIL} -eq 0 ]]; then
  echo "  RESULT: ALL CHECKS PASSED"
  exit 0
else
  echo "  RESULT: ${FAIL} CHECK(S) FAILED"
  echo ""
  echo "  Review the [FAIL] items above and consult the"
  echo "  troubleshooting section in EXECUTION-RUNBOOK.md"
  exit 1
fi
