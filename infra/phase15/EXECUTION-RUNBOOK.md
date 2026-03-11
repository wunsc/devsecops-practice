# Phase 15: Execution Sequence & Validation Runbook

## Overview

This runbook provides the complete sequential execution order for deploying the
entire DevSecOps workflow. All manifests were generated in Phases 1-14 — this
phase documents the exact order to apply them and how to validate each step.

**IMPORTANT:** Execute steps in order. Each step depends on the previous one.
Do NOT skip steps or run them in parallel unless explicitly noted.

## Prerequisites

- OpenShift 4.x cluster with cluster-admin access
- `oc` CLI authenticated: `oc login --server=<api-url>`
- `git` CLI installed
- `bash` shell (Linux/macOS)
- Cluster domain: `apps.cluster-pmqwq.pmqwq.sandbox270.opentlc.com`
- StorageClass: `gp3-csi` (default)

## Environment Variables (Set Once)

```bash
export CLUSTER_DOMAIN="apps.cluster-pmqwq.pmqwq.sandbox270.opentlc.com"
export APP_NAME="sampleapi"

# These are set after respective components are deployed
export GITLAB_URL=""
export GITLAB_TOKEN=""
export JENKINS_URL=""
export SONARQUBE_URL=""
export SONARQUBE_TOKEN=""
export ACS_CENTRAL_URL=""
export ACS_ADMIN_PASSWORD=""
export ARGOCD_URL=""
export ARGOCD_PASSWORD=""
```

---

## STEP 1: Infrastructure Foundation (Phase 1)

**Estimated time:** 2 minutes

```bash
# Apply namespaces first (everything depends on these)
oc apply -f infra/phase1/namespaces.yaml

# Apply RBAC
oc apply -f infra/phase1/serviceaccounts.yaml
oc apply -f infra/phase1/clusterroles.yaml
oc apply -f infra/phase1/rolebindings.yaml

# Apply network policies
oc apply -f infra/phase1/networkpolicies/default-deny.yaml
oc apply -f infra/phase1/networkpolicies/allow-ingress-router.yaml
oc apply -f infra/phase1/networkpolicies/allow-monitoring.yaml
oc apply -f infra/phase1/networkpolicies/tools-egress.yaml
oc apply -f infra/phase1/networkpolicies/app-namespace.yaml

# Apply resource quotas and limit ranges
oc apply -f infra/phase1/resourcequotas.yaml
oc apply -f infra/phase1/limitranges.yaml
```

### Verify Step 1

```bash
# All namespaces created
oc get ns -l team=devsecops
# Expected: devsecops-tools, devsecops-gitlab, sampleapi-dev, sampleapi-sit,
#           sampleapi-uat, sampleapi-prod

# Service accounts exist
oc get sa jenkins-sa -n devsecops-tools
oc get sa sampleapi-sa -n sampleapi-dev

# Network policies applied
oc get netpol -n sampleapi-dev
# Expected: default-deny, allow-ingress-router, allow-monitoring, allow-app-internal

# Quotas applied
oc get quota -n sampleapi-dev
oc get limitrange -n sampleapi-dev
```

**GATE:** All 6 namespaces exist, ServiceAccounts created, NetworkPolicies applied.

---

## STEP 2: GitLab CE (Phase 2)

**Estimated time:** 10-15 minutes (GitLab takes ~5 min to initialize)

```bash
# Secrets first (referenced by other resources)
oc apply -f infra/phase2/gitlab-secrets.yaml

# PostgreSQL (must be ready before GitLab)
oc apply -f infra/phase2/gitlab-postgresql-statefulset.yaml
oc wait --for=condition=ready pod -l app=gitlab-postgresql -n devsecops-gitlab --timeout=120s

# Redis (must be ready before GitLab)
oc apply -f infra/phase2/gitlab-redis-statefulset.yaml
oc wait --for=condition=ready pod -l app=gitlab-redis -n devsecops-gitlab --timeout=120s

# GitLab CE
oc apply -f infra/phase2/gitlab-configmap.yaml
oc apply -f infra/phase2/gitlab-deployment.yaml
oc apply -f infra/phase2/gitlab-service.yaml
oc apply -f infra/phase2/gitlab-route.yaml

# Wait for GitLab (takes 5+ minutes on first start)
oc wait --for=condition=ready pod -l app=gitlab-ce -n devsecops-gitlab --timeout=600s
```

### Verify Step 2

```bash
# All pods running
oc get pods -n devsecops-gitlab
# Expected: gitlab-ce (Running), gitlab-postgresql-0 (Running), gitlab-redis-0 (Running)

# Route accessible
export GITLAB_URL="https://gitlab-devsecops-gitlab.${CLUSTER_DOMAIN}"
curl -sk -o /dev/null -w "%{http_code}" "${GITLAB_URL}/users/sign_in"
# Expected: 200
```

### Post-Deploy: Manual Steps (see README-gitlab-setup.md)

1. Login as root with password from `gitlab-secrets.yaml`
2. Create group: `devsecops`
3. Create 4 repos: `app-source`, `build-config`, `jenkins-shared-lib`, `app-gitops`
4. Create personal access token (api, read_repository, write_repository)
5. Store token: `export GITLAB_TOKEN="glpat-..."`

**GATE:** GitLab accessible, 4 repos created, API token generated.

---

## STEP 3: SonarQube CE (Phase 3)

**Estimated time:** 5-8 minutes

```bash
# Secrets and PostgreSQL
oc apply -f infra/phase3/sonarqube-secrets.yaml
oc apply -f infra/phase3/sonarqube-postgresql-statefulset.yaml
oc wait --for=condition=ready pod -l app=sonarqube-postgresql -n devsecops-tools --timeout=120s

# SonarQube
oc apply -f infra/phase3/sonarqube-configmap.yaml
oc apply -f infra/phase3/sonarqube-deployment.yaml
oc apply -f infra/phase3/sonarqube-service.yaml
oc apply -f infra/phase3/sonarqube-route.yaml

# Wait for SonarQube
oc wait --for=condition=ready pod -l app=sonarqube -n devsecops-tools --timeout=300s
```

### Verify Step 3

```bash
export SONARQUBE_URL="https://sonarqube-devsecops-tools.${CLUSTER_DOMAIN}"
curl -sk -o /dev/null -w "%{http_code}" "${SONARQUBE_URL}/api/system/status"
# Expected: 200
```

### Post-Deploy: Manual Steps (see README-sonarqube-setup.md)

1. Login as admin/admin, change password
2. Create quality gate (or run `bash infra/phase13/apply-sonarqube-gate.sh`)
3. Create project: `sampleapi`
4. Generate token, store: `export SONARQUBE_TOKEN="sqa_..."`

**GATE:** SonarQube accessible, quality gate configured, token generated.

---

## STEP 4: Registry Configuration (Phase 4)

**Estimated time:** 1 minute

```bash
oc apply -f infra/phase4/registry-sa-rolebinding.yaml
```

### Verify Step 4

```bash
# Jenkins SA can push images
oc auth can-i create imagestreams --as=system:serviceaccount:devsecops-tools:jenkins-sa -n sampleapi-dev
# Expected: yes
```

**GATE:** Jenkins SA has image-builder and image-puller roles.

---

## STEP 5: Red Hat ACS / StackRox (Phase 5)

**Estimated time:** 10-15 minutes (operator + Central startup)

```bash
# Install operator
oc apply -f infra/phase5/acs-operator-subscription.yaml

# Wait for operator CSV
echo "Waiting for ACS operator..."
while ! oc get csv -n rhacs-operator 2>/dev/null | grep -q Succeeded; do
  sleep 10
  echo "  Still waiting..."
done
echo "ACS operator ready"

# Deploy Central
oc apply -f infra/phase5/acs-central.yaml
oc wait --for=condition=ready pod -l app=central -n stackrox --timeout=300s

# Generate init bundle and apply SecuredCluster
bash infra/phase5/acs-init-bundle-generate.sh
oc apply -f infra/phase5/acs-secured-cluster.yaml

# Wait for sensor
oc wait --for=condition=ready pod -l app=sensor -n stackrox --timeout=300s

# Apply custom security policies
oc apply -f infra/phase5/acs-policies/
```

### Verify Step 5

```bash
export ACS_CENTRAL_URL="https://central-stackrox.${CLUSTER_DOMAIN}"

# Central accessible
curl -sk -o /dev/null -w "%{http_code}" "${ACS_CENTRAL_URL}/v1/ping"
# Expected: 200

# All pods running
oc get pods -n stackrox
# Expected: central, scanner, scanner-db, sensor, collector (DaemonSet), admission-controller

# Get admin password
export ACS_ADMIN_PASSWORD=$(oc get secret central-htpasswd -n stackrox -o jsonpath='{.data.password}' | base64 -d)
```

**GATE:** ACS Central accessible, sensor connected, policies imported.

---

## STEP 6: OpenShift GitOps / ArgoCD (Phase 6)

**Estimated time:** 5-8 minutes

```bash
# Install operator (may already exist cluster-wide)
oc apply -f infra/phase6/gitops-operator-subscription.yaml

# Wait for operator
echo "Waiting for GitOps operator..."
while ! oc get pods -n openshift-gitops 2>/dev/null | grep -q Running; do
  sleep 10
  echo "  Still waiting..."
done
echo "GitOps operator ready"

# Configure ArgoCD
oc apply -f infra/phase6/argocd-repo-secret.yaml
oc apply -f infra/phase6/argocd-appproject.yaml
# Applications are applied AFTER repos are pushed to GitLab (Step 8)
```

### Verify Step 6

```bash
export ARGOCD_URL="https://openshift-gitops-server-openshift-gitops.${CLUSTER_DOMAIN}"
export ARGOCD_PASSWORD=$(oc get secret openshift-gitops-cluster -n openshift-gitops -o jsonpath='{.data.admin\.password}' | base64 -d)

# ArgoCD accessible
curl -sk -o /dev/null -w "%{http_code}" "${ARGOCD_URL}/healthz"
# Expected: 200
```

**GATE:** ArgoCD accessible, AppProject created, repo secret configured.

---

## STEP 7: Jenkins (Phase 7)

**Estimated time:** 15-20 minutes (agent image build + Jenkins startup)

```bash
# Build custom agent image
oc apply -f infra/phase7/agent-buildconfig.yaml
oc start-build jenkins-agent-devsecops -n devsecops-tools --follow
# Wait for build to complete (~10 min)

# Deploy Jenkins
oc apply -f infra/phase7/jenkins-sa-clusterrolebinding.yaml
oc apply -f infra/phase7/jenkins-credentials-secrets.yaml
oc apply -f infra/phase7/jenkins-casc-configmap.yaml
oc apply -f infra/phase7/jenkins-agent-pod-template.yaml
oc apply -f infra/phase7/jenkins-deployment.yaml

# Wait for Jenkins
oc wait --for=condition=ready pod -l app=jenkins -n devsecops-tools --timeout=300s
```

### Verify Step 7

```bash
export JENKINS_URL="https://jenkins-devsecops-tools.${CLUSTER_DOMAIN}"

# Jenkins accessible
curl -sk -o /dev/null -w "%{http_code}" "${JENKINS_URL}/login"
# Expected: 200

# Agent image built
oc get is jenkins-agent-devsecops -n devsecops-tools
```

### Post-Deploy: Configure Credentials

Jenkins credentials must be created via Script Console (NOT JCasC — known fix).
See `infra/phase7/README-jenkins-setup.md` for the Script Console commands.

Credentials to create:
- `gitlab-token` (UsernamePassword)
- `gitlab-api-token` (StringCredential)
- `sonarqube-token` (StringCredential)
- `acs-token` (StringCredential)
- `argocd-token` (StringCredential — contains admin password, NOT JWT)
- `cosign-signing-key` (FileCredential — optional)

**GATE:** Jenkins accessible, 3 jobs visible, agent image built, credentials configured.

---

## STEP 8: Push Repositories to GitLab

**Estimated time:** 5 minutes

```bash
# Push jenkins-shared-lib
cd jenkins-shared-lib/
git init && git add -A && git commit -m "Initial commit: Jenkins shared library"
git remote add origin "${GITLAB_URL}/devsecops/jenkins-shared-lib.git"
git push -u origin main
cd ..

# Push build-config
cd build-config/
git init && git add -A && git commit -m "Initial commit: Build configuration"
git remote add origin "${GITLAB_URL}/devsecops/build-config.git"
git push -u origin main
cd ..

# Push app-source
cd app-source/
git init && git add -A && git commit -m "Initial commit: SampleAPI .NET 8.0"
git remote add origin "${GITLAB_URL}/devsecops/app-source.git"
git push -u origin main
cd ..

# Push app-gitops
cd app-gitops/
git init && git add -A && git commit -m "Initial commit: GitOps manifests"
git remote add origin "${GITLAB_URL}/devsecops/app-gitops.git"
git push -u origin main
cd ..
```

### Now apply ArgoCD Applications (requires repos in GitLab)

```bash
oc apply -f infra/phase6/argocd-app-dev.yaml
oc apply -f infra/phase6/argocd-app-sit.yaml
oc apply -f infra/phase6/argocd-app-uat.yaml
oc apply -f infra/phase6/argocd-app-prod.yaml
```

### Verify Step 8

```bash
# All repos accessible
for repo in app-source build-config jenkins-shared-lib app-gitops; do
  echo -n "$repo: "
  curl -sk -o /dev/null -w "%{http_code}" \
    -H "PRIVATE-TOKEN: ${GITLAB_TOKEN}" \
    "${GITLAB_URL}/api/v4/projects?search=${repo}"
  echo ""
done

# ArgoCD sees applications
argocd app list --server "${ARGOCD_URL}" --auth-token "$(argocd login ${ARGOCD_URL} --username admin --password ${ARGOCD_PASSWORD} --insecure --grpc-web 2>/dev/null && argocd account generate-token 2>/dev/null)" 2>/dev/null || \
  echo "Check ArgoCD UI: ${ARGOCD_URL}"
```

**GATE:** 4 repos in GitLab with code, ArgoCD Applications created, DEV auto-sync starts.

---

## STEP 9: Configure Webhooks (Phase 12)

**Estimated time:** 2 minutes

```bash
export GITLAB_PROJECT_ID=1  # app-source project ID (adjust if different)
bash infra/phase12/setup-webhooks.sh
```

### Verify Step 9

```bash
# List webhooks
curl -sk -H "PRIVATE-TOKEN: ${GITLAB_TOKEN}" \
  "${GITLAB_URL}/api/v4/projects/${GITLAB_PROJECT_ID}/hooks" | python3 -m json.tool

# Test webhook connectivity (should return 200)
curl -sk -X POST "${JENKINS_URL}/project/sampleapi-merge" -o /dev/null -w "%{http_code}"
# Expected: 200
```

**GATE:** 3 webhooks created, Jenkins responds to webhook POSTs.

---

## STEP 10: Apply Security Policies (Phase 13)

**Estimated time:** 3 minutes

```bash
# SonarQube quality gate
bash infra/phase13/apply-sonarqube-gate.sh

# ACS security policies
bash infra/phase13/apply-acs-policies.sh
```

### Verify Step 10

```bash
# Quality gate exists
curl -sk -u "${SONARQUBE_TOKEN}:" \
  "${SONARQUBE_URL}/api/qualitygates/list" | python3 -c "
import json, sys
data = json.load(sys.stdin)
for g in data.get('qualitygates', []):
    default = ' (DEFAULT)' if g.get('isDefault') else ''
    print(f'  {g[\"name\"]}{default}')
"

# ACS policies imported
curl -sk -u "admin:${ACS_ADMIN_PASSWORD}" \
  "${ACS_CENTRAL_URL}/v1/policies?query=" | python3 -c "
import json, sys
data = json.load(sys.stdin)
custom = [p for p in data.get('policies', []) if not p.get('isDefault', True)]
print(f'Custom policies: {len(custom)}')
for p in custom:
    print(f'  {p[\"name\"]}')
" 2>/dev/null || echo "Check ACS UI"
```

**GATE:** Quality gate "DevSecOps-Gate" is default, ACS custom policies imported.

---

## STEP 11: Apply Monitoring (Phase 14)

**Estimated time:** 5 minutes

```bash
# Enable user workload monitoring (cluster-admin required)
oc apply -f infra/phase14/user-workload-monitoring.yaml

# Wait for user workload monitoring pods
oc wait --for=condition=ready pod -l app.kubernetes.io/name=prometheus \
  -n openshift-user-workload-monitoring --timeout=120s 2>/dev/null || \
  echo "User workload monitoring may take a few minutes to start"

# Apply ServiceMonitors and PrometheusRules to each app namespace
for ns in sampleapi-dev sampleapi-sit sampleapi-uat sampleapi-prod; do
  oc apply -f infra/phase14/servicemonitor-app.yaml -n $ns
  oc apply -f infra/phase14/prometheus-rules.yaml -n $ns
done

# Apply logging (requires Logging operator installed)
oc apply -f infra/phase14/cluster-log-forwarder.yaml -n openshift-logging 2>/dev/null || \
  echo "WARNING: OpenShift Logging operator not installed — skip logging config"

# ACS notifiers — configure via UI (see acs-notifier-config.yaml)
oc apply -f infra/phase14/acs-notifier-config.yaml
```

### Verify Step 11

```bash
# User workload monitoring pods
oc get pods -n openshift-user-workload-monitoring

# ServiceMonitors created
oc get servicemonitor -n sampleapi-dev

# PrometheusRules created
oc get prometheusrule -n sampleapi-dev

# Import Grafana dashboards via OCP Console:
#   Observe > Dashboards > Import JSON
#   Use: infra/phase14/grafana-dashboard-app.json
#   Use: infra/phase14/grafana-dashboard-pipeline.json
```

**GATE:** Prometheus monitoring user workloads, alerts defined, ServiceMonitors active.

---

## STEP 12: End-to-End Validation

### Test 12A: T1 — MR Validation Pipeline

```bash
# Create a feature branch
cd app-source/
git checkout -b feature/e2e-test
echo "// e2e test $(date)" >> src/SampleApi/Program.cs
git add -A && git commit -m "test: e2e validation"
git push origin feature/e2e-test

# Create MR via GitLab API
curl -sk -X POST \
  -H "PRIVATE-TOKEN: ${GITLAB_TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{"source_branch":"feature/e2e-test","target_branch":"main","title":"E2E Test MR"}' \
  "${GITLAB_URL}/api/v4/projects/${GITLAB_PROJECT_ID}/merge_requests"

# Check Jenkins: sampleapi-mr should trigger
echo "Check: ${JENKINS_URL}/job/sampleapi-mr/"

# Expected stages: Checkout > Build > Test > SonarQube > Dep-Check > Report
# Expected result: SUCCESS (or UNSTABLE if coverage < 80%)
# Expected: GitLab MR shows pipeline status
```

### Test 12B: T2 — Merge to Main Pipeline

```bash
# Merge the MR (or push directly)
git checkout main
git merge feature/e2e-test
git push origin main

# Check Jenkins: sampleapi-merge should trigger
echo "Check: ${JENKINS_URL}/job/sampleapi-merge/"

# Expected stages: Checkout > Build > Test > SAST > SCA > Image Build >
#                  Push > ACS Scan > GitOps Update > Deploy DEV
# Expected result: SUCCESS
# Expected: Image in internal registry, DEV deployed

# Verify DEV deployment
curl -sk "https://sampleapi-sampleapi-dev.${CLUSTER_DOMAIN}/healthz"
# Expected: Healthy

curl -sk "https://sampleapi-sampleapi-dev.${CLUSTER_DOMAIN}/api/WeatherForecast" | head -c 200
# Expected: JSON weather data

curl -sk "https://sampleapi-sampleapi-dev.${CLUSTER_DOMAIN}/api/info"
# Expected: App info with version
```

### Test 12C: T3 — Tag Release Pipeline

```bash
# Create and push a tag
git tag v1.0.0-e2e
git push origin v1.0.0-e2e

# Check Jenkins: sampleapi-tag should trigger
echo "Check: ${JENKINS_URL}/job/sampleapi-tag/"

# Expected stages: Checkout > Build > Test > SAST > SCA > Image Build >
#                  Push > ACS Strict > DAST > Sign
# Expected result: SUCCESS or UNSTABLE (cosign key may be missing)
# CRITICAL: No deployment should happen (image only)

# Verify image exists
oc get is sampleapi -n sampleapi-dev -o jsonpath='{.status.tags[*].tag}' | tr ' ' '\n'
# Expected: includes "v1.0.0-e2e"
```

### Test 12D: Environment Promotion (GitOps)

```bash
# Update SIT overlay to use the tagged image
cd app-gitops/
# Edit overlays/sit/kustomization.yaml — change image tag to v1.0.0-e2e
# Commit and push
git add -A && git commit -m "chore(sit): promote v1.0.0-e2e to SIT"
git push origin main

# In ArgoCD UI, manually sync sampleapi-sit
echo "ArgoCD UI: ${ARGOCD_URL}"
# Or via CLI:
# argocd app sync sampleapi-sit --server ${ARGOCD_URL}
```

### Test 12E: Security Gate Enforcement

```bash
# Test SonarQube gate: introduce a vulnerability
cd app-source/
git checkout -b feature/vuln-test
# Add code with a SQL injection vulnerability (intentional)
# Push and create MR
# Expected: Pipeline should report quality gate FAILED on the MR

# Test ACS gate: try deploying a root container
# Modify deployment to use USER 0
# Expected: ACS should block or scale-to-zero
```

### Test 12F: Rollback

```bash
# In ArgoCD, select sampleapi-dev
# Click "History and Rollback"
# Select a previous revision
# Click "Rollback"
# Verify the previous version is running

# Or via CLI:
# argocd app rollback sampleapi-dev <revision-number>
```

---

## Validation Summary Checklist

| # | Validation | Command | Expected |
|---|-----------|---------|----------|
| 1 | Namespaces exist | `oc get ns -l team=devsecops` | 6 namespaces |
| 2 | GitLab accessible | `curl -sk ${GITLAB_URL}/users/sign_in` | HTTP 200 |
| 3 | 4 repos in GitLab | GitLab UI or API | app-source, build-config, jenkins-shared-lib, app-gitops |
| 4 | SonarQube accessible | `curl -sk ${SONARQUBE_URL}/api/system/status` | HTTP 200, status UP |
| 5 | Quality gate configured | SonarQube API | DevSecOps-Gate (default) |
| 6 | ACS Central accessible | `curl -sk ${ACS_CENTRAL_URL}/v1/ping` | HTTP 200 |
| 7 | ACS policies imported | ACS UI or API | 6 custom policies |
| 8 | ArgoCD accessible | `curl -sk ${ARGOCD_URL}/healthz` | HTTP 200 |
| 9 | ArgoCD apps created | ArgoCD UI | 4 apps (dev, sit, uat, prod) |
| 10 | Jenkins accessible | `curl -sk ${JENKINS_URL}/login` | HTTP 200 |
| 11 | 3 Jenkins jobs exist | Jenkins UI | sampleapi-mr, sampleapi-merge, sampleapi-tag |
| 12 | Agent image built | `oc get is jenkins-agent-devsecops -n devsecops-tools` | Image exists |
| 13 | 3 webhooks configured | GitLab API | MR, push, tag webhooks |
| 14 | T1 pipeline works | Trigger MR | Build SUCCESS |
| 15 | T2 pipeline works | Push to main | Build SUCCESS + DEV deployed |
| 16 | T3 pipeline works | Push tag | Build SUCCESS/UNSTABLE + image only |
| 17 | DEV app healthy | `curl -sk .../healthz` | Healthy |
| 18 | ArgoCD DEV synced | ArgoCD UI | Synced + Healthy |
| 19 | Monitoring active | OCP Console > Observe | Targets visible |
| 20 | Alerts defined | `oc get prometheusrule` | sampleapi-alerts in app namespaces |

---

## Credential Reference

| Secret | Namespace | Contains | Used By | How to Obtain |
|--------|-----------|----------|---------|---------------|
| `gitlab-secrets` | devsecops-gitlab | DB creds, root pwd, encryption keys | GitLab pods | Set in phase2 manifests |
| `gitlab-token` | devsecops-tools | GitLab username + PAT | Jenkins (git clone) | Generated in GitLab UI |
| `gitlab-api-token` | devsecops-tools | GitLab PAT (string) | Jenkins (MR status) | Same PAT as above |
| `sonarqube-token` | devsecops-tools | SonarQube API token | Jenkins (SAST) | Generated in SonarQube UI |
| `acs-token` | devsecops-tools | ACS API token | Jenkins (roxctl) | Generated in ACS UI |
| `argocd-token` | devsecops-tools | ArgoCD admin password | Jenkins (argocd CLI) | From OCP Secret in openshift-gitops |
| `cosign-signing-key` | devsecops-tools | Cosign private key file | Jenkins (image sign) | Generated via `cosign generate-key-pair` |
| `sampleapi-secret` | sampleapi-{env} | DB pwd, API key, JWT secret | App pods | Per-env in GitOps overlays |

---

## Troubleshooting Quick Reference

| Symptom | Likely Cause | Fix |
|---------|-------------|-----|
| Webhook returns 403 | Anonymous permissions | Grant Overall/Read, Job/Read, Job/Build to anonymous |
| Webhook returns 404 | Job doesn't exist | Check JCasC reload, recreate via Script Console |
| Pipeline can't clone | GitLab credential wrong | Recreate `gitlab-token` via Script Console |
| SonarQube gate fails | Quality gate not set as default | Run `apply-sonarqube-gate.sh` |
| Image push fails | Jenkins SA missing image-builder | Reapply `infra/phase4/registry-sa-rolebinding.yaml` |
| ArgoCD can't sync | Repo secret wrong | Update `argocd-repo-secret.yaml` with correct token |
| ArgoCD sync stuck | Namespace missing labels | `oc label ns sampleapi-dev argocd.argoproj.io/managed-by=openshift-gitops` |
| ACS scan shows 0 findings | Registry auth not configured | Configure image integration in ACS Central |
| Agent pod OOMKilled | Memory limit too low | Increase in JCasC pod template or agent-pod-template.yaml |
| Podman build fails | Storage driver not vfs | Ensure STORAGE_DRIVER=vfs env var and --storage-driver=vfs flag |
| Tag pipeline no trigger | Webhook missing tag_push_events | Recreate webhook with tag_push_events=true |
| JCasC credentials empty | Circular resolution bug | Use Script Console for credentials, not JCasC |
| GitLab CipherError on restart | Missing encryption keys | Ensure db_key_base, secret_key_base, otp_key_base are set |

---

## Architecture Summary

```
┌─────────────────────────────────────────────────────────────────┐
│                     Developer Workstation                        │
│  pre-commit (gitleaks) → git push → GitLab (app-source)        │
└──────────────────────────────┬──────────────────────────────────┘
                               │ webhook
                               ▼
┌─────────────────────────────────────────────────────────────────┐
│                     Jenkins (devsecops-tools)                    │
│  ┌─────────────┐  ┌──────────────┐  ┌───────────────────────┐  │
│  │ sampleapi-mr│  │sampleapi-    │  │ sampleapi-tag         │  │
│  │ (T1: MR)    │  │merge (T2)    │  │ (T3: Tag)             │  │
│  └──────┬──────┘  └──────┬───────┘  └───────────┬───────────┘  │
│         │                │                      │               │
│         └────────────────┼──────────────────────┘               │
│                          │                                      │
│         @Library('devsecops-shared-lib@main') _                 │
│         pipelineMR() / pipelineMerge() / pipelineTag()          │
│                          │                                      │
│  ┌───────────────────────┼──────────────────────────────┐       │
│  │     Shared Library    │   (jenkins-shared-lib repo)  │       │
│  │  checkoutSource ──────┤                              │       │
│  │  checkoutBuildConfig ─┤   ← clones build-config repo│       │
│  │  buildDotnet ─────────┤                              │       │
│  │  runUnitTests ────────┤                              │       │
│  │  scanSonarQube ───────┼──► SonarQube (SAST)          │       │
│  │  scanDependencyCheck ─┤   (OWASP SCA)                │       │
│  │  buildContainerImage ─┤   (Podman)                   │       │
│  │  pushToRegistry ──────┼──► OCP Internal Registry     │       │
│  │  scanACSImage ────────┼──► ACS Central (roxctl)      │       │
│  │  scanOWASPZAP ────────┤   (DAST, T3 only)           │       │
│  │  signImage ───────────┤   (Cosign, optional)         │       │
│  │  updateGitOps ────────┼──► GitLab (app-gitops repo)  │       │
│  │  deployToEnvironment ─┼──► ArgoCD (sync DEV)         │       │
│  │  reportToGitLab ──────┼──► GitLab (MR status)        │       │
│  └───────────────────────┴──────────────────────────────┘       │
└─────────────────────────────────────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────┐
│                     ArgoCD (openshift-gitops)                    │
│  app-gitops/overlays/dev/ ──auto-sync──► sampleapi-dev          │
│  app-gitops/overlays/sit/ ──manual────► sampleapi-sit           │
│  app-gitops/overlays/uat/ ──manual────► sampleapi-uat           │
│  app-gitops/overlays/production/ ─────► sampleapi-prod          │
│                                                                  │
│  Promotion: MR to update overlay → approve → manual sync        │
└─────────────────────────────────────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────┐
│                     OpenShift Cluster                            │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────────────┐   │
│  │sampleapi │ │sampleapi │ │sampleapi │ │sampleapi         │   │
│  │-dev      │ │-sit      │ │-uat      │ │-prod             │   │
│  │1 replica │ │2 replicas│ │2 replicas│ │3 replicas + PDB  │   │
│  │Debug log │ │Info log  │ │Info log  │ │Warning log       │   │
│  │Swagger on│ │Swagger on│ │Swagger of│ │Swagger off       │   │
│  └──────────┘ └──────────┘ └──────────┘ └──────────────────┘   │
│                                                                  │
│  Observability: Prometheus → Grafana, Vector → Loki, ACS alerts │
└─────────────────────────────────────────────────────────────────┘
```
