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
//   Map with keys: status, exitCode, duration, reason, summaryFile
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

        // Run k6 — exit code 99 means threshold breach, 0 means all passed
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

        // Parse summary for key metrics (best-effort)
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
 */
def parseSummary() {
    try {
        if (!fileExists('k6-summary.json')) {
            return 'No summary file'
        }
        // k6 handleSummary() output structure has metrics at top level:
        //   .metrics.http_req_duration["p(95)"]  (not under .values)
        //   .metrics.http_req_failed.value        (not .rate)
        //   .metrics.http_reqs.count
        // Use a single jq call to extract all values at once to avoid
        // shell quoting issues with parenthesized keys in Groovy→bash
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

        // Format numbers
        try { p90 = String.format('%.1f', p90 as Double) } catch (e) {}
        try { p95 = String.format('%.1f', p95 as Double) } catch (e) {}
        try { maxMs = String.format('%.0f', maxMs as Double) } catch (e) {}
        try { errRate = String.format('%.4f', errRate as Double) } catch (e) {}

        return "p90=${p90}ms, p95=${p95}ms, max=${maxMs}ms, error_rate=${errRate}, total_requests=${totalReqs}"
    } catch (Exception e) {
        return "Summary parse error: ${e.message}"
    }
}
