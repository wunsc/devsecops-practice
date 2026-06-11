// jenkins-shared-lib/vars/commentOnMR.groovy
// Posts a summary comment on the GitLab Merge Request
// Shows stage results, coverage, scan findings, and links
//
// KNOWN FIXES:
//   - Uses GitLab Notes API: POST /projects/:id/merge_requests/:iid/notes
//   - env.gitlabMergeRequestIid comes from GitLab webhook payload (T1 only)
//   - For T2 (push-to-main), MR IID is looked up from commit SHA via GitLab API
//   - Markdown table format renders in GitLab MR comments
//   - JSON body must escape special chars — use --data-binary with temp file
//
// Usage:
//   commentOnMR(status: 'SUCCESS', results: results, pipelineConfig: config)                    # T1
//   commentOnMR(status: 'SUCCESS', results: results, pipelineConfig: config, pipelineType: 'merge')  # T2
def call(Map config = [:]) {
    def status = config.status ?: 'SUCCESS'
    def results = config.results ?: [:]
    def pipelineConfig = config.pipelineConfig
    def failedStage = config.failedStage ?: ''
    def pipelineType = config.pipelineType ?: 'mr'  // 'mr' (T1) or 'merge' (T2)
    def gitlabUrl = config.gitlabUrl ?: pipelineConfig?.gitlabUrl ?: env.GITLAB_URL ?: ''
    def projectId = config.projectId ?: env.GITLAB_PROJECT_ID ?: '1'
    def mrIid = config.mrIid ?: env.gitlabMergeRequestIid ?: ''
    def tokenCredId = config.tokenCredId ?: 'gitlab-api-token'

    if (!gitlabUrl) {
        echo "WARNING: No gitlabUrl — skipping MR comment"
        return [status: 'SKIPPED', reason: 'No GitLab URL']
    }

    // For T2 (push-to-main), env.gitlabMergeRequestIid is NOT set by webhook.
    // Look up the MR IID from the merge commit SHA via GitLab API.
    // Accept commitSha from caller (workspace may be cleaned by the time post block runs)
    def commitSha = config.commitSha ?: ''
    if (!mrIid) {
        mrIid = lookupMRFromCommit(gitlabUrl, projectId, tokenCredId, commitSha)
    }

    if (!mrIid) {
        echo "WARNING: No MR IID available — skipping MR comment"
        return [status: 'SKIPPED', reason: 'No MR IID']
    }

    try {
        def comment = buildComment(status, results, failedStage, pipelineType)

        withCredentials([string(credentialsId: tokenCredId, variable: 'GITLAB_TOKEN')]) {
            // Write comment to temp file to avoid shell escaping issues
            writeFile file: '/tmp/mr-comment.json', text: groovy.json.JsonOutput.toJson([body: comment])

            sh """
                curl -sfk -X POST "${gitlabUrl}/api/v4/projects/${projectId}/merge_requests/${mrIid}/notes" \
                    -H "PRIVATE-TOKEN: \${GITLAB_TOKEN}" \
                    -H "Content-Type: application/json" \
                    --data-binary @/tmp/mr-comment.json || echo "WARNING: Failed to post MR comment"
            """
        }

        echo "MR comment posted (MR !${mrIid}, type=${pipelineType})"
        return [status: 'SUCCESS']
    } catch (Exception e) {
        echo "WARNING: Failed to post MR comment — ${e.message}"
        return [status: 'FAILURE', error: e.message]
    }
}

/**
 * Look up the MR IID from a commit SHA.
 * Uses GitLab API: GET /projects/:id/repository/commits/:sha/merge_requests
 * Returns the most recent merged MR IID, or empty string if not found.
 * commitShaParam: pass commit SHA explicitly (workspace may be cleaned in post block)
 */
private String lookupMRFromCommit(String gitlabUrl, String projectId, String tokenCredId, String commitShaParam) {
    try {
        // Prefer explicit SHA, then env.GIT_COMMIT, then git rev-parse (may fail after cleanWs)
        def commitSha = commitShaParam ?: env.GIT_COMMIT ?: ''
        if (!commitSha) {
            try {
                commitSha = sh(script: 'git rev-parse HEAD', returnStdout: true).trim()
            } catch (Exception e) {
                echo "WARNING: Cannot determine commit SHA (workspace cleaned?) — ${e.message}"
                return ''
            }
        }
        echo "Looking up MR for commit ${commitSha}..."

        def mrJson = ''
        withCredentials([string(credentialsId: tokenCredId, variable: 'GITLAB_TOKEN')]) {
            mrJson = sh(
                script: """
                    curl -sfk "${gitlabUrl}/api/v4/projects/${projectId}/repository/commits/${commitSha}/merge_requests" \
                        -H "PRIVATE-TOKEN: \${GITLAB_TOKEN}" 2>/dev/null || echo '[]'
                """,
                returnStdout: true
            ).trim()
        }

        // Parse JSON to find the merged MR
        def mrs = new groovy.json.JsonSlurper().parseText(mrJson)
        if (mrs && mrs.size() > 0) {
            // Take the first (most recent) MR — typically the one just merged
            def foundIid = mrs[0].iid?.toString()
            echo "Found MR !${foundIid} from commit ${commitSha}"
            return foundIid
        }
        echo "No MR found for commit ${commitSha}"
        return ''
    } catch (Exception e) {
        echo "WARNING: Failed to look up MR from commit — ${e.message}"
        return ''
    }
}

/**
 * Build the markdown comment body based on pipeline results
 * pipelineType: 'mr' (T1 — validation only) or 'merge' (T2 — build + deploy)
 */
private String buildComment(String status, Map results, String failedStage, String pipelineType) {
    def icon = status == 'SUCCESS' ? ':white_check_mark:' : ':x:'
    def isMerge = (pipelineType == 'merge')
    def title = isMerge ?
        (status == 'SUCCESS' ? 'Merge Pipeline Passed — Deployed to DEV' : 'Merge Pipeline Failed') :
        (status == 'SUCCESS' ? 'Pipeline Passed' : 'Pipeline Failed')
    def buildUrl = env.BUILD_URL ?: ''
    def buildNum = env.BUILD_NUMBER ?: '?'
    def branch = env.gitlabSourceBranch ?: env.GIT_BRANCH ?: 'main'

    def sb = new StringBuilder()

    // Header
    sb.append("## ${icon} Jenkins CI — ${title}\n\n")
    sb.append("**Branch:** `${branch}` | **Build:** [#${buildNum}](${buildUrl})\n\n")

    // Stage results table
    sb.append("| Stage | Status | Details |\n")
    sb.append("|-------|--------|--------|\n")

    // Build
    def buildResult = results.build
    if (buildResult) {
        def buildIcon = buildResult.status == 'SUCCESS' ? ':white_check_mark:' : ':x:'
        def buildDetail = buildResult.status == 'SUCCESS' ?
            "${buildResult.duration ?: '?'}s" :
            "Error: ${buildResult.error ?: 'unknown'}"
        sb.append("| Build | ${buildIcon} ${buildResult.status} | ${buildDetail} |\n")
    }

    // Unit Tests
    def testResult = results.unitTests
    if (testResult) {
        def testIcon = testResult.status == 'SUCCESS' ? ':white_check_mark:' : ':x:'
        def coverage = testResult.coverage != null ? "${testResult.coverage}%" : 'N/A'
        def testDetail = testResult.status == 'SUCCESS' ?
            "Coverage: **${coverage}** (${testResult.duration ?: '?'}s)" :
            "Error: ${testResult.error ?: 'unknown'}"
        sb.append("| Unit Tests | ${testIcon} ${testResult.status} | ${testDetail} |\n")
    }

    // SonarQube
    def sonarResult = results.sonarqube
    if (sonarResult) {
        def sonarIcon = sonarResult.status == 'SUCCESS' ? ':white_check_mark:' : ':x:'
        def sonarDetail = sonarResult.status == 'SUCCESS' ?
            "Quality Gate: **${sonarResult.gateStatus ?: 'OK'}** — [Dashboard](${sonarResult.report ?: '#'})" :
            "Quality Gate: **${sonarResult.gateStatus ?: 'FAILED'}** — ${sonarResult.error ?: ''}"
        sb.append("| SonarQube (SAST) | ${sonarIcon} ${sonarResult.status} | ${sonarDetail} |\n")
    }

    // Dependency Check
    def depResult = results.dependencyCheck
    if (depResult) {
        def findings = depResult.findings ?: 0
        def depIcon = (depResult.status == 'SUCCESS' && findings == 0) ? ':white_check_mark:' :
            (depResult.status == 'FAILURE' ? ':x:' : ':warning:')
        def depDetail = (depResult.status == 'FAILURE' && !depResult.findings) ?
            "Error: ${depResult.error ?: 'unknown'}" :
            "**${findings}** findings (${depResult.duration ?: '?'}s)${depResult.error ? ' — ' + depResult.error : ''}"
        sb.append("| Dependency Check (SCA) | ${depIcon} ${depResult.status} | ${depDetail} |\n")
    }

    // SBOM
    def sbomResult = results.sbom
    if (sbomResult) {
        def sbomIcon = sbomResult.status == 'SUCCESS' ? ':white_check_mark:' :
            (sbomResult.gateResult == 'FAILED' ? ':x:' : ':warning:')
        def vulnSummary = ''
        if (sbomResult.totalVulns != null && (sbomResult.totalVulns as int) > 0) {
            vulnSummary = " | C:**${sbomResult.critical ?: 0}** H:**${sbomResult.high ?: 0}** " +
                          "M:**${sbomResult.medium ?: 0}** L:**${sbomResult.low ?: 0}**"
        }
        def trustifyLink = sbomResult.trustifyUrl ? " | [Trustify](${sbomResult.trustifyUrl})" : ''
        sb.append("| SBOM (CycloneDX) | ${sbomIcon} ${sbomResult.status} | " +
                  "**${sbomResult.components ?: 0}** components, Upload: ${sbomResult.uploadStatus ?: 'N/A'}${vulnSummary}${trustifyLink} |\n")
    }

    // Image Signing (T2/T3 only)
    def signResult = results.signImage
    if (signResult) {
        def signIcon = signResult.status == 'SUCCESS' ? ':white_check_mark:' : ':x:'
        def signDetail = signResult.status == 'SUCCESS' ?
            "Keyless (RHTAS) | Attestation: ${signResult.attestStatus ?: 'N/A'}" :
            "Error: ${signResult.error ?: 'failed'}"
        sb.append("| Image Signing | ${signIcon} ${signResult.status} | ${signDetail} |\n")
    }

    // Image Verification (T2/T3 only)
    def verifyResult = results.verifyImage
    if (verifyResult) {
        def verifyIcon = verifyResult.status == 'SUCCESS' ? ':white_check_mark:' : ':x:'
        def verifyDetail = verifyResult.status == 'SUCCESS' ?
            "Signature: ${verifyResult.signatureValid ? 'VALID' : 'INVALID'} | " +
            "Attestation: ${verifyResult.attestationValid ? 'VALID' : 'INVALID'}" :
            "Error: ${verifyResult.error ?: 'verification failed'}"
        sb.append("| Image Verification | ${verifyIcon} ${verifyResult.status} | ${verifyDetail} |\n")
    }

    // ── T2-only stages (merge pipeline) ──

    // Container Image Build
    def imgResult = results.imageBuild
    if (imgResult) {
        def imgIcon = imgResult.status == 'SUCCESS' ? ':white_check_mark:' : ':x:'
        def imgDetail = imgResult.status == 'SUCCESS' ?
            "${imgResult.duration ?: '?'}s" :
            "Error: ${imgResult.error ?: 'unknown'}"
        sb.append("| Container Image Build | ${imgIcon} ${imgResult.status} | ${imgDetail} |\n")
    }

    // Push to Registry
    def pushResult = results.push
    if (pushResult) {
        def pushIcon = pushResult.status == 'SUCCESS' ? ':white_check_mark:' : ':x:'
        def pushDetail = pushResult.status == 'SUCCESS' ?
            "${pushResult.imageRef ?: 'pushed'}" :
            "Error: ${pushResult.error ?: 'unknown'}"
        sb.append("| Push to Registry | ${pushIcon} ${pushResult.status} | ${pushDetail} |\n")
    }

    // ACS Image Scan
    // KNOWN FIX: scanACSImage returns criticalCount/highCount (not critical/high/findings)
    def acsResult = results.acsScan
    if (acsResult) {
        def acsCritical = acsResult.criticalCount ?: 0
        def acsHigh = acsResult.highCount ?: 0
        def acsIcon = (acsResult.status == 'FAILURE') ? ':x:' :
            (acsCritical > 0) ? ':x:' : (acsHigh > 0 ? ':warning:' : ':white_check_mark:')
        def acsDetail = (acsResult.status == 'FAILURE' && acsCritical == 0 && acsHigh == 0) ?
            "Error: ${acsResult.error ?: 'scan failed — check ACS connectivity'}" :
            "Critical: **${acsCritical}** / High: **${acsHigh}**"
        sb.append("| ACS Image Scan | ${acsIcon} ${acsResult.status} | ${acsDetail} |\n")
    }

    // GitOps Update
    def gitopsResult = results.gitops
    if (gitopsResult) {
        def gitopsIcon = gitopsResult.status == 'SUCCESS' ? ':white_check_mark:' : ':x:'
        def gitopsDetail = gitopsResult.status == 'SUCCESS' ?
            "Updated DEV overlay" :
            "Error: ${gitopsResult.error ?: 'unknown'}"
        sb.append("| GitOps Update | ${gitopsIcon} ${gitopsResult.status} | ${gitopsDetail} |\n")
    }

    // Deploy to DEV
    def deployResult = results.deploy
    if (deployResult) {
        def deployIcon = deployResult.status == 'SUCCESS' ? ':white_check_mark:' : ':x:'
        def deployDetail = deployResult.status == 'SUCCESS' ?
            "ArgoCD synced to DEV" :
            "Error: ${deployResult.error ?: 'unknown'}"
        sb.append("| Deploy to DEV | ${deployIcon} ${deployResult.status} | ${deployDetail} |\n")
    }

    sb.append("\n")

    if (status == 'SUCCESS') {
        if (isMerge) {
            // T2 success summary — post-merge deployment info
            sb.append("### Merge Pipeline Summary\n\n")
            sb.append("- All quality & security gates **passed**\n")
            if (testResult?.coverage != null) {
                sb.append("- Code coverage: **${testResult.coverage}%**\n")
            }
            if (acsResult) {
                sb.append("- ACS image scan: **${acsResult.criticalCount ?: 0}** critical, **${acsResult.highCount ?: 0}** high\n")
            }
            if (signResult?.status == 'SUCCESS') {
                sb.append("- Image signed: **keyless (RHTAS)** | SBOM attested: **${signResult.sbomAttested ? 'yes' : 'no'}**\n")
            }
            if (verifyResult?.status == 'SUCCESS') {
                sb.append("- Signature verified: **${verifyResult.signatureValid ? 'valid' : 'invalid'}**\n")
            }
            if (sonarResult?.report) {
                sb.append("- [View SonarQube Report](${sonarResult.report})\n")
            }
            sb.append("\n> Image deployed to **DEV** environment. Ready for promotion to SIT.\n")
        } else {
            // T1 success summary — pre-merge review info
            sb.append("### Summary for Reviewer\n\n")
            sb.append("- All quality gates **passed**\n")
            if (testResult?.coverage != null) {
                sb.append("- Code coverage: **${testResult.coverage}%**\n")
            }
            if (sonarResult?.report) {
                sb.append("- [View SonarQube Report](${sonarResult.report})\n")
            }
            if (depResult?.findings != null) {
                sb.append("- Dependency vulnerabilities: **${depResult.findings}**\n")
            }
            if (sbomResult?.totalVulns != null && (sbomResult.totalVulns as int) > 0) {
                sb.append("- SBOM vulnerabilities: **${sbomResult.critical ?: 0}** critical, " +
                          "**${sbomResult.high ?: 0}** high")
                if (sbomResult.trustifyUrl) {
                    sb.append(" — [View in Trustify](${sbomResult.trustifyUrl})")
                }
                sb.append("\n")
            }
            sb.append("\n> This MR is ready for code review and approval.\n")
        }
    } else {
        // Failure details (same for T1 and T2)
        sb.append("### Failure Details\n\n")
        if (failedStage) {
            sb.append("**Failed Stage:** `${failedStage}`\n\n")
        }

        // Show which stages failed
        results.each { key, val ->
            if (val instanceof Map && val.status == 'FAILURE') {
                sb.append("- **${key}**: ${val.error ?: 'Failed'}\n")
            }
        }

        if (isMerge) {
            sb.append("\n> Merge pipeline failed. Image was **NOT** deployed to DEV.\n")
        } else {
            sb.append("\n> Please fix the issues above and push again. The pipeline will re-run automatically.\n")
        }
        sb.append("> [View Full Console Log](${buildUrl}console)\n")
    }

    return sb.toString()
}
