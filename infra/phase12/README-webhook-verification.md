# Phase 12: Jenkins Job Definitions & Webhook Wiring

## Overview

This phase defines the 7 Jenkins pipeline jobs and GitLab webhook configuration
that connect Git events to CI/CD pipelines. No Jenkinsfiles exist in any
repository — all pipeline logic lives in the Jenkins shared library (Rule 1 + Rule 6).

## Architecture

```
GitLab (app-source repo, ID=1)
  ├── MR opened/updated ──► POST /project/sampleapi-mr    ──► pipelineMR()
  ├── Push to main ────────► POST /project/sampleapi-merge ──► pipelineMerge()
  └── Tag pushed ──────────► POST /project/sampleapi-tag   ──► pipelineTag()

GitLab (notificationapi-source repo, ID=5)
  ├── MR opened/updated ──► POST /project/notificationapi-mr    ──► pipelineMR(service:'notificationapi')
  ├── Push to main ────────► POST /project/notificationapi-merge ──► pipelineMerge(service:'notificationapi')
  └── Tag pushed ──────────► POST /project/notificationapi-tag   ──► pipelineTag(service:'notificationapi')

GitLab (app-gitops repo, ID=4)
  └── Push to main ────────► POST /project/sampleapi-promote ──► pipelinePromote()
                                      │
                                      ▼
                              Jenkins (JCasC jobs)
                                      │
                                      ▼
                         jenkins-shared-lib/vars/
                         (orchestrator functions)
```

## Files

| File | Purpose |
|------|---------|
| `jenkins-casc-jobs.yaml` | ConfigMap with Job DSL scripts defining 7 pipeline jobs |
| `setup-webhooks.sh` | Script to create 7 GitLab webhooks via API |
| `README-webhook-verification.md` | This file — verification procedures |

## Job Definitions

| Job Name | Source Repo (ID) | Trigger | Shared Lib Function | What It Does |
|----------|------------------|---------|---------------------|--------------|
| `sampleapi-mr` | app-source (1) | GitLab MR event | `pipelineMR()` | Validate feature branch: test, SAST, SCA |
| `sampleapi-merge` | app-source (1) | Push to `main` | `pipelineMerge()` | Build image, scan, push, deploy DEV |
| `sampleapi-tag` | app-source (1) | Tag push | `pipelineTag()` | Full validation + DAST + perf test, release image, NO deploy |
| `notificationapi-mr` | notificationapi-source (5) | GitLab MR event | `pipelineMR(service: 'notificationapi')` | Validate feature branch: test, SAST, SCA |
| `notificationapi-merge` | notificationapi-source (5) | Push to `main` | `pipelineMerge(service: 'notificationapi')` | Build image, scan, push, deploy DEV |
| `notificationapi-tag` | notificationapi-source (5) | Tag push | `pipelineTag(service: 'notificationapi')` | Full validation + DAST + perf test, release image, NO deploy |
| `sampleapi-promote` | app-gitops (4) | Push to `main` | `pipelinePromote()` | Detect service+env change, sync ArgoCD, create next promotion MR |

## Webhook Setup

### Prerequisites
1. GitLab is running with admin access (5 repos created)
2. Jenkins is running with all 7 jobs created
3. Jenkins anonymous access grants: `Overall/Read`, `Job/Read`, `Job/Build`

### Running the Setup Script

```bash
# Set required environment variables
export GITLAB_URL="https://gitlab-devsecops-gitlab.apps.cluster-pmqwq.pmqwq.sandbox270.opentlc.com"
export GITLAB_TOKEN="glpat-..."  # GitLab personal access token with 'api' scope
export JENKINS_URL="https://jenkins-devsecops-tools.apps.cluster-pmqwq.pmqwq.sandbox270.opentlc.com"
export GITLAB_PROJECT_APP_SOURCE=1         # app-source project ID
export GITLAB_PROJECT_NOTIFICATION_SOURCE=5  # notificationapi-source project ID
export GITLAB_PROJECT_APP_GITOPS=4         # app-gitops project ID

# Optional: clean up existing webhooks first
export CLEANUP_EXISTING=true

# Run the script (creates 7 webhooks across 3 projects)
bash infra/phase12/setup-webhooks.sh
```

### Verifying Webhooks Exist

```bash
# List webhooks on app-source (3 webhooks)
curl -sk -H "PRIVATE-TOKEN: ${GITLAB_TOKEN}" \
  "${GITLAB_URL}/api/v4/projects/1/hooks" | python3 -m json.tool

# List webhooks on notificationapi-source (3 webhooks)
curl -sk -H "PRIVATE-TOKEN: ${GITLAB_TOKEN}" \
  "${GITLAB_URL}/api/v4/projects/5/hooks" | python3 -m json.tool

# List webhooks on app-gitops (1 webhook)
curl -sk -H "PRIVATE-TOKEN: ${GITLAB_TOKEN}" \
  "${GITLAB_URL}/api/v4/projects/4/hooks" | python3 -m json.tool
```

Expected output: 7 webhooks total across 3 projects:
- app-source (ID=1): `sampleapi-mr`, `sampleapi-merge`, `sampleapi-tag`
- notificationapi-source (ID=5): `notificationapi-mr`, `notificationapi-merge`, `notificationapi-tag`
- app-gitops (ID=4): `sampleapi-promote`

## Verification Procedures

### Test T1: MR Validation Pipeline

```bash
# 1. Create a feature branch and push a change
cd app-source/
git checkout -b feature/test-webhook
echo "// test" >> src/SampleApi/Program.cs
git add -A && git commit -m "test: webhook trigger"
git push origin feature/test-webhook

# 2. Create a Merge Request via GitLab API
curl -sk -X POST \
  -H "PRIVATE-TOKEN: ${GITLAB_TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{
    "source_branch": "feature/test-webhook",
    "target_branch": "main",
    "title": "test: verify MR webhook"
  }' \
  "${GITLAB_URL}/api/v4/projects/${GITLAB_PROJECT_ID}/merge_requests"

# 3. Check Jenkins — sampleapi-mr should have a new build
# Expected stages: Checkout → Build → Test → SonarQube → Dependency-Check → Report
```

### Test T2: Merge to Main Pipeline

```bash
# 1. Merge the MR (or push directly to main)
git checkout main
git merge feature/test-webhook
git push origin main

# 2. Check Jenkins — sampleapi-merge should have a new build
# Expected stages: Checkout → Build → Test → SAST → SCA → Image Build
#                  → Push → ACS Scan → GitOps Update → Deploy DEV

# 3. Verify DEV deployment
curl -sk https://sampleapi-dev.apps.cluster-pmqwq.pmqwq.sandbox270.opentlc.com/healthz
```

### Test T3: Tag Release Pipeline

```bash
# 1. Create and push a version tag
git tag v1.0.0-test
git push origin v1.0.0-test

# 2. Check Jenkins — sampleapi-tag should have a new build
# Expected stages: Checkout → Build → Test → SAST → SCA → Image Build
#                  → Push → ACS Strict → DAST → Sign → NO Deploy

# 3. Verify image exists in registry (but NOT deployed to any environment)
oc get is sampleapi -n sampleapi-dev -o jsonpath='{.status.tags[*].tag}'
```

## Troubleshooting

### Webhook Returns 403

Jenkins anonymous access is not configured. Verify:

```bash
# Check Jenkins JCasC authorization
# Must include:
#   "Overall/Read:anonymous"
#   "Job/Read:anonymous"
#   "Job/Build:anonymous"
```

Fix: Apply the JCasC ConfigMap from Phase 7 or add permissions via
Jenkins > Manage Jenkins > Security > Matrix Authorization.

### Webhook Returns 404

The Jenkins job does not exist. Verify:

```bash
# Check job exists
curl -sk -u admin:${JENKINS_API_TOKEN} \
  "${JENKINS_URL}/job/sampleapi-merge/api/json" | python3 -m json.tool
```

Fix: Trigger JCasC reload or manually create the job via Script Console.

### Webhook Fires But No Build Starts

Check the GitLab plugin trigger configuration:
- T1 (MR): `buildOnMergeRequestEvents(true)` must be set
- T2 (Merge): `buildOnPushEvents(true)` + `includeBranches('main')` must be set
- T3 (Tag): Uses SCM refspec `+refs/tags/*:refs/remotes/origin/tags/*`

Also check Jenkins logs:
```bash
oc logs deployment/jenkins -n devsecops-tools | grep -i "webhook\|trigger\|gitlab"
```

### Tag Pipeline Doesn't Detect Tags

The tag pipeline uses `branches('*/tags/*')` with refspec
`+refs/tags/*:refs/remotes/origin/tags/*`. If tags aren't detected:

1. Verify the webhook sends `tag_push_events` (not `push_events`)
2. Check that the refspec is correct in the job SCM config
3. The pipeline derives TAG_NAME from `env.GIT_BRANCH`:
   ```groovy
   TAG_NAME = env.GIT_BRANCH.replaceAll('.*/tags/', '').replaceAll('.*/', '')
   ```

### Duplicate Builds

If push to main triggers both `sampleapi-merge` and `sampleapi-mr`:
- Verify `sampleapi-mr` has `buildOnPushEvents(false)`
- Verify `sampleapi-merge` has `buildOnMergeRequestEvents(false)`

## Jenkins Anonymous Access Verification

```bash
# Test that anonymous can trigger a build
curl -sk -X POST "${JENKINS_URL}/project/sampleapi-merge"
# Should return 200 (even if no SCM change detected)

# If it returns 403, anonymous permissions are missing
```

## Integration Points

```
GitLab (app-source, ID=1) ──webhook──► Jenkins (/project/sampleapi-*)
GitLab (notificationapi-source, ID=5) ──webhook──► Jenkins (/project/notificationapi-*)
GitLab (app-gitops, ID=4) ──webhook──► Jenkins (/project/sampleapi-promote)
Jenkins ──shared-lib──► GitLab (jenkins-shared-lib, ID=3)
Jenkins ──clone──► GitLab (build-config, ID=2)
Jenkins ──status──► GitLab (app-source, ID=1 / notificationapi-source, ID=5) via API
Jenkins ──gitops──► GitLab (app-gitops, ID=4)
```
