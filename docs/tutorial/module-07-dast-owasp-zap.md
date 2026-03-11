# Module 7: DAST with OWASP ZAP

**Track:** Security | **Duration:** ~45 minutes | **Difficulty:** Intermediate

---

## What You'll Learn

By the end of this module you will be able to:

- Explain why DAST exists and what it catches that SAST cannot.
- Run OWASP ZAP as a sidecar container on OpenShift alongside a Jenkins agent.
- Drive ZAP through its REST API to spider, scan, and generate reports.
- Archive HTML and JSON scan reports as Jenkins pipeline artifacts.
- Tune scan policies so ZAP focuses on what matters for your API.

---

## Prerequisites

Before starting this module, confirm:

> **Environment variables:** Before running any commands, source the environment file:
> ```bash
> source ./env.sh
> ```
> This sets `$OC`, `$APPS_DOMAIN`, `$NS_TOOLS`, and all other cluster-specific variables used throughout this module. See `env.sh` for the full variable list.

1. A .NET application is **deployed and reachable** in the DEV environment.
   ```bash
   curl -sk https://sampleapi-${NS_DEV}.${APPS_DOMAIN}/healthz
   # Expected: Healthy
   ```
2. Jenkins is running with the custom agent image (`devsecops-agent`).
3. You have completed Module 6 (ACS Image Scanning) or are comfortable with the shared library pattern.

---

## 1. Concepts: Why Test the Running Application?

### The Gap That SAST Cannot Fill

Every security tool you have used so far -- SonarQube, Dependency-Check, ACS image scanning -- examines code or artifacts **at rest**. None of them can tell you what happens when a real HTTP request hits your application with a malicious payload.

Consider a simple controller that reads a query parameter and passes it into a SQL query. SonarQube might flag the code pattern, but it cannot confirm whether the parameter actually reaches the database unsanitized at runtime. Framework middleware, input validation attributes, connection pooling wrappers, and WAF rules all intervene between the source code and the live behavior. Only a test against the **running** application reveals the actual exploitable surface.

That is what Dynamic Application Security Testing (DAST) does. It acts like an external attacker: it sends crafted requests, observes the responses, and reports what it finds.

### SAST vs DAST: Where Each Fits

| Dimension | SAST (SonarQube) | DAST (OWASP ZAP) |
|-----------|------------------|-------------------|
| **What it examines** | Source code, bytecode | Running application via HTTP |
| **When it runs** | Before the app is built or deployed | After the app is deployed and reachable |
| **What it finds** | Code smells, insecure patterns, hardcoded secrets | Injection flaws, misconfigurations, header issues, auth bypasses |
| **False positives** | Higher -- cannot confirm runtime behavior | Lower -- confirms exploitability at runtime |
| **False negatives** | Misses runtime-only issues (CORS, headers, auth flows) | Misses code-level issues it cannot reach via HTTP |
| **Speed** | Fast (seconds to minutes) | Slower (minutes to tens of minutes) |
| **Impact on target** | None -- reads source only | Active scans send attack payloads -- never run against production |

The key takeaway: SAST and DAST are complementary. Run both. SAST catches problems early and cheaply; DAST catches problems that only manifest at runtime.

### OWASP Top 10 and ZAP

OWASP ZAP's scan rules map directly to the [OWASP Top 10](https://owasp.org/www-project-top-ten/) categories. When ZAP reports "SQL Injection" or "Cross-Site Scripting," both map to A03:2021-Injection (XSS was merged into the Injection category in the 2021 edition). Missing security headers map to A05:2021-Security Misconfiguration. The scan policy you configure determines which of these rules fire and how aggressively they probe.

### Two Scan Modes

ZAP operates in two modes that matter for CI/CD:

- **Baseline (passive only):** ZAP spiders the application, observes responses, and flags issues like missing security headers, insecure cookies, and information disclosure. It sends no attack payloads. Safe, fast, and suitable for every pipeline run.
- **Active:** After the passive scan, ZAP sends attack payloads -- SQL injection strings, XSS vectors, path traversal sequences -- to every discovered parameter. Slower, thorough, and must never target production.

In our pipeline, T3 (tag/release) runs a baseline scan by default. You can switch to active mode for pre-release validation.

---

## 2. Architecture: The Sidecar Pattern

### Why ZAP Cannot Live in the Agent Image

The custom Jenkins agent image (`devsecops-agent`) already contains the .NET SDK, Podman, roxctl, sonar-scanner, Dependency-Check, gitleaks, cosign, and kustomize. The ZAP Docker image (`ghcr.io/zaproxy/zaproxy:stable`) adds another 1+ GB of tools, Java runtime, and scan rules.

Baking ZAP into the agent image would make every pipeline -- including T1 (merge request) and T2 (merge to main), which never run DAST -- pay the cost of pulling a multi-gigabyte image. That is wasteful.

Instead, ZAP runs as a **sidecar container** in the agent pod, but only when the T3 pipeline needs it.

### How the Sidecar Works

```
┌─────────────────────────────────────────────────────┐
│              Jenkins Agent Pod (T3)                  │
│                                                     │
│  ┌───────────────────┐    ┌──────────────────────┐  │
│  │   jnlp container  │    │    zap container     │  │
│  │                    │    │                      │  │
│  │  dotnet, podman,   │    │  ZAP daemon mode     │  │
│  │  roxctl, scanner   │    │  listening on        │  │
│  │                    │    │  localhost:8090       │  │
│  │  Pipeline steps    │    │                      │  │
│  │  run here          │    │  REST API available  │  │
│  │        │           │    │  at /JSON/...        │  │
│  │        │           │    │        ^             │  │
│  │        └───────────┼────┼────────┘             │  │
│  │         curl calls │    │   via localhost      │  │
│  └───────────────────┘    └──────────────────────┘  │
│                                                     │
│  Containers share the same network namespace        │  <-- This is why localhost works
│  (standard Kubernetes pod behavior)                 │
└─────────────────────────────────────────────────────┘
         │
         │  ZAP sends HTTP requests
         v
┌─────────────────────┐
│  DEV Environment    │
│  sampleapi-dev pod  │
│  (the scan target)  │
└─────────────────────┘
```

Containers within the same Kubernetes pod share a network namespace. That means the jnlp container can reach ZAP on `localhost:8090`, and ZAP can reach the DEV application's external route over the cluster network. No extra Service or Route is needed for the communication between jnlp and ZAP.

---

## 3. Step 1: Define the ZAP Sidecar in the Pipeline

Open the T3 orchestrator to see how the sidecar is declared.

**File:** `jenkins-shared-lib/vars/pipelineTag.groovy` (lines 37-70)

```groovy
agent {
    kubernetes {
        inheritFrom 'devsecops-agent'       // <-- Inherits the jnlp container
                                            //     with dotnet, podman, roxctl, etc.
        yaml """
apiVersion: v1
kind: Pod
spec:
  securityContext:
    runAsUser: 0                            // <-- ZAP needs root inside its container.
                                            //     inheritFrom does NOT reliably merge
                                            //     the base template's securityContext,
                                            //     so we set it explicitly here.
  containers:
  - name: zap
    image: ghcr.io/zaproxy/zaproxy:stable   // <-- The official ZAP image
    command:
    - zap.sh                                // <-- Start ZAP in daemon mode (no GUI)
    args:
    - -daemon
    - -host
    - '0.0.0.0'                             // <-- Listen on all interfaces inside pod
    - -port
    - '8090'                                // <-- The port scanOWASPZAP.groovy connects to
    - -config
    - api.addrs.addr.name=.*                // <-- Allow API calls from any address
    - -config
    - api.addrs.addr.regex=true
    - -config
    - api.disablekey=true                   // <-- Disable API key (pod-internal only)
    resources:
      requests:
        cpu: 200m
        memory: 256Mi
      limits:
        cpu: 500m
        memory: 1Gi
"""
    }
}
```

Three things to note:

1. **`inheritFrom 'devsecops-agent'`** -- the pod still has the full jnlp container with all CI tools. The YAML override *adds* the ZAP container alongside it; it does not replace it.

2. **`runAsUser: 0`** at the pod level -- this is required because `inheritFrom` does not reliably merge the base template's security context. Without this, ZAP cannot bind to port 8090. This was a real production bug that took hours to diagnose.

3. **Resource limits** -- ZAP is capped at 500m CPU and 1Gi memory. The `devsecops-tools` namespace quota was increased from 8CPU/16Gi to 16CPU/32Gi specifically to accommodate this sidecar alongside Jenkins, SonarQube, and SonarQube's PostgreSQL.

> **Lesson learned:** Before adding a sidecar, always check your namespace ResourceQuota. A pod that exceeds the quota will stay in `Pending` forever with no obvious error unless you check `$OC get events`.

---

## 4. Step 2: Understand the ZAP Scan Integration

The scan logic lives in a single shared library function. Walk through it section by section.

**File:** `jenkins-shared-lib/vars/scanOWASPZAP.groovy`

### 4.1 Function Signature and Defaults

```groovy
def call(Map config = [:]) {
    def target = config.target ?: ''            // <-- URL of the deployed app to scan
    def reportDir = config.reportDir ?: 'zap-report'
    def scanType = config.scanType ?: 'baseline'  // 'baseline' or 'active'
    def zapPort = config.zapPort ?: 8090           // Must match the sidecar port
    def zapUrl = "http://localhost:${zapPort}"      // Sidecar is on localhost
    def spiderTimeoutMin = config.spiderTimeoutMin ?: 2
    def activeScanTimeoutMin = config.activeScanTimeoutMin ?: 10
```

Every parameter has a sensible default. The caller (pipelineTag.groovy) passes the minimum:

```groovy
results.owaspZap = scanOWASPZAP(
    target: devRoute,       // <-- The DEV environment URL
    scanType: 'baseline',   // <-- Passive only for speed
    zapPort: 8090           // <-- Matches sidecar config
)
```

### 4.2 Wait for ZAP to Be Ready

ZAP's daemon mode takes a few seconds to initialize its Java runtime and load scan rules. The function polls until ZAP responds:

```groovy
// Wait for ZAP sidecar to be ready
for (int i = 0; i < 30; i++) {                                    // <-- Up to 150 seconds
    def rc = sh(
        script: "curl -sf ${zapUrl}/JSON/core/view/version/ "
              + "2>/dev/null | grep -q 'version'",
        returnStatus: true
    )
    if (rc == 0) {
        zapReady = true
        break
    }
    sleep(5)                                                       // <-- Poll every 5 seconds
}
```

This is a critical pattern for sidecar architectures. The jnlp container starts first (it is the main container), but the ZAP sidecar might not be ready yet. Without this readiness loop, the scan would fail immediately with a "connection refused" error.

### 4.3 Verify the Target Is Reachable

Before asking ZAP to scan, confirm the target application is actually up:

```groovy
def targetReachable = sh(
    script: "curl -sk --max-time 15 -o /dev/null "
          + "-w '%{http_code}' '${target}/healthz' 2>/dev/null || echo '000'",
    returnStdout: true
).trim()
```

If the DEV deployment is broken, the scan is skipped with `status: 'SKIPPED'` rather than failing. This prevents a deployment issue in DEV from blocking a tag release.

### 4.4 Spider: Discover Endpoints

ZAP's spider crawls the application starting from the target URL, following links and API paths to build a site map:

```groovy
// Start the spider via REST API
def spiderResponse = sh(
    script: "curl -sf '${zapUrl}/JSON/spider/action/scan/"
          + "?url=${target}&maxChildren=10&recurse=true'",
    returnStdout: true
).trim()

// Poll until the spider reaches 100%
for (int elapsed = 0; elapsed < spiderTimeout; elapsed += 5) {
    def status = sh(
        script: "curl -sf '${zapUrl}/JSON/spider/view/status/' "
              + "| python3 -c \"import json,sys; "
              + "print(json.load(sys.stdin).get('status','0'))\"",
        returnStdout: true
    ).trim()
    if (status == '100') { break }
    sleep(5)
}
```

The spider is the foundation. It determines *what* ZAP tests. If the spider misses an endpoint (for example, one that requires authentication or is only accessible via POST), ZAP will not test it. For REST APIs, the spider typically discovers the root and any linked resources, but it may miss endpoints that are not linked from any response. You can address this with an OpenAPI import, covered in the Challenge section.

### 4.5 Passive Scan: Observe Without Attacking

The passive scan runs automatically during spidering. Every response ZAP receives is analyzed for issues like:

- Missing `Content-Security-Policy` header
- Missing `X-Content-Type-Options` header
- Cookies without `Secure` or `HttpOnly` flags
- Server version disclosure
- Information leakage in error pages

```groovy
// Wait for passive scan to finish processing all responses
for (int i = 0; i < 30; i++) {
    def recordsToScan = sh(
        script: "curl -sf '${zapUrl}/JSON/pscan/view/recordsToScan/' "
              + "| python3 -c \"import json,sys; "
              + "print(json.load(sys.stdin).get('recordsToScan','0'))\" "
              + "2>/dev/null || echo '0'",
        returnStdout: true
    ).trim()
    // When recordsToScan drops to 0, passive scanning is done
    if (recordsToScan == '0') { break }
    echo "  Passive scan: ${recordsToScan} records remaining..."
    sleep(5)
}
```

The passive scan is safe for any environment because it only observes -- it sends no extra requests beyond what the spider already sent.

### 4.6 Active Scan: Send Attack Payloads (Optional)

When `scanType` is set to `'active'`, ZAP replays requests with attack payloads:

```groovy
if (scanType == 'active') {
    def ascanResponse = sh(
        script: "curl -sf '${zapUrl}/JSON/ascan/action/scan/"
              + "?url=${target}&recurse=true&inScopeOnly=false'",
        returnStdout: true
    ).trim()

    // Poll until active scan reaches 100% or times out
    for (int elapsed = 0; elapsed < activeScanTimeout; elapsed += 10) {
        // ... poll status ...
        sleep(10)
    }
}
```

> **Warning:** Active scanning sends real attack payloads -- SQL injection strings, XSS vectors, path traversal attempts. Never point an active scan at a production environment. In this project, DAST targets the DEV endpoint only.

### 4.7 Collect Results and Generate Reports

After scanning, the function retrieves the alert summary and generates reports:

```groovy
// Get the alert summary from ZAP
def alertsSummary = sh(
    script: "curl -sf '${zapUrl}/JSON/alert/view/alertsSummary/"
          + "?baseurl=${target}'",
    returnStdout: true
).trim()

// Generate HTML report (human-readable)
sh "curl -sf '${zapUrl}/OTHER/core/other/htmlreport/' "
 + "-o '${reportDir}/zap-report.html'"

// Generate JSON report (machine-parseable)
sh "curl -sf '${zapUrl}/JSON/alert/view/alerts/"
 + "?baseurl=${target}&start=0&count=500' "
 + "-o '${reportDir}/zap-report.json'"

// Archive as Jenkins build artifacts
archiveArtifacts artifacts: "${reportDir}/**", allowEmptyArchive: true
```

The function returns a structured result map that the orchestrator and `SecurityGate` class can evaluate:

```groovy
return [
    status: status,         // 'SUCCESS', 'UNSTABLE', or 'FAILURE'
    duration: duration,     // Scan time in seconds
    findings: totalAlerts,  // Total alert count
    high: highAlerts,       // High-severity count
    medium: mediumAlerts,
    low: lowAlerts,
    info: infoAlerts,
    reportDir: reportDir,
    urlsDiscovered: urlsFound
]
```

### 4.8 How the Orchestrator Uses the Results

Back in `pipelineTag.groovy`, the result drives the pipeline status:

```groovy
results.owaspZap = scanOWASPZAP(
    target: devRoute,
    scanType: 'baseline',
    zapPort: 8090
)

if (results.owaspZap.high > 0) {
    unstable("ZAP found ${results.owaspZap.high} high-severity alerts")
    // <-- Pipeline becomes UNSTABLE, not FAILED.
    //     This is a deliberate choice: DAST findings on DEV may include
    //     false positives from the dev environment itself. The team
    //     reviews the HTML report and decides whether to block promotion.
}
```

And in `SecurityGate.groovy`, ZAP failures contribute to the overall gate evaluation:

```groovy
if (results.owaspZap?.status == 'FAILURE') {
    failures.add("OWASP ZAP DAST scan FAILED: "
        + "${results.owaspZap?.error ?: 'vulnerabilities found'}")
}
```

---

## 5. Step 3: The ZAP Scan Policy

A scan policy controls which rules ZAP runs and how aggressively it probes. Without tuning, ZAP's default rules produce many false positives on REST APIs -- for example, flagging missing `X-Frame-Options` on a JSON endpoint that will never be rendered in a browser.

**File:** `build-config/zap-scan-config.yaml`

```yaml
env:
  contexts:
    - name: "SampleApi"
      urls:
        - "https://sampleapi-sampleapi-dev.apps.example.com"  # <-- REPLACE with your cluster's DEV route URL
      includePaths:
        - "https://sampleapi-sampleapi-dev.apps.example.com/api/.*"  # <-- Only scan API endpoints
      excludePaths:
        - "https://sampleapi-sampleapi-dev.apps.example.com/healthz"  # <-- Skip health probes
        - "https://sampleapi-sampleapi-dev.apps.example.com/readyz"   #     (they clutter results)

scanPolicy:
  name: "SampleApi-Policy"
  rules:
    # --- Disable rules that are noise for REST APIs ---
    - id: 10020    # X-Frame-Options -- irrelevant for JSON responses
      threshold: "OFF"
    - id: 10038    # Content Security Policy -- irrelevant for APIs
      threshold: "OFF"

    # --- Enable critical rules at higher sensitivity ---
    - id: 40018    # SQL Injection
      threshold: "MEDIUM"
      strength: "HIGH"
    - id: 40012    # Cross-Site Scripting
      threshold: "MEDIUM"
      strength: "HIGH"
    - id: 6        # Path Traversal
      threshold: "MEDIUM"
      strength: "MEDIUM"
    - id: 7        # Remote File Inclusion
      threshold: "MEDIUM"
      strength: "MEDIUM"
    - id: 40046    # Server Side Request Forgery
      threshold: "MEDIUM"
      strength: "MEDIUM"
```

The `threshold` controls how easily a rule triggers an alert (LOW = very sensitive, MEDIUM = balanced, HIGH = conservative, OFF = disabled). The `strength` controls how many attack variations ZAP tries (LOW = few, MEDIUM = moderate, HIGH = exhaustive).

Note that the URLs in the config file are placeholders (`apps.example.com`). The `scanOWASPZAP.groovy` function does not use this file directly -- it drives ZAP through its REST API and passes the target URL as a parameter. The config file serves as a reference for what a production Automation Framework plan looks like.

> **Note:** The current `scanOWASPZAP.groovy` drives ZAP via its REST API rather than the Automation Framework plan file. The `zap-scan-config.yaml` serves as a reference for what a production scan policy looks like. To use it directly, you would start ZAP with `-autorun zap-scan-config.yaml` instead of calling the REST API. The Challenge section at the end explores this.

---

## 6. Step 4: Run ZAP in the Pipeline

### Trigger a T3 Pipeline

To see ZAP in action, push a version tag to the app-source repository:

```bash
cd /path/to/app-source
git tag -a v1.5.0 -m "Release v1.5.0 -- test DAST"
git push origin v1.5.0
```

This triggers the T3 (tag) pipeline in Jenkins. Watch the build at:

```
${JENKINS_URL}/job/sampleapi-tag/
```

### What Happens Internally

1. Jenkins schedules an agent pod with **two containers**: the jnlp agent and the ZAP sidecar.
2. The pipeline runs checkout, build, unit tests, SonarQube, Dependency-Check, image build, push, and ACS scan -- all in the jnlp container.
3. When the "OWASP ZAP DAST" stage starts, `scanOWASPZAP.groovy` begins polling `localhost:8090` for ZAP readiness.
4. Once ready, the function spiders the DEV endpoint, waits for passive scanning to complete, and collects results.
5. HTML and JSON reports are archived as build artifacts.
6. The agent pod is destroyed, taking the ZAP sidecar with it.

### Verify the Scan Ran

In the Jenkins build console output, look for:

```
=== OWASP ZAP DAST Scan ===
  Target: https://sampleapi-${NS_DEV}.${APPS_DOMAIN}
  Scan type: baseline
  ZAP API: http://localhost:8090
Waiting for ZAP daemon to be ready...
ZAP daemon ready: {"version":"2.x.x"}
Target health check: HTTP 200
--- Step 1: Spidering target ---
Spider started: {"scan":"0"}
  Spider progress: 50%
Spider complete (100%)
URLs discovered: 5
--- Step 2: Passive scan (automatic during spider) ---
Passive scan complete
--- Skipping active scan (baseline mode) ---
--- Step 4: Collecting results ---
ZAP Results: High=0, Medium=1, Low=3, Info=2, Total=6
--- Step 5: Generating reports ---
Reports saved to zap-report/
OWASP ZAP DAST scan completed in 45s
```

---

## 7. Step 5: Review the HTML Report

After the pipeline completes, download the ZAP report:

1. Open the Jenkins build page for `sampleapi-tag` build.
2. Click **"Build Artifacts"** in the left sidebar.
3. Open `zap-report/zap-report.html`.

The HTML report contains:

- **Summary table** -- alert counts by risk level (High, Medium, Low, Informational).
- **Alert details** -- for each finding, ZAP shows the URL tested, the parameter exploited (if any), the request/response evidence, and a remediation recommendation.
- **Confidence rating** -- how certain ZAP is that the finding is real (High, Medium, Low, False Positive).

Common findings on a default .NET API:

| Finding | Risk | Typical Cause | Action |
|---------|------|---------------|--------|
| Missing `X-Content-Type-Options` | Low | ASP.NET does not add it by default | Add middleware: `app.Use((ctx, next) => { ctx.Response.Headers["X-Content-Type-Options"] = "nosniff"; return next(); });` |
| Server Leaks Version Info | Low | `Server: Kestrel` header | Set `KestrelServerOptions.AddServerHeader = false` |
| Missing `Strict-Transport-Security` | Low | HSTS not configured | Add `app.UseHsts()` in production |
| Cookie Without `SameSite` | Medium | Session cookie default | Configure cookie options in `AddAuthentication()` |

The JSON report (`zap-report.json`) contains the same data in machine-parseable format, useful for integrating with ticketing systems or dashboards.

---

## 8. Recap

Here is what you covered in this module:

- **DAST tests the running application.** It catches runtime issues that SAST cannot see: missing security headers, injection flaws that survive framework defenses, authentication bypasses.

- **ZAP runs as a sidecar** in the Jenkins agent pod, added only for T3 (tag) pipelines. This avoids bloating the agent image and keeps T1/T2 pipelines lean.

- **Communication happens over localhost.** Containers in a Kubernetes pod share a network namespace. The jnlp container drives ZAP through its REST API on port 8090.

- **The scan flow is: spider, passive scan, (optional) active scan, report.** The spider discovers endpoints. Passive scanning observes responses. Active scanning sends attack payloads.

- **Reports are archived as pipeline artifacts.** HTML for humans, JSON for machines. Both are stored with each build.

- **High-severity findings mark the pipeline as UNSTABLE**, not FAILED. This is a team decision -- the HTML report is reviewed before blocking promotion.

---

## 9. Common Mistakes

These are real issues encountered during implementation. Each one cost significant debugging time.

### Mistake 1: Baking ZAP into the Agent Image

**Symptom:** Agent image is 5+ GB. Every pipeline takes minutes just to pull the image, even when DAST is not needed.

**Fix:** Use the sidecar pattern. ZAP is only added to the pod for T3 pipelines via `yaml` override in the `kubernetes` agent block.

### Mistake 2: Missing `runAsUser: 0` in the Sidecar Pod

**Symptom:** ZAP container starts but immediately crashes with a permission error. `oc logs <pod> -c zap` shows "Permission denied" when trying to bind port 8090.

**Fix:** Set `runAsUser: 0` explicitly at the pod level in the yaml override:

```yaml
spec:
  securityContext:
    runAsUser: 0
```

The `inheritFrom` directive does **not** reliably merge the base template's security context. You must set it again in the child template.

### Mistake 3: Namespace Quota Too Low

**Symptom:** The T3 agent pod stays in `Pending`. `$OC get events -n $NS_TOOLS` shows `exceeded quota`.

**Fix:** The T3 pod needs resources for *both* the jnlp container (2 CPU / 4Gi) and the ZAP sidecar (500m CPU / 1Gi), plus Jenkins itself and SonarQube in the same namespace. Increase the namespace quota:

```bash
# Original quota was 8 CPU / 16Gi -- not enough
# Updated to 16 CPU / 32Gi
$OC patch resourcequota devsecops-tools-quota -n $NS_TOOLS \
  -p '{"spec":{"hard":{"limits.cpu":"16","limits.memory":"32Gi"}}}'
```

### Mistake 4: Scanning the Wrong URL

**Symptom:** ZAP reports zero findings and zero URLs discovered.

**Fix:** The target URL must be the **externally routable** URL of the application, not the internal service name. From inside the agent pod, the external route is reachable via the cluster's ingress:

```
# Correct: external route
https://sampleapi-${NS_DEV}.${APPS_DOMAIN}

# Wrong: internal service (ZAP cannot resolve this in daemon mode)
http://sampleapi.sampleapi-dev.svc:8080
```

### Mistake 5: No Readiness Check Before Scanning

**Symptom:** The scan fails with "Connection refused to localhost:8090."

**Fix:** ZAP takes 10-30 seconds to start. Always poll the `/JSON/core/view/version/` endpoint before beginning the spider. The implementation uses a loop with 30 attempts at 5-second intervals (up to 150 seconds).

---

## 10. Challenge: Customize the Scan Policy

Now that you understand the baseline implementation, try these modifications:

### Challenge A: Switch to Active Scanning

In `pipelineTag.groovy`, change the `scanType` parameter:

```groovy
results.owaspZap = scanOWASPZAP(
    target: devRoute,
    scanType: 'active',    // <-- Change from 'baseline' to 'active'
    zapPort: 8090,
    activeScanTimeoutMin: 15
)
```

Push a new tag and observe the difference in scan duration and findings. Active scans typically take 5-15 minutes compared to 30-60 seconds for baseline.

### Challenge B: Import an OpenAPI Specification

ZAP's spider may miss API endpoints that are not linked from any response. If your API exposes a Swagger/OpenAPI spec, you can import it before scanning:

```groovy
// Add this before the spider step in scanOWASPZAP.groovy:
sh """
    curl -sf '${zapUrl}/JSON/openapi/action/importUrl/' \
        --data-urlencode 'url=${target}/swagger/v1/swagger.json' \
        2>/dev/null || echo 'OpenAPI import skipped'
"""
```

This gives ZAP a complete list of endpoints, parameters, and expected request bodies -- dramatically improving coverage for REST APIs.

### Challenge C: Use the Automation Framework Plan File

Instead of driving ZAP via individual REST API calls, use the Automation Framework plan file (`build-config/zap-scan-config.yaml`). This requires changing the sidecar command to:

```yaml
command:
  - zap.sh
args:
  - -cmd
  - -autorun
  - /zap/wrk/zap-scan-config.yaml
```

You would mount `zap-scan-config.yaml` into the ZAP container via a ConfigMap or by copying it from the workspace. This approach is more declarative and easier to version-control.

---

## 11. Self-Assessment

Answer these questions to confirm your understanding:

1. **Why does DAST only run in T3 (tag pipeline) and not T1 or T2?**
   DAST requires a running, deployed application. T1 validates a merge request (no deployment). T2 deploys to DEV, but adding DAST would make every merge-to-main pipeline 5-15 minutes longer. T3 is the release gate where thorough scanning justifies the time cost.

2. **Why does the ZAP sidecar use `api.disablekey=true`?**
   The ZAP API key is a security feature to prevent unauthorized access. Inside a pod, only the jnlp container can reach localhost:8090 -- there is no external exposure. Disabling the key simplifies the REST API calls.

3. **What is the difference between `threshold` and `strength` in a scan policy rule?**
   Threshold controls how easily a rule triggers an alert (sensitivity). Strength controls how many attack variations ZAP tries (thoroughness). A rule with LOW threshold and HIGH strength is very sensitive and very thorough -- it will find more issues but take longer.

4. **Why does the pipeline use `unstable()` instead of `error()` for high-severity ZAP findings?**
   The UNSTABLE status signals "review needed" without blocking the pipeline entirely. DAST findings on a DEV endpoint may include false positives caused by the development environment itself (for example, missing HSTS because DEV uses a self-signed certificate). The team reviews the HTML report and decides whether the finding is real before approving the promotion MR to SIT.

5. **What would happen if you ran an active scan against production?**
   ZAP would send SQL injection payloads, XSS vectors, and path traversal attacks to the production application. This could corrupt data, trigger WAF blocks, flood logs with security alerts, or cause outages. Never target production with an active scan.

---

## 12. What's Next

**Module 8: The 3-Trigger Pipeline** brings together everything from Modules 1-7. You will see how the three pipeline orchestrators (T1: merge request, T2: merge to main, T3: git tag) plus the promotion trigger (T4) orchestrate checkout, build, SAST, SCA, image build, ACS scan, DAST, performance testing, and GitOps deployment into a cohesive workflow -- each trigger running exactly the stages it needs, no more, no less.
