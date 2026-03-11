# Module 9B: Multi-Service Architecture

| | |
|---|---|
| **Track** | Integration |
| **Duration** | ~75 minutes |
| **Difficulty** | Intermediate |
| **Prerequisites** | Module 8 (3-Trigger Pipeline working for SampleApi), Module 9 (Per-Environment Configuration concepts) |

---

## What You'll Learn

By the end of this module you will be able to:

1. Explain why a realistic DevSecOps demo needs more than one service (distributed tracing, inter-service security, cascading failures).
2. Deploy PostgreSQL and Redis as StatefulSets managed by ArgoCD (infrastructure layer).
3. Deploy a second microservice (NotificationApi) that SampleApi calls.
4. Add service-parameterized Jenkins pipelines — each service has its own jobs and webhooks.
5. Demonstrate per-service GitOps isolation: update one service's image tag without touching the other.
6. Verify inter-service communication: SampleApi talks to NotificationApi, queries PostgreSQL, and checks Redis.

---

## Prerequisites

> **Environment variables:** Source the environment file before running any commands:
> ```bash
> source ./env.sh
> ```

Confirm the following:

```bash
# Module 8 complete — SampleApi pipeline works
$OC get pods -n $NS_DEV -l app=sampleapi
# Expected: 1 pod Running

# ArgoCD has at least the sampleapi-dev app
$OC get applications -n $NS_GITOPS -o custom-columns='NAME:.metadata.name' | grep sampleapi
# Expected: sampleapi-dev (and possibly sit/uat/prod)

# GitLab has 5 repos
GITLAB_TOKEN=$($OC get secret gitlab-token -n $NS_TOOLS -o jsonpath='{.data.token}' | base64 -d)
curl -sk -H "PRIVATE-TOKEN: $GITLAB_TOKEN" \
  "${GITLAB_URL}/api/v4/groups/devsecops/projects" | jq -r '.[].path'
# Expected: app-source, build-config, jenkins-shared-lib, app-gitops, notificationapi-source
```

---

## 1. Concepts: Why Multiple Services? (10 min)

### The Problem with a Single Service Demo

With just one service, your DevSecOps platform looks like a toy. You cannot demonstrate:

- **Distributed tracing** — a trace with only one span is not a distributed trace
- **Inter-service NetworkPolicies** — with one service, there is no "who can talk to whom"
- **Cascading failures** — if NotificationApi goes down, does SampleApi handle it gracefully?
- **Independent deployment** — promoting SampleApi to SIT should not require redeploying NotificationApi
- **Per-service pipelines** — how do you parameterize one pipeline to build different services?

### Architecture After This Module

```
┌─────────────────────────────────────────────────────────────────────┐
│                     sampleapi-{env} namespace                       │
│                                                                     │
│   ┌──────────────┐     ┌───────────────────┐                       │
│   │  SampleApi   │────→│  NotificationApi  │                       │
│   │  (.NET 8)    │     │  (.NET 8)         │                       │
│   │  Port 8080   │     │  Port 8081        │                       │
│   └──────┬───────┘     └────────┬──────────┘                       │
│          │                      │                                   │
│   ┌──────▼───────┐     ┌───────▼───────────┐                      │
│   │ PostgreSQL   │     │     Redis          │                      │
│   │ (StatefulSet)│     │  (StatefulSet)     │                      │
│   │ Port 5432    │     │  Port 6379         │                      │
│   └──────────────┘     └───────────────────┘                       │
└─────────────────────────────────────────────────────────────────────┘
```

| Service | Purpose | Managed By |
|---------|---------|------------|
| **SampleApi** | Main API — weather forecasts, health checks, calls NotificationApi | `sampleapi-{env}` ArgoCD app |
| **NotificationApi** | Internal microservice — receives notifications from SampleApi | `notificationapi-{env}` ArgoCD app |
| **PostgreSQL** | Persistent data store for SampleApi | `infra-{env}` ArgoCD app |
| **Redis** | Caching + pub/sub for both services | `infra-{env}` ArgoCD app |

### Three ArgoCD Apps per Environment

Instead of one monolithic ArgoCD Application per environment, we use three:

```
sampleapi-dev     → syncs services/sampleapi/overlays/dev/
notificationapi-dev → syncs services/notificationapi/overlays/dev/
infra-dev          → syncs infra/overlays/dev/
```

This means:
- Updating SampleApi's image tag triggers only the `sampleapi-dev` sync — NotificationApi is unaffected
- The infra ArgoCD app manages PostgreSQL and Redis independently from application services
- Each app has its own sync status, health, and rollback history

---

## 2. Examine the GitOps Repository Structure (10 min)

The `app-gitops` repository has been restructured for per-service isolation:

```
app-gitops/
├── services/                        # Per-service deployment manifests
│   ├── sampleapi/
│   │   ├── base/                    # Shared across all environments
│   │   │   ├── kustomization.yaml
│   │   │   ├── deployment.yaml      # envFrom: sampleapi-env + sampleapi-secret
│   │   │   ├── service.yaml
│   │   │   └── route.yaml           # External access (has a Route)
│   │   └── overlays/
│   │       ├── dev/                 # 1 replica, DEV-specific config
│   │       │   ├── kustomization.yaml     # ← ONE image tag here
│   │       │   ├── configmap-env.yaml     # ← sampleapi-env ConfigMap
│   │       │   ├── secret-env.yaml        # ← sampleapi-secret
│   │       │   └── patch-deployment.yaml
│   │       ├── sit/                 # 2 replicas
│   │       ├── uat/                 # 2 replicas
│   │       └── production/          # 3 replicas + PDB
│   └── notificationapi/
│       ├── base/                    # NO route (internal service only)
│       │   ├── kustomization.yaml
│       │   ├── deployment.yaml      # envFrom: notificationapi-env + notificationapi-secret
│       │   └── service.yaml
│       └── overlays/
│           ├── dev/                 # 1 replica, own ConfigMap + Secret
│           ├── sit/
│           ├── uat/
│           └── production/          # 2 replicas + PDB
├── infra/                           # Shared infrastructure
│   ├── base/
│   │   ├── kustomization.yaml
│   │   ├── serviceaccount.yaml
│   │   ├── postgresql/             # StatefulSet + Service
│   │   └── redis/                  # StatefulSet + Service
│   └── overlays/
│       ├── dev/                     # infra-secret only (PG + Redis creds)
│       ├── sit/
│       ├── uat/
│       └── production/              # infra-secret + PG resource patch
├── argocd/                          # 12 ArgoCD Application CRDs
│   ├── project.yaml
│   ├── sampleapi-{dev,sit,uat,prod}.yaml      # 4 apps
│   ├── notificationapi-{dev,sit,uat,prod}.yaml # 4 apps
│   └── infra-{dev,sit,uat,prod}.yaml           # 4 apps
└── README.md
```

### Verify the Structure Locally

```bash
# Check that all 12 ArgoCD app CRDs exist
ls app-gitops/argocd/
```

Expected:
```
infra-dev.yaml    infra-sit.yaml    infra-uat.yaml    infra-prod.yaml
notificationapi-dev.yaml  notificationapi-sit.yaml  notificationapi-uat.yaml  notificationapi-prod.yaml
project.yaml
sampleapi-dev.yaml  sampleapi-sit.yaml  sampleapi-uat.yaml  sampleapi-prod.yaml
```

```bash
# Check per-service overlay structure
ls app-gitops/services/sampleapi/overlays/dev/
ls app-gitops/services/notificationapi/overlays/dev/
```

Expected (for each):
```
configmap-env.yaml  kustomization.yaml  patch-deployment.yaml  secret-env.yaml
```

---

## 3. Deploy the 12 ArgoCD Applications (15 min)

### Step 1: Apply the ArgoCD AppProject and All Applications

```bash
# Apply the AppProject first (defines which repos and namespaces ArgoCD can manage)
$OC apply -f app-gitops/argocd/project.yaml

# Apply all 12 ArgoCD Applications
$OC apply -f app-gitops/argocd/
```

### Step 2: Verify All Applications Exist

```bash
$OC get applications -n $NS_GITOPS \
  -o custom-columns='NAME:.metadata.name,SYNC:.status.sync.status,HEALTH:.status.health.status'
```

Expected:
```
NAME                   SYNC     HEALTH
infra-dev              Synced   Healthy
infra-prod             Synced   Healthy
infra-sit              Synced   Healthy
infra-uat              Synced   Healthy
notificationapi-dev    Synced   Healthy
notificationapi-prod   Synced   Healthy
notificationapi-sit    Synced   Healthy
notificationapi-uat    Synced   Healthy
sampleapi-dev          Synced   Healthy
sampleapi-prod         Synced   Healthy
sampleapi-sit          Synced   Healthy
sampleapi-uat          Synced   Healthy
```

> **DEV apps auto-sync.** The DEV applications have `syncPolicy: automated` — when you push changes to the GitOps repo, ArgoCD applies them automatically. SIT, UAT, and PROD require manual sync or a merge request + T4 pipeline.

### Step 3: Check What Got Deployed in DEV

```bash
$OC get pods -n $NS_DEV
```

Expected:
```
NAME                               READY   STATUS    RESTARTS   AGE
notificationapi-<hash>             1/1     Running   0          ...
postgresql-0                       1/1     Running   0          ...
redis-0                            1/1     Running   0          ...
sampleapi-<hash>                   1/1     Running   0          ...
```

Four pods: two app services + two infrastructure services. All managed by their respective ArgoCD apps.

---

## 4. Verify Inter-Service Communication (10 min)

### Step 1: Check SampleApi Health

```bash
curl -sk ${APP_DEV_URL}/healthz | jq .
```

Expected:
```json
{
  "status": "healthy",
  "timestamp": "2026-03-10T..."
}
```

### Step 2: Check SampleApi Readiness (DB + Redis)

```bash
curl -sk ${APP_DEV_URL}/readyz | jq .
```

Expected: A 200 response showing PostgreSQL and Redis connectivity status.

### Step 3: Test the WeatherForecast API

```bash
curl -sk ${APP_DEV_URL}/api/WeatherForecast | jq '.[0]'
```

Expected: A JSON forecast with `location` set to the DEV environment value from the ConfigMap.

### Step 4: Check NotificationApi Health (Internal Only)

NotificationApi has no Route (it's an internal service). Check it from inside the cluster:

```bash
$OC exec -n $NS_DEV deploy/sampleapi -- \
  curl -s http://notificationapi.${NS_DEV}.svc:8081/healthz
```

Expected:
```json
{"status":"healthy",...}
```

---

## 5. NetworkPolicies for Inter-Service Security (10 min)

With multiple services, you need to control which pods can talk to which. The policies are in `infra/phase17/networkpolicies/`:

```bash
$OC get netpol -n $NS_DEV
```

Expected:
```
NAME                              POD-SELECTOR                                   AGE
allow-app-to-notificationapi     app=notificationapi                            ...
allow-app-to-postgresql           app=postgresql                                 ...
allow-app-to-redis                app=redis                                      ...
allow-from-argocd                 <none>                                         ...
allow-from-openshift-ingress      app=sampleapi                                  ...
allow-monitoring                  app=sampleapi                                  ...
allow-monitoring-newservices      app in (notificationapi,postgresql,redis)       ...
allow-router-to-notificationapi   app=notificationapi                            ...
allow-same-namespace              <none>                                         ...
default-deny-ingress              <none>                                         ...
deny-postgresql-egress            app=postgresql                                 ...
deny-redis-egress                 app=redis                                      ...
```

### Key Policies Explained

| Policy | What It Does | Why |
|--------|-------------|-----|
| `default-deny-ingress` | Blocks ALL incoming traffic by default | Zero-trust baseline |
| `allow-app-to-postgresql` | SampleApi (port 5432) → PostgreSQL | Only the app that needs the DB can reach it |
| `allow-app-to-redis` | SampleApi + NotificationApi → Redis (port 6379) | Both services use the cache |
| `allow-app-to-notificationapi` | SampleApi → NotificationApi (port 8081) | Inter-service call |
| `deny-postgresql-egress` | PostgreSQL has NO egress | Database should never initiate outbound connections |
| `deny-redis-egress` | Redis has NO egress | Cache should never initiate outbound connections |

> **Security principle:** PostgreSQL and Redis have zero egress. If an attacker compromises the database container, they cannot reach the internet, cannot call other services, and cannot exfiltrate data. The only connections are inbound from app pods.

---

## 6. Service-Parameterized Pipelines (15 min)

### The Design

Instead of building one mega-pipeline that handles all services, each service has its own Jenkins jobs:

| Job Name | Service | Trigger | Calls |
|----------|---------|---------|-------|
| `sampleapi-mr` | SampleApi | MR webhook (project 1) | `pipelineMR()` |
| `sampleapi-merge` | SampleApi | Push to main (project 1) | `pipelineMerge()` |
| `sampleapi-tag` | SampleApi | Tag push (project 1) | `pipelineTag()` |
| `notificationapi-mr` | NotificationApi | MR webhook (project 5) | `pipelineMR(service: 'notificationapi')` |
| `notificationapi-merge` | NotificationApi | Push to main (project 5) | `pipelineMerge(service: 'notificationapi')` |
| `notificationapi-tag` | NotificationApi | Tag push (project 5) | `pipelineTag(service: 'notificationapi')` |
| `sampleapi-promote` | Shared | Push to app-gitops main (project 4) | `pipelinePromote()` |

The same shared library orchestrators (`pipelineMR`, `pipelineMerge`, `pipelineTag`) serve all services. The `service` parameter tells `PipelineConfig.configureForService()` which repo to clone, which image to build, and which GitOps path to update.

SampleApi jobs omit `service:` (defaults to `sampleapi`). NotificationApi jobs pass `service: 'notificationapi'`.

### How It Works Internally

```groovy
// In jenkins-shared-lib/src/com/devsecops/PipelineConfig.groovy
@NonCPS
void configureForService(String serviceName) {
    this.activeServiceName = serviceName
    switch (serviceName) {
        case 'notificationapi':
            this.activeImageName = 'notificationapi'
            this.activeSourceRepo = "${this.gitlabUrl}/devsecops/notificationapi-source.git"
            this.activeBuildArgs = [PROJECT_NAME: 'NotificationApi',
                                   SOLUTION_NAME: 'NotificationApi',
                                   APP_PORT: '8081']
            this.sonarProjectKey = 'notificationapi'
            this.gitlabProjectId = '5'
            break
        default: // sampleapi
            this.activeImageName = this.imageName
            this.activeSourceRepo = this.appSourceRepo
            this.activeBuildArgs = [:]       // <-- empty: Dockerfile defaults handle SampleApi
            this.sonarProjectKey = this.appName
            break
    }
}
```

> **Why `activeBuildArgs = [:]` for SampleApi?** The Dockerfile's `ARG PROJECT_NAME=SampleApi` provides defaults. SampleApi is the "default service" — it doesn't need explicit build args. NotificationApi overrides the defaults with its own values. This means the Dockerfile works for both services with a single `--build-arg` mechanism.

### Verify Jenkins Has All 7 Jobs

Open Jenkins at `${JENKINS_URL}` and confirm you see these jobs on the dashboard:

```bash
echo "Jenkins URL: ${JENKINS_URL}"
```

You should see: `sampleapi-mr`, `sampleapi-merge`, `sampleapi-tag`, `notificationapi-mr`, `notificationapi-merge`, `notificationapi-tag`, `sampleapi-promote`.

### Verify Webhooks

```bash
GITLAB_TOKEN=$($OC get secret gitlab-token -n $NS_TOOLS -o jsonpath='{.data.token}' | base64 -d)

echo "=== app-source webhooks (project 1) ==="
curl -sk -H "PRIVATE-TOKEN: $GITLAB_TOKEN" \
  "${GITLAB_URL}/api/v4/projects/1/hooks" | jq '.[].url'

echo "=== notificationapi-source webhooks (project 5) ==="
curl -sk -H "PRIVATE-TOKEN: $GITLAB_TOKEN" \
  "${GITLAB_URL}/api/v4/projects/5/hooks" | jq '.[].url'

echo "=== app-gitops webhooks (project 4) ==="
curl -sk -H "PRIVATE-TOKEN: $GITLAB_TOKEN" \
  "${GITLAB_URL}/api/v4/projects/4/hooks" | jq '.[].url'
```

Expected: 3 webhooks on project 1, 3 on project 5, 1 on project 4 — each pointing to the correct Jenkins job.

---

## 7. Test Concurrent Pipelines (5 min)

The ultimate test of per-service isolation: trigger both services' merge pipelines simultaneously.

If both services merge to main at the same time, both T2 pipelines try to push to the `app-gitops` repo. Without protection, one push gets rejected (`non-fast-forward`). The shared library handles this with a `git pull --rebase` retry loop in `updateGitOps.groovy` (3 attempts).

You don't need to test this manually right now — Module 10 walks through the full E2E flow including concurrent testing. But it's important to know that this was a real bug that was found and fixed during implementation.

---

## What Just Happened?

Let's connect the pieces:

1. **12 ArgoCD Applications** — each service has 4 apps (one per environment), and infrastructure has 4 apps. Total: 12. Each syncs independently.

2. **Per-service isolation** — SampleApi's image tag is in `services/sampleapi/overlays/dev/kustomization.yaml`. NotificationApi's is in `services/notificationapi/overlays/dev/kustomization.yaml`. Updating one does not cause a Git conflict with the other.

3. **Infrastructure as a separate layer** — PostgreSQL and Redis are managed by the `infra-{env}` ArgoCD app, with their own secrets (`infra-secret`). Application services reference the database via DNS (`postgresql.sampleapi-dev.svc`) and inject their own app-level credentials from their own secrets.

4. **7 Jenkins jobs** — 3 per service (MR, merge, tag) + 1 shared promote job. The shared library orchestrators accept a `service` parameter that configures everything.

5. **NetworkPolicies** — zero-trust with explicit allow rules. Database and cache pods have zero egress.

---

## Common Mistakes

| Mistake | Symptom | Fix |
|---------|---------|-----|
| Forgot to create SonarQube project for NotificationApi | SAST scan fails with "project not found" | Create project `notificationapi` in SonarQube with the DevSecOps quality gate |
| Redis readiness probe wrong | Pod `CrashLoopBackOff` with `check-container: not found` | RHEL Redis image has no `/usr/libexec/check-container`. Use `redis-cli -a "$REDIS_PASSWORD" ping` |
| DATABASE_URL missing Username | SampleApi `NullReferenceException` on DB access | Npgsql requires explicit `Username=sampleapi` in the connection string |
| Concurrent T2 push conflict | `updateGitOps` fails with `non-fast-forward` | Already fixed: `updateGitOps.groovy` has `git pull --rebase` retry (3 attempts) |
| Wrong GitLab project ID for NotificationApi | Webhooks trigger wrong pipeline | Verify: `notificationapi-source` should be project 5. Check `env.sh` |

---

## Self-Assessment

- [ ] I can explain why we need 12 ArgoCD Applications instead of 4
- [ ] I can describe the three-layer GitOps structure: services, infra, argocd
- [ ] I know where PostgreSQL credentials live (`infra-secret`) vs app credentials (`sampleapi-secret`)
- [ ] I can verify inter-service communication from SampleApi to NotificationApi
- [ ] I understand why database and cache pods have zero egress NetworkPolicies
- [ ] I can list all 7 Jenkins jobs and explain which service each serves
- [ ] I know how concurrent T2 pushes to the GitOps repo are handled

---

## Next Module Preview

**Module 10: End-to-End DevSecOps Walkthrough** — Now that all the pieces are in place, you'll trace a code change from a developer's feature branch through all four pipeline triggers across both services. You'll create a merge request (T1), merge it (T2), tag a release (T3), and watch the cascading promotion chain create MRs for SIT, UAT, and PROD (T4). You'll see performance test results on the GitLab MR and verify that each service promotes independently.

---

*Module 9B complete. Estimated time: 75 minutes.*
