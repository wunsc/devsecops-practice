// jenkins-shared-lib/src/com/devsecops/SecurityGate.groovy
// Evaluates security scan results against defined thresholds
// Used by orchestrators to decide pass/fail after scanning stages
//
// KNOWN FIX: BigDecimal.round(int) not supported in CPS — use Math.round()
package com.devsecops

import com.cloudbees.groovy.cps.NonCPS

class SecurityGate implements Serializable {
    private static final long serialVersionUID = 1L

    /**
     * Evaluate whether all security gates passed
     * @param results Map of stage results from pipeline execution
     * @param config PipelineConfig with thresholds
     * @return Map with overall pass/fail and details
     */
    @NonCPS
    static Map evaluate(Map results, PipelineConfig config) {
        def failures = []
        def warnings = []

        // Check SonarQube quality gate
        if (results.sonarqube?.status == 'FAILURE') {
            failures.add("SonarQube quality gate FAILED: ${results.sonarqube?.error ?: 'gate not passed'}")
        }

        // Check unit test coverage
        if (results.unitTests?.status == 'SUCCESS' && results.unitTests?.coverage != null) {
            double coverage = results.unitTests.coverage as double
            if (coverage < config.coverageThreshold) {
                // KNOWN FIX: Use Math.round instead of BigDecimal.round(int)
                long rounded = Math.round(coverage * 100) / 100
                warnings.add("Code coverage ${rounded}% is below threshold ${config.coverageThreshold}%")
            }
        }

        // Check ACS image scan
        if (results.acsScan?.status == 'FAILURE') {
            failures.add("ACS image scan FAILED: ${results.acsScan?.error ?: 'policy violations found'}")
        }
        if (results.acsScan?.criticalCount != null && (results.acsScan.criticalCount as int) > config.maxCriticalVulns) {
            failures.add("ACS found ${results.acsScan.criticalCount} critical vulnerabilities (max: ${config.maxCriticalVulns})")
        }

        // Check Dependency-Check
        if (results.dependencyCheck?.status == 'FAILURE') {
            failures.add("Dependency-Check FAILED: ${results.dependencyCheck?.error ?: 'vulnerabilities found'}")
        }

        // Check SBOM generation and vulnerability gate
        if (results.sbom?.status == 'FAILURE') {
            def sbomDetail = results.sbom?.critical ?
                " (Critical: ${results.sbom.critical}, High: ${results.sbom.high})" : ''
            failures.add("SBOM vulnerability gate FAILED: ${results.sbom?.gateResult ?: 'generation or analysis failed'}${sbomDetail}")
        }

        // Check image signing (T2/T3 only — absent in T1, safe-nav returns null)
        if (results.signImage?.status == 'FAILURE') {
            failures.add("Image signing FAILED: ${results.signImage?.error ?: 'cosign sign failed'}")
        }

        // Check image verification (T2/T3 only)
        if (results.verifyImage?.status == 'FAILURE') {
            failures.add("Image verification FAILED: ${results.verifyImage?.error ?: 'signature or attestation invalid'}")
        }

        // Check OWASP ZAP (if run)
        if (results.owaspZap?.status == 'FAILURE') {
            failures.add("OWASP ZAP DAST scan FAILED: ${results.owaspZap?.error ?: 'vulnerabilities found'}")
        }

        // Check gitleaks
        if (results.gitleaks?.status == 'FAILURE') {
            failures.add("Gitleaks detected secrets in source code")
        }

        def passed = failures.isEmpty()

        return [
            passed: passed,
            failures: failures,
            warnings: warnings,
            summary: passed ? "All security gates PASSED" : "FAILED: ${failures.size()} gate(s) failed"
        ]
    }

    /**
     * Format gate results for logging
     */
    @NonCPS
    static String formatReport(Map gateResult) {
        def sb = new StringBuilder()
        sb.append("=== Security Gate Report ===\n")
        sb.append("Status: ${gateResult.passed ? 'PASSED' : 'FAILED'}\n")

        if (gateResult.failures) {
            sb.append("\nFailures:\n")
            gateResult.failures.each { f -> sb.append("  - ${f}\n") }
        }

        if (gateResult.warnings) {
            sb.append("\nWarnings:\n")
            gateResult.warnings.each { w -> sb.append("  - ${w}\n") }
        }

        sb.append("============================\n")
        return sb.toString()
    }
}
