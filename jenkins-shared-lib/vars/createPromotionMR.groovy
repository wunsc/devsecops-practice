// jenkins-shared-lib/vars/createPromotionMR.groovy
// Creates a Merge Request in app-gitops to promote a release image to SIT
// Called by pipelineTag.groovy after a successful T3 pipeline
//
// The MR description contains the full T3 pipeline summary (scan results,
// ZAP findings, ACS results, etc.) so the approver has all the info needed
// to make a promotion decision — just click Approve + Merge.
//
// After merge, the T4 pipeline (pipelinePromote) automatically triggers
// via webhook and syncs ArgoCD.
//
// Usage:
//   createPromotionMR(
//       imageTag: 'v1.2.3',
//       results: results,
//       pipelineConfig: pipelineConfig
//   )
def call(Map config = [:]) {
    def startTime = System.currentTimeMillis()
    def imageTag = config.imageTag ?: ''
    def targetEnv = config.targetEnv ?: 'sit'
    def results = config.results ?: [:]
    def pipelineConfig = config.pipelineConfig
    def gitopsRepo = config.gitopsRepo ?: pipelineConfig?.gitopsRepo ?: ''
    def gitlabUrl = config.gitlabUrl ?: pipelineConfig?.gitlabUrl ?: ''
    def appName = config.appName ?: pipelineConfig?.appName ?: 'sampleapi'
    def credentialsId = config.credentialsId ?: 'gitlab-token'
    def apiTokenCredId = config.apiTokenCredId ?: 'gitlab-api-token'
    // app-gitops project ID (separate from app-source)
    def gitopsProjectId = config.gitopsProjectId ?: '7'

    // activeImageName is set by configureForService() — defaults to appName
    // Used for: branch naming, overlay path, kustomize image name
    def activeImage = pipelineConfig?.activeImageName ?: appName

    // Display label: 'production' overlay → 'PROD' label
    def envLabel = (targetEnv == 'production') ? 'PROD' : targetEnv.toUpperCase()

    echo "=== Create Promotion MR ==="
    echo "  Service: ${activeImage}"
    echo "  Image tag: ${imageTag}"
    echo "  Target environment: ${envLabel}"
    echo "  GitOps repo: ${gitopsRepo}"

    if (!imageTag) {
        echo "WARNING: No image tag — skipping promotion MR"
        return [status: 'SKIPPED', reason: 'No image tag']
    }

    if (!gitopsRepo) {
        echo "WARNING: No gitops repo URL — skipping promotion MR"
        return [status: 'SKIPPED', reason: 'No gitops repo']
    }

    // Include service name in branch to avoid collisions between services
    def branchName = "promote/${activeImage}/${imageTag}-to-${targetEnv}"
    def mrTitle = "Promote ${activeImage} ${imageTag} to ${envLabel}"

    try {
        // Build the MR description with T3 pipeline summary
        def mrDescription = buildPromotionDescription(imageTag, targetEnv, results, appName)
        def sourceBranchSha = ''

        dir('gitops-promotion') {
            withCredentials([usernamePassword(credentialsId: credentialsId, usernameVariable: 'GIT_USER', passwordVariable: 'GIT_PASS')]) {
                // Clone app-gitops
                def repoUrl = gitopsRepo.replace('https://', "https://${GIT_USER}:${GIT_PASS}@")
                sh "git clone ${repoUrl} . 2>/dev/null"
                sh "git config user.email 'jenkins@devsecops.local'"
                sh "git config user.name 'Jenkins CI'"

                // Create promotion branch (delete remote branch if exists from previous run)
                sh(script: "git push origin --delete ${branchName} 2>&1 || true", returnStatus: true)
                sh "git checkout -b ${branchName}"

                // Update image tag in the per-service overlay
                // Structure: services/{service}/overlays/{env}/kustomization.yaml
                def overlayDir = "services/${activeImage}/overlays/${targetEnv}"
                if (!fileExists(overlayDir)) {
                    error "Overlay directory not found: ${overlayDir}"
                }

                dir(overlayDir) {
                    // Update the active service's image tag only
                    def fullImageRef = "${pipelineConfig.imageRegistry}/${pipelineConfig.imageNamespace}/${activeImage}:${imageTag}"
                    sh "kustomize edit set image ${activeImage}=${fullImageRef}"
                    echo "Updated ${overlayDir}/kustomization.yaml → ${activeImage}=${fullImageRef}"
                }

                // Commit and push the branch
                sh "git add -A"
                def hasChanges = sh(script: 'git diff --cached --quiet', returnStatus: true) != 0
                if (hasChanges) {
                    sh "git commit -m 'promote(${targetEnv}): update ${activeImage} to ${imageTag}'"
                } else {
                    echo "No changes to commit — overlay already has ${imageTag}"
                    // Still push branch so MR can be created
                    sh "git commit --allow-empty -m 'promote(${targetEnv}): ${activeImage} ${imageTag} (no overlay change)'"
                }
                // Capture the commit SHA before push — needed for MR pipeline status
                sourceBranchSha = sh(script: 'git rev-parse HEAD', returnStdout: true).trim()
                sh "git push origin ${branchName}"
                echo "Branch ${branchName} pushed (SHA: ${sourceBranchSha})"
            }
        }

        // Clean up workspace
        sh 'rm -rf gitops-promotion'

        // Create the MR via GitLab API
        def mrIid = ''
        withCredentials([string(credentialsId: apiTokenCredId, variable: 'GITLAB_TOKEN')]) {
            // Write description to temp file to handle special chars
            writeFile file: '/tmp/promotion-mr.json', text: groovy.json.JsonOutput.toJson([
                source_branch: branchName,
                target_branch: 'main',
                title: mrTitle,
                description: mrDescription,
                remove_source_branch: true
            ])

            def mrResponse = sh(
                script: """
                    curl -sfk -X POST "${gitlabUrl}/api/v4/projects/${gitopsProjectId}/merge_requests" \
                        -H "PRIVATE-TOKEN: \${GITLAB_TOKEN}" \
                        -H "Content-Type: application/json" \
                        --data-binary @/tmp/promotion-mr.json 2>/dev/null || echo '{}'
                """,
                returnStdout: true
            ).trim()

            // Parse MR IID from response
            try {
                def mrData = new groovy.json.JsonSlurper().parseText(mrResponse)
                mrIid = mrData.iid?.toString() ?: ''
                if (mrIid) {
                    echo "Promotion MR created: !${mrIid}"
                    echo "  URL: ${gitlabUrl}/devsecops/app-gitops/-/merge_requests/${mrIid}"
                } else {
                    echo "WARNING: MR response did not contain IID: ${mrResponse.take(200)}"
                }
            } catch (Exception pe) {
                echo "WARNING: Could not parse MR response: ${pe.message}"
            }

            // Post commit status on the source branch HEAD commit so it appears
            // in the MR's Pipelines tab (not just at the bottom as commit status).
            // GitLab MR Pipelines tab only shows pipelines linked to the MR's
            // source branch HEAD commit.
            if (sourceBranchSha) {
                def pipelineDescription = "All quality gates passed — ready for promotion to ${envLabel}"
                def statusResponse = sh(
                    script: """
                        curl -sfk -X POST \
                            -H "PRIVATE-TOKEN: \${GITLAB_TOKEN}" \
                            "${gitlabUrl}/api/v4/projects/${gitopsProjectId}/statuses/${sourceBranchSha}" \
                            -d "state=success" \
                            -d "name=jenkins/quality-gates" \
                            -d "ref=${branchName}" \
                            -d "target_url=${env.BUILD_URL ?: ''}" \
                            -d "description=${pipelineDescription}" \
                            -o /dev/null -w "%{http_code}" 2>/dev/null || echo "000"
                    """,
                    returnStdout: true
                ).trim()
                echo "MR pipeline status posted on source branch: HTTP ${statusResponse}"
            }
        }

        def duration = (System.currentTimeMillis() - startTime) / 1000
        echo "Promotion MR created in ${duration}s"

        return [
            status: 'SUCCESS',
            duration: duration,
            mrIid: mrIid,
            branch: branchName,
            targetEnv: targetEnv,
            imageTag: imageTag
        ]
    } catch (Exception e) {
        echo "ERROR: Failed to create promotion MR — ${e.message}"
        sh 'rm -rf gitops-promotion || true'
        return [status: 'FAILURE', error: e.message]
    }
}

/**
 * Build the MR description with pipeline summary.
 * When called from T3: includes full scan results (build, tests, SAST, SCA, ACS, DAST).
 * When called from T4 (cascading): includes previous environment deployment status.
 */
private String buildPromotionDescription(String imageTag, String targetEnv, Map results, String appName) {
    def buildUrl = env.BUILD_URL ?: ''
    def buildNum = env.BUILD_NUMBER ?: '?'
    def envLabel = (targetEnv == 'production') ? 'PROD' : targetEnv.toUpperCase()

    // Detect if this is a T3 call (has scan results) or T4 cascading call (has syncResults)
    def isFromTagPipeline = results.containsKey('build') || results.containsKey('sonarqube')
    def isFromPromotePipeline = results.containsKey('syncResults')

    def sb = new StringBuilder()

    sb.append("## Release ${imageTag} — Promotion to ${envLabel}\n\n")

    if (isFromTagPipeline) {
        sb.append("This MR was automatically created by the **T3 Tag Pipeline** after all quality and security gates passed.\n\n")
    } else if (isFromPromotePipeline) {
        // Find which env was just deployed successfully
        def previousEnv = results.syncResults?.find { it.healthStatus == 'HEALTHY' }
        def prevLabel = previousEnv?.env ?: 'previous environment'
        sb.append("This MR was automatically created after **${prevLabel}** deployment was verified **HEALTHY**.\n\n")
        sb.append("Cascading promotion: the same image that passed all T3 gates and deployed successfully to ${prevLabel}.\n\n")
    } else {
        sb.append("This MR was automatically created for promotion.\n\n")
    }

    sb.append("**Action required:** Review the results below, then **Approve** and **Merge** to deploy.\n\n")
    sb.append("---\n\n")

    if (isFromTagPipeline) {
        // T3 scan results table
        sb.append("### T3 Pipeline Results ([Build #${buildNum}](${buildUrl}))\n\n")
    } else if (isFromPromotePipeline) {
        // T4 deployment results
        sb.append("### Previous Environment Deployment ([Build #${buildNum}](${buildUrl}))\n\n")
    }

    sb.append("| Stage | Status | Details |\n")
    sb.append("|-------|--------|--------|\n")

    // Build
    if (results.build) {
        def r = results.build
        def icon = r.status == 'SUCCESS' ? '✅' : '❌'
        sb.append("| .NET Build | ${icon} ${r.status} | ${r.duration ?: '?'}s |\n")
    }

    // Unit Tests
    if (results.unitTests) {
        def r = results.unitTests
        def icon = r.status == 'SUCCESS' ? '✅' : '❌'
        def coverage = r.coverage != null ? "Coverage: **${r.coverage}%**" : ''
        sb.append("| Unit Tests | ${icon} ${r.status} | ${coverage} (${r.duration ?: '?'}s) |\n")
    }

    // SonarQube
    if (results.sonarqube) {
        def r = results.sonarqube
        def icon = r.status == 'SUCCESS' ? '✅' : '❌'
        def gate = r.gateStatus ?: 'N/A'
        def link = r.report ? "[Dashboard](${r.report})" : ''
        sb.append("| SonarQube (SAST) | ${icon} ${r.status} | Gate: **${gate}** ${link} |\n")
    }

    // Dependency Check
    if (results.dependencyCheck) {
        def r = results.dependencyCheck
        def icon = r.status == 'SUCCESS' ? '✅' : '⚠️'
        sb.append("| Dependency Check (SCA) | ${icon} ${r.status} | **${r.findings ?: 0}** findings (${r.duration ?: '?'}s) |\n")
    }

    // Container Image Build
    if (results.imageBuild) {
        def r = results.imageBuild
        def icon = r.status == 'SUCCESS' ? '✅' : '❌'
        sb.append("| Container Image | ${icon} ${r.status} | ${r.duration ?: '?'}s |\n")
    }

    // Push to Registry
    if (results.push) {
        def r = results.push
        def icon = r.status == 'SUCCESS' ? '✅' : '❌'
        sb.append("| Push to Registry | ${icon} ${r.status} | ${r.imageRef ?: imageTag} |\n")
    }

    // ACS Image Scan
    if (results.acsScan) {
        def r = results.acsScan
        def critical = r.criticalCount ?: 0
        def high = r.highCount ?: 0
        def icon = (r.status == 'FAILURE') ? '❌' :
            critical > 0 ? '❌' : (high > 0 ? '⚠️' : '✅')
        def detail = (r.status == 'FAILURE' && critical == 0 && high == 0) ?
            "Error: ${r.error ?: 'scan failed — check ACS connectivity'}" :
            "Critical: **${critical}** / High: **${high}**"
        sb.append("| ACS Image Scan (Strict) | ${icon} ${r.status} | ${detail} |\n")
    }

    // OWASP ZAP DAST
    if (results.owaspZap) {
        def r = results.owaspZap
        def icon = (r.high ?: 0) > 0 ? '⚠️' : '✅'
        sb.append("| OWASP ZAP (DAST) | ${icon} ${r.status} | High: **${r.high ?: 0}** / Med: **${r.medium ?: 0}** / Low: **${r.low ?: 0}** / Info: **${r.info ?: 0}** |\n")
    }

    // Performance Test (k6)
    if (results.perfTest) {
        def r = results.perfTest
        def icon = r.status == 'SUCCESS' ? '✅' : (r.status == 'SKIPPED' ? '⏭️' : '❌')
        def details = r.summary ?: (r.reason ?: "${r.duration ?: '?'}s")
        sb.append("| Performance Test (k6) | ${icon} ${r.status} | ${details} |\n")
    }

    // Image Signing
    if (results.signImage) {
        def r = results.signImage
        def icon = r.status == 'SUCCESS' ? '✅' : '❌'
        def detail = r.status == 'SUCCESS' ?
            "Keyless (RHTAS) | Attestation: ${r.attestStatus ?: 'N/A'}" :
            "Error: ${r.error ?: 'failed'}"
        sb.append("| Image Signing (Cosign) | ${icon} ${r.status} | ${detail} |\n")
    }

    // Image Verification
    if (results.verifyImage) {
        def r = results.verifyImage
        def icon = r.status == 'SUCCESS' ? '✅' : '❌'
        def sigValid = r.signatureValid ? 'Valid' : 'Invalid'
        def attValid = r.attestationValid ? 'Valid' : 'Invalid'
        sb.append("| Image Verification | ${icon} ${r.status} | Signature: **${sigValid}** / Attestation: **${attValid}** |\n")
    }

    // SBOM
    if (results.sbom) {
        def r = results.sbom
        def icon = r.status == 'SUCCESS' ? '✅' : (r.gateResult == 'FAILED' ? '❌' : '⚠️')
        def upload = r.uploadStatus ?: 'N/A'
        def vulns = (r.totalVulns ?: 0) > 0 ?
            " | C:${r.critical ?: 0}/H:${r.high ?: 0}/M:${r.medium ?: 0}/L:${r.low ?: 0}" : ''
        sb.append("| SBOM (CycloneDX) | ${icon} ${r.status} | **${r.components ?: 0}** components, Upload: ${upload}${vulns} |\n")
    }

    // T4 cascading: show previous environment deployment results
    if (results.syncResults) {
        results.syncResults.each { envMeta ->
            def icon = (envMeta.healthStatus == 'HEALTHY') ? '✅' : '❌'
            sb.append("| ${envMeta.env} Deployment | ${icon} ${envMeta.healthStatus} | Pods: ${envMeta.readyPods ?: '?'} / Image: `${envMeta.deployedImage ?: '?'}` |\n")
        }
    }

    sb.append("\n---\n\n")

    // What this MR does
    def argoAppName = (targetEnv == 'production') ? "${appName}-prod" : "${appName}-${targetEnv}"
    sb.append("### Changes\n\n")
    sb.append("- Updates `services/${appName}/overlays/${targetEnv}/kustomization.yaml` image tag to `${imageTag}`\n")
    sb.append("- After merge, the **T4 Promotion Pipeline** will automatically:\n")
    sb.append("  1. Detect the ${envLabel} overlay change\n")
    sb.append("  2. Sync ArgoCD application `${argoAppName}`\n")
    sb.append("  3. Verify deployment health\n")
    sb.append("  4. Post results back as a commit comment\n")

    // Mention next promotion if not end of chain
    if (targetEnv == 'uat') {
        sb.append("  5. If healthy, auto-create **PROD** promotion MR\n")
    } else if (targetEnv != 'production') {
        def nextLabel = (targetEnv == 'sit') ? 'UAT' : targetEnv.toUpperCase()
        sb.append("  5. If healthy, auto-create **${nextLabel}** promotion MR\n")
    }

    sb.append("\n---\n\n")

    def pipelineLabel = isFromTagPipeline ? 'T3 Tag Pipeline' : 'T4 Promotion Pipeline'
    sb.append("_Automatically created by Jenkins ${pipelineLabel} — [Build #${buildNum}](${buildUrl})_\n")

    return sb.toString()
}
