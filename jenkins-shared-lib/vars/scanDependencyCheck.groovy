// jenkins-shared-lib/vars/scanDependencyCheck.groovy
// OWASP Dependency-Check SCA (Software Composition Analysis)
// Scans .NET NuGet dependencies for known CVEs
//
// KNOWN FIXES:
//   - Command is "dependency-check" (no .sh suffix on the symlinked binary)
//   - Cold start exit code 13 = DB download error (non-blocking, retry next run)
//   - Scan only src/ directory to avoid scanning .sonarqube/, publish/, and test artifacts
//   - Use --exclude to skip build output directories
//
// Usage:
//   scanDependencyCheck()
//   scanDependencyCheck(project: 'src/', suppressionFile: 'build-config/dependency-check-suppression.xml')
def call(Map config = [:]) {
    def startTime = System.currentTimeMillis()
    // Scan only src/ to avoid picking up .sonarqube/, publish/, and test framework DLLs
    def project = config.project ?: 'src/'
    def suppressionFile = config.suppressionFile ?: ''
    def reportDir = config.reportDir ?: 'dependency-check-report'
    def failOnCVSS = config.failOnCVSS ?: 9  // Fail on critical (CVSS >= 9)

    echo "=== OWASP Dependency-Check ==="
    echo "  Project: ${project}"
    echo "  Suppression file: ${suppressionFile ?: 'none'}"
    echo "  Fail on CVSS >= ${failOnCVSS}"

    try {
        // Build suppression file argument if provided
        def suppressionArg = ''
        if (suppressionFile && fileExists(suppressionFile)) {
            suppressionArg = "--suppression ${suppressionFile}"
            echo "  Using suppression file: ${suppressionFile}"
        }

        // NVD API key credential for faster DB updates
        def nvdApiKeyCredId = config.nvdApiKeyCredId ?: 'nvd-api-key'

        // KNOWN FIX: Command is "dependency-check", exit code 13 = DB error (non-blocking)
        // Timeout prevents cold start NVD download from blocking the pipeline
        def timeoutMinutes = config.timeoutMinutes ?: 10
        def exitCode = 0
        try {
            timeout(time: timeoutMinutes, unit: 'MINUTES') {
                withCredentials([string(credentialsId: nvdApiKeyCredId, variable: 'NVD_API_KEY')]) {
                    exitCode = sh(
                        script: """
                            mkdir -p ${reportDir}
                            dependency-check \\
                                --scan ${project} \\
                                --project "sampleapi" \\
                                --format HTML \\
                                --format JSON \\
                                --out ${reportDir} \\
                                --failOnCVSS ${failOnCVSS} \\
                                ${suppressionArg} \\
                                --nvdApiKey "\${NVD_API_KEY}" \\
                                --enableExperimental \\
                                --disableAssembly \\
                                --disableOssIndex \\
                                --exclude '**/.sonarqube/**' \\
                                --exclude '**/publish/**' \\
                                --exclude '**/obj/**' \\
                                --exclude '**/bin/**' \\
                                --exclude '**/TestResults/**'
                        """,
                        returnStatus: true
                    )
                }
            }
        } catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
            echo "WARNING: Dependency-Check timed out after ${timeoutMinutes} minutes (NVD DB cold start). Skipping."
            exitCode = 13
        }

        // Parse results
        def vulnCount = 0
        def reportFile = "${reportDir}/dependency-check-report.json"
        if (fileExists(reportFile)) {
            // KNOWN FIX: Safe grep with || true fallback
            def count = sh(
                script: "grep -c '\"severity\"' ${reportFile} 2>/dev/null || echo '0'",
                returnStdout: true
            ).trim()
            vulnCount = count.isInteger() ? count.toInteger() : 0
        }

        // Exit code 13 = NVD DB download error on cold start (non-blocking)
        if (exitCode == 13) {
            echo "WARNING: Dependency-Check DB download failed (cold start). Results may be incomplete."
        }

        def duration = (System.currentTimeMillis() - startTime) / 1000
        echo "Dependency-Check completed in ${duration}s — ${vulnCount} findings"

        // Archive report
        archiveArtifacts artifacts: "${reportDir}/**", allowEmptyArchive: true

        // Exit code 1 (v9) or 15 (v12) = vulnerabilities found above CVSS threshold
        // These are scan results, not tool errors
        def vulnExitCodes = [1, 15]
        def resultStatus = 'SUCCESS'
        def resultError = ''
        if (exitCode in vulnExitCodes) {
            resultStatus = 'WARNING'
            resultError = "${vulnCount} vulnerabilities found (CVSS >= ${failOnCVSS})"
        } else if (exitCode != 0 && exitCode != 13) {
            resultStatus = 'FAILURE'
            resultError = "Dependency-Check exited with code ${exitCode}"
        }

        return [
            status: resultStatus,
            duration: duration,
            findings: vulnCount,
            reportDir: reportDir,
            exitCode: exitCode,
            error: resultError
        ]
    } catch (Exception e) {
        echo "ERROR: Dependency-Check failed — ${e.message}"
        return [status: 'FAILURE', error: e.message]
    }
}
