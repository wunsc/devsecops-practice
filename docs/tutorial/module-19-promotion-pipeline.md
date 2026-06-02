# Module 19: The T4 Promotion Pipeline

| | |
|---|---|
| **Track** | Supply Chain & Multi-Language |
| **Duration** | ~75 minutes |
| **Difficulty** | Advanced |
| **Prerequisites** | Module 8 (3-trigger pipeline concepts), Module 16 (Java services), Modules 17-18 (supply chain stages understood) |

> **Environment variables:** Before running any commands, source the environment file:
> ```bash
> source ./env.sh
> ```
> This sets `$OC`, `$APPS_DOMAIN`, `$NS_TOOLS`, `$NS_GITOPS`, `$NS_DEV`, `$NS_SIT`, `$NS_UAT`, `$NS_PROD`, `$NS_JAVA_DEV`, `$NS_JAVA_SIT`, `$NS_JAVA_UAT`, `$NS_JAVA_PROD`, `$GITLAB_URL`, `$JENKINS_URL`, `$ARGOCD_URL`, and all other cluster-specific variables used throughout this module. See `env.sh` for the full variable list.

---

## What You'll Learn

By the end of this module you will be able to:

1. Explain why manual environment promotions do not scale for multi-service architectures and how cascading MRs solve the problem
2. Read `pipelinePromote.groovy` and explain each stage: change detection, environment sync, health verification, and cascading MR creation
3. Read `createPromotionMR.groovy` and explain how it clones the GitOps repo, updates the Kustomize overlay, creates a branch, and opens a GitLab MR with full T3 scan results
4. Walk a single service through the complete promotion chain: T3 tag → SIT MR → approve → T4 → UAT MR → approve → T4 → PROD MR → approve → T4 → production
5. Walk multiple services through concurrent independent promotion chains and verify per-service isolation
6. Identify the approval chain (Team Lead → QA Lead → CAB) and explain why MRs are the audit trail for every production change

---

## Prerequisites

Before starting this module, confirm:

```bash
# Source your environment
source ./env.sh

# You are logged in with cluster-admin
$OC whoami
# Expected: admin

# Jenkins is running with all pipeline jobs configured
$OC get pods -n $NS_TOOLS -l app=jenkins --no-headers
# Expected: jenkins-xxx   1/1   Running   ...

# All ArgoCD apps exist for sampleapi (4 envs)
argocd app list | grep sampleapi
# Expected:
# sampleapi-dev    ...  Synced  Healthy
# sampleapi-sit    ...  Synced  Healthy
# sampleapi-uat    ...  Synced  Healthy
# sampleapi-prod   ...  Synced  Healthy

# All ArgoCD apps exist for order-service (4 envs)
argocd app list | grep order-service
# Expected:
# order-service-dev    ...  Synced  Healthy
# order-service-sit    ...  Synced  Healthy
# order-service-uat    ...  Synced  Healthy
# order-service-prod   ...  Synced  Healthy

# GitLab accessible with API token
GITLAB_TOKEN=$($OC get secret gitlab-token -n $NS_TOOLS -o jsonpath='{.data.token}' | base64 -d)
curl -sk -H "PRIVATE-TOKEN: $GITLAB_TOKEN" \
  "${GITLAB_URL}/api/v4/projects/$GITLAB_PROJECT_APP_GITOPS" | python3 -c "import json,sys; print(json.load(sys.stdin)['name'])"
# Expected: app-gitops

# DEV environments have running pods
$OC get pods -n $NS_DEV -l app=sampleapi --no-headers
# Expected: sampleapi-xxx   1/1   Running   ...
$OC get pods -n $NS_JAVA_DEV -l app=order-service --no-headers
# Expected: order-service-xxx   1/1   Running   ...
```

If any of these fail, go back to the relevant module and resolve the issue before continuing.

---

## 1. Concepts: Cascading Promotion (15 min)

### The Manual Promotion Problem

In Modules 1-10, you saw how T2 deploys to DEV automatically. But what about SIT, UAT, and PROD? Without automation, every promotion is a manual operation:

1. A developer finishes testing in DEV
2. Someone edits the SIT kustomization.yaml to update the image tag
3. Someone commits and pushes the change
4. Someone syncs ArgoCD manually
5. Someone verifies the deployment
6. Someone repeats steps 2-5 for UAT
7. Someone repeats steps 2-5 for PROD

With five services and four environments, that is:

```
5 services x 3 environments (SIT, UAT, PROD) = 15 manual operations per release
```

Each operation involves editing YAML, pushing to Git, syncing ArgoCD, and verifying health. At 10 minutes per operation, a full platform release takes **2.5 hours of manual toil**. And manual operations mean manual mistakes -- wrong image tag, wrong overlay, wrong namespace, missed service.

### The Cascading Solution

The T4 Promotion Pipeline eliminates all manual operations except one: **clicking Approve and Merge on a GitLab MR**. Everything else is automated.

The insight is simple: if a service deploys healthy to SIT, it is a candidate for UAT. If it deploys healthy to UAT, it is a candidate for PROD. The pipeline detects each successful deployment and automatically creates the next promotion MR. The human only reviews and approves.

```
T3 Tag Pipeline (sampleapi v1.5.0)
  |
  | All gates pass (SAST, SCA, SBOM, ACS, DAST, k6, signing)
  |
  v
Creates SIT MR automatically
  +-- Branch: promote/sampleapi/v1.5.0-to-sit
  +-- Title: "Promote sampleapi v1.5.0 to SIT"
  +-- Description: full T3 results table (build, tests, scans, signing)
  +-- Diff: one line in services/sampleapi/overlays/sit/kustomization.yaml
  |
  | Team Lead reviews scan results in MR description
  | Team Lead clicks Approve + Merge
  |
  v
T4 fires (push to main on app-gitops)
  |
  | Detects: services/sampleapi/overlays/sit/ changed
  | ArgoCD sync: sampleapi-sit
  | Health check: curl https://sampleapi-sampleapi-sit.apps.../healthz → "healthy"
  |
  v
Creates UAT MR automatically
  +-- Branch: promote/sampleapi/v1.5.0-to-uat
  +-- Title: "Promote sampleapi v1.5.0 to UAT"
  +-- Description: SIT deployment status + original T3 results
  |
  | QA Lead reviews + Approve + Merge
  |
  v
T4 fires again
  |
  | Detects: services/sampleapi/overlays/uat/ changed
  | ArgoCD sync: sampleapi-uat
  | Health check: → "healthy"
  |
  v
Creates PROD MR automatically
  +-- Branch: promote/sampleapi/v1.5.0-to-production
  +-- Title: "Promote sampleapi v1.5.0 to PROD"
  +-- Description: UAT deployment status + original T3 results
  |
  | CAB (Change Advisory Board) reviews + Approve + Merge
  |
  v
T4 fires one last time
  |
  | Detects: services/sampleapi/overlays/production/ changed
  | ArgoCD sync: sampleapi-prod
  | Health check: → "healthy"
  | nextEnvMap[PROD] = null → end of chain
  |
  v
Done. sampleapi v1.5.0 is in production.
```

The entire promotion chain is:

```
T3 → SIT MR → approve → T4 → UAT MR → approve → T4 → PROD MR → approve → T4 → done
```

Four pipeline runs. Three human approvals. Zero manual YAML editing.

### The Approval Chain

Each environment has a designated approver. The MR enforces the approval -- you cannot merge without the right person reviewing it:

| Environment | Approver | What They Check |
|-------------|----------|-----------------|
| **SIT** | Team Lead | T3 scan results (SAST, SCA, SBOM, ACS, DAST, performance), code readiness |
| **UAT** | QA Lead | SIT deployment health, functional testing results, regression tests |
| **PROD** | CAB (Change Advisory Board) | UAT deployment health, change risk assessment, rollback plan |

> **Why this matters:** In regulated industries (banking, healthcare, government), every production change must have an auditable approval trail. "Who approved this release, when, and what information did they have?" The MR answers all three: the approver's name is on the merge event, the timestamp is on the merge event, and the scan results are in the MR description. Auditors love MRs because they are immutable records.

### Why MRs Are the Audit Trail

Consider what happens during a security audit. The auditor asks: "Show me that version 1.5.0 of sampleapi was reviewed before going to production."

With the T4 pipeline, you open three GitLab MRs:

1. **SIT MR !42**: Shows T3 scan results (SAST: OK, SCA: 0 critical, ACS: 4 critical / 5 high, DAST: 0 high, performance: p90=6.1ms). Team Lead approved on 2026-05-15 at 14:32.
2. **UAT MR !43**: Shows SIT deployment was HEALTHY with 2/2 pods running. QA Lead approved on 2026-05-16 at 09:15.
3. **PROD MR !44**: Shows UAT deployment was HEALTHY. CAB approved on 2026-05-19 at 11:00 (after change window).

Each MR contains:
- The exact image tag being promoted
- The exact diff (one line: `newTag: v1.5.0`)
- The full security scan results from T3
- The deployment health status from the previous environment
- Who approved it and when

This is a complete, tamper-proof audit trail -- stored in Git, not in someone's email or a spreadsheet.

---

## 2. Examine pipelinePromote.groovy (15 min)

The T4 pipeline is `pipelinePromote.groovy` -- 530 lines that orchestrate the entire promotion flow. Let us walk through each stage.

### 2.1 Pipeline Options

```groovy
// jenkins-shared-lib/vars/pipelinePromote.groovy (lines 24-37)
pipeline {
    agent { label 'devsecops-agent' }

    environment {
        APP_NAME = 'sampleapi'
    }

    options {
        timestamps()
        ansiColor('xterm')
        timeout(time: 15, unit: 'MINUTES')        // ← THIS IS KEY: short timeout -- sync + verify only
        disableConcurrentBuilds()                   // ← THIS IS KEY: prevents race conditions (see Known Issues)
        gitLabConnection('gitlab')                  // ← THIS IS KEY: enables GitLab plugin for commit status
    }
}
```

Notice the timeout is 15 minutes -- T4 is lightweight. It does not build, test, or scan anything. It only syncs ArgoCD and verifies health. Compare this to T3 which has a 90-minute timeout for DAST and performance testing.

The `disableConcurrentBuilds()` option is both a safety mechanism and a limitation. It prevents two promotion syncs from running simultaneously (which could cause ArgoCD conflicts), but it also means rapid-fire webhook events from merging multiple MRs in quick succession will be dropped. We will revisit this in the Known Issues section.

### 2.2 Stage: Initialize

```groovy
// jenkins-shared-lib/vars/pipelinePromote.groovy (lines 41-61)
stage('Initialize') {
    steps {
        script {
            pipelineConfig.initFromEnv(env)
            echo "=== T4: GitOps Promotion Pipeline ==="

            // Capture who triggered -- from GitLab webhook payload
            // ← THIS IS KEY: the webhook sends the GitLab username
            //    of whoever merged the MR
            def triggeredBy = env.gitlabUserName ?: env.gitlabUserEmail ?: 'webhook'
            def mergeCommit = env.gitlabAfter ?: env.GIT_COMMIT ?: 'unknown'
            results.triggeredBy = triggeredBy
            results.mergeCommit = mergeCommit

            // Report running status to GitLab commit
            // ← THIS IS KEY: uses the GitLab Commit Status API directly
            //    because the GitLab plugin doesn't reliably match
            //    cross-repo webhooks (app-gitops != app-source)
            setGitLabPipelineStatus('running', mergeCommit, pipelineConfig)
        }
    }
}
```

The `triggeredBy` field captures the GitLab username of whoever merged the MR. This is the audit trail -- you can trace every production deployment back to the person who approved it.

### 2.3 Stage: Detect Changes

This is the core intelligence of T4. It reads the git diff to determine which service and which environment changed:

```groovy
// jenkins-shared-lib/vars/pipelinePromote.groovy (lines 84-168)
stage('Detect Changes') {
    steps {
        script {
            // ← THIS IS KEY: Use gitlabBefore..gitlabAfter from webhook payload
            //    when available. This correctly handles rapid sequential merges.
            //    Fallback to HEAD~1..HEAD for manual triggers.
            def diffFrom = env.gitlabBefore ?: ''
            def diffTo = env.gitlabAfter ?: 'HEAD'
            def diffRange = (diffFrom && diffFrom != '0000000000000000000000000000000000000000')
                ? "${diffFrom}..${diffTo}"
                : 'HEAD~1..HEAD'

            def changedFiles = sh(
                script: "git diff --name-only ${diffRange} 2>/dev/null || echo ''",
                returnStdout: true
            ).trim()

            // ← THIS IS KEY: regex extracts service name and environment
            //    from the GitOps directory structure
            //    services/{service}/overlays/{env}/
            changedFiles.split('\n').each { file ->
                def match = (file =~ /^services\/([^\/]+)\/overlays\/([^\/]+)\//)
                if (match) {
                    def service = match[0][1]   // e.g., "sampleapi"
                    def envDir = match[0][2]    // e.g., "sit"

                    // ← THIS IS KEY: skip DEV -- auto-synced by ArgoCD, T4 doesn't manage it
                    if (envDir == 'dev') return

                    // 'production' overlay → 'PROD' label
                    def envLabel = envDir == 'production' ? 'PROD' : envDir.toUpperCase()
                    // ArgoCD app name: {service}-{env} (production → prod)
                    def argoApp = envDir == 'production'
                        ? "${service}-prod"
                        : "${service}-${envDir}"

                    envsToSync.add([
                        service: service,
                        app: argoApp,           // e.g., "sampleapi-sit"
                        env: envLabel,           // e.g., "SIT"
                        envDir: envDir,          // e.g., "sit"
                        approver: approverMap[envLabel] ?: 'Team'
                    ])
                }
            }
        }
    }
}
```

The regex `/^services\/([^\/]+)\/overlays\/([^\/]+)\//` is the key to multi-service isolation. It extracts two capture groups:

1. **Service name** (`[^\/]+` after `services/`): `sampleapi`, `order-service`, `inventory-service`, etc.
2. **Environment** (`[^\/]+` after `overlays/`): `dev`, `sit`, `uat`, `production`

This means a single T4 pipeline handles all services. When someone merges a SIT promotion MR for `sampleapi`, the diff contains `services/sampleapi/overlays/sit/kustomization.yaml`. The regex extracts `service=sampleapi` and `env=sit`. Only `sampleapi-sit` gets synced. Other services are untouched.

> **Why this matters:** Without per-service regex detection, a single T4 pipeline would either need one job per service (doubling the job count) or would blindly sync all environments for all services on every merge. The regex approach is both scalable (add a new service = add a new overlay directory, no pipeline changes) and safe (promotes only what changed).

After detecting which overlays changed, the stage also extracts the image tag from the diff:

```groovy
// ← THIS IS KEY: extract the new image tag from the kustomization.yaml diff
envsToSync.each { envMeta ->
    def kustomFile = "services/${envMeta.service}/overlays/${envMeta.envDir}/kustomization.yaml"
    // Get the newTag line that was ADDED (not removed) in this commit
    def tag = sh(
        script: "git diff ${diffRange} -- ${kustomFile} | grep '^+.*newTag:' | head -1 | awk '{print \$NF}' || echo ''",
        returnStdout: true
    ).trim()
    envMeta.imageTag = tag
}
```

The `grep '^+.*newTag:'` pattern matches only lines that were added (prefixed with `+` in the diff), not removed (prefixed with `-`). This gives us the new tag value, not the old one.

### 2.4 Stage: Sync Environments

```groovy
// jenkins-shared-lib/vars/pipelinePromote.groovy (lines 171-209)
stage('Sync Environments') {
    steps {
        script {
            // ← THIS IS KEY: sync in promotion order, not alphabetical
            def syncOrder = ['SIT', 'UAT', 'PROD']
            def orderedEnvs = syncOrder.collectMany { envName ->
                results.envsToSync.findAll { it.env == envName }
            }

            orderedEnvs.each { envMeta ->
                echo "=== Syncing ${envMeta.env} ==="

                // ← THIS IS KEY: calls deployToEnvironment.groovy
                //    which does: argocd login → argocd app sync → argocd app wait
                def deployResult = deployToEnvironment(
                    app: envMeta.app,                    // e.g., "sampleapi-sit"
                    argocdServer: pipelineConfig.argocdServer
                )

                envMeta.deployResult = deployResult

                if (deployResult.status == 'SUCCESS') {
                    echo "${envMeta.env}: Sync=${deployResult.syncStatus}, Health=${deployResult.healthStatus}"
                } else {
                    echo "WARNING: ${envMeta.env} deployment returned: ${deployResult.status}"
                    // ← THIS IS KEY: don't fail the whole pipeline on one env failure
                    //    report per-env status and continue
                }
            }
        }
    }
}
```

The sequential sync order (`SIT → UAT → PROD`) matters because in theory a single merge could change multiple overlays (though in practice our T4 chain only changes one at a time). If someone manually edited both SIT and UAT overlays in one commit, we want SIT to deploy first.

The `deployToEnvironment()` function (in `deployToEnvironment.groovy`) does three things:

1. **`argocd login`** -- authenticates with the ArgoCD server using admin credentials
2. **`argocd app sync {app} --force --prune`** -- forces a sync of the specific ArgoCD application, pruning any resources that are no longer in the manifest
3. **`argocd app wait {app} --health`** -- waits for the application to report Healthy status (all pods Running and Ready)

### 2.5 Stage: Post-Deploy Verification

After ArgoCD reports healthy, T4 performs its own independent health verification:

```groovy
// jenkins-shared-lib/vars/pipelinePromote.groovy (lines 212-277)
stage('Post-Deploy Verification') {
    steps {
        script {
            results.syncResults.each { envMeta ->
                def serviceName = envMeta.service ?: pipelineConfig.appName

                // ← THIS IS KEY: Java services live in javaapp-{env}
                //    .NET services live in sampleapi-{env}
                def javaServices = ['order-service', 'inventory-service', 'payment-service']
                def nsPrefix = javaServices.contains(serviceName) ? 'javaapp' : 'sampleapi'
                def ns = "${nsPrefix}-${envMeta.env.toLowerCase()}"
                if (envMeta.env == 'PROD') {
                    ns = "${nsPrefix}-prod"
                }

                // Check pod readiness for the specific service
                def readyPods = sh(
                    script: "oc get pods -n ${ns} -l app=${serviceName} --no-headers | grep Running | grep '1/1' | wc -l",
                    returnStdout: true
                ).trim()

                // Get the route and health check
                def route = sh(
                    script: "oc get route ${serviceName} -n ${ns} -o go-template='{{.spec.host}}' 2>/dev/null || echo ''",
                    returnStdout: true
                ).trim()

                def healthStatus = 'UNKNOWN'
                if (route) {
                    // ← THIS IS KEY: dual health check path
                    //    Java uses /actuator/health → {"status":"UP"}
                    //    .NET uses /healthz → {"status":"healthy"}
                    def healthPath = javaServices.contains(serviceName) ? '/actuator/health' : '/healthz'
                    def healthResponse = sh(
                        script: "curl -sk --max-time 10 https://${route}${healthPath}",
                        returnStdout: true
                    ).trim()
                    healthStatus = (healthResponse.contains('"healthy"') || healthResponse.contains('"UP"'))
                        ? 'HEALTHY' : 'UNHEALTHY'
                }

                // ← THIS IS KEY: fallback to pod readiness when no route exists
                //    (e.g., notificationapi has no Route, only ClusterIP)
                if (healthStatus == 'UNKNOWN' && readyPods.toInteger() > 0) {
                    echo "  No route found -- using pod readiness as health indicator"
                    healthStatus = 'HEALTHY'
                }

                envMeta.healthStatus = healthStatus
            }
        }
    }
}
```

The dual health check is necessary because the platform supports two application stacks:

```
.NET services  →  /healthz  →  {"status": "healthy"}
Java services  →  /actuator/health  →  {"status": "UP"}
```

The fallback to pod readiness handles services like NotificationApi that have no external Route (they are internal-only services accessed via ClusterIP). If there is no route to curl but the pods are Running and Ready (1/1), the pipeline considers the service healthy.

### 2.6 Stage: Create Next Promotion MR

This is where the cascade happens:

```groovy
// jenkins-shared-lib/vars/pipelinePromote.groovy (lines 282-347)
stage('Create Next Promotion MR') {
    steps {
        script {
            // ← THIS IS KEY: the promotion chain map
            //    SIT (healthy) → create UAT MR
            //    UAT (healthy) → create PROD MR
            //    PROD → null (end of chain)
            def nextEnvMap = [
                'SIT':  [targetEnv: 'uat',        overlayDir: 'uat'],
                'UAT':  [targetEnv: 'production', overlayDir: 'production'],
                'PROD': null  // ← THIS IS KEY: end of chain, no next MR
            ]

            results.syncResults.each { envMeta ->
                def nextEnv = nextEnvMap[envMeta.env]

                // End of chain check
                if (!nextEnv) {
                    echo "${envMeta.env}: End of promotion chain -- no next MR needed."
                    return
                }

                // ← THIS IS KEY: only cascade if deployment is HEALTHY
                //    If SIT deployment is UNHEALTHY, do NOT create UAT MR
                //    The chain stops here until someone investigates
                if (envMeta.healthStatus != 'HEALTHY') {
                    echo "${envMeta.env}: Deployment NOT healthy -- skipping next promotion MR."
                    return
                }

                // ← THIS IS KEY: must have an image tag to promote
                if (!imageTag || imageTag == 'unknown') {
                    echo "${envMeta.env}: No image tag found -- skipping next promotion MR."
                    return
                }

                // Configure for the specific service
                pipelineConfig.configureForService(serviceName)

                def mrResult = createPromotionMR(
                    imageTag: imageTag,
                    targetEnv: nextEnv.overlayDir,   // 'uat' or 'production'
                    results: results,
                    pipelineConfig: pipelineConfig,
                    gitopsRepo: pipelineConfig.gitopsRepo,
                    gitlabUrl: pipelineConfig.gitlabUrl
                )

                if (mrResult.status == 'SUCCESS') {
                    echo "${serviceName} ${targetLabel} promotion MR !${mrResult.mrIid} created"
                }
            }
        }
    }
}
```

Three conditions must be met for the cascade to continue:

1. **Not end of chain**: `nextEnvMap[PROD]` is `null`, so PROD deployments never create further MRs
2. **Deployment is healthy**: If the health check failed, the chain stops. No unhealthy service gets promoted further.
3. **Image tag is known**: The pipeline must know which tag to promote. If it could not extract the tag from the diff, it stops.

This is a **safety-first design**. The chain breaks on any failure, requiring human intervention to investigate and restart the promotion.

> **Why this matters:** Without the health gate, a broken deployment would cascade forward. Imagine SIT has a crashlooping pod -- without the health check, T4 would still create the UAT MR. If someone approved it without checking SIT first, the same broken image would deploy to UAT. The health gate prevents this: the chain stops at the first unhealthy environment.

---

## 3. Examine createPromotionMR.groovy (10 min)

The `createPromotionMR.groovy` function (368 lines) is called in two places:

1. **From T3 (pipelineTag.groovy)**: Creates the first SIT MR with full T3 scan results
2. **From T4 (pipelinePromote.groovy)**: Creates the cascading UAT and PROD MRs with deployment health status

### 3.1 Branch Naming Convention

```groovy
// jenkins-shared-lib/vars/createPromotionMR.groovy (lines 56-57)
// ← THIS IS KEY: include service name to avoid branch collisions
//    when promoting multiple services simultaneously
def branchName = "promote/${activeImage}/${imageTag}-to-${targetEnv}"
def mrTitle = "Promote ${activeImage} ${imageTag} to ${envLabel}"
```

Examples:
- `promote/sampleapi/v1.5.0-to-sit` -- sampleapi promoted to SIT
- `promote/order-service/v2.1.0-to-uat` -- order-service promoted to UAT
- `promote/sampleapi/v1.5.0-to-production` -- sampleapi promoted to PROD

The service name in the branch path prevents collisions. Without it, promoting `sampleapi v1.5.0` and `order-service v1.5.0` to the same environment would create branches with the same name.

### 3.2 Kustomize Overlay Update

```groovy
// jenkins-shared-lib/vars/createPromotionMR.groovy (lines 77-88)
// ← THIS IS KEY: update the per-service overlay only
//    Structure: services/{service}/overlays/{env}/kustomization.yaml
def overlayDir = "services/${activeImage}/overlays/${targetEnv}"

dir(overlayDir) {
    // ← THIS IS KEY: kustomize edit set image updates only the
    //    named image in the kustomization.yaml
    def fullImageRef = "${pipelineConfig.imageRegistry}/${pipelineConfig.imageNamespace}/${activeImage}:${imageTag}"
    sh "kustomize edit set image ${activeImage}=${fullImageRef}"
    echo "Updated ${overlayDir}/kustomization.yaml -> ${activeImage}=${fullImageRef}"
}
```

The `kustomize edit set image` command modifies the `images` section of `kustomization.yaml`:

```yaml
# Before: services/sampleapi/overlays/sit/kustomization.yaml
images:
  - name: sampleapi
    newName: image-registry.openshift-image-registry.svc:5000/sampleapi-dev/sampleapi
    newTag: v1.4.0

# After: kustomize edit set image sampleapi=...registry.../sampleapi:v1.5.0
images:
  - name: sampleapi
    newName: image-registry.openshift-image-registry.svc:5000/sampleapi-dev/sampleapi
    newTag: v1.5.0     # ← THIS IS KEY: only this line changes
```

This is a surgical change -- one line in one file for one service in one environment. The MR diff will show exactly this.

### 3.3 MR Description With T3 Results

When called from T3, the MR description includes the full scan results table:

```groovy
// jenkins-shared-lib/vars/createPromotionMR.groovy (lines 193-368)
// ← THIS IS KEY: detect whether this is a T3 call or T4 cascading call
def isFromTagPipeline = results.containsKey('build') || results.containsKey('sonarqube')
def isFromPromotePipeline = results.containsKey('syncResults')
```

The function builds a markdown table that renders beautifully in GitLab:

```markdown
## Release v1.5.0 -- Promotion to SIT

This MR was automatically created by the **T3 Tag Pipeline** after all quality
and security gates passed.

**Action required:** Review the results below, then **Approve** and **Merge** to deploy.

---

### T3 Pipeline Results ([Build #47](https://jenkins.../job/47/))

| Stage | Status | Details |
|-------|--------|--------|
| .NET Build | ✅ SUCCESS | 22.8s |
| Unit Tests | ✅ SUCCESS | Coverage: **21.92%** (14.3s) |
| SonarQube (SAST) | ✅ SUCCESS | Gate: **OK** [Dashboard](...) |
| Dependency Check (SCA) | ✅ SUCCESS | **0** findings (18.2s) |
| Container Image | ✅ SUCCESS | 35.1s |
| Push to Registry | ✅ SUCCESS | v1.5.0 |
| ACS Image Scan (Strict) | ❌ FAILURE | Critical: **4** / High: **5** |
| OWASP ZAP (DAST) | ✅ SUCCESS | High: **0** / Med: **0** / Low: **5** / Info: **12** |
| Performance Test (k6) | ✅ SUCCESS | p90=6.1ms, error_rate=0.00 |
| Image Signing (Cosign) | ✅ SUCCESS | Keyless (RHTAS) | Attestation: SUCCESS |
| Image Verification | ✅ SUCCESS | Signature: **Valid** / Attestation: **Valid** |
| SBOM (CycloneDX) | ✅ SUCCESS | **173** components, Upload: SUCCESS |

---

### Changes

- Updates `services/sampleapi/overlays/sit/kustomization.yaml` image tag to `v1.5.0`
- After merge, the **T4 Promotion Pipeline** will automatically:
  1. Detect the SIT overlay change
  2. Sync ArgoCD application `sampleapi-sit`
  3. Verify deployment health
  4. Post results back as a commit comment
  5. If healthy, auto-create **UAT** promotion MR
```

When called from T4 (cascading), the description changes to show the previous environment's deployment health instead of scan results:

```markdown
## Release v1.5.0 -- Promotion to UAT

This MR was automatically created after **SIT** deployment was verified **HEALTHY**.

Cascading promotion: the same image that passed all T3 gates and deployed
successfully to SIT.

**Action required:** Review the results below, then **Approve** and **Merge** to deploy.

---

### Previous Environment Deployment ([Build #12](https://jenkins.../job/12/))

| Stage | Status | Details |
|-------|--------|--------|
| SIT Deployment | ✅ HEALTHY | Pods: 2 / Image: `sampleapi:v1.5.0` |
```

### 3.4 Commit Status on MR Source Branch

```groovy
// jenkins-shared-lib/vars/createPromotionMR.groovy (lines 147-167)
// ← THIS IS KEY: post commit status on the SOURCE branch, not main
//    This makes the pipeline status appear in GitLab's MR Pipelines tab
if (sourceBranchSha) {
    def statusResponse = sh(
        script: """
            curl -sfk -X POST \
                -H "PRIVATE-TOKEN: \${GITLAB_TOKEN}" \
                "${gitlabUrl}/api/v4/projects/${gitopsProjectId}/statuses/${sourceBranchSha}" \
                -d "state=success" \
                -d "name=jenkins/quality-gates" \
                -d "ref=${branchName}" \
                -d "target_url=${env.BUILD_URL ?: ''}" \
                -d "description=All quality gates passed -- ready for promotion" \
                -o /dev/null -w "%{http_code}" 2>/dev/null || echo "000"
        """,
        returnStdout: true
    ).trim()
}
```

This is a subtle but important detail. GitLab shows pipeline status on MRs, but it only matches pipelines that are linked to the MR's source branch HEAD commit. By posting a commit status with `name=jenkins/quality-gates` on the source branch SHA, the reviewer sees a green checkmark on the MR page without needing to leave GitLab to check Jenkins.

> **Why this matters:** The reviewer should have everything they need to make a promotion decision on the MR page itself: the scan results table in the description, the green pipeline status in the sidebar, and the one-line diff showing the image tag change. No context-switching to Jenkins, ArgoCD, or any other tool.

---

## 4. Walk Through a Single-Service Promotion (20 min)

Now let us see the complete chain in action. We will tag `sampleapi`, watch T3 create the SIT MR, and follow the promotion all the way to production.

### 4.1 Trigger T3 by Pushing a Tag

First, confirm the current state:

```bash
# Check what version is currently deployed in each environment
for ENV in dev sit uat prod; do
    NS="sampleapi-${ENV}"
    TAG=$($OC get deploy sampleapi -n $NS -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null | awk -F: '{print $NF}')
    echo "${ENV}: ${TAG}"
done
# Expected output (your tags will differ):
# dev: main-abc1234
# sit: v1.4.0
# uat: v1.4.0
# prod: v1.4.0
```

Now push a new tag on the sampleapi source repo. Open the GitLab UI or use the API:

```bash
# Get the latest commit SHA on main
GITLAB_TOKEN=$($OC get secret gitlab-token -n $NS_TOOLS -o jsonpath='{.data.token}' | base64 -d)

LATEST_SHA=$(curl -sk -H "PRIVATE-TOKEN: $GITLAB_TOKEN" \
  "${GITLAB_URL}/api/v4/projects/${GITLAB_PROJECT_APP_SOURCE}/repository/branches/main" \
  | python3 -c "import json,sys; print(json.load(sys.stdin)['commit']['id'])")
echo "Latest main commit: $LATEST_SHA"

# Create a new tag (increment from your current latest)
NEW_TAG="v1.5.0"  # Adjust based on your current latest tag
curl -sk -X POST -H "PRIVATE-TOKEN: $GITLAB_TOKEN" \
  "${GITLAB_URL}/api/v4/projects/${GITLAB_PROJECT_APP_SOURCE}/repository/tags" \
  -d "tag_name=${NEW_TAG}" \
  -d "ref=${LATEST_SHA}" \
  -d "message=Release ${NEW_TAG}" | python3 -m json.tool | head -5
# Expected: JSON with "name": "v1.5.0"
```

### 4.2 Watch T3 Execute

Open Jenkins in your browser:

```bash
echo "Jenkins URL: ${JENKINS_URL}/job/sampleapi-tag/"
```

The T3 pipeline runs all gates: build, unit tests, SAST, SCA, SBOM, container build, push, ACS scan (strict), DAST, performance test, image signing, image verification.

```bash
# Wait for T3 to start (webhook fires within seconds)
# Poll Jenkins for the latest build
sleep 10
curl -sk "${JENKINS_URL}/job/sampleapi-tag/lastBuild/api/json" \
  | python3 -c "import json,sys; d=json.load(sys.stdin); print(f'Build #{d[\"number\"]}: {d[\"result\"] or \"IN PROGRESS\"}')"
# Expected: Build #N: IN PROGRESS (or SUCCESS after ~20 minutes)
```

Wait for T3 to complete. This takes approximately 20-25 minutes because it runs DAST (OWASP ZAP) and performance testing (k6).

### 4.3 Verify T3 Created the SIT MR

Once T3 completes successfully, it calls `createPromotionMR()` to create a SIT promotion MR in the app-gitops repo:

```bash
# List open MRs in app-gitops
curl -sk -H "PRIVATE-TOKEN: $GITLAB_TOKEN" \
  "${GITLAB_URL}/api/v4/projects/${GITLAB_PROJECT_APP_GITOPS}/merge_requests?state=opened" \
  | python3 -c "
import json, sys
mrs = json.load(sys.stdin)
for mr in mrs:
    print(f'  MR !{mr[\"iid\"]}: {mr[\"title\"]}')
    print(f'    Branch: {mr[\"source_branch\"]}')
    print(f'    Created: {mr[\"created_at\"]}')
"
# Expected output:
#   MR !42: Promote sampleapi v1.5.0 to SIT
#     Branch: promote/sampleapi/v1.5.0-to-sit
#     Created: 2026-05-29T14:35:12Z
```

### 4.4 Inspect the MR in GitLab

Open the MR in your browser:

```bash
echo "SIT MR URL: ${GITLAB_URL}/${APP_GROUP}/app-gitops/-/merge_requests"
```

On the MR page, check three things:

1. **Description**: The full T3 results table with all security scan results, performance metrics, and signing status
2. **Changes tab**: A single file changed -- `services/sampleapi/overlays/sit/kustomization.yaml` with one line: `newTag: v1.5.0`
3. **Pipeline status**: Green checkmark from `jenkins/quality-gates` commit status

You can also verify the diff via API:

```bash
# Get the MR IID (replace with actual)
MR_IID=42  # Use the actual IID from the previous command

# View the MR diff
curl -sk -H "PRIVATE-TOKEN: $GITLAB_TOKEN" \
  "${GITLAB_URL}/api/v4/projects/${GITLAB_PROJECT_APP_GITOPS}/merge_requests/${MR_IID}/changes" \
  | python3 -c "
import json, sys
data = json.load(sys.stdin)
for change in data.get('changes', []):
    print(f'File: {change[\"new_path\"]}')
    print(change['diff'])
" 2>/dev/null
# Expected:
# File: services/sampleapi/overlays/sit/kustomization.yaml
# @@ -X,Y +X,Y @@
# -    newTag: v1.4.0
# +    newTag: v1.5.0
```

### 4.5 Approve and Merge the SIT MR

In a real workflow, the Team Lead reviews the scan results and approves. For this tutorial, merge via API:

```bash
# Merge the SIT MR (simulates Team Lead approval)
curl -sk -X PUT -H "PRIVATE-TOKEN: $GITLAB_TOKEN" \
  "${GITLAB_URL}/api/v4/projects/${GITLAB_PROJECT_APP_GITOPS}/merge_requests/${MR_IID}/merge" \
  | python3 -c "import json,sys; d=json.load(sys.stdin); print(f'Merged: {d.get(\"state\", \"error\")}  SHA: {d.get(\"merge_commit_sha\", \"?\")[:8]}')"
# Expected: Merged: merged  SHA: a1b2c3d4
```

### 4.6 Watch T4 Fire for SIT

The merge to main on app-gitops fires the webhook, which triggers the T4 (promote) pipeline:

```bash
# Wait for T4 to start
sleep 10
echo "T4 Pipeline: ${JENKINS_URL}/job/sampleapi-promote/"

# Check latest build
curl -sk "${JENKINS_URL}/job/sampleapi-promote/lastBuild/api/json" \
  | python3 -c "import json,sys; d=json.load(sys.stdin); print(f'Build #{d[\"number\"]}: {d[\"result\"] or \"IN PROGRESS\"}')"
# Expected: Build #N: IN PROGRESS
```

T4 runs fast -- typically 2-5 minutes. It detects that `services/sampleapi/overlays/sit/` changed, syncs `sampleapi-sit` via ArgoCD, verifies health, and creates the UAT MR.

Wait for it to complete:

```bash
# Poll until complete (T4 is fast, ~2-5 min)
for i in $(seq 1 30); do
    STATUS=$(curl -sk "${JENKINS_URL}/job/sampleapi-promote/lastBuild/api/json" \
      | python3 -c "import json,sys; print(json.load(sys.stdin).get('result', 'null'))" 2>/dev/null)
    if [ "$STATUS" != "null" ] && [ "$STATUS" != "" ]; then
        echo "T4 completed: $STATUS"
        break
    fi
    echo "  Waiting... (attempt $i/30)"
    sleep 10
done
# Expected: T4 completed: SUCCESS
```

### 4.7 Verify SIT Deployment

```bash
# Check pods in SIT
$OC get pods -n $NS_SIT -l app=sampleapi
# Expected:
# NAME                         READY   STATUS    RESTARTS   AGE
# sampleapi-7d8f9c6b4-k2m3n   1/1     Running   0          2m
# sampleapi-7d8f9c6b4-p5q6r   1/1     Running   0          2m

# Verify the image tag
$OC get deploy sampleapi -n $NS_SIT -o jsonpath='{.spec.template.spec.containers[0].image}' | awk -F: '{print $NF}'
# Expected: v1.5.0

# Health check
curl -sk https://sampleapi-${NS_SIT}.${APPS_DOMAIN}/healthz
# Expected: {"status":"healthy"}

# ArgoCD status
argocd app get sampleapi-sit --grpc-web | head -5
# Expected:
# Name:               openshift-gitops/sampleapi-sit
# Server:             https://kubernetes.default.svc
# Sync Status:        Synced
# Health Status:      Healthy
```

### 4.8 Verify UAT MR Was Auto-Created

Because SIT deployed healthy, T4 should have automatically created a UAT promotion MR:

```bash
# Check for new open MRs
curl -sk -H "PRIVATE-TOKEN: $GITLAB_TOKEN" \
  "${GITLAB_URL}/api/v4/projects/${GITLAB_PROJECT_APP_GITOPS}/merge_requests?state=opened" \
  | python3 -c "
import json, sys
mrs = json.load(sys.stdin)
for mr in mrs:
    print(f'  MR !{mr[\"iid\"]}: {mr[\"title\"]}')
    print(f'    Branch: {mr[\"source_branch\"]}')
"
# Expected:
#   MR !43: Promote sampleapi v1.5.0 to UAT
#     Branch: promote/sampleapi/v1.5.0-to-uat
```

### 4.9 Continue the Chain: UAT

Approve and merge the UAT MR:

```bash
# Get the UAT MR IID
UAT_MR_IID=$(curl -sk -H "PRIVATE-TOKEN: $GITLAB_TOKEN" \
  "${GITLAB_URL}/api/v4/projects/${GITLAB_PROJECT_APP_GITOPS}/merge_requests?state=opened" \
  | python3 -c "
import json, sys
mrs = json.load(sys.stdin)
for mr in mrs:
    if 'UAT' in mr['title'] and 'sampleapi' in mr['title']:
        print(mr['iid'])
        break
")
echo "UAT MR: !${UAT_MR_IID}"

# Merge (simulates QA Lead approval)
curl -sk -X PUT -H "PRIVATE-TOKEN: $GITLAB_TOKEN" \
  "${GITLAB_URL}/api/v4/projects/${GITLAB_PROJECT_APP_GITOPS}/merge_requests/${UAT_MR_IID}/merge" \
  | python3 -c "import json,sys; print(f'Merged: {json.load(sys.stdin).get(\"state\", \"error\")}')"
# Expected: Merged: merged
```

Wait for T4 to fire again, sync UAT, and create the PROD MR:

```bash
# Wait for T4 to complete
sleep 30
for i in $(seq 1 20); do
    STATUS=$(curl -sk "${JENKINS_URL}/job/sampleapi-promote/lastBuild/api/json" \
      | python3 -c "import json,sys; print(json.load(sys.stdin).get('result', 'null'))" 2>/dev/null)
    if [ "$STATUS" != "null" ] && [ "$STATUS" != "" ]; then
        echo "T4 completed: $STATUS"
        break
    fi
    sleep 10
done

# Verify UAT deployment
$OC get deploy sampleapi -n $NS_UAT -o jsonpath='{.spec.template.spec.containers[0].image}' | awk -F: '{print $NF}'
# Expected: v1.5.0

curl -sk https://sampleapi-${NS_UAT}.${APPS_DOMAIN}/healthz
# Expected: {"status":"healthy"}

# Check for PROD MR
curl -sk -H "PRIVATE-TOKEN: $GITLAB_TOKEN" \
  "${GITLAB_URL}/api/v4/projects/${GITLAB_PROJECT_APP_GITOPS}/merge_requests?state=opened" \
  | python3 -c "
import json, sys
for mr in json.load(sys.stdin):
    if 'PROD' in mr['title'] and 'sampleapi' in mr['title']:
        print(f'  MR !{mr[\"iid\"]}: {mr[\"title\"]}')
"
# Expected:
#   MR !44: Promote sampleapi v1.5.0 to PROD
```

### 4.10 Final Step: PROD

```bash
# Get the PROD MR IID
PROD_MR_IID=$(curl -sk -H "PRIVATE-TOKEN: $GITLAB_TOKEN" \
  "${GITLAB_URL}/api/v4/projects/${GITLAB_PROJECT_APP_GITOPS}/merge_requests?state=opened" \
  | python3 -c "
import json, sys
for mr in json.load(sys.stdin):
    if 'PROD' in mr['title'] and 'sampleapi' in mr['title']:
        print(mr['iid'])
        break
")
echo "PROD MR: !${PROD_MR_IID}"

# Merge (simulates CAB approval)
curl -sk -X PUT -H "PRIVATE-TOKEN: $GITLAB_TOKEN" \
  "${GITLAB_URL}/api/v4/projects/${GITLAB_PROJECT_APP_GITOPS}/merge_requests/${PROD_MR_IID}/merge" \
  | python3 -c "import json,sys; print(f'Merged: {json.load(sys.stdin).get(\"state\", \"error\")}')"
# Expected: Merged: merged

# Wait for T4 to sync PROD
sleep 30
for i in $(seq 1 20); do
    STATUS=$(curl -sk "${JENKINS_URL}/job/sampleapi-promote/lastBuild/api/json" \
      | python3 -c "import json,sys; print(json.load(sys.stdin).get('result', 'null'))" 2>/dev/null)
    if [ "$STATUS" != "null" ] && [ "$STATUS" != "" ]; then
        echo "T4 completed: $STATUS"
        break
    fi
    sleep 10
done
# Expected: T4 completed: SUCCESS
```

### 4.11 Verify: All Environments Running v1.5.0

```bash
# Check all environments
echo "=== Post-Promotion Status ==="
for ENV in dev sit uat prod; do
    NS="sampleapi-${ENV}"
    TAG=$($OC get deploy sampleapi -n $NS -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null | awk -F: '{print $NF}')
    PODS=$($OC get pods -n $NS -l app=sampleapi --no-headers 2>/dev/null | grep Running | grep '1/1' | wc -l)
    HEALTH=$(curl -sk --max-time 5 https://sampleapi-${NS}.${APPS_DOMAIN}/healthz 2>/dev/null | python3 -c "import json,sys; print(json.load(sys.stdin).get('status','?'))" 2>/dev/null || echo "no-route")
    echo "  ${ENV}: tag=${TAG}  pods=${PODS}  health=${HEALTH}"
done
# Expected:
#   dev: tag=main-abc1234  pods=1  health=healthy
#   sit: tag=v1.5.0  pods=2  health=healthy
#   uat: tag=v1.5.0  pods=2  health=healthy
#   prod: tag=v1.5.0  pods=3  health=healthy
```

Notice that DEV still has the `main-xxx` tag from T2. SIT, UAT, and PROD all have `v1.5.0` from the promotion chain. The pod counts differ per environment (1 in DEV, 2 in SIT/UAT, 3 in PROD) because each overlay has different replica counts.

### 4.12 Verify: ArgoCD Shows All Synced

```bash
# ArgoCD status for all sampleapi environments
for ENV in dev sit uat prod; do
    STATUS=$(argocd app get sampleapi-${ENV} --grpc-web -o json 2>/dev/null \
      | python3 -c "import json,sys; d=json.load(sys.stdin); print(f'{d[\"status\"][\"sync\"][\"status\"]} / {d[\"status\"][\"health\"][\"status\"]}')" 2>/dev/null || echo "?")
    echo "  sampleapi-${ENV}: ${STATUS}"
done
# Expected:
#   sampleapi-dev: Synced / Healthy
#   sampleapi-sit: Synced / Healthy
#   sampleapi-uat: Synced / Healthy
#   sampleapi-prod: Synced / Healthy
```

### 4.13 Verify: Audit Trail in GitLab

```bash
# List all MERGED MRs in app-gitops for this release
curl -sk -H "PRIVATE-TOKEN: $GITLAB_TOKEN" \
  "${GITLAB_URL}/api/v4/projects/${GITLAB_PROJECT_APP_GITOPS}/merge_requests?state=merged&search=v1.5.0" \
  | python3 -c "
import json, sys
mrs = json.load(sys.stdin)
for mr in mrs:
    print(f'  MR !{mr[\"iid\"]}: {mr[\"title\"]}')
    print(f'    Merged by: {mr.get(\"merged_by\", {}).get(\"username\", \"?\")}')
    print(f'    Merged at: {mr.get(\"merged_at\", \"?\")}')
    print()
"
# Expected:
#   MR !42: Promote sampleapi v1.5.0 to SIT
#     Merged by: root
#     Merged at: 2026-05-29T14:40:00Z
#
#   MR !43: Promote sampleapi v1.5.0 to UAT
#     Merged by: root
#     Merged at: 2026-05-29T14:55:00Z
#
#   MR !44: Promote sampleapi v1.5.0 to PROD
#     Merged by: root
#     Merged at: 2026-05-29T15:10:00Z
```

This is your audit trail. Three MRs, three merge events, three timestamps, three approver identities. An auditor can reconstruct the exact promotion history for any version of any service.

---

## 5. Walk Through a Multi-Service Promotion (15 min)

Now let us see what happens when two services are promoted concurrently. This demonstrates per-service isolation -- promoting `order-service` does not affect `sampleapi`, and vice versa.

### 5.1 Tag order-service

```bash
# Get the latest commit on order-service main branch
ORDER_SHA=$(curl -sk -H "PRIVATE-TOKEN: $GITLAB_TOKEN" \
  "${GITLAB_URL}/api/v4/projects/${GITLAB_PROJECT_ORDER_SERVICE}/repository/branches/main" \
  | python3 -c "import json,sys; print(json.load(sys.stdin)['commit']['id'])")
echo "order-service latest main: $ORDER_SHA"

# Create a tag
ORDER_TAG="v2.1.0"  # Adjust based on your current latest tag
curl -sk -X POST -H "PRIVATE-TOKEN: $GITLAB_TOKEN" \
  "${GITLAB_URL}/api/v4/projects/${GITLAB_PROJECT_ORDER_SERVICE}/repository/tags" \
  -d "tag_name=${ORDER_TAG}" \
  -d "ref=${ORDER_SHA}" \
  -d "message=Release ${ORDER_TAG}" | python3 -c "import json,sys; print(f'Tag: {json.load(sys.stdin).get(\"name\", \"error\")}')"
# Expected: Tag: v2.1.0
```

### 5.2 Wait for T3 to Create the SIT MR

The `order-service-tag` Jenkins job runs its own T3 pipeline. After it completes (including Java-specific stages: Maven build, JUnit tests, SonarQube for Java, ACS, ZAP, k6):

```bash
# Wait for T3 to complete (~20-25 min for Java service)
echo "Watch T3 for order-service: ${JENKINS_URL}/job/order-service-tag/"

# Poll until T3 finishes
for i in $(seq 1 60); do
    STATUS=$(curl -sk "${JENKINS_URL}/job/order-service-tag/lastBuild/api/json" \
      | python3 -c "import json,sys; print(json.load(sys.stdin).get('result', 'null'))" 2>/dev/null)
    if [ "$STATUS" != "null" ] && [ "$STATUS" != "" ]; then
        echo "order-service T3 completed: $STATUS"
        break
    fi
    echo "  Waiting... (attempt $i/60)"
    sleep 20
done
```

### 5.3 Two SIT MRs -- Independent Services

After both T3 pipelines complete, you should see two independent SIT MRs:

```bash
# List open MRs in app-gitops
curl -sk -H "PRIVATE-TOKEN: $GITLAB_TOKEN" \
  "${GITLAB_URL}/api/v4/projects/${GITLAB_PROJECT_APP_GITOPS}/merge_requests?state=opened" \
  | python3 -c "
import json, sys
mrs = json.load(sys.stdin)
print(f'Open MRs: {len(mrs)}')
for mr in mrs:
    print(f'  MR !{mr[\"iid\"]}: {mr[\"title\"]}')
    print(f'    Branch: {mr[\"source_branch\"]}')
"
# Expected:
# Open MRs: 2
#   MR !45: Promote order-service v2.1.0 to SIT
#     Branch: promote/order-service/v2.1.0-to-sit
#   MR !42: Promote sampleapi v1.5.0 to SIT     (if not yet merged)
#     Branch: promote/sampleapi/v1.5.0-to-sit
```

Each MR changes a different file:
- `sampleapi` MR changes `services/sampleapi/overlays/sit/kustomization.yaml`
- `order-service` MR changes `services/order-service/overlays/sit/kustomization.yaml`

There is no conflict between them. They can be merged in any order.

### 5.4 Merge order-service SIT MR

```bash
# Get the order-service SIT MR IID
ORDER_SIT_MR=$(curl -sk -H "PRIVATE-TOKEN: $GITLAB_TOKEN" \
  "${GITLAB_URL}/api/v4/projects/${GITLAB_PROJECT_APP_GITOPS}/merge_requests?state=opened" \
  | python3 -c "
import json, sys
for mr in json.load(sys.stdin):
    if 'order-service' in mr['title'] and 'SIT' in mr['title']:
        print(mr['iid'])
        break
")
echo "order-service SIT MR: !${ORDER_SIT_MR}"

# Merge
curl -sk -X PUT -H "PRIVATE-TOKEN: $GITLAB_TOKEN" \
  "${GITLAB_URL}/api/v4/projects/${GITLAB_PROJECT_APP_GITOPS}/merge_requests/${ORDER_SIT_MR}/merge" \
  | python3 -c "import json,sys; print(f'Merged: {json.load(sys.stdin).get(\"state\", \"error\")}')"
# Expected: Merged: merged
```

### 5.5 Watch T4 -- It Only Syncs order-service

```bash
# Wait for T4 to complete
sleep 30
for i in $(seq 1 20); do
    STATUS=$(curl -sk "${JENKINS_URL}/job/sampleapi-promote/lastBuild/api/json" \
      | python3 -c "import json,sys; print(json.load(sys.stdin).get('result', 'null'))" 2>/dev/null)
    if [ "$STATUS" != "null" ] && [ "$STATUS" != "" ]; then
        echo "T4 completed: $STATUS"
        break
    fi
    sleep 10
done
```

Check the T4 console output in Jenkins. You should see:

```
=== T4: GitOps Promotion Pipeline ===
  Triggered by merge to app-gitops main branch

Changed files:
services/order-service/overlays/sit/kustomization.yaml

Environments to sync: order-service/SIT

=== Syncing SIT ===
  ArgoCD App: order-service-sit
  Image Tag: v2.1.0
  Approved by: Team Lead (via MR)

SIT: Sync=Synced, Health=Healthy

=== Verifying SIT / order-service (namespace: javaapp-sit) ===
  Pods ready: 2
  Health: HEALTHY
  Image: order-service:v2.1.0

=== order-service/SIT is HEALTHY -- creating UAT promotion MR ===
order-service UAT promotion MR !46 created
```

Notice: only `order-service-sit` was synced. `sampleapi-sit` was not touched.

### 5.6 Verify Per-Service Isolation

```bash
# order-service in SIT: should be v2.1.0
$OC get deploy order-service -n $NS_JAVA_SIT -o jsonpath='{.spec.template.spec.containers[0].image}' | awk -F: '{print $NF}'
# Expected: v2.1.0

# sampleapi in SIT: should still be whatever it was before (unchanged by order-service promotion)
$OC get deploy sampleapi -n $NS_SIT -o jsonpath='{.spec.template.spec.containers[0].image}' | awk -F: '{print $NF}'
# Expected: v1.5.0 (or v1.4.0 if you haven't promoted sampleapi yet)

# order-service health check (Java uses /actuator/health)
curl -sk https://order-service-${NS_JAVA_SIT}.${APPS_DOMAIN}/actuator/health \
  | python3 -c "import json,sys; print(json.load(sys.stdin).get('status','?'))"
# Expected: UP
```

### 5.7 Continue Both Chains Independently

You can now approve the order-service UAT MR and the sampleapi UAT MR independently. They do not block each other:

```bash
# List all open promotion MRs
curl -sk -H "PRIVATE-TOKEN: $GITLAB_TOKEN" \
  "${GITLAB_URL}/api/v4/projects/${GITLAB_PROJECT_APP_GITOPS}/merge_requests?state=opened" \
  | python3 -c "
import json, sys
for mr in json.load(sys.stdin):
    print(f'  MR !{mr[\"iid\"]}: {mr[\"title\"]}')
"
# Expected (example):
#   MR !46: Promote order-service v2.1.0 to UAT
#   MR !43: Promote sampleapi v1.5.0 to UAT
```

Each service has its own independent promotion chain:

```
sampleapi v1.5.0:      SIT → UAT → PROD  (its own timeline)
order-service v2.1.0:  SIT → UAT → PROD  (its own timeline)
```

You can promote sampleapi to PROD while order-service is still in UAT. You can hold order-service in SIT indefinitely while sampleapi moves forward. The services are completely decoupled.

### 5.8 Final Multi-Service Verification

After promoting both services through all environments:

```bash
echo "=== Multi-Service Status ==="
echo ""
echo "--- sampleapi ---"
for ENV in dev sit uat prod; do
    NS="sampleapi-${ENV}"
    TAG=$($OC get deploy sampleapi -n $NS -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null | awk -F: '{print $NF}')
    echo "  ${ENV}: ${TAG}"
done

echo ""
echo "--- order-service ---"
for ENV in dev sit uat prod; do
    NS="javaapp-${ENV}"
    TAG=$($OC get deploy order-service -n $NS -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null | awk -F: '{print $NF}')
    echo "  ${ENV}: ${TAG}"
done
# Expected:
# --- sampleapi ---
#   dev: main-abc1234
#   sit: v1.5.0
#   uat: v1.5.0
#   prod: v1.5.0
#
# --- order-service ---
#   dev: main-def5678
#   sit: v2.1.0
#   uat: v2.1.0
#   prod: v2.1.0
```

Each service has its own version in each environment. There is no coupling between them.

---

## Recap: What Just Happened?

Let us trace the complete promotion lifecycle:

```
Developer pushes tag v1.5.0 on sampleapi
    |
    v
+--[ T3: Tag Pipeline ]---------------------------------------------+
| Build (.NET) → Tests → SAST → SCA → SBOM → Container Build       |
| → Push → ACS Strict → DAST (ZAP) → Performance (k6)              |
| → Sign (Cosign/RHTAS) → Verify Signature                          |
|                                                                    |
| ALL GATES PASS                                                     |
|                                                                    |
| → createPromotionMR(imageTag: 'v1.5.0', targetEnv: 'sit')        |
|   → Clone app-gitops                                               |
|   → Create branch: promote/sampleapi/v1.5.0-to-sit                |
|   → kustomize edit set image sampleapi=...registry.../sampleapi:v1.5.0 |
|   → Commit + push                                                  |
|   → GitLab API: create MR with T3 results table                   |
|   → Post commit status: jenkins/quality-gates = success            |
+--------------------------------------------------------------------+
    |
    v
GitLab MR !42: "Promote sampleapi v1.5.0 to SIT"
  Reviewer sees: T3 scan results + one-line diff + green pipeline
    |
    | Team Lead clicks Approve + Merge
    v
+--[ T4: Promote Pipeline (SIT) ]-----------------------------------+
| Initialize → Checkout app-gitops → Detect Changes                  |
|   → regex: services/sampleapi/overlays/sit/ changed               |
|   → envLabel=SIT, argoApp=sampleapi-sit, imageTag=v1.5.0          |
|                                                                    |
| Sync: argocd app sync sampleapi-sit --force --prune               |
|       argocd app wait sampleapi-sit --health                       |
|                                                                    |
| Verify: oc get pods -n sampleapi-sit -l app=sampleapi             |
|         curl https://sampleapi-sampleapi-sit.../healthz            |
|         → HEALTHY                                                  |
|                                                                    |
| Cascade: nextEnvMap[SIT] = uat                                     |
|   → createPromotionMR(imageTag: 'v1.5.0', targetEnv: 'uat')      |
+--------------------------------------------------------------------+
    |
    v
GitLab MR !43: "Promote sampleapi v1.5.0 to UAT"
    |
    | QA Lead clicks Approve + Merge
    v
+--[ T4: Promote Pipeline (UAT) ]-----------------------------------+
| Same flow: detect uat change → sync sampleapi-uat → verify HEALTHY|
| Cascade: nextEnvMap[UAT] = production                              |
|   → createPromotionMR(imageTag: 'v1.5.0', targetEnv: 'production')|
+--------------------------------------------------------------------+
    |
    v
GitLab MR !44: "Promote sampleapi v1.5.0 to PROD"
    |
    | CAB clicks Approve + Merge
    v
+--[ T4: Promote Pipeline (PROD) ]----------------------------------+
| Detect production change → sync sampleapi-prod → verify HEALTHY   |
| Cascade: nextEnvMap[PROD] = null → END OF CHAIN                   |
+--------------------------------------------------------------------+
    |
    v
sampleapi v1.5.0 is in production. Audit trail: 3 MRs, 3 approvals.
```

What we examined:

- **pipelinePromote.groovy** (530 lines) -- the T4 orchestrator that detects changes via regex, syncs ArgoCD, verifies health, and cascades MRs
- **createPromotionMR.groovy** (368 lines) -- the function that clones the GitOps repo, updates the overlay, creates a branch, opens a MR with full scan results, and posts commit status
- **deployToEnvironment.groovy** (80 lines) -- the ArgoCD sync and wait function
- **The approval chain**: Team Lead (SIT) → QA Lead (UAT) → CAB (PROD)
- **Per-service isolation**: each service has its own promotion chain, detected by regex on the git diff path
- **The audit trail**: every promotion is a GitLab MR with scan results, approver identity, and timestamp

The T4 pipeline transforms a 2.5-hour manual promotion process (5 services x 3 environments x 10 minutes) into a series of click-Approve-and-Merge actions. The human reviews. The automation executes.

---

## Known Issues

### Issue 1: disableConcurrentBuilds() Drops Rapid-Fire Webhooks

The `pipelinePromote.groovy` pipeline uses `disableConcurrentBuilds()` to prevent race conditions when two merges happen simultaneously. However, this means that if you merge 5 promotion MRs within a few seconds, Jenkins queues them but may drop some due to the "abort queued builds" behavior.

**Symptom:** You merge 5 SIT MRs quickly. T4 runs for the first one, but the other 4 services never get synced to SIT.

**Workaround:** Manually sync the missed services via ArgoCD:

```bash
# Check which services are out of sync
argocd app list --grpc-web | grep -v Synced
# Expected: services that missed the T4 trigger

# Manually sync them
argocd app sync <app-name> --force --prune --grpc-web
```

**Prevention:** Merge promotion MRs one at a time, waiting for T4 to complete between merges. Or merge them with 30-60 seconds between each.

### Issue 2: Shallow Clone Depth

The `pipelinePromote.groovy` checkout uses `depth: 10` (shallow clone). If there are more than 10 commits between `gitlabBefore` and `gitlabAfter` (e.g., a large batch merge), `git diff` may fail because the earlier commit is not in the shallow clone.

**Symptom:** `git diff` returns empty results. T4 detects no changes. No sync happens.

**Workaround:** Increase the clone depth or trigger T4 manually with a full clone.

### Issue 3: Overlay Directory Must Exist

`createPromotionMR.groovy` checks `fileExists(overlayDir)` before running `kustomize edit set image`. If a new service is added to the GitOps repo but the overlay directory for a target environment does not exist yet, the function fails with `Overlay directory not found`.

**Fix:** Ensure all overlay directories exist for all environments before promoting a new service:

```
services/{service}/overlays/dev/kustomization.yaml
services/{service}/overlays/sit/kustomization.yaml
services/{service}/overlays/uat/kustomization.yaml
services/{service}/overlays/production/kustomization.yaml
```

---

## Common Mistakes

These are real issues encountered during implementation. Each one cost debugging time.

### Mistake 1: Confusing DEV Overlay Changes With Promotion

```
Changed files:
  services/sampleapi/overlays/dev/kustomization.yaml

No per-service overlay changes detected. Skipping sync.
DEV overlay changed -- auto-sync handles this via ArgoCD. No manual sync needed.
```

T2 (merge pipeline) updates the DEV overlay when code merges to main. This fires a webhook to the app-gitops repo, which triggers T4. But T4 explicitly skips DEV because DEV is auto-synced by ArgoCD. The pipeline correctly identifies this and exits early.

If you see this message, it is not an error. It is T4 doing its job -- ignoring DEV changes.

### Mistake 2: Merging Multiple MRs Simultaneously

```
# WRONG: Merge 3 MRs in 5 seconds
for MR in 42 43 44; do
    curl -sk -X PUT ... /merge_requests/${MR}/merge &
done
wait
# Result: T4 fires once, processes the first merge, drops the other two

# RIGHT: Merge one at a time, wait for T4 to complete
curl -sk -X PUT ... /merge_requests/42/merge
# Wait for T4 to complete...
curl -sk -X PUT ... /merge_requests/43/merge
# Wait for T4 to complete...
```

Because of `disableConcurrentBuilds()`, rapid-fire merges cause webhook events to be dropped. Always wait for one T4 run to complete before merging the next MR.

### Mistake 3: Forgetting the production vs prod Naming

```groovy
// The overlay directory is 'production' but the ArgoCD app is '{service}-prod'
// This mapping is handled in pipelinePromote.groovy:
def argoApp = envDir == 'production' ? "${service}-prod" : "${service}-${envDir}"

// And in createPromotionMR.groovy:
def envLabel = (targetEnv == 'production') ? 'PROD' : targetEnv.toUpperCase()
```

The overlay directory is named `production` (matching Kustomize convention), but the ArgoCD application is named `{service}-prod` (matching the namespace suffix `sampleapi-prod`). Both pipelines handle this translation, but if you are debugging manually, be aware of the naming difference:

```
Overlay directory:   services/sampleapi/overlays/production/
ArgoCD application:  sampleapi-prod
OpenShift namespace: sampleapi-prod
```

### Mistake 4: Missing Webhook on app-gitops

If T4 never fires after merging a promotion MR, the most likely cause is a missing webhook on the app-gitops repo:

```bash
# Check webhooks on app-gitops (project ID 4)
curl -sk -H "PRIVATE-TOKEN: $GITLAB_TOKEN" \
  "${GITLAB_URL}/api/v4/projects/${GITLAB_PROJECT_APP_GITOPS}/hooks" \
  | python3 -c "
import json, sys
hooks = json.load(sys.stdin)
for h in hooks:
    print(f'  Hook {h[\"id\"]}: {h[\"url\"]}')
    print(f'    Push events: {h[\"push_events\"]}')
"
# Expected: at least one hook pointing to Jenkins with push_events: true
```

If no webhook exists, the GitLab push event after merge does not reach Jenkins, and T4 never triggers. See Module 8 for webhook setup.

### Mistake 5: Health Check Returning UNHEALTHY Breaks the Chain

```
SIT: Deployment NOT healthy (UNHEALTHY) -- skipping next promotion MR.
```

If the health check fails, T4 does not create the next promotion MR. The chain stops. Common causes:

- The application is crashlooping (bad config, missing env vars, database connection failure)
- The Route does not exist for this environment
- The health endpoint returns an unexpected response format

Debug with:

```bash
# Check pod status
$OC get pods -n $NS_SIT -l app=sampleapi
# Look for CrashLoopBackOff or Error status

# Check pod logs
$OC logs -n $NS_SIT -l app=sampleapi --tail=50

# Test health endpoint manually
ROUTE=$($OC get route sampleapi -n $NS_SIT -o jsonpath='{.spec.host}')
curl -sk -v https://${ROUTE}/healthz
```

### Mistake 6: Image Tag Extraction Failure

```
SIT: No image tag found -- skipping next promotion MR.
```

This happens when T4 cannot extract the image tag from the git diff. The most common cause is that the diff range (`gitlabBefore..gitlabAfter`) does not include the kustomization.yaml change -- for example, when the webhook payload is stale or the shallow clone is too shallow.

Debug with:

```bash
# Check what T4 sees in its console output
# Look for: "Diff range: ..." and "Changed files: ..."
echo "Check console: ${JENKINS_URL}/job/sampleapi-promote/lastBuild/console"
```

---

## Self-Assessment

Answer these without looking back at the module. If you cannot answer confidently, re-read the relevant section.

1. How does `pipelinePromote.groovy` determine which service and which environment changed? What regex does it use, and what are the two capture groups?

2. Why does T4 skip DEV overlay changes? What mechanism handles DEV deployments instead?

3. The promotion chain is: SIT → UAT → PROD. What three conditions must be true for the chain to continue from one environment to the next?

4. `createPromotionMR.groovy` is called from two places. What information does the MR description contain when called from T3 vs when called from T4?

5. You merge 5 SIT promotion MRs for 5 different services simultaneously. Only 2 services get synced to SIT. What happened, and how do you fix it?

6. The T4 health check uses `curl -sk https://{route}/healthz` for .NET and `curl -sk https://{route}/actuator/health` for Java. What happens when a service has no Route (e.g., NotificationApi)?

7. An auditor asks: "Prove that version v1.5.0 of sampleapi was approved before reaching production." Where do you find the evidence, and what does it contain?

<details>
<summary>Answers</summary>

1. T4 runs `git diff --name-only` and applies the regex `/^services\/([^\/]+)\/overlays\/([^\/]+)\//` to each changed file. The first capture group is the **service name** (e.g., `sampleapi`), and the second is the **environment** (e.g., `sit`). This determines which ArgoCD app to sync (e.g., `sampleapi-sit`).

2. T4 explicitly checks `if (envDir == 'dev') return` and skips DEV. DEV deployments are handled by ArgoCD auto-sync -- the `sampleapi-dev` ArgoCD application has `automated: { prune: true, selfHeal: true }`, so any change to the DEV overlay is automatically applied without pipeline involvement.

3. The three conditions are: (a) `nextEnvMap[currentEnv]` is not `null` (not end of chain), (b) `envMeta.healthStatus == 'HEALTHY'` (deployment health verified), and (c) `imageTag` is known and not `'unknown'` (the tag was successfully extracted from the diff).

4. When called from **T3**: the MR description contains the full T3 scan results table -- build time, test coverage, SAST gate status, SCA findings, SBOM component count, ACS critical/high counts, DAST results, performance test metrics, signing status. When called from **T4** (cascading): the MR description contains the previous environment's deployment status -- health status, pod count, deployed image tag.

5. `disableConcurrentBuilds()` causes Jenkins to drop queued T4 builds when a T4 is already running. Only the first 1-2 merges trigger T4 before the queue fills and drops subsequent events. **Fix:** Manually sync the missed services via `argocd app sync {service}-sit --force --prune --grpc-web`. **Prevention:** Merge MRs one at a time, waiting for each T4 to complete before merging the next.

6. When no Route exists, `oc get route` returns empty, and the `curl` health check is skipped. T4 falls back to pod readiness: `if (healthStatus == 'UNKNOWN' && readyPods.toInteger() > 0)` -- if pods are Running and Ready (1/1), the service is considered HEALTHY. This handles internal-only services that are accessed via ClusterIP.

7. Open the app-gitops repository in GitLab and find three merged MRs matching `v1.5.0`: the SIT MR (approved by Team Lead), the UAT MR (approved by QA Lead), and the PROD MR (approved by CAB). Each MR contains: (a) the scan results from T3 in the description, (b) the one-line diff showing `newTag: v1.5.0`, (c) the approver identity and merge timestamp in the MR metadata, and (d) the commit status showing `jenkins/quality-gates = success`.

</details>

---

## Next Module Preview

**Module 20: Platform Operations Runbook** covers Day 2 operations for the complete DevSecOps platform:

- Handling pipeline failures: when T3 gates fail, when T4 health checks fail, when ArgoCD sync fails
- Rolling back a single service to a previous version without affecting other services
- Scaling the platform: adding a new service (from repo creation to full 4-environment promotion chain)
- Certificate rotation for RHTAS, ACS, and GitLab
- Disaster recovery: rebuilding the Jenkins job configuration, restoring ArgoCD applications, re-creating webhooks
- Monitoring the pipeline itself: Jenkins build duration trends, webhook delivery rates, promotion chain latency

With T4 in place, you have a fully automated promotion chain. Module 20 ensures you can operate it reliably under production conditions.

---

*Module 19 of the DevSecOps Tutorial Series -- Supply Chain & Multi-Language Track*
*Files referenced in this module:*
- *`jenkins-shared-lib/vars/pipelinePromote.groovy` -- T4 orchestrator (change detection, sync, verify, cascade)*
- *`jenkins-shared-lib/vars/createPromotionMR.groovy` -- GitOps MR creation with scan results and commit status*
- *`jenkins-shared-lib/vars/deployToEnvironment.groovy` -- ArgoCD sync and health wait*
- *`jenkins-shared-lib/vars/pipelineTag.groovy` -- T3 orchestrator (calls createPromotionMR for initial SIT MR)*
- *`env.sh` -- Environment variables (`$NS_SIT`, `$NS_UAT`, `$NS_PROD`, `$NS_JAVA_SIT`, etc.)*
