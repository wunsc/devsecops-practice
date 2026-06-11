// jenkins-shared-lib/vars/scanACSImage.groovy
// Red Hat ACS (StackRox) image scan via roxctl CLI
// Runs image check (policy evaluation) and image scan (vulnerability scan)
//
// KNOWN FIXES:
//   - roxctl v4.5.4 has no --force-print-all-violations flag
//   - ACS Central may get 401 from internal registry (no image integration)
//     → handled gracefully with 0 findings fallback
//   - Image MUST be pushed to registry BEFORE scanning (ACS pulls from registry)
//   - grep -c needs || true fallback for safe numeric extraction
//
// Usage:
//   scanACSImage(imageRef: 'registry/ns/app:tag')
//   scanACSImage(imageRef: 'registry/ns/app:tag', strict: true)
def call(Map config = [:]) {
    def startTime = System.currentTimeMillis()
    def imageRef = config.imageRef ?: ''
    def acsUrl = config.acsUrl ?: env.ACS_CENTRAL_URL ?: ''
    def tokenCredId = config.tokenCredId ?: 'acs-token'
    def strict = config.strict ?: false  // T3 uses strict mode
    def reportDir = config.reportDir ?: 'acs-report'

    echo "=== ACS Image Scan ==="
    echo "  Image: ${imageRef}"
    echo "  ACS URL: ${acsUrl}"
    echo "  Strict mode: ${strict}"

    if (!imageRef) {
        echo "WARNING: No imageRef provided — skipping ACS scan"
        return [status: 'SKIPPED', findings: 0]
    }

    try {
        withCredentials([string(credentialsId: tokenCredId, variable: 'ROX_API_TOKEN')]) {
            sh "mkdir -p ${reportDir}"

            // Image check — evaluates against ACS policies
            // NOTE: roxctl reads ROX_API_TOKEN from env (set by withCredentials above).
            // Do NOT use --token flag (doesn't exist) or || true (swallows errors).
            // returnStatus:true captures exit code without failing the step.
            // Strip protocol and trailing port/slash — roxctl -e expects host:port
            def acsEndpoint = acsUrl.replaceAll('^https?://', '').replaceAll('/+$', '')
            if (!acsEndpoint.contains(':')) { acsEndpoint += ':443' }

            def checkExitCode = sh(
                script: """
                    roxctl -e "${acsEndpoint}" \\
                        --insecure-skip-tls-verify \\
                        image check \\
                        --image "${imageRef}" \\
                        --output json > ${reportDir}/image-check.json 2>${reportDir}/image-check-stderr.log
                """,
                returnStatus: true
            )

            // Image scan — vulnerability scanning
            def scanExitCode = sh(
                script: """
                    roxctl -e "${acsEndpoint}" \\
                        --insecure-skip-tls-verify \\
                        image scan \\
                        --image "${imageRef}" \\
                        --output json > ${reportDir}/image-scan.json 2>${reportDir}/image-scan-stderr.log
                """,
                returnStatus: true
            )

            // Log any stderr for debugging
            if (checkExitCode != 0) {
                echo "WARNING: roxctl image check exited with code ${checkExitCode}"
                sh "cat ${reportDir}/image-check-stderr.log 2>/dev/null || true"
            }
            if (scanExitCode != 0) {
                echo "WARNING: roxctl image scan exited with code ${scanExitCode}"
                sh "cat ${reportDir}/image-scan-stderr.log 2>/dev/null || true"
            }

            // Parse results — extract vulnerability counts
            // KNOWN FIX: Safe grep with || true fallback
            def criticalCount = sh(
                script: "val=\$(grep -oc '\"CRITICAL\"' ${reportDir}/image-scan.json 2>/dev/null || true); echo \${val:-0}",
                returnStdout: true
            ).trim()
            def highCount = sh(
                script: "val=\$(grep -oc '\"IMPORTANT\"\\|\"HIGH\"' ${reportDir}/image-scan.json 2>/dev/null || true); echo \${val:-0}",
                returnStdout: true
            ).trim()

            def critical = criticalCount.isInteger() ? criticalCount.toInteger() : 0
            def high = highCount.isInteger() ? highCount.toInteger() : 0

            echo "ACS Results: ${critical} critical, ${high} high vulnerabilities"

            // Archive reports
            archiveArtifacts artifacts: "${reportDir}/**", allowEmptyArchive: true

            // Determine pass/fail
            def failed = false
            if (strict && (critical > 0 || checkExitCode != 0)) {
                failed = true
                echo "STRICT MODE: Failing due to ${critical} critical vulns or policy violations (checkExit=${checkExitCode})"
            } else if (checkExitCode != 0) {
                // Non-strict: policy check failed but don't block pipeline
                echo "WARNING: ACS policy check failed (exit code ${checkExitCode}) — review image-check.json"
            }
            if (critical > 0) {
                echo "WARNING: ${critical} critical vulnerabilities found"
            }

            // Check if scan actually produced valid results
            def scanFileSize = sh(
                script: "wc -c < ${reportDir}/image-scan.json 2>/dev/null || echo 0",
                returnStdout: true
            ).trim()
            if (scanFileSize.toInteger() < 10 && scanExitCode != 0) {
                echo "WARNING: ACS image scan failed and produced no results (exit=${scanExitCode}). This is NOT a pass — check ACS connectivity and registry access."
                failed = true
            }

            def duration = (System.currentTimeMillis() - startTime) / 1000
            echo "ACS scan completed in ${duration}s"

            return [
                status: failed ? 'FAILURE' : 'SUCCESS',
                duration: duration,
                criticalCount: critical,
                highCount: high,
                reportDir: reportDir,
                checkExitCode: checkExitCode,
                scanExitCode: scanExitCode
            ]
        }
    } catch (Exception e) {
        echo "ERROR: ACS scan failed — ${e.message}"
        return [status: 'FAILURE', error: e.message, criticalCount: 0, highCount: 0]
    }
}
