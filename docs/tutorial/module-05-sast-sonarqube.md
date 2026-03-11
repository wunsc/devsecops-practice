# Module 5: SAST with SonarQube

**Duration:** ~60 minutes | **Track:** Security | **Prerequisites:** Module 1

---

## What You'll Learn

By the end of this module, you will be able to:

1. Explain what SAST is and why it belongs early in the pipeline ("shift left")
2. Deploy SonarQube Community Edition on OpenShift with a dedicated PostgreSQL backend
3. Configure a quality gate that acts as an automated code reviewer
4. Integrate the .NET SonarScanner into a Jenkins pipeline
5. Poll the SonarQube API to enforce the quality gate programmatically -- failing the build when code does not meet the bar

This module covers the first security layer in our pipeline. Module 6 (ACS Container Security) and Module 7 (DAST with OWASP ZAP) complete the picture.

---

## Prerequisites

Before starting, confirm you have:

- [ ] A running OpenShift 4.x cluster with `cluster-admin` access
- [ ] The `devsecops-tools` namespace created (Module 1 / Phase 1)
- [ ] Privileged SCC available for init containers (we will grant it explicitly)
- [ ] The `oc` CLI authenticated to the cluster
- [ ] Familiarity with Kubernetes Deployments, Services, Routes, and Secrets

> **Environment variables:** Before running any commands, source the environment file:
> ```bash
> source ./env.sh
> ```
> This sets `$OC`, `$APPS_DOMAIN`, `$NS_TOOLS`, and all other cluster-specific variables used throughout this module. See `env.sh` for the full variable list.

---

## 1. Concepts: Why SAST, and Why Here?

### What Is SAST?

Static Application Security Testing (SAST) analyzes your **source code** -- without executing it -- to find bugs, vulnerabilities, code smells, and duplication. Think of it as a tireless code reviewer who reads every line on every commit and never goes on vacation.

The key word is *static*. SAST does not deploy your application or send HTTP requests. It reads the abstract syntax tree, traces data flows, and matches patterns against known weakness categories (CWE). That makes it fast, deterministic, and safe to run on every single commit.

### Why Shift Left?

A security defect found during code review costs roughly 6x less to fix than the same defect found in production. SAST catches issues at the earliest possible moment -- while the developer still has the code open in their editor. In our pipeline, SAST runs immediately after unit tests, *before* a container image is even built:

```
Checkout --> Build --> Unit Tests --> SAST (here) --> SCA --> Container Build --> ...
```

If SAST fails, we never waste time building an image from code we know is flawed.

### The Quality Gate: Your Automated Reviewer

A quality gate is a set of boolean conditions applied to every analysis. If any condition fails, the gate fails, and the pipeline stops. Think of it as a merge request reviewer who will never approve code that:

- Introduces new bugs
- Introduces new vulnerabilities
- Drops test coverage below a threshold
- Exceeds a duplication percentage

The gate evaluates only **new code** (code changed since the last version baseline), so legacy issues do not block new work. This is deliberate -- it lets you adopt SAST on an existing codebase without drowning in thousands of pre-existing findings.

### SonarQube Architecture (30-Second Version)

```
                   +------------------+
                   |   Web Server     |  <-- UI + REST API (port 9000)
                   +--------+---------+
                            |
                   +--------+---------+
                   | Compute Engine   |  <-- Background analysis processing
                   +--------+---------+
                            |
                   +--------+---------+
                   |  Elasticsearch   |  <-- Search indices (embedded)
                   +--------+---------+
                            |
                   +--------+---------+
                   |   PostgreSQL     |  <-- Analysis data, quality gates, rules
                   +------------------+
```

SonarQube bundles three JVM processes (Web Server, Compute Engine, Elasticsearch) in a single container. Elasticsearch is embedded -- you do not deploy it separately -- but it does require the Linux kernel parameter `vm.max_map_count >= 262144`. On OpenShift, an init container handles that.

---

## 2. Step 1: Deploy SonarQube on OpenShift

We deploy SonarQube as a standard Deployment (not an Operator) with a dedicated PostgreSQL StatefulSet. Six manifests, applied in dependency order.

### 2.1 Grant the Privileged SCC

SonarQube's embedded Elasticsearch needs `vm.max_map_count` set at the kernel level. An init container does this via `sysctl`, which requires root privileges.

```bash
# --- WHY: The init container writes to /proc/sys/vm/max_map_count,
#          which is a kernel parameter. Only root can do that.
$OC adm policy add-scc-to-user privileged -z default -n $NS_TOOLS
```

> **Callout -- Security trade-off:** Granting `privileged` SCC to the default ServiceAccount is acceptable for a tooling namespace that only operators administer. Never do this in an application namespace. An alternative is a custom SCC that allows only the specific sysctl. We chose the simpler path here because `devsecops-tools` is not internet-facing and has a small blast radius. See DECISIONS.md for the full rationale.

### 2.2 Apply the Manifests

The files live in `infra/phase3/`. Apply them in this order -- each resource depends on the one before it:

```bash
# 1. Secrets first -- PostgreSQL and SonarQube both reference these
$OC apply -f infra/phase3/sonarqube-secrets.yaml

# 2. PostgreSQL -- SonarQube cannot start without its database
$OC apply -f infra/phase3/sonarqube-postgresql-statefulset.yaml

# 3. Wait for PostgreSQL to accept connections
$OC wait --for=condition=ready pod -l app=sonarqube-postgresql \
  -n $NS_TOOLS --timeout=180s

# 4. SonarQube config, deployment, networking
$OC apply -f infra/phase3/sonarqube-configmap.yaml
$OC apply -f infra/phase3/sonarqube-deployment.yaml
$OC apply -f infra/phase3/sonarqube-service.yaml
$OC apply -f infra/phase3/sonarqube-route.yaml

# 5. Wait -- first boot takes 3-5 minutes (Elasticsearch index creation)
$OC wait --for=condition=ready pod -l app=sonarqube \
  -n $NS_TOOLS --timeout=360s
```

### 2.3 What Each Manifest Does

| File | Purpose | Key Detail |
|------|---------|------------|
| `sonarqube-secrets.yaml` | DB credentials + JDBC URL | Referenced by both PostgreSQL and SonarQube |
| `sonarqube-postgresql-statefulset.yaml` | PostgreSQL 15 with 10Gi PVC | StatefulSet for stable storage identity |
| `sonarqube-configmap.yaml` | `sonar.properties` tuning | ES heap 512m, telemetry off, force auth |
| `sonarqube-deployment.yaml` | SonarQube CE v26.3.0 + init containers | Two init containers (sysctl + volume perms) |
| `sonarqube-service.yaml` | ClusterIP on port 9000 | Internal-only; Route provides external access |
| `sonarqube-route.yaml` | TLS edge-terminated Route | HTTPS externally, HTTP internally |

### 2.4 Look Inside the Deployment

The deployment has two init containers that run before SonarQube starts. This is worth understanding because it is a common OpenShift pattern for privileged setup:

```yaml
# From infra/phase3/sonarqube-deployment.yaml (abbreviated)
initContainers:
  # --- Init container 1: Kernel tuning for Elasticsearch ---
  # WHY: Elasticsearch refuses to start if vm.max_map_count < 262144.
  #      This is a hard requirement, not a recommendation.
  - name: sysctl-init
    image: registry.access.redhat.com/ubi9/ubi-minimal:latest
    command:
      - sh
      - -c
      - |
        echo 262144 > /proc/sys/vm/max_map_count
        echo 131072 > /proc/sys/fs/file-max
    securityContext:
      privileged: true        # <-- requires privileged SCC
      runAsUser: 0             # <-- must be root to write to /proc/sys

  # --- Init container 2: Volume permissions ---
  # WHY: PVCs are provisioned with root ownership, but SonarQube
  #      runs as UID 1000. Without chown, SonarQube gets permission denied.
  - name: volume-permissions
    image: registry.access.redhat.com/ubi9/ubi-minimal:latest
    command:
      - sh
      - -c
      - |
        chown -R 1000:0 /opt/sonarqube/data
        chown -R 1000:0 /opt/sonarqube/extensions
        chown -R 1000:0 /opt/sonarqube/logs
        chown -R 1000:0 /opt/sonarqube/temp
    securityContext:
      runAsUser: 0
```

After both init containers complete, the main `sonarqube` container starts as UID 1000 (non-root) with properly owned volumes and a correctly tuned kernel.

### Verify: SonarQube Is Running

```bash
# Check pods -- expect 2 Running (sonarqube + sonarqube-postgresql)
$OC get pods -n $NS_TOOLS -l 'app in (sonarqube, sonarqube-postgresql)'
# Expected output:
# NAME                        READY   STATUS    RESTARTS   AGE
# sonarqube-57757c4f-nv4bh    1/1     Running   3          2d

# Get the external URL (or use $SONARQUBE_URL from env.sh)
SONAR_URL="https://$($OC get route sonarqube -n $NS_TOOLS -o jsonpath='{.spec.host}')"
echo "SonarQube URL: $SONAR_URL"

# Health check via API (no auth needed for system status)
curl -sk "$SONAR_URL/api/system/status" | python3 -m json.tool
# Expected output:
# {
#     "id": "28C13D1D-AZzGyG4dw_l981M0VN8E",
#     "version": "26.3.0.120487",
#     "status": "UP"
# }
```

If the status is `UP`, SonarQube is healthy. Open the URL in a browser -- you should see the login page.

---

## 3. Step 2: Configure the Quality Gate

The quality gate is the single most important configuration in SonarQube. Without it, SAST is just informational. With it, SAST becomes an enforced policy.

### 3.1 First Login

Open the SonarQube URL in your browser. The default credentials are `admin` / `admin`. SonarQube will force you to change the password on first login. Choose a strong password and record it.

### 3.2 Create the Quality Gate

Navigate to **Quality Gates** (top menu) and click **Create**.

Name it: **DevSecOps Standard**

Add these conditions (all apply to **New Code** only):

| # | Metric | Condition | Value | Why |
|---|--------|-----------|-------|-----|
| 1 | New Bugs | is greater than | 0 | Zero tolerance for new bugs |
| 2 | New Vulnerabilities | is greater than | 0 | Zero tolerance for new security issues |
| 3 | New Code Coverage | is less than | 80% | Untested code is a risk |
| 4 | New Duplicated Lines (%) | is greater than | 3% | Copy-paste breeds maintenance debt |
| 5 | New Security Rating | is worse than | A | Must be the highest rating |
| 6 | New Reliability Rating | is worse than | A | No reliability regressions |
| 7 | New Security Hotspots Reviewed | is less than | 100% | Every hotspot must be triaged |

Click **Set as Default** so every new project inherits this gate.

> **Callout -- "Isn't 80% coverage aggressive?"** For new code, no. You are not asking for 80% on the entire codebase. The gate only measures lines added or changed in this analysis. Developers writing new code should be testing it. Legacy untested code is excluded from the "New Code" window.

### 3.3 Verify the Quality Gate via API

```bash
# Requires authentication -- use the new admin password
SONAR_PASS="<your-new-admin-password>"

curl -sk -u "admin:$SONAR_PASS" \
  "$SONAR_URL/api/qualitygates/list" | python3 -m json.tool

# Look for: "name": "DevSecOps Standard", "isDefault": true
```

After both projects have been scanned by the pipeline, the SonarQube Projects dashboard shows the analysis results for each service:

![SonarQube Projects dashboard showing notificationapi and sampleapi with quality gate status, coverage, and duplication metrics](../screenshot/sonarqube-projects-dashboard.png)

---

## 4. Step 3: Create a Project and Generate a Token

### 4.1 Create the Project

In the SonarQube UI:

1. Go to **Projects** --> **Create Project** --> **Manually**
2. Project Key: `sampleapi`
3. Display Name: `SampleApi`
4. Main branch: `main`
5. Assign the **DevSecOps Standard** quality gate to this project

The project key (`sampleapi`) is what the scanner uses to identify where to send analysis results. It must match exactly in your pipeline configuration.

### 4.2 Generate an Analysis Token

Tokens are how external tools authenticate to SonarQube without using a password.

1. Click your avatar (top-right) --> **My Account** --> **Security**
2. Token name: `jenkins-integration`
3. Type: **Global Analysis Token**
4. Expiration: 365 days (or per your organization's rotation policy)
5. Click **Generate** and copy the token immediately -- it is shown only once

### 4.3 Store the Token in OpenShift

Jenkins will retrieve this token from a Kubernetes Secret at runtime. Never hardcode tokens in pipeline scripts.

```bash
# Store the token where Jenkins can access it via withCredentials()
$OC create secret generic sonarqube-token \
  --from-literal=text=<PASTE-TOKEN-HERE> \
  -n $NS_TOOLS

# Label it for discoverability
$OC label secret sonarqube-token \
  team=devsecops component=jenkins -n $NS_TOOLS
```

### Verify: Token Works

```bash
SONAR_TOKEN="<your-token>"

# Token auth uses the token as username, empty password
curl -sk -u "$SONAR_TOKEN:" \
  "$SONAR_URL/api/authentication/validate"
# Expected: {"valid":true}
```

> **Callout -- Auth syntax matters.** Notice the colon after `$SONAR_TOKEN:` with nothing after it. SonarQube token authentication uses the token as the username and an empty password. Forgetting the trailing colon results in a 401. This is the single most common integration mistake.

---

## 5. Step 4: Understand .NET SonarScanner Integration

This is where .NET diverges from every other language SonarQube supports, and where most teams hit their first wall.

### 5.1 The .NET Scanner Is Different

For Java, Python, or JavaScript projects, you would install the standalone `sonar-scanner` CLI and point it at a `sonar-project.properties` file. Simple.

For .NET, that approach **does not work**. The .NET SonarScanner is a `dotnet` global tool that wraps the build process:

```
dotnet sonarscanner begin ...   <-- instruments the build
dotnet build                    <-- your normal build (scanner hooks into it)
dotnet sonarscanner end ...     <-- collects results and uploads
```

The scanner must wrap the build because it hooks into the Roslyn compiler to extract the AST. A standalone scanner cannot do this -- it does not understand `.csproj` files or NuGet dependencies.

### 5.2 The `sonar-project.properties` Gotcha

Our repository has a `build-config/sonar-project.properties` file. You might expect the scanner to read it. **It does not.** The .NET SonarScanner ignores `sonar-project.properties` entirely. All configuration must be passed as CLI arguments to `dotnet sonarscanner begin`.

The properties file exists purely as documentation -- a reference for what settings the pipeline uses. Here is the key comment from the file itself:

```properties
# From build-config/sonar-project.properties:
#
# IMPORTANT: The .NET SonarScanner (dotnet-sonarscanner) does NOT read this file.
# It uses CLI arguments instead (see scanSonarQube.groovy in the shared library).
# This file serves as DOCUMENTATION of the scanner settings used by the pipeline.
```

### 5.3 The CE Branch Limitation

SonarQube Community Edition does not support `sonar.branch.name`. That parameter requires the Developer Edition. In CE, every analysis lands on the "main branch" regardless of which Git branch you actually scanned.

Our workaround: use `sonar.projectVersion` to tag each scan with context, and `sonar.analysis.*` custom properties for metadata:

```
T1 (MR):    projectVersion = "MR-42-feature/login"    <-- identifies MR scans
T2 (Merge): projectVersion = "main-abc1234"            <-- identifies main scans
T3 (Tag):   projectVersion = "v1.2.0"                  <-- identifies release scans
```

SonarQube's "New Code" definition uses version boundaries, so each scan context gets its own baseline for quality gate evaluation. Not perfect, but functional on CE.

### 5.4 The Three-Phase Scan Pattern

Here is the actual scanner invocation from our shared library (`scanSonarQube.groovy`), stripped to its essence:

```groovy
// Phase 1: BEGIN -- configure the scanner and instrument the build
sh """
    dotnet sonarscanner begin \\
        /k:"sampleapi" \\                                    # <-- project key
        /v:"main-abc1234" \\                                  # <-- version tag
        /d:sonar.host.url="https://sonarqube.example.com" \\ # <-- server URL
        /d:sonar.token="\${SONAR_TOKEN}" \\                   # <-- auth token
        /d:sonar.cs.opencover.reportsPaths="**/coverage.opencover.xml" \\
        /d:sonar.qualitygate.wait=false                       # <-- we poll manually
"""

// Phase 2: BUILD -- the scanner hooks into Roslyn during compilation
sh "dotnet build . --no-incremental"

// Phase 3: END -- collect results and upload to SonarQube server
sh """
    dotnet sonarscanner end \\
        /d:sonar.token="\${SONAR_TOKEN}"
"""
```

Note `sonar.qualitygate.wait=false`. We do not use the scanner's built-in gate wait because it blocks the Jenkins executor thread. Instead, we poll the API ourselves with a timeout, which gives us better control and clearer error messages.

---

## 6. Step 5: Run SAST in the Pipeline

### 6.1 The Shared Library Function

The `scanSonarQube.groovy` function in `jenkins-shared-lib/vars/` encapsulates the entire SAST flow. Every pipeline trigger (T1, T2, T3) calls this same function with different parameters.

Here is the function signature and its key parameters:

```groovy
// From jenkins-shared-lib/vars/scanSonarQube.groovy
def call(Map config = [:]) {
    def projectKey    = config.projectKey    ?: env.SONAR_PROJECT_KEY ?: 'sampleapi'
    def sonarUrl      = config.sonarUrl      ?: env.SONARQUBE_URL ?: ''
    def tokenCredId   = config.tokenCredId   ?: 'sonarqube-token'
    def project       = config.project       ?: '.'
    def coverageDir   = config.coverageDir   ?: 'coverage-results'
    def timeoutMinutes = config.timeoutMinutes ?: 5

    // Version context -- different per trigger type
    def projectVersion = config.projectVersion ?: '1.0'
    def analysisMode   = config.analysisMode   ?: 'default'   // 'mr', 'merge', 'tag'
    def branchName     = config.branchName     ?: ''
    def mrIid          = config.mrIid          ?: ''
    // ...
}
```

Every parameter has a default, making the function safe to call with minimal configuration. But the orchestrators pass explicit values for clarity.

### 6.2 How the Orchestrators Call It

**T1 -- Merge Request (validation scan):**

```groovy
// From pipelineMR.groovy -- tags the scan with MR context
def branch = env.gitlabSourceBranch ?: env.GIT_BRANCH ?: 'unknown'
def mrIid = env.gitlabMergeRequestIid ?: ''
def versionLabel = mrIid ? "MR-${mrIid}-${branch}" : "branch-${branch}"

results.sonarqube = scanSonarQube(
    projectKey: pipelineConfig.sonarProjectKey,
    sonarUrl: pipelineConfig.sonarUrl,
    project: '.',
    projectVersion: versionLabel,       // <-- "MR-42-feature/login"
    analysisMode: 'mr',
    branchName: branch,
    mrIid: mrIid
)
```

**T2 -- Merge to Main (production scan):**

```groovy
// From pipelineMerge.groovy -- tags with commit SHA
def shortSha = results.commitSha ? results.commitSha.take(7) : 'unknown'

results.sonarqube = scanSonarQube(
    projectKey: pipelineConfig.sonarProjectKey,
    sonarUrl: pipelineConfig.sonarUrl,
    project: '.',
    projectVersion: "main-${shortSha}",  // <-- "main-abc1234"
    analysisMode: 'merge',
    branchName: 'main'
)
```

Notice how the same function serves both triggers. The `projectVersion` and `analysisMode` parameters change, but the scanning logic is identical. This is the benefit of the shared library pattern -- one tested implementation, multiple callers.

### 6.3 Credential Handling

The function wraps all scanner invocations in a `withCredentials` block:

```groovy
withCredentials([string(credentialsId: tokenCredId, variable: 'SONAR_TOKEN')]) {
    // SONAR_TOKEN is available as an environment variable inside this block.
    // Jenkins masks it in logs automatically -- any occurrence of the
    // token value in console output is replaced with ******.
    sh """
        dotnet sonarscanner begin /d:sonar.token="\${SONAR_TOKEN}" ...
    """
}
```

The `\${SONAR_TOKEN}` syntax (escaped dollar sign) is intentional. It tells Groovy to pass the literal string `${SONAR_TOKEN}` to the shell, where the shell expands the environment variable. If you wrote `${SONAR_TOKEN}` (unescaped), Groovy would interpolate it *before* the shell runs, potentially leaking it in the Groovy call stack.

---

## 7. Step 6: Check the Quality Gate Programmatically

After the scanner uploads results, SonarQube processes them asynchronously in the Compute Engine. The quality gate result is not available immediately. We must poll.

### 7.1 The Polling Function

```groovy
// From scanSonarQube.groovy -- simplified for clarity
def pollQualityGate(String sonarUrl, String projectKey,
                    int timeoutMinutes, String sonarToken) {
    def maxAttempts = timeoutMinutes * 6   // <-- check every 10 seconds
    def gateStatus = 'UNKNOWN'

    for (int i = 0; i < maxAttempts; i++) {
        sleep(10)   // <-- 10 seconds between polls
        def response = sh(
            script: """
                curl -sf -u "\${SONAR_TOKEN}:" \
                  "${sonarUrl}/api/qualitygates/project_status?projectKey=${projectKey}"
            """,
            returnStdout: true
        ).trim()

        // Parse status from JSON response
        def statusMatch = (response =~ /"status"\s*:\s*"([^"]+)"/)
        if (statusMatch) {
            gateStatus = statusMatch[0][1]
            if (gateStatus != 'NONE' && gateStatus != 'IN_PROGRESS') {
                return gateStatus   // <-- 'OK' or 'ERROR'
            }
        }
    }
    return gateStatus   // <-- 'UNKNOWN' if we timed out
}
```

Three things to note:

1. **Auth is required.** The `-u "\${SONAR_TOKEN}:"` is not optional. SonarQube CE with `sonar.forceAuthentication=true` rejects unauthenticated API calls. Omit the token and you get a 401, which `curl -sf` translates to an empty response, which the regex cannot match, which means the gate appears to never resolve.

2. **The gate returns `OK` or `ERROR`.** Not `PASS`/`FAIL`. The function maps these to the pipeline result:

```groovy
if (gateStatus != 'OK') {
    return [status: 'FAILURE', gateStatus: gateStatus,
            error: "Quality gate status: ${gateStatus}"]
}
return [status: 'SUCCESS', gateStatus: gateStatus]
```

3. **Timeout handling.** If the Compute Engine is slow (large codebase, resource-constrained SonarQube), the gate may not resolve within the timeout. The function returns `UNKNOWN`, and the pipeline treats that as a failure. You can increase `timeoutMinutes` for large projects.

### 7.2 Try It Manually

You can simulate what the pipeline does from the command line:

```bash
SONAR_TOKEN="<your-token>"
PROJECT_KEY="sampleapi"

# Poll the quality gate
curl -sk -u "$SONAR_TOKEN:" \
  "$SONAR_URL/api/qualitygates/project_status?projectKey=$PROJECT_KEY" \
  | python3 -m json.tool

# Expected (after at least one analysis):
# {
#     "projectStatus": {
#         "status": "OK",            <-- or "ERROR" if gate failed
#         "conditions": [
#             {
#                 "status": "OK",
#                 "metricKey": "new_bugs",
#                 "comparator": "GT",
#                 "errorThreshold": "0",
#                 "actualValue": "0"
#             },
#             ...
#         ]
#     }
# }
```

If no analysis has been run yet, the API returns `"status": "NONE"`. That is expected -- there is no gate result until the first scan completes.

### 7.3 What Happens When the Gate Fails

When a developer introduces a vulnerability and pushes to a merge request, here is the chain of events:

```
1. GitLab webhook fires --> Jenkins starts T1 pipeline
2. Code is checked out, built, unit tested
3. scanSonarQube() runs the three-phase scan (begin/build/end)
4. Scanner uploads results to SonarQube server
5. Pipeline polls /api/qualitygates/project_status every 10 seconds
6. SonarQube CE finishes analysis, gate evaluates to "ERROR"
7. scanSonarQube() returns [status: 'FAILURE', gateStatus: 'ERROR']
8. Pipeline stage fails --> post{failure} block runs
9. Jenkins reports FAILURE status back to the GitLab MR
10. The MR shows a red X -- reviewer sees "Quality gate failed"
```

The developer sees the failure in their merge request within minutes of pushing. They fix the vulnerability, push again, and the cycle repeats. No vulnerable code reaches `main`.

---

## 8. Recap

Here is what we covered, end to end:

| Step | What | Why |
|------|------|-----|
| Deploy SonarQube | PostgreSQL + SonarQube CE with init containers for kernel tuning | Platform for all SAST analysis |
| Configure quality gate | 7 conditions on new code (bugs, vulns, coverage, duplication, ratings) | Automated enforcement -- not advisory |
| Create project + token | `sampleapi` project key, Global Analysis Token stored as K8s Secret | Pipeline identity and authentication |
| .NET scanner integration | Three-phase `begin`/`build`/`end` pattern, CLI args only | .NET scanner cannot use properties file |
| Pipeline function | `scanSonarQube.groovy` with `Map config`, `withCredentials`, structured return | Reusable across T1/T2/T3 triggers |
| Gate polling | `curl -u "$TOKEN:" .../api/qualitygates/project_status`, 10s interval, timeout | Fail the build when code does not meet the bar |

---

## 9. Common Mistakes

These are real issues encountered during implementation. Each one cost debugging time. Learn from them.

### Mistake 1: Using `sonar-project.properties` with .NET

```
# WRONG -- .NET SonarScanner ignores this file entirely
sonar-scanner -Dsonar.projectKey=sampleapi

# RIGHT -- use the dotnet global tool
dotnet sonarscanner begin /k:"sampleapi" /d:sonar.host.url="..."
dotnet build
dotnet sonarscanner end
```

The standalone `sonar-scanner` CLI works for Java, Python, JavaScript, Go, and many other languages. It does *not* work for .NET. The .NET scanner is a separate tool (`dotnet-sonarscanner`) that must wrap the build.

### Mistake 2: Missing Auth on API Calls

```bash
# WRONG -- returns 401 when forceAuthentication=true
curl -s "$SONAR_URL/api/qualitygates/project_status?projectKey=sampleapi"

# RIGHT -- token as username, empty password (note the trailing colon)
curl -s -u "$SONAR_TOKEN:" "$SONAR_URL/api/qualitygates/project_status?projectKey=sampleapi"
```

SonarQube CE defaults to `sonar.forceAuthentication=true`. Our ConfigMap explicitly sets this. Every API call except `/api/system/status` requires authentication.

### Mistake 3: Expecting Branch Analysis on CE

```groovy
// WRONG -- sonar.branch.name requires Developer Edition ($$$)
dotnet sonarscanner begin /d:sonar.branch.name="feature/login"
// Result: analysis fails or is silently ignored

// RIGHT -- use projectVersion to separate scan contexts on CE
dotnet sonarscanner begin /v:"MR-42-feature/login"
```

SonarQube Community Edition has exactly one branch: main. The `sonar.branch.name` parameter is a paid feature. Using `sonar.projectVersion` as a workaround gives you separate "New Code" baselines per version string, which is good enough for gate evaluation.

### Mistake 4: Using the Scanner's Built-In Gate Wait

```groovy
// FRAGILE -- blocks the Jenkins executor thread, no timeout control
/d:sonar.qualitygate.wait=true

// BETTER -- poll the API yourself with explicit timeout and error handling
/d:sonar.qualitygate.wait=false
// ...then call pollQualityGate() with configurable timeout
```

The built-in wait ties up a Jenkins executor with a sleeping thread. Our polling approach checks every 10 seconds, has a configurable timeout, and produces clear log messages about what is happening.

### Mistake 5: Forgetting Coverage Reports

```groovy
// WRONG -- scanner finds no coverage data, reports 0% coverage, gate fails
dotnet sonarscanner begin /k:"sampleapi"

// RIGHT -- point to the OpenCover report generated by dotnet test
dotnet sonarscanner begin /k:"sampleapi" \
    /d:sonar.cs.opencover.reportsPaths="**/coverage.opencover.xml"
```

The scanner does not generate coverage data. Your unit test step must produce an OpenCover-format XML report (via Coverlet), and you must tell the scanner where to find it. If the path is wrong or the file does not exist, coverage shows as 0% and the quality gate fails.

---

## 10. Challenge: Add Custom Quality Profile Rules

**Objective:** Create a custom quality profile that enforces additional rules beyond the default.

1. In SonarQube, go to **Quality Profiles** --> **C#** --> **Copy** the built-in Sonar way profile
2. Name it: `DevSecOps C# Profile`
3. Activate these additional rules:
   - **S2092** -- Cookies should be secure (Security)
   - **S5122** -- CORS policy should not be overly permissive (Security)
   - **S4830** -- Server certificates should be verified (Security)
   - **S2077** -- SQL queries should not be vulnerable to injection (Vulnerability)
4. Set this profile as the default for C# projects
5. Re-run an analysis and observe the additional findings in the dashboard

**Bonus:** Export the profile as XML (`GET /api/qualityprofiles/backup?language=cs&qualityProfile=DevSecOps+C%23+Profile`) and store it in the `build-config` repository. This makes your quality profile reproducible if you ever rebuild SonarQube from scratch.

---

## 11. Self-Assessment

Answer these without looking back at the module. If you cannot answer confidently, re-read the relevant section.

1. Why does SonarQube need an init container on OpenShift, and what kernel parameter does it set?
2. What is the difference between the standalone `sonar-scanner` CLI and `dotnet-sonarscanner`? When would you use each?
3. Your quality gate reports `status: "NONE"`. What does this mean, and what is the most likely cause?
4. Write the `curl` command to check the quality gate status for project key `myapp`, authenticating with token `sqa_abc123`.
5. A developer says "SonarQube is blocking my MR but the bug is in code I did not change." Explain why they are wrong (given our gate configuration).
6. Why do we set `sonar.qualitygate.wait=false` in the scanner and poll the API ourselves?
7. What happens if you use `sonar.branch.name` on SonarQube Community Edition?

<details>
<summary>Answers</summary>

1. The init container sets `vm.max_map_count` to 262144 (required by the embedded Elasticsearch). Without it, Elasticsearch refuses to start and SonarQube never becomes healthy.

2. `sonar-scanner` is a standalone CLI for non-.NET languages -- it reads `sonar-project.properties`. `dotnet-sonarscanner` is a dotnet global tool that wraps the build process (begin/build/end) to hook into the Roslyn compiler. Use `sonar-scanner` for Java/Python/JS/Go; use `dotnet-sonarscanner` for .NET.

3. `"NONE"` means no quality gate evaluation has been performed. Either no analysis has been submitted, or the Compute Engine has not finished processing. The most likely cause is that no scan has been run against this project yet.

4. `curl -s -u "sqa_abc123:" "https://sonarqube.example.com/api/qualitygates/project_status?projectKey=myapp"`

5. The quality gate conditions evaluate against **New Code** only (code changed since the last version baseline). If the developer did not change the code containing the bug, it would not appear as a "new bug." They likely introduced the issue in their own changes, or they changed a file that triggered a new analysis finding in code adjacent to their change.

6. The built-in wait blocks the Jenkins executor thread with no timeout control. Polling the API ourselves gives us configurable timeouts, retry intervals, and clear log messages.

7. The parameter is silently ignored or causes an error (depending on version). Branch analysis is a paid feature in the Developer, Enterprise, and Data Center editions. On CE, use `sonar.projectVersion` as a workaround.

</details>

---

## 12. What's Next

You now have SAST enforced as a pipeline gate. Code with bugs or vulnerabilities cannot reach `main`. But source code analysis is only one layer.

In **Module 6: Container Security with ACS (StackRox)**, we add the next layer -- scanning the *container image* for OS-level CVEs, misconfigurations (running as root, writable filesystems), and policy violations. If SAST asks "is the code safe?", ACS asks "is the container safe to run in our cluster?"

Together, SAST + container scanning + DAST (Module 7) form the security triad that makes our pipeline production-grade.

---

*Module 5 of the DevSecOps Tutorial Series -- Security Track*
*Files referenced in this module:*
- *`infra/phase3/` -- SonarQube deployment manifests*
- *`jenkins-shared-lib/vars/scanSonarQube.groovy` -- Pipeline SAST function*
- *`build-config/sonar-project.properties` -- Scanner configuration reference (documentation only for .NET)*
