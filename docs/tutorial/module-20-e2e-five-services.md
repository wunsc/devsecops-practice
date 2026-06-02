# Module 20: Full 5-Service End-to-End Walkthrough

| | |
|---|---|
| **Track** | Supply Chain & Multi-Language |
| **Duration** | ~90 minutes |
| **Difficulty** | Advanced |
| **Prerequisites** | Modules 16-19 complete, all 5 services deployed in DEV, all pipeline triggers working |

---

## What You Will Learn

This is the capstone module. You have spent the previous 19 modules building a DevSecOps platform from scratch -- namespaces, Jenkins, ArgoCD, SonarQube, ACS, OWASP ZAP, k6 performance testing, SBOMs with Trustify, image signing with RHTAS, distributed tracing, and Grafana dashboards. You have 5 microservices, 16 Jenkins jobs, 28+ ArgoCD applications, and 8 namespaces across 2 language ecosystems.

Now you will use all of it together, end to end, in a single 90-minute session.

By the end of this module you will have:

1. Verified the complete platform inventory -- all 5 services, all 16 Jenkins jobs, all 28+ ArgoCD apps, all observability tooling
2. Driven a .NET service (SampleApi) through the full T1 -> T2 -> T3 -> T4 pipeline chain from feature branch to production
3. Driven a Java service (order-service) through the same chain, observing where the pipeline diverges for language-specific stages
4. Released both services simultaneously, watching concurrent T3 pipelines queue and resolve through the Jenkins containerCap=2 limit
5. Cascaded both releases through SIT -> UAT -> PROD via the automated T4 promotion chain
6. Verified the complete supply chain trail -- SBOMs in Trustify, signatures in Rekor, traces in Grafana, and all ArgoCD apps Synced/Healthy across 4 environments
7. Understood why this platform handles 2 languages, 5 services, 4 environments, and 16 pipelines with the same shared library and GitOps structure

---

## Prerequisites

> **Environment variables:** Before running any commands, source the environment file:
> ```bash
> source ./env.sh
> ```
> This sets `$OC`, `$APPS_DOMAIN`, `$NS_TOOLS`, `$NS_DEV`, `$NS_JAVA_DEV`, `$TRUSTIFY_URL`, `$RHTAS_REKOR_URL`, and all other cluster-specific variables used throughout this module. See `env.sh` for the full variable list.

Before starting, confirm the following:

```bash
# Source your environment
source ./env.sh

# All tool namespaces healthy
$OC get pods -n $NS_TOOLS   # Jenkins, SonarQube, Grafana running
$OC get pods -n $NS_GITLAB  # GitLab running
$OC get pods -n $NS_ACS     # ACS Central, Sensor running
$OC get pods -n $NS_GITOPS  # ArgoCD running

# Log in to ArgoCD (needed for all argocd commands in this module)
ARGOCD_PASS=$($OC get secret openshift-gitops-cluster -n $NS_GITOPS \
  -o jsonpath='{.data.admin\.password}' | base64 -d)
argocd login openshift-gitops-server.openshift-gitops.svc:443 \
  --username admin --password "$ARGOCD_PASS" --insecure --grpc-web
# --> 'admin:login' logged in successfully

# All DEV pods running (.NET services)
$OC get pods -n ${NS_DEV}
# Expected:
# NAME                               READY   STATUS    RESTARTS   AGE
# notificationapi-847754bdb8-vg2xf   1/1     Running   1          28h
# postgresql-0                       1/1     Running   2          46h
# redis-0                            1/1     Running   2          46h
# sampleapi-674bb887c9-lbm9l         1/1     Running   1          26h

# All DEV pods running (Java services)
$OC get pods -n ${NS_JAVA_DEV}
# Expected:
# NAME                                  READY   STATUS    RESTARTS   AGE
# inventory-service-6b8f9c7d44-xk2ml   1/1     Running   0          12h
# order-service-5d4f8c6b77-rj9np       1/1     Running   0          12h
# payment-service-7c9e8d5f33-tn4wq     1/1     Running   0          12h
# postgresql-0                         1/1     Running   0          12h
# redis-0                              1/1     Running   0          12h

# GitLab accessible
curl -sk ${GITLAB_URL}/-/readiness | jq .status
# Expected: "ok"
```

You should also have the GitLab root credentials and the Personal Access Token ready (see SECRETS.md for locations).

---

## 1. The Big Picture (10 min)

Before touching any code, study the complete architecture. This is everything you have built.

### The Full Platform Architecture

```
                    GITLAB CE                                    JENKINS (on OpenShift)
  +---------------------------------------+        +------------------------------------------+
  |  8 Repositories:                      |        |  16 Jobs:                                |
  |                                       |        |                                          |
  |  .NET:                                |        |  .NET (6 jobs):                          |
  |    app-source (ID 1)     ──webhook──> |------->|    sampleapi-mr, -merge, -tag             |
  |    notificationapi-source (ID 8)  ──> |------->|    notificationapi-mr, -merge, -tag       |
  |                                       |        |                                          |
  |  Java:                                |        |  Java (9 jobs):                          |
  |    order-service (ID 10)  ──webhook─> |------->|    order-service-mr, -merge, -tag         |
  |    inventory-service (ID 11) ──────>  |------->|    inventory-service-mr, -merge, -tag     |
  |    payment-service (ID 12) ──────>    |------->|    payment-service-mr, -merge, -tag       |
  |                                       |        |                                          |
  |  Shared:                              |        |  Shared (1 job):                         |
  |    build-config (ID 5)                |        |    sampleapi-promote                     |
  |    jenkins-shared-lib (ID 6)          |        |    (handles ALL services via regex)       |
  |    app-gitops (ID 7) ──webhook──────> |------->|                                          |
  +---------------------------------------+        +------------------------------------------+
                                                              |
                                                              v
  +--------------------------------------------+   +------------------------------------------+
  |  ARGOCD (28+ Applications)                 |   |  SUPPLY CHAIN                            |
  |                                            |   |                                          |
  |  .NET Namespace Set (sampleapi-{env}):     |   |  Trustify (RHTPA):                       |
  |    sampleapi-dev/sit/uat/prod          (4) |   |    SBOM storage + vulnerability analysis |
  |    notificationapi-dev/sit/uat/prod    (4) |   |                                          |
  |    infra-sampleapi-dev/sit/uat/prod    (4) |   |  RHTAS:                                  |
  |                                            |   |    Fulcio (keyless certs)                 |
  |  Java Namespace Set (javaapp-{env}):       |   |    Rekor (transparency log)               |
  |    order-service-dev/sit/uat/prod      (4) |   |    cosign sign + verify + attest          |
  |    inventory-service-dev/sit/uat/prod  (4) |   |                                          |
  |    payment-service-dev/sit/uat/prod    (4) |   |  SonarQube: 5 projects                   |
  |    infra-javaapp-dev/sit/uat/prod      (4) |   |  ACS: image scan + admission control     |
  |                                            |   |  OWASP ZAP: DAST on T3                   |
  |  Total: 28 ArgoCD applications             |   |  k6: performance gate on T3              |
  +--------------------------------------------+   +------------------------------------------+
                    |
                    v
  +--------------------------------------------+
  |  8 NAMESPACES                              |
  |                                            |
  |  sampleapi-dev   (1 replica per svc)       |
  |  sampleapi-sit   (2 replicas per svc)      |
  |  sampleapi-uat   (2 replicas per svc)      |
  |  sampleapi-prod  (3 replicas sampleapi,    |
  |                   2 replicas notificationapi)|
  |                                            |
  |  javaapp-dev     (1 replica per svc)       |
  |  javaapp-sit     (2 replicas per svc)      |
  |  javaapp-uat     (2 replicas per svc)      |
  |  javaapp-prod    (3 replicas order-svc,    |
  |                   2 replicas others)        |
  +--------------------------------------------+
```

### Pipeline Stage Comparison: .NET vs Java

Both languages flow through the same shared library orchestrators (`pipelineMR`, `pipelineMerge`, `pipelineTag`). The orchestrator calls `pipelineConfig.configureForService(serviceName)`, which sets the language, Dockerfile, and scan configuration. Here is where the stages differ:

| Stage | .NET (SampleApi) | Java (order-service) |
|-------|------------------|----------------------|
| Build | `buildDotnet.groovy` -- `dotnet restore` / `dotnet build` / `dotnet publish` | `buildJava.groovy` -- `mvn package -DskipTests -B` |
| Unit Tests | `runUnitTests.groovy` -- `dotnet test` (xunit) | `runJavaTests.groovy` -- `mvn test -B` (JUnit/Surefire) |
| SAST | `scanSonarQube.groovy` -- dotnet-sonarscanner begin/end | `scanSonarQubeJava.groovy` -- `mvn sonar:sonar` (Maven plugin) |
| SBOM | dotnet-CycloneDX (~173 components) | cyclonedx-maven-plugin (~95 components) |
| SCA | OWASP Dependency-Check (same CLI for both) | Same |
| Container Build | `Dockerfile` (.NET 8 multi-stage: SDK -> ASP.NET runtime) | `Dockerfile.java` (multi-stage: Maven+JDK 21 -> UBI9-minimal+JRE) |
| ACS Scan | `roxctl image check` / `roxctl image scan` (identical) | Same |
| DAST (T3) | OWASP ZAP baseline against DEV route | Same |
| Performance (T3) | k6 load test against DEV route | Same |
| Sign + Verify | cosign keyless (RHTAS) + SBOM attestation | Same |
| Health Endpoint | `/healthz` -> `{"status":"healthy"}` | `/actuator/health` -> `{"status":"UP"}` |

Notice that 6 out of 11 stages are identical regardless of language. Once the image is built, the platform does not care whether it came from C# or Java.

### The Full Release Flow (One Service)

This is the path every code change takes from your editor to production:

```
1. Create feature branch, push code
2. Create MR in GitLab ──────────────────> T1 fires (build, test, SAST, SCA)
                                           Result: pass/fail on MR
3. Merge MR ─────────────────────────────> T2 fires (T1 + SBOM + image + sign
                                           + verify + ACS + GitOps + deploy DEV)
                                           Result: running in DEV
4. Create git tag ───────────────────────> T3 fires (T2 + DAST + k6 + attestation
                                           + create SIT promotion MR)
                                           Result: release-ready image, MR in app-gitops
5. Approve + merge SIT MR ──────────────> T4 fires → ArgoCD syncs SIT
                                           → verifies healthy → creates UAT MR
6. Approve + merge UAT MR ──────────────> T4 fires → ArgoCD syncs UAT
                                           → verifies healthy → creates PROD MR
7. Approve + merge PROD MR ─────────────> T4 fires → ArgoCD syncs PROD
                                           → end of chain
```

The developer does 4 things (push, open MR, merge MR, push tag). Approvers do 3 things (merge SIT MR, merge UAT MR, merge PROD MR). Everything else is automated.

### The Numbers

| Category | Count | Details |
|----------|-------|---------|
| Services | 5 | sampleapi, notificationapi (.NET 8); order-service, inventory-service, payment-service (Java 21) |
| GitLab Repos | 8 | 5 source + build-config + shared-lib + app-gitops |
| Jenkins Jobs | 16 | 5 services x 3 triggers + 1 promotion |
| ArgoCD Apps | 28 | 5 services x 4 envs + 2 infra x 4 envs |
| Namespaces | 8 | 2 namespace sets x 4 environments |
| SonarQube Projects | 5 | 1 per service |
| GitLab Webhooks | 16 | 3 per source repo (MR/push/tag) + 1 on app-gitops |
| Security Gates | 7 per T3 | Build, test, SAST, SCA, SBOM, ACS, DAST + performance |

> **Why this matters:** This is not a demo with one service and one pipeline. This is the scale of a real enterprise platform -- multiple teams, multiple languages, multiple environments, multiple security gates. And it is all driven by a single Jenkins shared library and a single GitOps repository structure. If you can operate this, you can operate the real thing.

---

## 2. Prepare: Reset the Stage (10 min)

Before starting the release cycle, verify every piece of the platform is healthy. This is what you would do on a Monday morning before any deployments.

### 2.1 Verify All 5 Services in DEV

```bash
# .NET services
echo "=== .NET Services (${NS_DEV}) ==="
$OC get pods -n ${NS_DEV}
# Expected:
# NAME                               READY   STATUS    RESTARTS   AGE
# notificationapi-847754bdb8-vg2xf   1/1     Running   ...
# postgresql-0                       1/1     Running   ...
# redis-0                            1/1     Running   ...
# sampleapi-674bb887c9-lbm9l         1/1     Running   ...

# Java services
echo ""
echo "=== Java Services (${NS_JAVA_DEV}) ==="
$OC get pods -n ${NS_JAVA_DEV}
# Expected:
# NAME                                  READY   STATUS    RESTARTS   AGE
# inventory-service-6b8f9c7d44-xk2ml   1/1     Running   ...
# order-service-5d4f8c6b77-rj9np       1/1     Running   ...
# payment-service-7c9e8d5f33-tn4wq     1/1     Running   ...
# postgresql-0                         1/1     Running   ...
# redis-0                              1/1     Running   ...

# Health checks — .NET
echo ""
echo "=== Health Checks ==="
echo -n "SampleApi:     "; curl -sk https://${APP_NAME}-${NS_DEV}.${APPS_DOMAIN}/healthz | jq -r '.status'
# Expected: healthy

# Health checks — Java
echo -n "OrderService:  "; curl -sk https://order-service-${NS_JAVA_DEV}.${APPS_DOMAIN}/actuator/health | jq -r '.status'
# Expected: UP
```

### 2.2 Verify All 16 Jenkins Jobs

```bash
GITLAB_TOKEN=$($OC get secret gitlab-token -n $NS_TOOLS -o jsonpath='{.data.token}' | base64 -d)

echo "=== Jenkins Jobs ==="
for JOB in \
  sampleapi-mr sampleapi-merge sampleapi-tag \
  notificationapi-mr notificationapi-merge notificationapi-tag \
  order-service-mr order-service-merge order-service-tag \
  inventory-service-mr inventory-service-merge inventory-service-tag \
  payment-service-mr payment-service-merge payment-service-tag \
  sampleapi-promote; do
  STATUS=$(curl -sk "${JENKINS_URL}/job/${JOB}/api/json" 2>/dev/null | jq -r '.color // "NOT_FOUND"')
  printf "  %-30s %s\n" "${JOB}" "${STATUS}"
done
# Expected: 16 jobs listed, each showing blue (success), yellow (unstable), or notBuilt
# NOT_FOUND means the job is missing — go back to the relevant module
```

### 2.3 Verify All ArgoCD Applications

```bash
echo "=== ArgoCD Applications ==="
argocd app list \
  --server openshift-gitops-server.openshift-gitops.svc:443 \
  --insecure --grpc-web 2>/dev/null \
  | grep -E 'NAME|sampleapi|notificationapi|order-service|inventory-service|payment-service|infra'
# Expected: 28 apps, all showing Synced/Healthy
# DEV apps: Auto sync policy
# SIT/UAT/PROD apps: Manual sync policy
```

### 2.4 Verify Supply Chain Tools

```bash
# Trustify (RHTPA) — SBOM storage
echo -n "Trustify: "
$OC get pods -n $NS_RHTPA -l app.kubernetes.io/name=trustify-server --no-headers 2>/dev/null | head -1 | awk '{print $3}'
# Expected: Running

# RHTAS — Image signing
echo -n "Rekor:    "
curl -sk ${RHTAS_REKOR_URL}/api/v1/log | jq -r '.treeSize // "unreachable"'
# Expected: a number (the transparency log entry count)

# Grafana — Observability
echo -n "Grafana:  "
curl -sk -o /dev/null -w "%{http_code}" ${GRAFANA_URL}/login
# Expected: 200
```

### 2.5 Determine Next Version Tags

Check the latest tags for each service so you know what version numbers to use:

```bash
echo "=== Latest Tags ==="
GITLAB_HOST=$(echo $GITLAB_URL | sed 's|https://||')

for PROJECT_INFO in \
  "app-source:${GITLAB_PROJECT_APP_SOURCE}:sampleapi" \
  "notificationapi-source:${GITLAB_PROJECT_NOTIFICATION_SOURCE}:notificationapi" \
  "order-service:${GITLAB_PROJECT_ORDER_SERVICE}:order-service" \
  "inventory-service:${GITLAB_PROJECT_INVENTORY_SERVICE}:inventory-service" \
  "payment-service:${GITLAB_PROJECT_PAYMENT_SERVICE}:payment-service"; do

  IFS=':' read -r REPO PID SVC <<< "$PROJECT_INFO"
  LATEST=$(curl -sk -H "PRIVATE-TOKEN: $GITLAB_TOKEN" \
    "${GITLAB_URL}/api/v4/projects/${PID}/repository/tags?order_by=version&sort=desc&per_page=1" \
    | jq -r '.[0].name // "none"')
  printf "  %-25s latest: %-10s → next: v%s\n" "${SVC}" "${LATEST}" \
    "$(echo $LATEST | sed 's/v//' | awk -F. '{printf "%d.%d.0", $1, $2+1}')"
done
# Example output:
#   sampleapi                 latest: v1.8.0     → next: v1.9.0
#   notificationapi           latest: v1.2.0     → next: v1.3.0
#   order-service             latest: v2.1.0     → next: v2.2.0
#   inventory-service         latest: v1.1.0     → next: v1.2.0
#   payment-service           latest: v1.1.0     → next: v1.2.0
```

Note the version numbers from your output. You will use them in Acts 3 and 4 when creating tags. For the rest of this module, we will use `v2.0.0` as the release tag for both SampleApi and order-service. Adjust to match your actual next version.

> **Why this matters:** In a real team, you would never tag a release without knowing the current version. Tagging `v1.8.0` twice overwrites the previous release. Tagging `v1.8.1` implies a patch release. Tagging `v2.0.0` implies a breaking change. The version number carries meaning to the promotion approvers.

---

## 3. Act 1: Feature Development on SampleApi (.NET) (15 min)

We start with the .NET side. You will push a feature branch, open an MR, watch T1 validate it, merge the MR, and watch T2 build an image and deploy to DEV.

### 3.1 Clone and Create Feature Branch

```bash
GITLAB_HOST=$(echo $GITLAB_URL | sed 's|https://||')

cd /tmp
rm -rf e2e-five-services && mkdir e2e-five-services && cd e2e-five-services

# Clone the SampleApi source repo
git clone https://root:${GITLAB_TOKEN}@${GITLAB_HOST}/${APP_GROUP}/app-source.git
cd app-source

# Create a feature branch
git checkout -b feature/add-build-info
```

### 3.2 Make a Code Change

Add a build info field to the WeatherForecast response. This is a small, safe change -- but it touches the model and the controller, so every scan stage has real work to do.

```bash
# Add a build timestamp property to the WeatherForecast class
# Find the class definition and add the property
cat >> src/SampleApi/Controllers/WeatherForecastController.cs << 'PATCH'

// Added in Module 20 — build metadata in API response
// This demonstrates the full pipeline for a .NET code change
PATCH

git add -A
git commit -m "feat: add build info annotation for Module 20 walkthrough"
git push origin feature/add-build-info
```

> **What just happened:** You pushed a feature branch to `app-source` (project ID ${GITLAB_PROJECT_APP_SOURCE}). Nothing triggers yet -- T1 only fires when you open a Merge Request. The branch push alone is not enough.

### 3.3 Open a Merge Request -- Watch T1

```bash
# Create the MR via GitLab API
MR_RESPONSE=$(curl -sk -X POST -H "PRIVATE-TOKEN: $GITLAB_TOKEN" \
  "${GITLAB_URL}/api/v4/projects/${GITLAB_PROJECT_APP_SOURCE}/merge_requests" \
  -d "source_branch=feature/add-build-info" \
  -d "target_branch=main" \
  -d "title=feat: add build info annotation for Module 20")

SA_MR_IID=$(echo $MR_RESPONSE | jq '.iid')
echo "SampleApi MR created: !${SA_MR_IID}"
echo "View at: ${GITLAB_URL}/${APP_GROUP}/app-source/-/merge_requests/${SA_MR_IID}"
echo ""
echo "T1 pipeline starting — watch at:"
echo "  ${JENKINS_URL}/job/sampleapi-mr/"
```

Open Jenkins immediately to watch the pipeline. The T1 pipeline (`pipelineMR.groovy`) runs these stages:

| Stage | What It Does | ~Duration |
|-------|-------------|-----------|
| Initialize | Reads `PipelineConfig`, configures for sampleapi | ~2s |
| Checkout Source | Clones `app-source` at feature branch | ~5s |
| Checkout Build Config | Clones `build-config` into `./build-config/` | ~3s |
| Build | `dotnet restore` + `dotnet build` + `dotnet publish` | ~30s |
| Unit Tests | `dotnet test` with coverage report | ~15s |
| SonarQube Analysis | dotnet-sonarscanner, polls quality gate | ~60s |
| Dependency Check | OWASP Dependency-Check scans `src/` for CVEs | ~60-600s |

> **What to watch for in the Jenkins console output:**
>
> - `=== T1: MR Validation Pipeline ===` -- confirms the correct orchestrator
> - `Service: sampleapi` -- confirms the language routing
> - `Branch: feature/add-build-info` -- confirms the right branch
> - Each stage prints its own status line with duration

### Verify T1

```bash
# Wait for T1 to complete (poll periodically)
curl -sk "${JENKINS_URL}/job/sampleapi-mr/lastBuild/api/json" | jq '.result'
# Expected: "SUCCESS" (once finished) or null (still running)

# Check GitLab MR pipeline status
curl -sk -H "PRIVATE-TOKEN: $GITLAB_TOKEN" \
  "${GITLAB_URL}/api/v4/projects/${GITLAB_PROJECT_APP_SOURCE}/merge_requests/${SA_MR_IID}" \
  | jq '.head_pipeline.status'
# Expected: "success"
```

Once T1 completes, go to the MR page in GitLab. You will see:

1. **Pipeline status badge** -- green checkmark next to the MR title
2. **Jenkins CI comment** with a table showing every gate result:

```
## ✅ Jenkins CI -- Pipeline Passed

| Stage | Status | Details |
|-------|--------|---------|
| Build | ✅ SUCCESS | 12s |
| Unit Tests | ✅ SUCCESS | Coverage: 21.92% (8s) |
| SonarQube (SAST) | ✅ SUCCESS | Quality Gate: OK |
| Dependency Check (SCA) | ✅ SUCCESS | 0 findings |
```

### 3.4 Merge the MR -- Watch T2

```bash
# Merge the MR — this triggers T2 (push-to-main webhook → sampleapi-merge job)
curl -sk -X PUT -H "PRIVATE-TOKEN: $GITLAB_TOKEN" \
  "${GITLAB_URL}/api/v4/projects/${GITLAB_PROJECT_APP_SOURCE}/merge_requests/${SA_MR_IID}/merge"

echo "SampleApi MR merged — T2 starting"
echo "Watch at: ${JENKINS_URL}/job/sampleapi-merge/"
```

The T2 pipeline (`pipelineMerge.groovy`) does everything T1 does, plus:

| Additional Stage | What It Does | Why |
|-----------------|-------------|-----|
| Generate SBOM | dotnet-CycloneDX (~173 components), upload to Trustify | Supply chain transparency |
| Build Container Image | `podman build -f build-config/Dockerfile .` | Creates OCI image |
| Push to Registry | Pushes to OCP internal registry with tag `main-<shortSHA>` | Makes it pullable |
| Sign Image | cosign keyless sign via RHTAS (Fulcio + Rekor) | Cryptographic provenance |
| Verify Image | cosign verify signature + SBOM attestation | Trust chain validation |
| ACS Image Scan | `roxctl image check` + `roxctl image scan` | CVE scan on image layers |
| Update GitOps | Updates `services/sampleapi/overlays/dev/kustomization.yaml` | Sets new image tag |
| Deploy to DEV | `argocd app sync sampleapi-dev` | Rolls out to DEV |

### Verify T2

```bash
# Wait for T2 to complete
curl -sk "${JENKINS_URL}/job/sampleapi-merge/lastBuild/api/json" | jq '.result'
# Expected: "SUCCESS"

# Check the new image tag in DEV
$OC get deploy sampleapi -n ${NS_DEV} -o jsonpath='{.spec.template.spec.containers[0].image}' | sed 's|.*/||'
# Expected: sampleapi:main-<7charSHA>

# SampleApi is healthy in DEV
curl -sk https://${APP_NAME}-${NS_DEV}.${APPS_DOMAIN}/healthz
# Expected: {"status":"healthy","timestamp":"..."}
```

The MR comment after T2 merging shows the full scan result table:

```
## ✅ Jenkins CI — Merge Pipeline Passed — Deployed to DEV

| Stage | Status | Details |
|-------|--------|---------|
| Build | ✅ SUCCESS | 22.8s |
| Unit Tests | ✅ SUCCESS | Coverage: 21.92% |
| SonarQube (SAST) | ✅ SUCCESS | Quality Gate: OK |
| Dependency Check (SCA) | ✅ SUCCESS | 0 findings |
| SBOM (CycloneDX) | ✅ SUCCESS | 173 components, Upload: SUCCESS |
| Image Signing | ✅ SUCCESS | Keyless (RHTAS) |
| Image Verification | ✅ SUCCESS | Signature: Valid / Attestation: Valid |
| ACS Image Scan | ❌ FAILURE | Critical: 4 / High: 5 |
| GitOps Update | ✅ SUCCESS | Updated DEV overlay |
| Deploy to DEV | ✅ SUCCESS | ArgoCD synced to DEV |
```

> **Why this matters:** The reviewer sees everything without leaving GitLab. The SBOM, the signing status, the ACS scan results, the deployment confirmation -- all on one MR page. This is the feedback loop that makes DevSecOps practical, not theoretical.

> **About ACS findings:** ACS often reports critical/high CVEs from base image layers (UBI9). These are flagged but do not block T2. Only T3 runs ACS in strict mode where critical CVEs block the release. This is intentional -- you want DEV to deploy so developers can test, even with known base image CVEs.

---

## 4. Act 2: Feature Development on order-service (Java) (15 min)

Now the Java side. The same flow, different language, same shared library.

### 4.1 Clone and Create Feature Branch

```bash
cd /tmp/e2e-five-services

# Clone the order-service source repo
git clone https://root:${GITLAB_TOKEN}@${GITLAB_HOST}/${APP_GROUP}/order-service.git
cd order-service

# Create a feature branch
git checkout -b feature/add-order-metadata
```

### 4.2 Make a Code Change

```bash
# Add a small annotation to the order service
cat >> src/main/java/com/devsecops/orderservice/OrderServiceApplication.java << 'PATCH'

// Added in Module 20 — build metadata for E2E walkthrough
// This demonstrates the full pipeline for a Java code change
PATCH

git add -A
git commit -m "feat: add order metadata annotation for Module 20 walkthrough"
git push origin feature/add-order-metadata
```

### 4.3 Open MR -- Watch T1 (Java Path)

```bash
# Create the MR
OS_MR_RESPONSE=$(curl -sk -X POST -H "PRIVATE-TOKEN: $GITLAB_TOKEN" \
  "${GITLAB_URL}/api/v4/projects/${GITLAB_PROJECT_ORDER_SERVICE}/merge_requests" \
  -d "source_branch=feature/add-order-metadata" \
  -d "target_branch=main" \
  -d "title=feat: add order metadata for Module 20")

OS_MR_IID=$(echo $OS_MR_RESPONSE | jq '.iid')
echo "order-service MR created: !${OS_MR_IID}"
echo "View at: ${GITLAB_URL}/${APP_GROUP}/order-service/-/merge_requests/${OS_MR_IID}"
echo ""
echo "T1 pipeline starting — watch at:"
echo "  ${JENKINS_URL}/job/order-service-mr/"
```

In the Jenkins console output, notice the language-specific stages:

```
=== T1: MR Validation Pipeline ===
  Service: order-service                          # ← THIS IS KEY — different from sampleapi
  Source repo: .../order-service.git
  Branch: feature/add-order-metadata
```

The stages that differ from SampleApi T1:

| Stage | SampleApi (.NET) | order-service (Java) |
|-------|-----------------|----------------------|
| Build | `dotnet restore` + `build` + `publish` (~30s) | `mvn package -DskipTests -B` (~60-90s) |
| Unit Tests | `dotnet test` (xunit, ~15s) | `mvn test -B` (JUnit/Surefire, ~30-45s) |
| SAST | dotnet-sonarscanner begin/end (~60s) | `mvn sonar:sonar` (Maven plugin, ~60s) |

Java builds take longer than .NET builds because Maven downloads dependencies on first run and the compilation step is heavier. Expect 5-7 minutes for a Java T1 versus 3-5 minutes for a .NET T1.

### Verify T1 (Java)

```bash
# Wait for T1 to complete
curl -sk "${JENKINS_URL}/job/order-service-mr/lastBuild/api/json" | jq '.result'
# Expected: "SUCCESS"

# Check GitLab MR status
curl -sk -H "PRIVATE-TOKEN: $GITLAB_TOKEN" \
  "${GITLAB_URL}/api/v4/projects/${GITLAB_PROJECT_ORDER_SERVICE}/merge_requests/${OS_MR_IID}" \
  | jq '.head_pipeline.status'
# Expected: "success"
```

### 4.4 Merge the MR -- Watch T2 (Java Path)

```bash
# Merge the order-service MR
curl -sk -X PUT -H "PRIVATE-TOKEN: $GITLAB_TOKEN" \
  "${GITLAB_URL}/api/v4/projects/${GITLAB_PROJECT_ORDER_SERVICE}/merge_requests/${OS_MR_IID}/merge"

echo "order-service MR merged — T2 starting"
echo "Watch at: ${JENKINS_URL}/job/order-service-merge/"
```

The T2 pipeline for Java is identical in structure to the .NET T2 -- the orchestrator (`pipelineMerge.groovy`) calls the same stages, but the language-specific functions differ:

```
pipelineMerge.groovy (same orchestrator)
  ├── configureForService('order-service')     # ← THIS IS KEY
  │     activeLanguage = 'java'                # ← routes to Java functions
  │     activeDockerfile = 'Dockerfile.java'
  │     imageNamespace = 'javaapp-dev'         # ← different namespace
  │     gitlabProjectId = '10'
  ├── buildJava(project: '.')                  # ← mvn package, not dotnet build
  ├── runJavaTests(project: '.')               # ← mvn test, not dotnet test
  ├── scanSonarQubeJava(...)                   # ← mvn sonar:sonar, not dotnet-sonarscanner
  ├── generateSBOM(language: 'java')           # ← cyclonedx-maven-plugin, not dotnet-CycloneDX
  ├── buildContainerImage(dockerfile: 'Dockerfile.java')  # ← JDK 21, not .NET 8
  ├── pushToRegistry(...)                      # ← pushes to javaapp-dev, not sampleapi-dev
  ├── signImage(...)                           # ← identical (cosign is language-agnostic)
  ├── verifyImage(...)                         # ← identical
  ├── scanACSImage(...)                        # ← identical (scans image layers, not source)
  ├── updateGitOps(appName: 'order-service')   # ← updates services/order-service/overlays/dev/
  └── deployToEnvironment(app: 'order-service-dev')  # ← syncs order-service-dev ArgoCD app
```

### Verify T2 (Java)

```bash
# Wait for T2 to complete
curl -sk "${JENKINS_URL}/job/order-service-merge/lastBuild/api/json" | jq '.result'
# Expected: "SUCCESS"

# Check the image in javaapp-dev (different namespace from sampleapi!)
$OC get deploy order-service -n ${NS_JAVA_DEV} -o jsonpath='{.spec.template.spec.containers[0].image}' | sed 's|.*/||'
# Expected: order-service:main-<7charSHA>

# order-service is healthy
curl -sk https://order-service-${NS_JAVA_DEV}.${APPS_DOMAIN}/actuator/health | jq '.status'
# Expected: "UP"
```

### 4.5 Compare: .NET vs Java T2 Results

Now compare the two T2 pipelines side by side:

```bash
echo "=== T2 Comparison ==="
echo ""

# .NET T2 duration
SA_DURATION=$(curl -sk "${JENKINS_URL}/job/sampleapi-merge/lastBuild/api/json" | jq '.duration / 1000 | floor')
echo "SampleApi T2 duration:      ${SA_DURATION}s"

# Java T2 duration
OS_DURATION=$(curl -sk "${JENKINS_URL}/job/order-service-merge/lastBuild/api/json" | jq '.duration / 1000 | floor')
echo "order-service T2 duration:  ${OS_DURATION}s"

echo ""
echo "SBOM component counts:"
echo "  .NET (SampleApi):    ~173 components (NuGet + transitive)"
echo "  Java (order-service): ~95 components (Maven + transitive)"
echo ""
echo "Image sizes:"
SA_SIZE=$($OC get istag sampleapi:$(oc get deploy sampleapi -n ${NS_DEV} -o jsonpath='{.spec.template.spec.containers[0].image}' | sed 's|.*:||') -n ${NS_DEV} -o jsonpath='{.image.dockerImageMetadata.Size}' 2>/dev/null || echo "N/A")
echo "  SampleApi image:     check in registry"
echo "  order-service image: check in registry"
```

> **Why this matters:** The .NET SBOM has nearly twice as many components as the Java SBOM because .NET resolves transitive NuGet dependencies more aggressively. This does not mean .NET is more vulnerable -- it means the .NET supply chain is more granular. Both SBOMs are uploaded to Trustify, where you can query "which of my services use a specific library version?" across both languages.

---

## 5. Act 3: Release Both Services (20 min)

This is where it gets interesting. You will create git tags for both services, triggering two T3 pipelines. Because Jenkins `containerCap=2` limits concurrent agent pods to 2, one pipeline will queue while the other runs. The T3 pipeline is the most comprehensive -- it includes DAST, performance testing, signing, and creates the promotion MR.

### 5.1 Tag SampleApi

```bash
cd /tmp/e2e-five-services/app-source
git checkout main && git pull

# Create the release tag — adjust version to match YOUR next version
git tag -a v2.0.0 -m "Release v2.0.0: Module 20 capstone release (.NET)"
git push origin v2.0.0

echo ""
echo "SampleApi tag pushed — T3 starting"
echo "Watch at: ${JENKINS_URL}/job/sampleapi-tag/"
```

### 5.2 Tag order-service (Immediately After)

```bash
cd /tmp/e2e-five-services/order-service
git checkout main && git pull

# Create the release tag — adjust version to match YOUR next version
git tag -a v2.0.0 -m "Release v2.0.0: Module 20 capstone release (Java)"
git push origin v2.0.0

echo ""
echo "order-service tag pushed — T3 starting (may queue)"
echo "Watch at: ${JENKINS_URL}/job/order-service-tag/"
```

### 5.3 Watch Concurrent T3 Pipelines

Open the Jenkins dashboard (`${JENKINS_URL}`) and watch the Build Queue:

```
Build Queue:
  [1] sampleapi-tag #N       ← Running (agent pod spinning up)
  [2] order-service-tag #N   ← Waiting for executor (containerCap=2)
```

> **Why only 2 concurrent agents:** The Kubernetes Cloud Plugin in Jenkins is configured with `containerCap=2`. This means at most 2 Jenkins agent pods can run simultaneously. This is a deliberate resource constraint -- each agent pod requests CPU and memory, and running 5 agents simultaneously on a small cluster would cause resource pressure. In production, you would scale this based on cluster capacity.

The T3 pipeline (`pipelineTag.groovy`) runs everything from T2, plus:

| Additional T3 Stage | What It Does | ~Duration |
|---------------------|-------------|-----------|
| ACS Image Scan (Strict) | Same `roxctl`, but `strict: true` -- fails on ANY critical CVE | ~30s |
| OWASP ZAP DAST | Spider + active scan against DEV route via ZAP sidecar container | ~2-5 min |
| Performance Test | k6 load test: 50 VUs, 4 min, ~15K requests against DEV endpoint | ~4 min |
| Sign Image | cosign keyless sign (RHTAS) with SBOM attestation | ~10s |
| Verify Image | cosign verify signature + attestation | ~5s |
| Create Promotion MR | Updates `services/{svc}/overlays/sit/kustomization.yaml`, creates MR in app-gitops | ~10s |

The T3 pipeline uses a special agent pod definition with a ZAP sidecar:

```
Agent pod:
  Container 1: jnlp (standard agent + dotnet/podman/roxctl/k6/cosign)
  Container 2: zap  (OWASP ZAP daemon on localhost:8090)
```

This is why T3 takes longer -- the ZAP container needs to start, the DAST scan crawls your application endpoints, and the k6 performance test runs a 4-minute load test.

### 5.4 Monitor T3 Progress

```bash
# Check both T3 pipelines periodically
echo "=== T3 Pipeline Status ==="
echo -n "sampleapi-tag:      "
curl -sk "${JENKINS_URL}/job/sampleapi-tag/lastBuild/api/json" | jq -r '.result // "RUNNING"'
echo -n "order-service-tag:  "
curl -sk "${JENKINS_URL}/job/order-service-tag/lastBuild/api/json" | jq -r '.result // "RUNNING"'
```

The .NET T3 will typically finish first (~10-12 min) because `dotnet build` is faster than `mvn package`. The Java T3 will finish second (~12-15 min). Watch for these key outputs in the console:

**SampleApi T3 console output highlights:**

```
=== T3: Tag/Release Pipeline ===
  Service: sampleapi
  Tag: v2.0.0

[DAST] ZAP spider scan completed — 45 URLs found
[DAST] ZAP active scan completed — High: 0 / Medium: 2 / Low: 5 / Info: 3

[Performance] k6 load test starting...
  Target: https://sampleapi-${NS_DEV}.${APPS_DOMAIN}
  Duration: 4m / VUs: 50
[Performance] Results: p90=180.0ms, p95=245.0ms, max=1200ms
[Performance] error_rate=0.0000, total_requests=15234
[Performance] All thresholds PASSED

[Sign] Keyless signing via RHTAS (Fulcio + Rekor)
[Sign] Signature recorded in Rekor transparency log
[Sign] SBOM attestation attached to image

Promotion MR created: !<N>
  URL: ${GITLAB_URL}/devsecops/app-gitops/-/merge_requests/<N>
```

**order-service T3 console output highlights:**

```
=== T3: Tag/Release Pipeline ===
  Service: order-service
  Tag: v2.0.0

[Build] mvn package -DskipTests -B      # ← Java build, not dotnet
[Test]  mvn test -B                      # ← JUnit/Surefire, not xunit
[SAST]  mvn sonar:sonar                  # ← Maven SonarQube plugin

[SBOM] cyclonedx-maven-plugin            # ← ~95 components (vs ~173 for .NET)

[DAST] ZAP target: https://order-service-javaapp-dev.apps.cluster-xxx...
[DAST] ZAP active scan completed — High: 0 / Medium: 1 / Low: 3 / Info: 2

[Performance] k6 target: https://order-service-javaapp-dev.apps.cluster-xxx...
[Performance] Results: p90=220.0ms, p95=310.0ms, max=1500ms
[Performance] error_rate=0.0000, total_requests=12890

Promotion MR created: !<M>
```

### Verify Both T3 Pipelines

```bash
# Wait for both T3 pipelines to complete
echo "=== T3 Results ==="
echo -n "sampleapi-tag:      "
curl -sk "${JENKINS_URL}/job/sampleapi-tag/lastBuild/api/json" | jq -r '.result'
echo -n "order-service-tag:  "
curl -sk "${JENKINS_URL}/job/order-service-tag/lastBuild/api/json" | jq -r '.result'
# Expected: both "SUCCESS" or "UNSTABLE"
# (UNSTABLE is acceptable — means ZAP found medium alerts, which is expected for APIs)

# Check versioned image tags exist
echo ""
echo "=== Versioned Image Tags ==="
$OC get istag sampleapi:v2.0.0 -n ${NS_DEV} 2>/dev/null && echo "  sampleapi:v2.0.0 EXISTS" || echo "  sampleapi:v2.0.0 MISSING"
$OC get istag order-service:v2.0.0 -n ${NS_JAVA_DEV} 2>/dev/null && echo "  order-service:v2.0.0 EXISTS" || echo "  order-service:v2.0.0 MISSING"

# Check latest-release tags
echo ""
echo "=== Latest-Release Tags ==="
$OC get istag sampleapi:latest-release -n ${NS_DEV} 2>/dev/null && echo "  sampleapi:latest-release EXISTS" || echo "  sampleapi:latest-release MISSING"
$OC get istag order-service:latest-release -n ${NS_JAVA_DEV} 2>/dev/null && echo "  order-service:latest-release EXISTS" || echo "  order-service:latest-release MISSING"
```

### 5.5 Inspect the Promotion MRs

Both T3 pipelines created SIT promotion MRs in `app-gitops`. Find them:

```bash
# List open MRs in app-gitops
echo "=== Promotion MRs ==="
curl -sk -H "PRIVATE-TOKEN: $GITLAB_TOKEN" \
  "${GITLAB_URL}/api/v4/projects/${GITLAB_PROJECT_APP_GITOPS}/merge_requests?state=opened&order_by=created_at&sort=desc" \
  | jq -r '.[] | "  !\(.iid) — \(.title)"'
# Expected:
#   !<N> — Promote sampleapi v2.0.0 to SIT
#   !<M> — Promote order-service v2.0.0 to SIT
```

Capture the MR IIDs:

```bash
# Get the two SIT promotion MR IIDs
SA_SIT_MR=$(curl -sk -H "PRIVATE-TOKEN: $GITLAB_TOKEN" \
  "${GITLAB_URL}/api/v4/projects/${GITLAB_PROJECT_APP_GITOPS}/merge_requests?state=opened&order_by=created_at&sort=desc" \
  | jq '[.[] | select(.title | contains("sampleapi"))][0].iid')

OS_SIT_MR=$(curl -sk -H "PRIVATE-TOKEN: $GITLAB_TOKEN" \
  "${GITLAB_URL}/api/v4/projects/${GITLAB_PROJECT_APP_GITOPS}/merge_requests?state=opened&order_by=created_at&sort=desc" \
  | jq '[.[] | select(.title | contains("order-service"))][0].iid')

echo "SampleApi SIT MR:      !${SA_SIT_MR}"
echo "  ${GITLAB_URL}/${APP_GROUP}/app-gitops/-/merge_requests/${SA_SIT_MR}"
echo ""
echo "order-service SIT MR:  !${OS_SIT_MR}"
echo "  ${GITLAB_URL}/${APP_GROUP}/app-gitops/-/merge_requests/${OS_SIT_MR}"
```

Open both MRs in GitLab. Each MR description contains the complete T3 pipeline summary. The SampleApi MR will show:

```
## Release v2.0.0 — Promotion to SIT

This MR was automatically created by the **T3 Tag Pipeline** after all
quality and security gates passed.

**Action required:** Review the results below, then **Approve** and **Merge** to deploy.

---

### T3 Pipeline Results (Build #N)

| Stage | Status | Details |
|-------|--------|---------|
| .NET Build | ✅ SUCCESS | 22s |
| Unit Tests | ✅ SUCCESS | Coverage: **21.92%** (8s) |
| SonarQube (SAST) | ✅ SUCCESS | Gate: **OK** |
| Dependency Check (SCA) | ✅ SUCCESS | **0** findings (45s) |
| Container Image | ✅ SUCCESS | 30s |
| Push to Registry | ✅ SUCCESS | sampleapi:v2.0.0 |
| ACS Image Scan (Strict) | ✅ SUCCESS | Critical: **0** / High: **0** |
| OWASP ZAP (DAST) | ✅ SUCCESS | High: **0** / Med: **2** / Low: **5** / Info: **3** |
| Performance Test (k6) | ✅ SUCCESS | p90=180.0ms, p95=245.0ms, max=1200ms, error_rate=0.0000, total_requests=15234 |
| Image Signing (Cosign) | ✅ SUCCESS | Keyless (RHTAS) | Attestation: SUCCESS |
| Image Verification | ✅ SUCCESS | Signature: **Valid** / Attestation: **Valid** |
| SBOM (CycloneDX) | ✅ SUCCESS | **173** components, Upload: SUCCESS |

### Changes
- Updates `services/sampleapi/overlays/sit/kustomization.yaml` image tag to `v2.0.0`
```

The order-service MR will show the same structure but with Java-specific metrics (95 components, different build times, different performance numbers).

> **Why this matters:** The SIT promotion approver (Team Lead) does not need to open Jenkins. Every security scan result, every performance metric, every signing verification -- it is all on the MR page. This is the audit trail. When a compliance auditor asks "what evidence existed before this release was promoted to SIT?", you point them to this MR.

### 5.6 Approve Both SIT MRs

Merge both SIT promotion MRs. Each merge triggers T4, which detects the changed overlay and syncs the corresponding ArgoCD app.

```bash
# Merge SampleApi SIT MR
echo "=== Merging SampleApi SIT MR !${SA_SIT_MR} ==="
curl -sk -X PUT -H "PRIVATE-TOKEN: $GITLAB_TOKEN" \
  "${GITLAB_URL}/api/v4/projects/${GITLAB_PROJECT_APP_GITOPS}/merge_requests/${SA_SIT_MR}/merge"
echo "Merged — T4 will sync sampleapi-sit and create UAT MR"
echo "Watch at: ${JENKINS_URL}/job/sampleapi-promote/"
```

**Wait for the first T4 to complete before merging the second.** The `sampleapi-promote` job has `disableConcurrentBuilds()`, so the second merge would queue anyway, but waiting ensures clean execution.

```bash
# Wait for T4 to complete
echo ""
echo "Waiting for T4 (SampleApi SIT)..."
while true; do
  RESULT=$(curl -sk "${JENKINS_URL}/job/sampleapi-promote/lastBuild/api/json" | jq -r '.result // "RUNNING"')
  echo -n "  Status: ${RESULT}"
  if [ "$RESULT" != "RUNNING" ] && [ "$RESULT" != "null" ]; then echo ""; break; fi
  echo " — waiting 15s..."
  sleep 15
done
```

Now merge the order-service SIT MR:

```bash
# Merge order-service SIT MR
echo "=== Merging order-service SIT MR !${OS_SIT_MR} ==="
curl -sk -X PUT -H "PRIVATE-TOKEN: $GITLAB_TOKEN" \
  "${GITLAB_URL}/api/v4/projects/${GITLAB_PROJECT_APP_GITOPS}/merge_requests/${OS_SIT_MR}/merge"
echo "Merged — T4 will sync order-service-sit and create UAT MR"
echo "Watch at: ${JENKINS_URL}/job/sampleapi-promote/"  # ← same job handles ALL services
```

> **Notice:** Both services use the SAME `sampleapi-promote` Jenkins job. The job name is misleading -- it is not SampleApi-specific. The `pipelinePromote.groovy` orchestrator uses regex `services/([^/]+)/overlays/([^/]+)/` on the git diff to detect which service changed. When order-service's SIT overlay changes, the same job correctly syncs `order-service-sit` instead of `sampleapi-sit`.

```bash
# Wait for T4 to complete (order-service SIT)
echo ""
echo "Waiting for T4 (order-service SIT)..."
while true; do
  RESULT=$(curl -sk "${JENKINS_URL}/job/sampleapi-promote/lastBuild/api/json" | jq -r '.result // "RUNNING"')
  echo -n "  Status: ${RESULT}"
  if [ "$RESULT" != "RUNNING" ] && [ "$RESULT" != "null" ]; then echo ""; break; fi
  echo " — waiting 15s..."
  sleep 15
done
```

### Verify SIT Deployments

```bash
echo "=== SIT Status ==="

# .NET SIT
echo "--- .NET Services (${NS_SIT}) ---"
$OC get pods -n ${NS_SIT}
echo ""
echo -n "sampleapi image: "
$OC get deploy sampleapi -n ${NS_SIT} -o jsonpath='{.spec.template.spec.containers[0].image}' | sed 's|.*/||'
# Expected: sampleapi:v2.0.0
echo ""
echo -n "sampleapi health: "
curl -sk https://${APP_NAME}-${NS_SIT}.${APPS_DOMAIN}/healthz | jq -r '.status'
# Expected: healthy

# Java SIT
echo ""
echo "--- Java Services (${NS_JAVA_SIT}) ---"
$OC get pods -n ${NS_JAVA_SIT}
echo ""
echo -n "order-service image: "
$OC get deploy order-service -n ${NS_JAVA_SIT} -o jsonpath='{.spec.template.spec.containers[0].image}' | sed 's|.*/||'
# Expected: order-service:v2.0.0
echo ""
echo -n "order-service health: "
curl -sk https://order-service-${NS_JAVA_SIT}.${APPS_DOMAIN}/actuator/health | jq -r '.status'
# Expected: UP
```

Both T4 runs should have auto-created UAT promotion MRs:

```bash
# Find UAT promotion MRs
echo "=== UAT Promotion MRs ==="
curl -sk -H "PRIVATE-TOKEN: $GITLAB_TOKEN" \
  "${GITLAB_URL}/api/v4/projects/${GITLAB_PROJECT_APP_GITOPS}/merge_requests?state=opened&order_by=created_at&sort=desc" \
  | jq -r '.[] | "  !\(.iid) — \(.title)"'
# Expected:
#   !<P> — Promote sampleapi v2.0.0 to UAT
#   !<Q> — Promote order-service v2.0.0 to UAT
```

The UAT MR description will be different from the SIT MR. It shows the SIT deployment health instead of T3 scan results:

```
## Release v2.0.0 — Promotion to UAT

This MR was automatically created after **SIT** deployment was verified **HEALTHY**.

Cascading promotion: the same image that passed all T3 gates and deployed
successfully to SIT.

**Action required:** Review the results below, then **Approve** and **Merge** to deploy.

---

### Previous Environment Deployment (Build #N)

| Stage | Status | Details |
|-------|--------|---------|
| SIT Deployment | ✅ HEALTHY | Pods: 2 / Image: `sampleapi:v2.0.0` |
```

> **Why the MR content differs:** The `createPromotionMR()` function detects its caller. When called from T3, it has `results.build`, `results.sonarqube`, `results.perfTest`, etc. -- the full scan results. When called from T4 (cascading), it has `results.syncResults` with deployment health data. The function adapts the MR description based on what data is available.

---

## 6. Act 4: Cascade to Production (10 min)

Now cascade both services through UAT and PROD. This is the approval chain -- each merge triggers T4, which syncs ArgoCD and creates the next promotion MR.

### 6.1 Promote to UAT

```bash
# Get UAT MR IIDs
SA_UAT_MR=$(curl -sk -H "PRIVATE-TOKEN: $GITLAB_TOKEN" \
  "${GITLAB_URL}/api/v4/projects/${GITLAB_PROJECT_APP_GITOPS}/merge_requests?state=opened&order_by=created_at&sort=desc" \
  | jq '[.[] | select(.title | contains("sampleapi") and contains("UAT"))][0].iid')

OS_UAT_MR=$(curl -sk -H "PRIVATE-TOKEN: $GITLAB_TOKEN" \
  "${GITLAB_URL}/api/v4/projects/${GITLAB_PROJECT_APP_GITOPS}/merge_requests?state=opened&order_by=created_at&sort=desc" \
  | jq '[.[] | select(.title | contains("order-service") and contains("UAT"))][0].iid')

echo "SampleApi UAT MR:      !${SA_UAT_MR}"
echo "order-service UAT MR:  !${OS_UAT_MR}"

# Merge SampleApi UAT MR
echo ""
echo "=== Merging SampleApi UAT MR ==="
curl -sk -X PUT -H "PRIVATE-TOKEN: $GITLAB_TOKEN" \
  "${GITLAB_URL}/api/v4/projects/${GITLAB_PROJECT_APP_GITOPS}/merge_requests/${SA_UAT_MR}/merge"
echo "Merged — waiting for T4..."
```

Wait for T4, then merge order-service UAT:

```bash
# Wait for T4 to complete
sleep 10  # Give the webhook time to fire
while true; do
  RESULT=$(curl -sk "${JENKINS_URL}/job/sampleapi-promote/lastBuild/api/json" | jq -r '.result // "RUNNING"')
  if [ "$RESULT" != "RUNNING" ] && [ "$RESULT" != "null" ]; then echo "T4 complete: ${RESULT}"; break; fi
  echo "  T4 running — waiting 15s..."
  sleep 15
done

# Merge order-service UAT MR
echo ""
echo "=== Merging order-service UAT MR ==="
curl -sk -X PUT -H "PRIVATE-TOKEN: $GITLAB_TOKEN" \
  "${GITLAB_URL}/api/v4/projects/${GITLAB_PROJECT_APP_GITOPS}/merge_requests/${OS_UAT_MR}/merge"
echo "Merged — waiting for T4..."

# Wait for T4
sleep 10
while true; do
  RESULT=$(curl -sk "${JENKINS_URL}/job/sampleapi-promote/lastBuild/api/json" | jq -r '.result // "RUNNING"')
  if [ "$RESULT" != "RUNNING" ] && [ "$RESULT" != "null" ]; then echo "T4 complete: ${RESULT}"; break; fi
  echo "  T4 running — waiting 15s..."
  sleep 15
done
```

### Verify UAT

```bash
echo "=== UAT Verification ==="

# .NET UAT
echo -n "sampleapi in UAT: "
$OC get deploy sampleapi -n ${NS_UAT} -o jsonpath='{.spec.template.spec.containers[0].image}' | sed 's|.*/||'
# Expected: sampleapi:v2.0.0

echo -n "sampleapi health: "
curl -sk https://${APP_NAME}-${NS_UAT}.${APPS_DOMAIN}/healthz | jq -r '.status'
# Expected: healthy

# Java UAT
echo -n "order-service in UAT: "
$OC get deploy order-service -n ${NS_JAVA_UAT} -o jsonpath='{.spec.template.spec.containers[0].image}' | sed 's|.*/||'
# Expected: order-service:v2.0.0

echo -n "order-service health: "
curl -sk https://order-service-${NS_JAVA_UAT}.${APPS_DOMAIN}/actuator/health | jq -r '.status'
# Expected: UP
```

### 6.2 Promote to PROD

```bash
# Get PROD MR IIDs
SA_PROD_MR=$(curl -sk -H "PRIVATE-TOKEN: $GITLAB_TOKEN" \
  "${GITLAB_URL}/api/v4/projects/${GITLAB_PROJECT_APP_GITOPS}/merge_requests?state=opened&order_by=created_at&sort=desc" \
  | jq '[.[] | select(.title | contains("sampleapi") and contains("PROD"))][0].iid')

OS_PROD_MR=$(curl -sk -H "PRIVATE-TOKEN: $GITLAB_TOKEN" \
  "${GITLAB_URL}/api/v4/projects/${GITLAB_PROJECT_APP_GITOPS}/merge_requests?state=opened&order_by=created_at&sort=desc" \
  | jq '[.[] | select(.title | contains("order-service") and contains("PROD"))][0].iid')

echo "SampleApi PROD MR:      !${SA_PROD_MR}"
echo "order-service PROD MR:  !${OS_PROD_MR}"

# Merge SampleApi PROD MR
echo ""
echo "=== Merging SampleApi PROD MR ==="
curl -sk -X PUT -H "PRIVATE-TOKEN: $GITLAB_TOKEN" \
  "${GITLAB_URL}/api/v4/projects/${GITLAB_PROJECT_APP_GITOPS}/merge_requests/${SA_PROD_MR}/merge"
echo "Merged — T4 will sync sampleapi-prod (end of chain for SampleApi)"

# Wait for T4
sleep 10
while true; do
  RESULT=$(curl -sk "${JENKINS_URL}/job/sampleapi-promote/lastBuild/api/json" | jq -r '.result // "RUNNING"')
  if [ "$RESULT" != "RUNNING" ] && [ "$RESULT" != "null" ]; then echo "T4 complete: ${RESULT}"; break; fi
  echo "  T4 running — waiting 15s..."
  sleep 15
done

# Merge order-service PROD MR
echo ""
echo "=== Merging order-service PROD MR ==="
curl -sk -X PUT -H "PRIVATE-TOKEN: $GITLAB_TOKEN" \
  "${GITLAB_URL}/api/v4/projects/${GITLAB_PROJECT_APP_GITOPS}/merge_requests/${OS_PROD_MR}/merge"
echo "Merged — T4 will sync order-service-prod (end of chain for order-service)"

# Wait for T4
sleep 10
while true; do
  RESULT=$(curl -sk "${JENKINS_URL}/job/sampleapi-promote/lastBuild/api/json" | jq -r '.result // "RUNNING"')
  if [ "$RESULT" != "RUNNING" ] && [ "$RESULT" != "null" ]; then echo "T4 complete: ${RESULT}"; break; fi
  echo "  T4 running — waiting 15s..."
  sleep 15
done
```

The Jenkins console output for the final PROD T4 will show:

```
=== Syncing PROD ===
  ArgoCD App: sampleapi-prod
  Image Tag: v2.0.0
  Approved by: CAB (via MR)

PROD: Sync=Synced, Health=Healthy

PROD: End of promotion chain — no next MR needed.
```

No further MRs are created. The chain is complete for both services.

### 6.3 What About the Other 3 Services?

You released SampleApi and order-service. But what about NotificationApi, inventory-service, and payment-service? They are completely unaffected:

```bash
echo "=== Untouched Services (Still at Previous Versions) ==="

echo -n "notificationapi in PROD:   "
$OC get deploy notificationapi -n ${NS_PROD} -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null | sed 's|.*/||'
echo ""
echo -n "inventory-service in PROD: "
$OC get deploy inventory-service -n ${NS_JAVA_PROD} -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null | sed 's|.*/||'
echo ""
echo -n "payment-service in PROD:   "
$OC get deploy payment-service -n ${NS_JAVA_PROD} -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null | sed 's|.*/||'
# Expected: all still at their previous version tags — NOT v2.0.0
```

> **Why this matters:** Per-service isolation. Releasing SampleApi v2.0.0 to production did not trigger a rebuild, rescan, or redeployment of any other service. Each service has its own GitLab repo, its own Jenkins jobs, its own ArgoCD apps, and its own overlay paths in the GitOps repo. This is how a multi-team organization operates -- team A ships without blocking team B.

---

## 7. Verify: The Complete Picture (10 min)

Both services are now in production. Let us verify the complete platform state -- supply chain evidence, observability, and all environments.

### 7.1 All Environments, All Services

```bash
echo "=========================================================="
echo "  FULL PLATFORM STATUS — ALL SERVICES, ALL ENVIRONMENTS"
echo "=========================================================="
echo ""

# .NET namespace set
for ENV_INFO in "DEV:${NS_DEV}" "SIT:${NS_SIT}" "UAT:${NS_UAT}" "PROD:${NS_PROD}"; do
  IFS=':' read -r ENV_NAME NS <<< "$ENV_INFO"
  echo "--- ${ENV_NAME} (.NET: ${NS}) ---"

  SA_IMAGE=$($OC get deploy sampleapi -n ${NS} -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null | sed 's|.*/||')
  SA_READY=$($OC get pods -n ${NS} -l app=sampleapi --no-headers 2>/dev/null | grep Running | wc -l)
  NA_IMAGE=$($OC get deploy notificationapi -n ${NS} -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null | sed 's|.*/||')
  NA_READY=$($OC get pods -n ${NS} -l app=notificationapi --no-headers 2>/dev/null | grep Running | wc -l)
  PG=$($OC get pod postgresql-0 -n ${NS} --no-headers 2>/dev/null | grep Running | wc -l)
  RD=$($OC get pod redis-0 -n ${NS} --no-headers 2>/dev/null | grep Running | wc -l)

  printf "  %-20s Pods: %s  Image: %s\n" "sampleapi" "${SA_READY}" "${SA_IMAGE}"
  printf "  %-20s Pods: %s  Image: %s\n" "notificationapi" "${NA_READY}" "${NA_IMAGE}"
  printf "  %-20s %s/1     %-20s %s/1\n" "postgresql" "${PG}" "redis" "${RD}"
  echo ""
done

# Java namespace set
for ENV_INFO in "DEV:${NS_JAVA_DEV}" "SIT:${NS_JAVA_SIT}" "UAT:${NS_JAVA_UAT}" "PROD:${NS_JAVA_PROD}"; do
  IFS=':' read -r ENV_NAME NS <<< "$ENV_INFO"
  echo "--- ${ENV_NAME} (Java: ${NS}) ---"

  for SVC in order-service inventory-service payment-service; do
    SVC_IMAGE=$($OC get deploy ${SVC} -n ${NS} -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null | sed 's|.*/||')
    SVC_READY=$($OC get pods -n ${NS} -l app=${SVC} --no-headers 2>/dev/null | grep Running | wc -l)
    printf "  %-20s Pods: %s  Image: %s\n" "${SVC}" "${SVC_READY}" "${SVC_IMAGE}"
  done

  PG=$($OC get pod postgresql-0 -n ${NS} --no-headers 2>/dev/null | grep Running | wc -l)
  RD=$($OC get pod redis-0 -n ${NS} --no-headers 2>/dev/null | grep Running | wc -l)
  printf "  %-20s %s/1     %-20s %s/1\n" "postgresql" "${PG}" "redis" "${RD}"
  echo ""
done
```

Expected output pattern:

```
==========================================================
  FULL PLATFORM STATUS — ALL SERVICES, ALL ENVIRONMENTS
==========================================================

--- DEV (.NET: sampleapi-dev) ---
  sampleapi            Pods: 1  Image: sampleapi:main-<sha>
  notificationapi      Pods: 1  Image: notificationapi:main-<sha>
  postgresql           1/1     redis                1/1

--- SIT (.NET: sampleapi-sit) ---
  sampleapi            Pods: 2  Image: sampleapi:v2.0.0
  notificationapi      Pods: 2  Image: notificationapi:v1.2.0
  postgresql           1/1     redis                1/1

--- UAT (.NET: sampleapi-uat) ---
  sampleapi            Pods: 2  Image: sampleapi:v2.0.0
  notificationapi      Pods: 2  Image: notificationapi:v1.2.0
  postgresql           1/1     redis                1/1

--- PROD (.NET: sampleapi-prod) ---
  sampleapi            Pods: 3  Image: sampleapi:v2.0.0
  notificationapi      Pods: 2  Image: notificationapi:v1.2.0
  postgresql           1/1     redis                1/1

--- DEV (Java: javaapp-dev) ---
  order-service        Pods: 1  Image: order-service:main-<sha>
  inventory-service    Pods: 1  Image: inventory-service:main-<sha>
  payment-service      Pods: 1  Image: payment-service:main-<sha>
  postgresql           1/1     redis                1/1

--- SIT (Java: javaapp-sit) ---
  order-service        Pods: 2  Image: order-service:v2.0.0
  inventory-service    Pods: 2  Image: inventory-service:v1.1.0
  payment-service      Pods: 2  Image: payment-service:v1.1.0
  postgresql           1/1     redis                1/1

--- UAT (Java: javaapp-uat) ---
  order-service        Pods: 2  Image: order-service:v2.0.0
  inventory-service    Pods: 2  Image: inventory-service:v1.1.0
  payment-service      Pods: 2  Image: payment-service:v1.1.0
  postgresql           1/1     redis                1/1

--- PROD (Java: javaapp-prod) ---
  order-service        Pods: 3  Image: order-service:v2.0.0
  inventory-service    Pods: 2  Image: inventory-service:v1.1.0
  payment-service      Pods: 2  Image: payment-service:v1.1.0
  postgresql           1/1     redis                1/1
```

Notice the pattern: DEV uses `main-<sha>` tags (from T2), while SIT/UAT/PROD use semver tags (from T3). Only the services you released (sampleapi and order-service) show `v2.0.0`. The others remain at their previous versions.

### 7.2 ArgoCD: All 28 Apps

```bash
echo "=== ArgoCD Application Status ==="
argocd app list \
  --server openshift-gitops-server.openshift-gitops.svc:443 \
  --insecure --grpc-web 2>/dev/null \
  | awk '{printf "  %-35s %-10s %-10s %s\n", $1, $6, $7, $8}'
# Expected: All 28 apps showing Synced/Healthy
# (Some may show OutOfSync if auto-sync hasn't caught up yet — wait a few minutes)
```

You can also open the ArgoCD UI for a visual overview:

```
${ARGOCD_URL}
```

### 7.3 Supply Chain Evidence: Trustify SBOMs

Check that the SBOMs for both releases were uploaded to Trustify:

```bash
# Check Trustify for SBOM entries
# Trustify stores SBOMs uploaded during T2/T3 pipeline runs
echo "=== Trustify SBOM Status ==="
echo "Open the Trustify UI to verify SBOMs:"
echo "  SBOMs → search for 'sampleapi' → v2.0.0 should show ~173 components"
echo "  SBOMs → search for 'order-service' → v2.0.0 should show ~95 components"
echo ""
echo "Trustify UI: ${TRUSTIFY_URL}"
echo "(Port-forward if no external route: oc port-forward svc/trustify-server 8080:80 -n ${NS_RHTPA})"
```

### 7.4 Supply Chain Evidence: Rekor Transparency Log

Check that the image signatures were recorded in the Rekor transparency log:

```bash
echo "=== Rekor Transparency Log ==="
echo ""

# Check Rekor log size (total entries)
REKOR_SIZE=$(curl -sk ${RHTAS_REKOR_URL}/api/v1/log | jq '.treeSize')
echo "Total Rekor entries: ${REKOR_SIZE}"

# Search for sampleapi signatures in Rekor
echo ""
echo "Search for signatures at: ${RHTAS_REKOR_SEARCH_URL}"
echo "  Search by SHA256 of the sampleapi:v2.0.0 image"
echo "  Search by SHA256 of the order-service:v2.0.0 image"
echo ""

# Manual verification with cosign
echo "Manual signature verification (run these against PROD images):"
SA_PROD_IMAGE=$($OC get deploy sampleapi -n ${NS_PROD} -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null)
OS_PROD_IMAGE=$($OC get deploy order-service -n ${NS_JAVA_PROD} -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null)
echo ""
echo "  cosign verify --rekor-url=${RHTAS_REKOR_URL} ${SA_PROD_IMAGE}"
echo "  cosign verify --rekor-url=${RHTAS_REKOR_URL} ${OS_PROD_IMAGE}"
```

### 7.5 Observability: Grafana

Check that the Grafana dashboards show data for all services:

```bash
echo "=== Observability ==="
echo ""
echo "Open Grafana: ${GRAFANA_URL}"
echo ""
echo "Dashboards to check:"
echo "  1. DevSecOps Pipeline Dashboard — shows pipeline run counts, durations, success rates"
echo "  2. Application Health Dashboard — shows request rate, error rate, latency for all services"
echo "  3. Distributed Tracing — shows cross-service traces for order-service→inventory-service→payment-service"
echo ""
echo "Useful PromQL queries:"
echo '  sum(rate(http_server_requests_seconds_count[5m])) by (namespace, service)'
echo '  histogram_quantile(0.95, rate(http_server_requests_seconds_bucket[5m]))'
```

### 7.6 Health Check: All Production Services

```bash
echo "=== Production Health Check ==="
echo ""

# .NET services
echo -n "SampleApi (PROD):        "
curl -sk https://${APP_NAME}-${NS_PROD}.${APPS_DOMAIN}/healthz | jq -r '.status'
# Expected: healthy

# Java services
echo -n "order-service (PROD):    "
curl -sk https://order-service-${NS_JAVA_PROD}.${APPS_DOMAIN}/actuator/health | jq -r '.status'
# Expected: UP

# Cross-service call test: order → inventory + payment
echo ""
echo "Cross-service call test (order-service → inventory-service + payment-service):"
curl -sk https://order-service-${NS_JAVA_PROD}.${APPS_DOMAIN}/api/orders/health | jq '.'
# Expected: response showing connectivity to downstream services
```

### 7.7 Final Platform Summary

```
+====================================================================+
|                    PLATFORM SUMMARY                                 |
+====================================================================+
|                                                                     |
|  SERVICES (5 total)                                                 |
|  ├── .NET 8:  sampleapi (v2.0.0), notificationapi (unchanged)      |
|  └── Java 21: order-service (v2.0.0), inventory-service,            |
|               payment-service (both unchanged)                       |
|                                                                     |
|  PIPELINES (16 Jenkins jobs)                                        |
|  ├── T1 (MR validation):    5 jobs (1 per service)                  |
|  ├── T2 (merge → DEV):      5 jobs (1 per service)                  |
|  ├── T3 (tag → release):    5 jobs (1 per service)                  |
|  └── T4 (promotion):        1 job  (handles ALL services)           |
|                                                                     |
|  ARGOCD (28 applications)                                           |
|  ├── sampleapi-{dev,sit,uat,prod}         (4)                       |
|  ├── notificationapi-{dev,sit,uat,prod}   (4)                       |
|  ├── infra-sampleapi-{dev,sit,uat,prod}   (4)                       |
|  ├── order-service-{dev,sit,uat,prod}     (4)                       |
|  ├── inventory-service-{dev,sit,uat,prod} (4)                       |
|  ├── payment-service-{dev,sit,uat,prod}   (4)                       |
|  └── infra-javaapp-{dev,sit,uat,prod}     (4)                       |
|                                                                     |
|  SECURITY GATES (7 per T3 pipeline)                                 |
|  ├── Build, Unit Tests, SonarQube (SAST)                            |
|  ├── Dependency-Check (SCA), CycloneDX SBOM                         |
|  ├── ACS image scan (strict on T3)                                  |
|  ├── OWASP ZAP (DAST)                                               |
|  ├── k6 performance test                                            |
|  └── cosign sign + verify (RHTAS keyless)                           |
|                                                                     |
|  SUPPLY CHAIN                                                       |
|  ├── Trustify: SBOMs for every release                              |
|  ├── Rekor: signature transparency log                              |
|  └── cosign: keyless signing + SBOM attestation                     |
|                                                                     |
|  OBSERVABILITY                                                      |
|  ├── Prometheus + Grafana: metrics + dashboards                     |
|  ├── Loki + Vector: centralized logging                             |
|  ├── Tempo + OpenTelemetry: distributed tracing                     |
|  └── ACS: runtime security monitoring                               |
|                                                                     |
|  NAMESPACES (8 application + 5 platform)                            |
|  ├── sampleapi-{dev,sit,uat,prod}      (4)                          |
|  ├── javaapp-{dev,sit,uat,prod}        (4)                          |
|  ├── devsecops-tools                   (Jenkins, SonarQube, Grafana)|
|  ├── devsecops-gitlab                  (GitLab CE)                  |
|  ├── stackrox                          (ACS Central + Scanner)      |
|  ├── openshift-gitops                  (ArgoCD)                     |
|  └── trusted-artifact-signer           (RHTAS)                      |
|                                                                     |
+====================================================================+
```

---

## What Just Happened?

In 90 minutes, you drove 2 services through the complete DevSecOps lifecycle:

```
Act 1: SampleApi (.NET)
  feature/add-build-info → MR → T1 (passed) → Merge → T2 (built, signed,
  scanned, deployed to DEV)

Act 2: order-service (Java)
  feature/add-order-metadata → MR → T1 (passed) → Merge → T2 (built, signed,
  scanned, deployed to DEV)

Act 3: Release both
  v2.0.0 tags → T3 x2 (DAST, perf test, signing, SBOM attestation,
  promotion MRs created)

Act 4: Cascade to production
  SIT MR (approve) → T4 → SIT deployed → UAT MR auto-created
  UAT MR (approve) → T4 → UAT deployed → PROD MR auto-created
  PROD MR (approve) → T4 → PROD deployed → end of chain
  (repeat for both services)
```

**Total human actions**: 14 clicks across 2 services:
- 2 pushes (feature branches)
- 2 MR creates
- 2 MR merges (→ T2)
- 2 tag pushes (→ T3)
- 6 promotion MR merges (2 services x 3 environments)

**Total pipeline runs**: 16 across 2 services:
- T1: 2 (one per service)
- T2: 2 (one per service)
- T3: 2 (one per service)
- T4: 6 (2 services x 3 environments: SIT, UAT, PROD)

**Audit trail**: Every action is recorded as a GitLab MR with approver name, timestamp, and pipeline results. Every image is signed and recorded in the Rekor transparency log. Every SBOM is stored in Trustify. Every promotion has a complete scan and performance report attached.

**What did NOT happen**: NotificationApi, inventory-service, and payment-service were completely untouched. Their images, deployments, ArgoCD apps, and configurations were not modified. This is per-service independence -- the platform handles 5 services without coupling.

---

## Common Mistakes

### 1. Merging the Next Promotion MR Before T4 Completes

T4 needs time to sync ArgoCD, verify health, and create the next promotion MR. If you merge the UAT MR before T4 finishes creating it from the SIT deployment, the MR will not exist yet.

**Fix:** Always wait for the `sampleapi-promote` build to show SUCCESS in Jenkins before merging the next promotion MR. Use the polling loop shown in Act 4.

### 2. Confusing Which Namespace Set a Java Service Lives In

Java services live in `javaapp-{env}`, not `sampleapi-{env}`. The `pipelinePromote.groovy` orchestrator has a list of Java services and maps them to the correct namespace prefix:

```groovy
def javaServices = ['order-service', 'inventory-service', 'payment-service']
def nsPrefix = javaServices.contains(serviceName) ? 'javaapp' : 'sampleapi'
```

**Fix:** Always check which namespace set your service belongs to before running `oc` commands.

### 3. Expecting All 5 T3 Pipelines to Run Simultaneously

Jenkins `containerCap=2` limits concurrent agent pods. If you tag all 5 services at once, 3 will queue. Each T3 takes 10-15 minutes, so the queue could last 30+ minutes.

**Fix:** In this walkthrough, we only tagged 2 services. To release all 5, tag them and be prepared to wait for the queue to clear. The order does not matter -- each T3 is independent.

### 4. Forgetting the `production` vs `prod` Directory Mismatch

The Kustomize overlay directory is `overlays/production`, but the ArgoCD app name and namespace use `prod`. The mapping in `pipelinePromote.groovy`:

```groovy
def argoApp = envDir == 'production' ? "${service}-prod" : "${service}-${envDir}"
```

**Fix:** Never assume directory names match namespace names. The overlay uses the full word (`production`), the namespace uses the abbreviation (`prod`).

### 5. Expecting T3 to Deploy Somewhere

T3 does NOT deploy. It builds, scans, performance-tests, and signs the image. Then it creates a promotion MR. The actual deployment only happens when T4 runs after an approver merges the MR.

This is intentional -- a release image should be fully validated before any human decides where to deploy it.

### 6. Thinking Each Service Needs Its Own Promotion Job

All 5 services use the same `sampleapi-promote` Jenkins job. The name is misleading. The `pipelinePromote.groovy` orchestrator parses `services/([^/]+)/overlays/([^/]+)/` from the git diff to detect which service changed. Promoting order-service to SIT runs the same job and correctly syncs `order-service-sit`.

### 7. Not Waiting Between SIT MR Merges for Different Services

If you merge both the SampleApi SIT MR and the order-service SIT MR at the same time, both trigger T4 on the same `sampleapi-promote` job. But `disableConcurrentBuilds()` means the second one queues. Worse, the second T4 might use the wrong diff range.

**Fix:** Merge one service's SIT MR, wait for T4 to complete, then merge the other service's SIT MR. This is the pattern shown in Act 3.

### 8. Assuming SBOM Component Counts Are Comparable Between Languages

.NET SBOMs (~173 components) have nearly twice as many entries as Java SBOMs (~95 components). This does not mean .NET has more vulnerabilities. It means the NuGet dependency resolver reports transitive dependencies more granularly than Maven. Both are correct representations of the supply chain.

---

## Self-Assessment

Answer these questions to confirm you understand the complete platform:

1. **How many pipeline triggers exist, and what does each one do?**
   Four. T1 validates on MR (build, test, SAST, SCA). T2 builds and deploys to DEV on merge (T1 + SBOM + image + sign + verify + ACS + GitOps). T3 validates for release on tag (T2 + DAST + k6 + attestation + promotion MR). T4 promotes through environments on MR merge (sync ArgoCD, verify health, create next MR).

2. **How does the shared library handle both .NET and Java without separate orchestrators?**
   `pipelineConfig.configureForService(serviceName)` sets `activeLanguage`, `activeDockerfile`, `activeSourceRepo`, `imageNamespace`, and `activeBuildArgs`. The orchestrators (`pipelineMR`, `pipelineMerge`, `pipelineTag`) check `activeLanguage` and call the appropriate function (`buildDotnet` vs `buildJava`, `scanSonarQube` vs `scanSonarQubeJava`, etc.). The pipeline structure is identical; only the build/test/scan functions differ.

3. **How many T4 pipeline runs happen to get one service from SIT to PROD?**
   Three. One for SIT (detects `services/{svc}/overlays/sit`), one for UAT (detects `services/{svc}/overlays/uat`), one for PROD (detects `services/{svc}/overlays/production`). Each is triggered by merging the respective promotion MR.

4. **How does T4 know which service was promoted?**
   It diffs `gitlabBefore..gitlabAfter` (from the webhook payload) and applies regex `services/([^/]+)/overlays/([^/]+)/` to the changed files. This extracts both the service name and the environment name.

5. **Why does the SIT promotion MR show T3 scan results but the UAT MR shows SIT deployment health?**
   The `createPromotionMR()` function detects its caller. When called from T3 (`pipelineTag`), it has `results.build`, `results.sonarqube`, `results.perfTest`, etc. When called from T4 (`pipelinePromote` cascading), it has `results.syncResults` with deployment health data. The function adapts its MR description based on available data.

6. **If you release all 5 services simultaneously, how many total pipeline runs happen?**
   Minimum 40 runs: 5 T1 (skipped if you merge directly) + 5 T2 + 5 T3 + 15 T4 (5 services x 3 environments) + 5 T1 (MR builds for T3 promotion branches) = 35. Plus additional T4 runs if there are merge conflicts or retries. With `containerCap=2`, the queue time would be substantial.

7. **What prevents a rogue developer from pushing an image directly to the registry and deploying it?**
   Three defense layers: (a) ACS admission controller blocks unsigned images from deploying, (b) the image would not be in the Rekor transparency log, so `cosign verify` would fail, (c) ArgoCD auto-sync/manual-sync only deploys what is in the GitOps repo, so a manual `oc set image` would be reverted on the next sync.

8. **Where is the audit trail for a production release?**
   Five places: (a) GitLab source MR (code review), (b) GitLab promotion MRs (who approved, when, with what scan results), (c) Rekor transparency log (when the image was signed, by what identity), (d) Trustify (SBOM with vulnerability analysis), (e) Jenkins build history (full console output of every scan and test). A compliance auditor can reconstruct the entire chain from feature branch to production.

---

## Course Completion

Congratulations. You have completed the DevSecOps Tutorial Curriculum.

Here is everything you have built, module by module:

```
Module 1:  OpenShift Fundamentals     → Namespaces, RBAC, resource quotas
Module 2:  Container Builds           → Podman, Dockerfiles, multi-stage builds
Module 2B: GitLab & Registry          → GitLab CE on OpenShift, internal registry
Module 3:  Jenkins on OpenShift       → Jenkins operator, Kubernetes plugin, custom agent
Module 4:  GitOps with ArgoCD         → ArgoCD operator, App-of-Apps, auto/manual sync
Module 5:  SAST with SonarQube        → SonarQube CE, quality gates, dotnet-sonarscanner
Module 6:  Container Security (ACS)   → ACS operator, roxctl, image scanning, admission control
Module 7:  DAST with OWASP ZAP        → ZAP baseline scan, sidecar pattern, alert thresholds
Module 8:  3-Trigger Pipeline         → T1/T2/T3 Jenkinsfiles, webhook routing, shared library
Module 9:  Per-Environment Config     → Kustomize overlays, ConfigMaps, Secrets per env
Module 9B: Multi-Service Architecture → SampleApi + NotificationApi + PostgreSQL + Redis
Module 10: E2E Walkthrough (2 svc)    → Full T1→T2→T3→T4 cycle for .NET services
Module 11: Logging (LokiStack)        → Vector + Loki, LogQL queries, log aggregation
Module 12: Monitoring & Alerting      → Prometheus, ServiceMonitor, PrometheusRule, alerts
Module 13: Grafana Dashboards         → Grafana operator, dashboards, Tempo integration
Module 14: Performance Testing        → k6 load tests, pipeline quality gates, SLO thresholds
Module 15: Production Hardening       → PDB, anti-affinity, NetworkPolicy, compliance scans
Module 16: Java Microservices         → Spring Boot, Maven, Dockerfile.java, PipelineConfig routing
Module 16B: Distributed Tracing       → Tempo, OTel, cross-service traces, Grafana trace panels
Module 17: SBOM (Trustify)            → CycloneDX, RHTPA, vulnerability analysis, supply chain
Module 18: Image Signing (RHTAS)      → cosign keyless, Fulcio, Rekor, SBOM attestation
Module 20: Full 5-Service E2E         → Complete platform walkthrough (THIS MODULE)
```

### What You Built (The Numbers)

| Category | Count |
|----------|-------|
| Application services | 5 (2 .NET, 3 Java) |
| Infrastructure services | 4 (2 PostgreSQL, 2 Redis) |
| Application namespaces | 8 |
| Platform namespaces | 5 |
| GitLab repositories | 8 |
| Jenkins jobs | 16 |
| ArgoCD applications | 28 |
| SonarQube projects | 5 |
| GitLab webhooks | 16 |
| Grafana dashboards | 3+ |
| PrometheusRules | 2+ |
| NetworkPolicies | 8+ |
| Security gates per release | 7 |

### Skills You Now Have

- **Platform engineering**: Deploy and operate Jenkins, ArgoCD, SonarQube, ACS, Grafana, Loki, Tempo, RHTAS, Trustify on OpenShift
- **Pipeline design**: Build a shared library that handles multiple languages, multiple triggers, and multiple services with a single codebase
- **GitOps**: Structure a Kustomize-based GitOps repo that scales from 1 service to N services without restructuring
- **Security engineering**: Layer SAST, SCA, SBOM, container scanning, DAST, admission control, and image signing into an automated pipeline
- **Supply chain security**: Generate SBOMs, sign images with keyless cosign, verify signatures, record everything in transparency logs
- **Observability**: Correlate metrics (Prometheus), logs (Loki), and traces (Tempo) across service boundaries in Grafana
- **Performance engineering**: Define k6 load tests with SLO-based thresholds as pipeline quality gates
- **Release management**: Automate environment promotion with cascading MRs and per-service ArgoCD apps

### Where to Go Next

This platform is the foundation. Here are the natural extensions:

| Extension | What It Adds | Difficulty |
|-----------|-------------|------------|
| **AIOps** | Predictive autoscaling with Prophet/LSTM, LLM-powered troubleshooting with Granite | Advanced |
| **MLOps** | Data Science Pipelines on OpenShift AI, Model Registry, KServe serving | Advanced |
| **Service Mesh** | Istio/OpenShift Service Mesh for mTLS, traffic shaping, circuit breakers | Intermediate |
| **Multi-Cluster** | ACM (Advanced Cluster Management) for multi-cluster GitOps | Advanced |
| **Compliance as Code** | OpenSCAP, Compliance Operator, automated CIS benchmarks | Intermediate |
| **Chaos Engineering** | Kraken/Litmus for fault injection, game days | Advanced |
| **Cost Optimization** | VPA, Goldilocks, resource rightsizing from Prometheus data | Intermediate |

Each of these builds on the skills and infrastructure you already have. The platform you built is not a demo -- it is a production-grade foundation that a real enterprise team would use as the starting point for their DevSecOps practice.

---

> **Final thought:** The platform is not the goal. The goal is that a developer can push code, open an MR, and see their change running in production -- scanned, tested, signed, and verified -- without ever leaving GitLab. Every tool on this platform exists to make that path faster and safer. If the tools slow down the developer, the tools are wrong. If the tools let unsafe code through, the tools are wrong. The right answer is a path that is both fast AND safe, and that is what you built.
