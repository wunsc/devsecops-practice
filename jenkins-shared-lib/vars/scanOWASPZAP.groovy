// jenkins-shared-lib/vars/scanOWASPZAP.groovy
// OWASP ZAP DAST (Dynamic Application Security Testing) scan
// Only runs in T3 (tag) pipeline against a deployed endpoint
//
// ARCHITECTURE:
//   ZAP runs as a sidecar container in the agent pod (added by pipelineTag.groovy).
//   The sidecar starts ZAP in daemon mode on localhost:8090.
//   This function uses ZAP's REST API via curl from the jnlp container to:
//     1. Spider the target to discover endpoints
//     2. Run passive scan (automatic during spider)
//     3. Optionally run active scan
//     4. Generate and archive HTML + JSON reports
//
// The ZAP sidecar is defined in pipelineTag.groovy's agent block:
//   image: ghcr.io/zaproxy/zaproxy:stable
//   command: zap.sh -daemon -host 0.0.0.0 -port 8090 ...
//
// Usage:
//   scanOWASPZAP(target: 'https://app-dev.apps.example.com')
//   scanOWASPZAP(target: 'https://app.example.com', scanType: 'active', zapPort: 8090)
def call(Map config = [:]) {
    def startTime = System.currentTimeMillis()
    def target = config.target ?: ''
    def reportDir = config.reportDir ?: 'zap-report'
    def scanType = config.scanType ?: 'baseline'  // 'baseline' (passive only) or 'active'
    def zapPort = config.zapPort ?: 8090
    def zapUrl = "http://localhost:${zapPort}"
    def spiderTimeoutMin = config.spiderTimeoutMin ?: 2
    def activeScanTimeoutMin = config.activeScanTimeoutMin ?: 10

    echo "=== OWASP ZAP DAST Scan ==="
    echo "  Target: ${target}"
    echo "  Scan type: ${scanType}"
    echo "  ZAP API: ${zapUrl}"

    if (!target) {
        echo "WARNING: No target URL provided — skipping ZAP scan"
        return [status: 'SKIPPED', findings: 0]
    }

    try {
        sh "mkdir -p ${reportDir}"

        // Wait for ZAP sidecar to be ready (daemon mode takes a few seconds to start)
        echo "Waiting for ZAP daemon to be ready..."
        def zapReady = false
        for (int i = 0; i < 30; i++) {
            def rc = sh(script: "curl -sf ${zapUrl}/JSON/core/view/version/ 2>/dev/null | grep -q 'version'", returnStatus: true)
            if (rc == 0) {
                zapReady = true
                def zapVersion = sh(script: "curl -sf ${zapUrl}/JSON/core/view/version/ 2>/dev/null", returnStdout: true).trim()
                echo "ZAP daemon ready: ${zapVersion}"
                break
            }
            sleep(5)
            echo "  Attempt ${i + 1}/30 — waiting for ZAP..."
        }

        if (!zapReady) {
            echo "WARNING: ZAP daemon not available at ${zapUrl} — skipping DAST"
            return [status: 'SKIPPED', findings: 0, reason: 'ZAP daemon not reachable']
        }

        // Verify target is reachable from the agent pod
        def targetReachable = sh(
            script: "curl -sk --max-time 15 -o /dev/null -w '%{http_code}' '${target}/healthz' 2>/dev/null || echo '000'",
            returnStdout: true
        ).trim()
        echo "Target health check: HTTP ${targetReachable}"

        if (targetReachable == '000') {
            echo "WARNING: Target ${target} not reachable from agent pod — skipping DAST"
            return [status: 'SKIPPED', findings: 0, reason: "Target unreachable: HTTP ${targetReachable}"]
        }

        // Step 1: Spider the target to discover URLs
        echo "--- Step 1: Spidering target ---"
        def spiderResponse = sh(
            script: "curl -sf '${zapUrl}/JSON/spider/action/scan/?url=${target}&maxChildren=10&recurse=true' 2>/dev/null || echo '{}'",
            returnStdout: true
        ).trim()
        echo "Spider started: ${spiderResponse}"

        // Wait for spider to complete (poll status)
        def spiderTimeout = spiderTimeoutMin * 60
        for (int elapsed = 0; elapsed < spiderTimeout; elapsed += 5) {
            def status = sh(
                script: "curl -sf '${zapUrl}/JSON/spider/view/status/' 2>/dev/null | python3 -c \"import json,sys; print(json.load(sys.stdin).get('status','0'))\" 2>/dev/null || echo '0'",
                returnStdout: true
            ).trim()
            if (status == '100') {
                echo "Spider complete (100%)"
                break
            }
            if (elapsed % 15 == 0) {
                echo "  Spider progress: ${status}%"
            }
            sleep(5)
        }

        // Get number of URLs found
        def urlsFound = sh(
            script: "curl -sf '${zapUrl}/JSON/spider/view/results/' 2>/dev/null | python3 -c \"import json,sys; print(len(json.load(sys.stdin).get('results',[])))\" 2>/dev/null || echo '0'",
            returnStdout: true
        ).trim()
        echo "URLs discovered: ${urlsFound}"

        // Step 2: Wait for passive scan to complete
        echo "--- Step 2: Passive scan (automatic during spider) ---"
        for (int i = 0; i < 30; i++) {
            def recordsToScan = sh(
                script: "curl -sf '${zapUrl}/JSON/pscan/view/recordsToScan/' 2>/dev/null | python3 -c \"import json,sys; print(json.load(sys.stdin).get('recordsToScan','0'))\" 2>/dev/null || echo '0'",
                returnStdout: true
            ).trim()
            if (recordsToScan == '0') {
                echo "Passive scan complete"
                break
            }
            echo "  Passive scan: ${recordsToScan} records remaining..."
            sleep(5)
        }

        // Step 3: Active scan (optional — only if scanType is 'active')
        if (scanType == 'active') {
            echo "--- Step 3: Active scan ---"
            def ascanResponse = sh(
                script: "curl -sf '${zapUrl}/JSON/ascan/action/scan/?url=${target}&recurse=true&inScopeOnly=false' 2>/dev/null || echo '{}'",
                returnStdout: true
            ).trim()
            echo "Active scan started: ${ascanResponse}"

            def activeScanTimeout = activeScanTimeoutMin * 60
            for (int elapsed = 0; elapsed < activeScanTimeout; elapsed += 10) {
                def status = sh(
                    script: "curl -sf '${zapUrl}/JSON/ascan/view/status/' 2>/dev/null | python3 -c \"import json,sys; print(json.load(sys.stdin).get('status','0'))\" 2>/dev/null || echo '0'",
                    returnStdout: true
                ).trim()
                if (status == '100') {
                    echo "Active scan complete (100%)"
                    break
                }
                if (elapsed % 30 == 0) {
                    echo "  Active scan progress: ${status}%"
                }
                sleep(10)
            }
        } else {
            echo "--- Skipping active scan (baseline mode) ---"
        }

        // Step 4: Get alerts summary
        echo "--- Step 4: Collecting results ---"
        def alertsSummary = sh(
            script: "curl -sf '${zapUrl}/JSON/alert/view/alertsSummary/?baseurl=${target}' 2>/dev/null || echo '{}'",
            returnStdout: true
        ).trim()
        echo "Alerts summary: ${alertsSummary}"

        // Parse alert counts
        def highAlerts = 0
        def mediumAlerts = 0
        def lowAlerts = 0
        def infoAlerts = 0
        try {
            def counts = sh(
                script: """
                    echo '${alertsSummary}' | python3 -c "
import json, sys
data = json.load(sys.stdin).get('alertsSummary', {})
print(data.get('High', 0))
print(data.get('Medium', 0))
print(data.get('Low', 0))
print(data.get('Informational', 0))
" 2>/dev/null || echo '0\n0\n0\n0'
                """,
                returnStdout: true
            ).trim().split('\n')
            if (counts.size() >= 4) {
                highAlerts = counts[0].trim().isInteger() ? counts[0].trim().toInteger() : 0
                mediumAlerts = counts[1].trim().isInteger() ? counts[1].trim().toInteger() : 0
                lowAlerts = counts[2].trim().isInteger() ? counts[2].trim().toInteger() : 0
                infoAlerts = counts[3].trim().isInteger() ? counts[3].trim().toInteger() : 0
            }
        } catch (Exception pe) {
            echo "WARNING: Could not parse alert counts: ${pe.message}"
        }

        def totalAlerts = highAlerts + mediumAlerts + lowAlerts + infoAlerts
        echo "ZAP Results: High=${highAlerts}, Medium=${mediumAlerts}, Low=${lowAlerts}, Info=${infoAlerts}, Total=${totalAlerts}"

        // Step 5: Generate reports
        echo "--- Step 5: Generating reports ---"

        // HTML report
        sh """
            curl -sf '${zapUrl}/OTHER/core/other/htmlreport/' -o '${reportDir}/zap-report.html' 2>/dev/null || echo '<html><body>Report generation failed</body></html>' > '${reportDir}/zap-report.html'
        """

        // JSON report (alerts list)
        sh """
            curl -sf '${zapUrl}/JSON/alert/view/alerts/?baseurl=${target}&start=0&count=500' -o '${reportDir}/zap-report.json' 2>/dev/null || echo '{"alerts":[]}' > '${reportDir}/zap-report.json'
        """

        echo "Reports saved to ${reportDir}/"

        // Archive reports as build artifacts
        archiveArtifacts artifacts: "${reportDir}/**", allowEmptyArchive: true

        def duration = (System.currentTimeMillis() - startTime) / 1000
        echo "OWASP ZAP DAST scan completed in ${duration}s"
        echo "  High: ${highAlerts} | Medium: ${mediumAlerts} | Low: ${lowAlerts} | Info: ${infoAlerts}"

        // Determine status based on findings
        def status = 'SUCCESS'
        if (highAlerts > 0) {
            echo "WARNING: ${highAlerts} high-severity alerts found — review ZAP report"
            status = 'UNSTABLE'
        }

        return [
            status: status,
            duration: duration,
            findings: totalAlerts,
            high: highAlerts,
            medium: mediumAlerts,
            low: lowAlerts,
            info: infoAlerts,
            reportDir: reportDir,
            urlsDiscovered: urlsFound
        ]
    } catch (Exception e) {
        echo "ERROR: OWASP ZAP scan failed — ${e.message}"
        return [status: 'FAILURE', error: e.message, findings: 0]
    }
}
