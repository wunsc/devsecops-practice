# Module 14: Performance Testing as Quality Gate

**Track:** Advanced (Modules 14-15)
**Duration:** ~60 minutes
**Prerequisites:** Modules 1-13 complete. T3 (tag) pipeline operational. DEV environment deployed and reachable.

---

## What You'll Learn

By the end of this module you will be able to:

1. Explain why performance testing belongs in CI/CD, not after release
2. Write a k6 load test script with ramping stages (ramp up, sustained, ramp down)
3. Define threshold-based performance gates that fail the pipeline automatically
4. Integrate k6 into the T3 (tag/release) pipeline as a first-class quality gate
5. Interpret k6 results and fix performance regressions before they ship
6. Archive performance summaries as Jenkins build artifacts

---

## Prerequisites

Before starting this module, confirm the following:

- T3 pipeline runs end-to-end (tag push triggers build, scan, DAST, push to registry)
- DEV environment has a running instance of the application (the target for load tests)
- Jenkins agent image is built from `Dockerfile.agent` (you will add k6 to it)
- You are comfortable editing Jenkins shared library functions (`vars/*.groovy`)

> **Environment variables:** Before running any commands, source the environment file:
> ```bash
> source ./env.sh
> ```
> This sets `$OC`, `$APPS_DOMAIN`, `$NS_TOOLS`, and all other cluster-specific variables used throughout this module. See `env.sh` for the full variable list.

Verify DEV is reachable:

```bash
curl -sk $APP_DEV_URL/healthz
# --> Expected:
# {"status":"healthy","timestamp":"2026-03-10T05:25:02.3751347Z"}
```

---

## 1. Concepts: Why Performance Testing in CI/CD

### The Problem With Late Performance Testing

Most teams discover performance problems in UAT or production. By then, the code change that introduced the regression is weeks old, buried under dozens of other commits, and the developer who wrote it has moved on to something else. Fixing it requires archaeology, not engineering.

### Shift-Left Performance

The same principle that drives shift-left security applies to performance: **catch regressions at the point where they are cheapest to fix -- during the release pipeline, before the image is promoted.**

In this project's pipeline model:

```
T1 (MR)     -- Unit tests, SAST, SCA       --> fast feedback, no deploy
T2 (Merge)  -- Build image, deploy DEV     --> functional validation
T3 (Tag)    -- DAST + Performance + Sign   --> release-readiness gate  <-- YOU ARE HERE
```

Performance testing lives in T3 because:

- **T1 is too early** -- there is no running application to test against.
- **T2 deploys to DEV** -- the application is available, but T2 runs on every merge. Load testing every merge wastes cluster resources and slows the feedback loop.
- **T3 is the release gate** -- it already runs DAST (ZAP) against DEV. Adding a load test here means the release-ready image has been validated for both security AND performance before any human approves promotion to SIT.

### k6 Architecture

k6 is a load testing tool written in Go that executes test scripts written in JavaScript. It runs entirely from the command line, produces structured output, and exits with a non-zero code when thresholds are breached.

```
                    +-----------------------+
                    |   Jenkins Agent Pod   |
                    |                       |
                    |  +--------+           |       +-------------------+
                    |  |  k6    |  HTTP ---------> | DEV Environment   |
                    |  | (CLI)  |  requests  |      | (sampleapi pod)   |
                    |  +--------+           |       +-------------------+
                    |       |               |
                    |       v               |
                    |  summary.json         |
                    |  (archived artifact)  |
                    +-----------------------+

k6 exit codes:
  0   -- all thresholds passed
  99  -- one or more thresholds breached    <-- pipeline fails here
  1   -- script error or runtime failure
```

The critical detail: **k6 exits with code 99 when thresholds are breached.** Jenkins sees a non-zero exit code and marks the stage as failed. A failed stage in T3 means the image is never signed, never pushed to the registry, and no promotion MR is created. The developer must fix the regression and push a new tag to re-trigger T3.

---

## 2. Step 1: Install k6 in the Jenkins Agent Image

k6 must be available in the Jenkins agent container. You will add it to `Dockerfile.agent`.

### TELL: Why a binary install

k6 is a single static binary. No runtime dependencies, no package manager conflicts. Download it, make it executable, done. This is the same pattern used for `roxctl`, `cosign`, `kustomize`, and other CLI tools already in the agent image.

### SHOW: The Dockerfile change

Open `infra/phase7/Dockerfile.agent` and add the k6 installation block after the existing tool installations (after the ArgoCD CLI block, before the Jenkins agent setup section):

```dockerfile
# --- k6 (load testing) ---
# k6 exits with code 99 when thresholds are breached, which Jenkins
# interprets as a stage failure — making it a natural quality gate.
# https://grafana.com/docs/k6/latest/
ENV K6_VERSION=0.54.0
RUN curl -sL "https://github.com/grafana/k6/releases/download/v${K6_VERSION}/k6-v${K6_VERSION}-linux-amd64.tar.gz" \
        | tar xz --strip-components=1 -C /usr/local/bin k6-v${K6_VERSION}-linux-amd64/k6 && \
    chmod +x /usr/local/bin/k6
```

### DO: Add the block

Edit the file. Place it between the ArgoCD CLI installation and the `# --- Jenkins agent setup ---` comment. Then add `k6 version` to the verification `RUN` at the bottom of the file:

```dockerfile
RUN dotnet --version && \
    podman --version && \
    gitleaks version && \
    kustomize version && \
    k6 version && \
    dependency-check --version || true && \
    echo "All tools installed successfully"
```

### VERIFY: Rebuild the agent image

```bash
# Trigger a new build of the agent image
$OC start-build jenkins-agent-devsecops -n $NS_TOOLS --follow

# Confirm k6 is present in the new image
$OC run k6-test --rm -i --restart=Never \
    --image=image-registry.openshift-image-registry.svc:5000/$NS_TOOLS/jenkins-agent-devsecops:latest \
    -- k6 version
# --> Expected: k6 v0.54.0 (...)
```

> **NOTE:** If you have `alwaysPullImage: true` in the JCasC pod template (and you should -- see Module 3), new agent pods will automatically pick up the rebuilt image. No Jenkins restart needed.

---

## 3. Step 2: Write the k6 Load Test Script

### TELL: Script design principles

A good load test script answers three questions:

1. **Can the application handle normal load?** (sustained phase)
2. **Does it degrade gracefully under ramp-up?** (ramp-up phase)
3. **Does it recover after load drops?** (ramp-down phase)

The script should exercise the same endpoints that real users hit. For the SampleApi, that means `/healthz` (lightweight) and `/api/WeatherForecast` (the actual business endpoint).

### SHOW: The test script

Create the performance test directory and script in the `build-config` repository (not `app-source` -- build and test configurations live in `build-config`, per project Rule 2):

**File: `build-config/tests/performance/load-test.js`**

```javascript
// build-config/tests/performance/load-test.js
// k6 load test — SampleApi WeatherForecast endpoint
//
// Validates performance thresholds that act as a quality gate in the T3
// (tag/release) pipeline. If any threshold is breached, k6 exits with
// code 99, which the Jenkins pipeline interprets as GATE FAILED.
//
// USAGE:
//   k6 run --env BASE_URL=https://sampleapi-dev.apps.example.com load-test.js
//
// THRESHOLDS (quality gates):
//   http_req_duration p(95) < 800ms     — 95th percentile response time
//   http_req_duration p(99) < 2000ms    — 99th percentile (abort on fail)
//   http_req_failed   rate < 1%         — error rate (abort on fail)
//   forecast_latency  p(95) < 500ms     — business endpoint latency
//   errors            rate < 5%         — custom check error rate
//
// STAGES:
//   30s ramp up to 25 VUs → 3m sustained at 50 VUs → 30s ramp down
//
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';
// checkResponse and checkForecastResponse are helper functions that
// encapsulate common response validation logic (status codes, JSON
// structure checks). They live in a separate file so the same checks
// can be reused across load-test.js, stress-test.js, and soak-test.js.
import { checkResponse, checkForecastResponse } from './helpers/checks.js';

// ── Custom metrics ──
// Only two custom metrics: error rate and forecast latency.
// k6 built-in metrics (http_req_duration, http_req_failed, etc.) cover
// the rest. Adding per-endpoint Trend metrics for every endpoint creates
// noise -- only the business-critical endpoint gets its own tracker.
const errorRate = new Rate('errors');
const forecastLatency = new Trend('forecast_latency', true);

export const options = {
  // ─── Load Stages ──────────────────────────────────────────────
  // Ramp up → Sustained → Ramp down
  //
  // WHY this pattern:
  //   Stage 1 (30s, 25 VUs): Gradual ramp-up simulates users arriving
  //     over time, not all at once. Tests connection pool behavior.
  //   Stage 2 (3m, 50 VUs): Sustained load at peak. This is the main
  //     test -- can the app hold steady under realistic concurrency?
  //   Stage 3 (30s, 0 VUs): Ramp-down verifies the app releases
  //     resources cleanly. Connection leaks show up here.
  stages: [
    { duration: '30s', target: 25 },     // Ramp up
    { duration: '3m',  target: 50 },     // Sustained load
    { duration: '30s', target: 0 },      // Ramp down
  ],
  thresholds: {
    // ── These are your quality gates ──
    //
    // p(95) is a string-only threshold (no abort). It reports at the end.
    // p(99) has abortOnFail -- if tail latency exceeds 2s, stop early.
    http_req_duration: [
      'p(95)<800',                        // 95th percentile under 800ms
      { threshold: 'p(99)<2000',          // 99th percentile under 2 seconds
        abortOnFail: true,
        delayAbortEval: '30s' },          // Give it 30s to stabilize first
    ],
    // Built-in HTTP failure rate (non-2xx/3xx responses)
    http_req_failed: [
      { threshold: 'rate<0.01',           // Less than 1% HTTP errors
        abortOnFail: true },
    ],
    // Custom check error rate -- more lenient than http_req_failed because
    // it includes response body validation failures, not just HTTP errors.
    errors: ['rate<0.05'],                // Custom check error rate under 5%
    // Per-endpoint threshold for the main business endpoint.
    // Tighter than the global p(95) because WeatherForecast should be fast.
    forecast_latency: ['p(95)<500'],      // Business endpoint under 500ms p95
  },
  // TLS: skip certificate verification for self-signed OCP routes
  insecureSkipTLSVerify: true,
};

// ─── Configuration ────────────────────────────────────────────────
// BASE_URL is injected by the Jenkins pipeline via --env flag.
// Never hardcode the target -- it changes per environment.
const BASE_URL = __ENV.BASE_URL || 'https://sampleapi-dev.apps.example.com';

// ─── Test Scenario ────────────────────────────────────────────────
// Each virtual user runs this function in a loop for the duration
// of the test. Two requests per iteration: health check + forecast.
export default function () {
  // ── Test 1: Health check (baseline) ──
  const healthRes = http.get(`${BASE_URL}/healthz`);
  check(healthRes, { 'health OK': (r) => r.status === 200 });

  // ── Test 2: WeatherForecast API (main business endpoint) ──
  const forecastRes = http.get(`${BASE_URL}/api/WeatherForecast`);
  forecastLatency.add(forecastRes.timings.duration);
  // checkForecastResponse validates status 200 + JSON array structure.
  // It returns true/false, which we invert for the error rate counter.
  const forecastOk = checkForecastResponse(forecastRes);
  errorRate.add(!forecastOk);

  sleep(1); // Think time — simulate real user pacing
}

// handleSummary writes a JSON summary that Jenkins archives as an artifact.
// The file name matches what runPerformanceTest.groovy expects to parse.
export function handleSummary(data) {
  return {
    'k6-summary.json': JSON.stringify(data, null, 2),
  };
}
```

### DO: Create the file

```bash
mkdir -p build-config/tests/performance/
```

Then create the file at `build-config/tests/performance/load-test.js` with the content above.

### VERIFY: Validate the script locally (syntax only)

```bash
# If k6 is available locally:
k6 inspect build-config/tests/performance/load-test.js

# If not, just check the JavaScript parses:
node --check build-config/tests/performance/load-test.js 2>/dev/null || echo "Syntax OK (node not required for k6)"
```

---

## 4. Step 3: Create the Shared Library Function

### TELL: Why a dedicated function

Every pipeline step in this project is a single shared library function (Rule 10 from `CLAUDE.md`). This keeps the orchestrator clean and the function reusable. The function follows the same contract as every other `vars/` function: accepts a `Map config`, wraps execution in `try/catch`, returns a structured result map.

### SHOW: The function

**File: `jenkins-shared-lib/vars/runPerformanceTest.groovy`**

```groovy
// jenkins-shared-lib/vars/runPerformanceTest.groovy
// k6 performance test execution + threshold evaluation
//
// Runs a k6 load test against a target URL and evaluates whether
// performance thresholds pass. Used as a quality gate in the T3
// (tag/release) pipeline — if thresholds fail, the pipeline fails
// and the release image is not pushed.
//
// PARAMETERS:
//   targetUrl      - URL to test against (default: APP_DEV_URL env var)
//   testScript     - Path to k6 script (default: build-config/tests/performance/load-test.js)
//   abortOnFailure - Whether to throw error on threshold breach (default: true)
//   serviceName    - Service being tested (for logging, default: 'sampleapi')
//   extraArgs      - Additional k6 CLI arguments (default: '')
//   notificationUrl - Internal URL for NotificationApi (for multi-service test, default: '')
//
// RETURNS:
//   Map with keys: status, exitCode, duration, reason, summary
//
// EXIT CODES:
//   0  → All thresholds passed
//   99 → Threshold breached (k6 built-in behavior)
//   Other → k6 runtime error
//
def call(Map config = [:]) {
    def startTime = System.currentTimeMillis()
    def targetUrl = config.targetUrl ?: env.APP_DEV_URL ?: ''
    def testScript = config.testScript ?: 'build-config/tests/performance/load-test.js'
    def abortOnFailure = config.abortOnFailure != false
    def serviceName = config.serviceName ?: 'sampleapi'
    def extraArgs = config.extraArgs ?: ''
    def notificationUrl = config.notificationUrl ?: ''

    echo "=== Performance Test ==="
    echo "  Service: ${serviceName}"
    echo "  Target: ${targetUrl}"
    echo "  Script: ${testScript}"
    echo "  Abort on failure: ${abortOnFailure}"

    if (!targetUrl) {
        echo "WARNING: No target URL provided — skipping performance test"
        return [status: 'SKIPPED', reason: 'No target URL']
    }

    if (!fileExists(testScript)) {
        echo "WARNING: Test script not found: ${testScript} — skipping"
        return [status: 'SKIPPED', reason: "Script not found: ${testScript}"]
    }

    try {
        // Build k6 command with env vars
        def envArgs = "--env BASE_URL=${targetUrl}"
        if (notificationUrl) {
            envArgs += " --env NOTIFICATION_URL=${notificationUrl}"
        }

        // Run k6 — exit code 99 means threshold breach, 0 means all passed.
        //
        // --summary-export writes a JSON file with all metric aggregates.
        // --out json writes per-sample data (useful for post-hoc analysis).
        // Pipe to tee so the output appears in Jenkins console AND is saved.
        //
        // returnStatus: true captures the exit code instead of failing the
        // step immediately. This lets us log a meaningful message and
        // optionally throw based on abortOnFailure.
        def exitCode = sh(
            script: """
                k6 run \
                  --summary-export=k6-summary.json \
                  --out json=k6-results.json \
                  ${envArgs} \
                  ${extraArgs} \
                  ${testScript} 2>&1 | tee k6-output.log
            """,
            returnStatus: true
        )

        // Archive results regardless of outcome
        archiveArtifacts artifacts: 'k6-summary.json,k6-results.json,k6-output.log',
                         allowEmptyArchive: true

        def duration = (System.currentTimeMillis() - startTime) / 1000

        // Parse summary for key metrics (best-effort).
        // parseSummary() uses jq (not Python) because:
        //   1. jq is lighter than python3 and already in the agent image
        //   2. k6 handleSummary() JSON has metrics at the top level
        //      (e.g., .metrics.http_req_duration["p(95)"]), NOT under .values
        //   3. Groovy single-quoted strings avoid shell interpolation issues
        //      with parenthesized keys like p(95)
        def summaryInfo = parseSummary()

        if (exitCode == 99) {
            echo "PERFORMANCE GATE FAILED — thresholds breached (k6 exit code 99)"
            echo "  ${summaryInfo}"
            def result = [
                status: 'FAILURE',
                exitCode: 99,
                duration: duration,
                reason: 'Threshold breached',
                summary: summaryInfo
            ]
            if (abortOnFailure) {
                error "Performance quality gate FAILED: ${summaryInfo}"
            }
            return result
        } else if (exitCode != 0) {
            echo "WARNING: k6 exited with code ${exitCode} — possible runtime error"
            return [
                status: 'FAILURE',
                exitCode: exitCode,
                duration: duration,
                reason: "k6 error (exit ${exitCode})"
            ]
        }

        echo "Performance test PASSED — all thresholds met"
        echo "  Duration: ${duration}s"
        echo "  ${summaryInfo}"
        return [
            status: 'SUCCESS',
            exitCode: 0,
            duration: duration,
            summary: summaryInfo
        ]
    } catch (Exception e) {
        // Archive any partial results
        archiveArtifacts artifacts: 'k6-*.json,k6-output.log', allowEmptyArchive: true
        return [status: 'FAILURE', error: e.message]
    }
}

/**
 * Parse k6-summary.json for key metrics to include in pipeline output.
 * Best-effort — returns empty string if file doesn't exist or can't be parsed.
 *
 * KEY GOTCHA: k6 handleSummary() output has a different JSON structure than
 * --summary-export. Values are at the top level of each metric object
 * (e.g., .metrics.http_req_duration["p(95)"]), NOT nested under .values.
 * The error rate is .value (not .rate), and only p(90)/p(95) exist (no p99).
 *
 * Uses triple-single-quoted strings ('''...''') so that Groovy does NOT
 * interpolate the jq filter — parentheses in p(90)/p(95) would break
 * Groovy string processing otherwise.
 */
def parseSummary() {
    try {
        if (!fileExists('k6-summary.json')) {
            return 'No summary file'
        }
        // Single jq call extracts all values and joins with comma.
        // This avoids multiple shell invocations and quoting issues.
        def raw = sh(
            script: '''jq -r '[
                (.metrics.http_req_duration["p(90)"] | tostring),
                (.metrics.http_req_duration["p(95)"] | tostring),
                (.metrics.http_req_duration.max | tostring),
                (.metrics.http_req_failed.value | tostring),
                (.metrics.http_reqs.count | tostring)
            ] | join(",")' k6-summary.json''',
            returnStdout: true
        ).trim()

        def parts = raw.split(',')
        if (parts.length < 5) {
            return "Unexpected summary format: ${raw}"
        }

        def p90 = parts[0], p95 = parts[1], maxMs = parts[2]
        def errRate = parts[3], totalReqs = parts[4]

        // Format numbers for readability
        try { p90 = String.format('%.1f', p90 as Double) } catch (e) {}
        try { p95 = String.format('%.1f', p95 as Double) } catch (e) {}
        try { maxMs = String.format('%.0f', maxMs as Double) } catch (e) {}
        try { errRate = String.format('%.4f', errRate as Double) } catch (e) {}

        return "p90=${p90}ms, p95=${p95}ms, max=${maxMs}ms, error_rate=${errRate}, total_requests=${totalReqs}"
    } catch (Exception e) {
        return "Summary parse error: ${e.message}"
    }
}
```

### DO: Create the file

Save the content above to `jenkins-shared-lib/vars/runPerformanceTest.groovy`.

### VERIFY: Groovy syntax

```bash
# If groovyc is available:
groovyc -e 'new GroovyShell().parse(new File("jenkins-shared-lib/vars/runPerformanceTest.groovy"))'

# Otherwise, basic syntax check:
grep -c 'def call(Map config' jenkins-shared-lib/vars/runPerformanceTest.groovy
# --> Expected: 1
```

---

## 5. Step 4: Integrate Into the T3 Pipeline

### TELL: Where the performance test fits

The T3 pipeline currently runs these stages in order:

```
Initialize --> Checkout --> Build --> Unit Tests --> SonarQube --> Dependency Check
--> Build Image --> ACS Scan (strict) --> OWASP ZAP DAST
--> Sign Image --> Push to Registry --> Create Promotion MR
```

The performance test runs **after DAST and before image signing**. The reasoning:

- **After DAST:** Both DAST and performance testing hit the running DEV application. Running them back-to-back avoids deploying a temporary environment.
- **Before signing and push:** If performance fails, there is no point signing, pushing, or creating a promotion MR. The release is not ready.
- **No wasted registry space:** Unlike a design where the image is pushed first, failing the performance gate here means the unvalidated image never reaches the registry.

### SHOW: The pipeline change

Add a new stage to `pipelineTag.groovy` after the `OWASP ZAP DAST` stage and before the `Sign Image` stage:

```groovy
            stage('Performance Test') {
                steps {
                    script {
                        // Performance quality gate — runs k6 load test against DEV endpoint.
                        // If thresholds breach (p95>800ms, p99>2s, errors>1%), pipeline fails.
                        // k6 exits with code 99 on threshold breach, which runPerformanceTest
                        // interprets as GATE FAILED and throws an error.
                        def appsDomain = pipelineConfig.gitlabUrl
                            .replace('https://gitlab-devsecops-gitlab.', '')
                        def devRoute = "https://${pipelineConfig.appName}-${pipelineConfig.appName}-dev.${appsDomain}"
                        echo "Performance test target: ${devRoute}"

                        results.perfTest = runPerformanceTest(
                            targetUrl: devRoute,
                            testScript: 'build-config/tests/performance/load-test.js',
                            serviceName: serviceName,
                            abortOnFailure: true
                        )
                    }
                }
            }
```

### DO: Edit pipelineTag.groovy

Open `jenkins-shared-lib/vars/pipelineTag.groovy`.

Locate the closing brace of the `stage('OWASP ZAP DAST')` block (around line 265). Insert the new `stage('Performance Test')` block immediately after it, before `stage('Sign Image')`.

The stage ordering should now be:

```
... --> OWASP ZAP DAST --> Performance Test --> Sign Image --> Push to Registry --> Create Promotion MR
```

### VERIFY: Structural check

```bash
grep -n "stage(" jenkins-shared-lib/vars/pipelineTag.groovy
# --> Should show stages in order, with 'Performance Test' between 'OWASP ZAP DAST' and 'Sign Image'
```

---

## 6. Step 5: Run and Interpret Results

### TELL: What to expect

When you push a new tag, the T3 pipeline will now include a Performance Test stage. There are three possible outcomes:

| k6 Exit Code | Pipeline Result | What Happens |
|---|---|---|
| 0 | Stage passes | All thresholds met. Image is signed, promotion MR created. |
| 99 | Stage fails, pipeline fails | Threshold breached. No signing, no promotion MR. Release blocked. |
| 1 | Stage fails, pipeline fails | Script error (typo, missing module). Fix and re-tag. |

### DO: Trigger a T3 pipeline

First, push the updated shared library and build-config to GitLab:

```bash
# Source environment variables
source ./env.sh

GITLAB_TOKEN=$($OC get secret gitlab-token -n $NS_TOOLS -o jsonpath='{.data.token}' | base64 -d)
GITLAB_HOST=$(echo $GITLAB_URL | sed 's|https://||')

# Push build-config (new k6 test script)
cd build-config
git add -A && git commit -m "Add k6 performance test script"
git push origin main
cd ..

# Push shared library (new runPerformanceTest.groovy + updated pipelineTag.groovy)
cd jenkins-shared-lib
git add -A && git commit -m "Add performance testing quality gate (k6)"
git push origin main
cd ..

# Now trigger T3 by tagging the app-source repo
cd app-source
git tag -a v1.5.0 -m "Release v1.5.0 with performance gate"
git push origin v1.5.0
```

### SHOW: Reading k6 output in Jenkins

In the Jenkins console output, look for this section:

```
=== Performance Test ===
  Service: sampleapi
  Target: https://sampleapi-sampleapi-dev.apps.cluster-xxxx.xxxx.example.com
  Script: build-config/tests/performance/load-test.js
  Abort on failure: true

          /\      |‾‾| /‾‾/   /‾‾/
     /\  /  \     |  |/  /   /  /
    /  \/    \    |     (   /   ‾‾\
   /          \   |  |\  \ |  (‾)  |
  / __________ \  |__| \__\ \_____/ .io

  execution: local
     script: build-config/tests/performance/load-test.js
     output: json (k6-results.json)

  scenarios: (100.00%) 1 scenario, 50 max VUs, 4m30s max duration
           default: Up to 50 looping VUs for 4m0s over 3 stages

running (0m30s), 25/25 VUs, 187 complete and 0 interrupted
running (1m00s), 50/50 VUs, 612 complete and 0 interrupted
running (2m00s), 50/50 VUs, 1437 complete and 0 interrupted
running (3m00s), 50/50 VUs, 2249 complete and 0 interrupted
running (3m30s), 25/50 VUs, 2611 complete and 0 interrupted
running (4m00s),  0/50 VUs, 2850 complete and 0 interrupted

     ✓ health OK
     ✓ forecast 200
     ✓ forecast has data
     ✓ forecast < 1s

     checks.....................: 100.00% ✓ 11400 ✗ 0
     http_req_duration..........: avg=45ms  min=12ms  med=38ms  max=892ms  p(95)=156ms  p(99)=423ms
   ✓ { threshold: p(95) < 800 }
   ✓ { threshold: p(99) < 2000 }
     errors.....................: 0.00%   ✓ 0     ✗ 2850
   ✓ { threshold: rate < 0.01 }

Performance test PASSED — all thresholds met
  Duration: 242.3s
  p90=112.5ms, p95=156.0ms, max=892ms, error_rate=0.0000, total_requests=2850
```

The checkmarks next to each threshold confirm they passed. If any had failed, you would see a cross mark and k6 would have exited with code 99.

### VERIFY: Check the archived artifact

After the pipeline completes, go to the Jenkins build page and look in "Build Artifacts" for:

```
k6-summary.json      <-- Machine-readable metrics from handleSummary() (for trending)
k6-results.json      <-- Per-sample data from --out json (for post-hoc analysis)
k6-output.log        <-- Full console output (for debugging)
```

You can download `k6-summary.json` and inspect it:

```bash
# From your workstation, after downloading:
# k6 handleSummary() output has metrics at the top level (not under .values):
#   .metrics.http_req_duration["p(95)"]  — percentile value directly
#   .metrics.http_req_failed.value       — error rate (note: .value, not .rate)
#   .metrics.http_reqs.count             — total request count
cat k6-summary.json | jq '.metrics.http_req_duration | {"p(90)": .["p(90)"], "p(95)": .["p(95)"], max: .max}'
```

---

## 7. Step 6: Handle a Failure (Quick Win)

### TELL: The feedback loop

This is the payoff of the entire module. When the performance gate catches a regression, the cycle is:

1. Developer pushes tag v1.5.0
2. T3 pipeline runs load test
3. k6 reports p(95) = 1200ms (threshold is 800ms)
4. k6 exits with code 99
5. Pipeline fails -- no promotion MR, no image promotion
6. Developer investigates, finds the slow code, fixes it
7. Developer pushes tag v1.5.1
8. T3 pipeline re-runs, load test passes
9. Promotion MR is created, release proceeds

### SHOW: Simulating a failure

To see the failure path in action, temporarily tighten the thresholds to values the application cannot meet. Edit `load-test.js` and change the `http_req_duration` thresholds:

```javascript
// Original (will pass):
http_req_duration: [
    'p(95)<800',                        // 95th percentile under 800ms
    { threshold: 'p(99)<2000',          // 99th percentile under 2 seconds
      abortOnFail: true,
      delayAbortEval: '30s' },
],

// Tightened (will fail -- p95 must be under 5ms, which is impossible over the network):
http_req_duration: [
    'p(95)<5',
    { threshold: 'p(99)<10',
      abortOnFail: true,
      delayAbortEval: '30s' },
],
```

Push to build-config, create a new tag (v1.5.1-test), and watch the pipeline fail at the Performance Test stage. Then revert the threshold and re-tag.

This exercise proves the gate works. You saw it pass. You saw it fail. You know it is real.

### DO: Revert and verify

After the experiment, restore the original thresholds and push:

```bash
cd build-config
# Revert the threshold change in tests/performance/load-test.js
git add -A && git commit -m "Revert: restore production thresholds"
git push origin main
```

---

## 8. Updating the SecurityGate (Optional Enhancement)

### TELL: Why extend SecurityGate

The `SecurityGate.groovy` class evaluates all scan results and produces a summary report. Adding a performance check here means the post-pipeline report includes performance alongside SAST, SCA, and DAST results.

### SHOW: The addition

In `jenkins-shared-lib/src/com/devsecops/SecurityGate.groovy`, inside the `evaluate` method, add after the OWASP ZAP check:

```groovy
        // Check performance test
        if (results.perfTest?.status == 'FAILURE') {
            failures.add("Performance test FAILED: ${results.perfTest?.error ?: 'thresholds breached'}")
        }
```

This is a single line of integration. The function already returns `failures` and `warnings` lists; the `formatReport` method will automatically include it.

---

## Recap

Here is what you built in this module:

| Component | File | Purpose |
|---|---|---|
| k6 in agent image | `infra/phase7/Dockerfile.agent` | k6 binary available in Jenkins agent |
| Load test script | `build-config/tests/performance/load-test.js` | Defines load pattern, thresholds, and checks |
| Shared lib function | `jenkins-shared-lib/vars/runPerformanceTest.groovy` | Wraps k6 execution, parses results, archives artifacts |
| T3 integration | `jenkins-shared-lib/vars/pipelineTag.groovy` | Calls `runPerformanceTest()` after DAST, before promotion |
| SecurityGate update | `jenkins-shared-lib/src/com/devsecops/SecurityGate.groovy` | Includes performance in the gate report |

The flow:

```
Tag pushed --> T3 pipeline --> ... --> DAST --> Performance Test --> Sign --> Push --> Promote
                                                     |
                                              k6 exit code 99?
                                              YES --> pipeline FAILS, no promotion
                                              NO  --> pipeline continues
```

Performance is now a first-class quality gate. It is not an afterthought, not a manual check, not something that gets skipped when the deadline is tight. It is automated, threshold-driven, and blocking.

---

## Common Mistakes

**1. Missing `abortOnFail` on thresholds**

Without `abortOnFail: true`, k6 still reports the breach at the end, but it runs the entire test duration first. For a 4-minute test, that is 4 minutes of wasted pipeline time when you already know the result after 30 seconds.

```javascript
// BAD: Runs for full duration even when breached
http_req_duration: ['p(95)<800'],

// GOOD: Stops early (after delayAbortEval) when breached
http_req_duration: [{ threshold: 'p(95)<800', abortOnFail: true, delayAbortEval: '30s' }],
```

**2. No `delayAbortEval` warmup period**

JIT compilation, connection pool warming, and DNS caching all cause the first few seconds of requests to be slow. Without a delay, the threshold evaluates during warmup and reports a false failure.

```javascript
// BAD: Evaluates immediately, false failure on cold start
{ threshold: 'p(95)<800', abortOnFail: true },

// GOOD: Waits 30s for the app to warm up
{ threshold: 'p(95)<800', abortOnFail: true, delayAbortEval: '30s' },
```

**3. Thresholds too aggressive for the environment**

DEV environments typically run with fewer replicas (1 pod) and lower resource limits than production (3 pods). Setting thresholds appropriate for production hardware against a single DEV pod produces constant false failures. Start with generous thresholds and tighten them as you collect baseline data.

**4. No think time between requests**

Without `sleep()` in the default function, each virtual user fires requests as fast as the network allows. This produces unrealistic request rates and makes the test a stress test, not a load test. Real users pause between actions.

```javascript
// BAD: Unrealistic machine-gun traffic
export default function () {
    http.get(`${BASE_URL}/api/WeatherForecast`);
}

// GOOD: Simulates human think time
export default function () {
    http.get(`${BASE_URL}/api/WeatherForecast`);
    sleep(Math.random() * 2 + 1);  // 1-3 seconds
}
```

**5. Using `|| true` after k6 in the shell command**

This is the same antipattern as using `|| true` after `roxctl` (see Module 6). Appending `|| true` to the k6 command swallows exit code 99, making the performance gate useless. Always use `returnStatus: true` in the `sh` step instead.

```groovy
// BAD: Exit code 99 becomes 0, threshold breach is invisible
sh "k6 run load-test.js || true"

// GOOD: Captures exit code for programmatic evaluation
def exitCode = sh(script: "k6 run load-test.js", returnStatus: true)
```

---

## Challenge: Add Custom Metrics

Extend the load test script with a custom business metric. For example, add a `Trend` metric that tracks the response size of the WeatherForecast endpoint:

```javascript
import { Trend } from 'k6/metrics';
const forecastResponseSize = new Trend('forecast_response_bytes');

// In the default function:
forecastResponseSize.add(forecastRes.body.length);
```

Then add a threshold for it:

```javascript
thresholds: {
    // ... existing thresholds ...
    'forecast_response_bytes': ['avg < 5000'],  // Response should be under 5KB on average
},
```

This is useful for catching regressions where the API starts returning bloated responses (e.g., N+1 queries returning excessive data).

---

## Self-Assessment

Before moving on, verify you can answer these questions:

1. **Why does k6 use exit code 99 specifically for threshold breaches, not exit code 1?**
   Answer: Exit code 1 means a script error (syntax, missing imports, runtime crash). Exit code 99 means the script ran correctly but the application failed to meet the defined thresholds. The distinction lets the pipeline handle "test infrastructure broken" differently from "application too slow."

2. **Why is `delayAbortEval` set to 30 seconds and not 0?**
   Answer: The application needs time to warm up (JIT compilation, connection pooling, DNS caching). Evaluating thresholds during the first few seconds produces false failures because cold-start latency is not representative of sustained performance.

3. **Why does the performance test run in T3 and not T2?**
   Answer: T2 runs on every merge to main. Load testing on every merge wastes cluster resources and slows the feedback loop. T3 runs only when a release is being prepared (tag push), making it the right place for heavyweight validation like load testing and DAST.

4. **What happens if the target URL is empty or the test script is missing?**
   Answer: The function checks two preconditions before starting k6: (1) if `targetUrl` is empty, it returns `status: 'SKIPPED'` with reason `'No target URL'`; (2) if the test script file does not exist (`!fileExists(testScript)`), it returns `status: 'SKIPPED'` with reason `'Script not found'`. If the URL is provided but the target is unreachable, k6 itself will report HTTP errors and likely breach the `http_req_failed` threshold, causing exit code 99.

5. **Where are the load test results stored after the pipeline completes?**
   Answer: In Jenkins build artifacts as `k6-summary.json` (machine-readable metrics from `handleSummary()`), `k6-results.json` (per-sample data from `--out json`), and `k6-output.log` (full console output piped through `tee`). These can be used for historical trend analysis.

---

## Next Module Preview

**Module 15: Production Hardening** covers the final steps to make this pipeline production-ready:

- Pod Disruption Budgets for zero-downtime deployments
- Anti-affinity rules to spread pods across nodes
- Resource tuning based on k6 performance baselines (you just collected these)
- Chaos testing to validate resilience under failure
- Backup strategies and disaster recovery procedures

You will use the performance data from this module to right-size the production deployment.