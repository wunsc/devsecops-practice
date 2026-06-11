// jenkins-shared-lib/vars/pipelinePromote.groovy
// T4 Orchestrator: GitOps Promotion Pipeline
// Triggered by push to main on app-gitops repo (after MR merge)
//
// Flow:
//   1. MR created in app-gitops (e.g., promote/sit-v1.1.0 → main)
//   2. Team Lead / QA Lead / CAB reviews and approves
//   3. MR merged → push to main → webhook fires → this pipeline runs
//   4. Pipeline detects which overlay(s) changed
//   5. ArgoCD sync for each changed environment
//   6. Post-deploy health verification
//   7. Reports result back to GitLab commit
//
// This ensures:
//   - Full audit trail (who approved the MR, who merged it)
//   - No manual port-forward or CLI access needed
//   - Jenkins runs ArgoCD sync from within the cluster (ClusterIP)
//   - ArgoCD health check confirms deployment success
//
def call(Map config = [:]) {
    def pipelineConfig = new com.devsecops.PipelineConfig()
    def results = [:]

    pipeline {
        agent { label 'devsecops-agent' }

        environment {
            APP_NAME = 'sampleapi'
        }

        options {
            timestamps()
            ansiColor('xterm')
            timeout(time: 15, unit: 'MINUTES')
            disableConcurrentBuilds()
            gitLabConnection('gitlab')
        }

        stages {
            stage('Initialize') {
                steps {
                    script {
                        pipelineConfig.initFromEnv(env)
                        echo "=== T4: GitOps Promotion Pipeline ==="
                        echo "  Triggered by merge to app-gitops main branch"
                        echo "  Build: #${env.BUILD_NUMBER}"

                        // Capture who triggered — from GitLab webhook payload
                        def triggeredBy = env.gitlabUserName ?: env.gitlabUserEmail ?: 'webhook'
                        def mergeCommit = env.gitlabAfter ?: env.GIT_COMMIT ?: 'unknown'
                        results.triggeredBy = triggeredBy
                        results.mergeCommit = mergeCommit
                        echo "  Triggered by: ${triggeredBy}"
                        echo "  Merge commit: ${mergeCommit}"

                        // Report running status to GitLab commit (app-gitops project)
                        // Use API directly — the GitLab plugin's updateGitlabCommitStatus
                        // doesn't reliably match the correct project for cross-repo webhooks
                        setGitLabPipelineStatus('running', mergeCommit, pipelineConfig)
                    }
                }
            }

            stage('Checkout GitOps') {
                steps {
                    script {
                        // Checkout the app-gitops repo to detect which overlays changed
                        // Use depth=10 to handle rapid sequential merges (e.g., two
                        // promotion MRs merged in quick succession)
                        checkout([
                            $class: 'GitSCM',
                            branches: [[name: '*/main']],
                            extensions: [[$class: 'CloneOption', depth: 10, shallow: true]],
                            userRemoteConfigs: [[
                                url: pipelineConfig.gitopsRepo,
                                credentialsId: pipelineConfig.gitCredentialsId
                            ]]
                        ])
                        results.checkout = [status: 'SUCCESS']
                    }
                }
            }

            stage('Detect Changes') {
                steps {
                    script {
                        // Use gitlabBefore..gitlabAfter from webhook payload when available.
                        // This correctly handles rapid sequential merges where HEAD~1
                        // only covers the latest commit but multiple overlays changed.
                        // Fallback to HEAD~1..HEAD for manual triggers.
                        def diffFrom = env.gitlabBefore ?: ''
                        def diffTo = env.gitlabAfter ?: 'HEAD'
                        def diffRange = (diffFrom && diffFrom != '0000000000000000000000000000000000000000')
                            ? "${diffFrom}..${diffTo}"
                            : 'HEAD~1..HEAD'
                        echo "Diff range: ${diffRange}"

                        def changedFiles = sh(
                            script: "git diff --name-only ${diffRange} 2>/dev/null || echo ''",
                            returnStdout: true
                        ).trim()

                        echo "Changed files:\n${changedFiles}"

                        // Parse changed files to detect service + environment
                        // New structure: services/{service}/overlays/{env}/
                        // Each service has its own ArgoCD app: {service}-{env}
                        def approverMap = [SIT: 'Team Lead', UAT: 'QA Lead', PROD: 'CAB']
                        def envsToSync = []

                        changedFiles.split('\n').each { file ->
                            def match = (file =~ /^services\/([^\/]+)\/overlays\/([^\/]+)\//)
                            if (match) {
                                def service = match[0][1]
                                def envDir = match[0][2]
                                // Skip DEV — auto-synced by ArgoCD, T4 doesn't manage it
                                if (envDir == 'dev') return
                                // 'production' overlay → 'PROD' label
                                def envLabel = envDir == 'production' ? 'PROD' : envDir.toUpperCase()
                                // ArgoCD app name: {service}-{env} (production → prod)
                                def argoApp = envDir == 'production' ? "${service}-prod" : "${service}-${envDir}"

                                if (!envsToSync.find { it.service == service && it.env == envLabel }) {
                                    envsToSync.add([
                                        service: service,
                                        app: argoApp,
                                        env: envLabel,
                                        envDir: envDir,
                                        approver: approverMap[envLabel] ?: 'Team'
                                    ])
                                }
                            }
                        }

                        if (envsToSync.isEmpty()) {
                            echo "No per-service overlay changes detected (might be infra/, base/, or argocd/ changes). Skipping sync."
                            // Check if dev overlay changed — dev is auto-synced, no action needed
                            if (changedFiles.contains('/overlays/dev/')) {
                                echo "DEV overlay changed — auto-sync handles this via ArgoCD. No manual sync needed."
                            }
                        } else {
                            echo "Environments to sync: ${envsToSync.collect { "${it.service}/${it.env}" }.join(', ')}"
                        }

                        results.envsToSync = envsToSync
                        results.changedFiles = changedFiles

                        // Extract the image tag that was CHANGED in this commit
                        // Each per-service kustomization has exactly ONE newTag
                        envsToSync.each { envMeta ->
                            def kustomFile = "services/${envMeta.service}/overlays/${envMeta.envDir}/kustomization.yaml"
                            // Get the newTag line that was ADDED (not removed) in this commit
                            def tag = sh(
                                script: "git diff ${diffRange} -- ${kustomFile} | grep '^+.*newTag:' | head -1 | awk '{print \$NF}' || echo ''",
                                returnStdout: true
                            ).trim()
                            // Fallback: if diff-based detection fails, take first newTag
                            if (!tag) {
                                tag = sh(
                                    script: "grep 'newTag:' ${kustomFile} | head -1 | awk '{print \$2}' || echo 'unknown'",
                                    returnStdout: true
                                ).trim()
                            }
                            envMeta.imageTag = tag
                            echo "  ${envMeta.service}/${envMeta.env}: image tag = ${tag}"
                        }
                    }
                }
            }

            stage('Sync Environments') {
                steps {
                    script {
                        if (!results.envsToSync || results.envsToSync.isEmpty()) {
                            echo "No environments to sync. Pipeline complete."
                            return
                        }

                        // Sync each changed environment sequentially
                        // (SIT before UAT before PROD — respect promotion order)
                        def syncOrder = ['SIT', 'UAT', 'PROD']
                        def orderedEnvs = syncOrder.collectMany { envName ->
                            results.envsToSync.findAll { it.env == envName }
                        }

                        orderedEnvs.each { envMeta ->
                            echo "=== Syncing ${envMeta.env} ==="
                            echo "  ArgoCD App: ${envMeta.app}"
                            echo "  Image Tag: ${envMeta.imageTag ?: 'unknown'}"
                            echo "  Approved by: ${envMeta.approver} (via MR)"

                            def deployResult = deployToEnvironment(
                                app: envMeta.app,
                                argocdServer: pipelineConfig.argocdServer
                            )

                            envMeta.deployResult = deployResult

                            if (deployResult.status == 'SUCCESS') {
                                echo "${envMeta.env}: Sync=${deployResult.syncStatus}, Health=${deployResult.healthStatus}"
                            } else {
                                echo "WARNING: ${envMeta.env} deployment returned: ${deployResult.status}"
                                // Don't fail the whole pipeline — report per-env status
                            }
                        }

                        results.syncResults = orderedEnvs
                    }
                }
            }

            stage('Post-Deploy Verification') {
                steps {
                    script {
                        if (!results.syncResults || results.syncResults.isEmpty()) {
                            echo "No deployments to verify."
                            return
                        }

                        results.syncResults.each { envMeta ->
                            def serviceName = envMeta.service ?: pipelineConfig.appName
                            // Java services live in javaapp-{env}, .NET services in sampleapi-{env}
                            def javaServices = ['order-service', 'inventory-service', 'payment-service']
                            def nsPrefix = javaServices.contains(serviceName) ? 'javaapp' : 'sampleapi'
                            def ns = "${nsPrefix}-${envMeta.env.toLowerCase()}"
                            if (envMeta.env == 'PROD') {
                                ns = "${nsPrefix}-prod"
                            }

                            echo "=== Verifying ${envMeta.env} / ${serviceName} (namespace: ${ns}) ==="

                            // Check pod readiness for the specific service
                            def readyPods = sh(
                                script: "oc get pods -n ${ns} -l app=${serviceName} --no-headers 2>/dev/null | grep Running | grep '1/1' | wc -l",
                                returnStdout: true
                            ).trim()

                            // Get the route and health check
                            // jsonpath wrapping: use go-template to avoid shell quote issues
                            def route = sh(
                                script: "oc get route ${serviceName} -n ${ns} -o go-template='{{.spec.host}}' 2>/dev/null || echo ''",
                                returnStdout: true
                            ).trim()

                            def healthStatus = 'UNKNOWN'
                            if (route && route != '' && !route.contains('Error')) {
                                // .NET uses /healthz → "healthy"; Java uses /actuator/health → "UP"
                                def javaServices = ['order-service', 'inventory-service', 'payment-service']
                                def healthPath = javaServices.contains(serviceName) ? '/actuator/health' : '/healthz'
                                def healthResponse = sh(
                                    script: "curl -sk --max-time 10 https://${route}${healthPath} 2>/dev/null || echo '{}'",
                                    returnStdout: true
                                ).trim()
                                healthStatus = (healthResponse.contains('"healthy"') || healthResponse.contains('"UP"')) ? 'HEALTHY' : 'UNHEALTHY'
                            }
                            // Fallback: if no route, check pod readiness
                            if (healthStatus == 'UNKNOWN' && readyPods.toInteger() > 0) {
                                echo "  No route found — using pod readiness as health indicator"
                                healthStatus = 'HEALTHY'
                            }

                            // Get deployed image tag for the specific service
                            def deployedImage = sh(
                                script: "oc get deploy ${serviceName} -n ${ns} -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null | sed 's|.*/||' || echo 'unknown'",
                                returnStdout: true
                            ).trim()

                            envMeta.readyPods = readyPods
                            envMeta.healthStatus = healthStatus
                            envMeta.deployedImage = deployedImage
                            envMeta.route = route

                            echo "  Pods ready: ${readyPods}"
                            echo "  Health: ${healthStatus}"
                            echo "  Image: ${deployedImage}"
                            echo "  Route: https://${route}"
                        }
                    }
                }
            }

            stage('Create Next Promotion MR') {
                steps {
                    script {
                        // Cascading promotion: if deployment was healthy, auto-create
                        // MR for the next environment in the chain:
                        //   SIT (healthy) → create UAT MR
                        //   UAT (healthy) → create PROD MR
                        //   PROD → end of chain (no next MR)
                        //
                        // Overlay dir mapping: sit→uat, uat→production
                        def nextEnvMap = [
                            'SIT':  [targetEnv: 'uat',        overlayDir: 'uat'],
                            'UAT':  [targetEnv: 'production', overlayDir: 'production'],
                            'PROD': null  // end of chain
                        ]

                        if (!results.syncResults || results.syncResults.isEmpty()) {
                            echo "No deployments — skipping next promotion MR."
                            return
                        }

                        results.syncResults.each { envMeta ->
                            def nextEnv = nextEnvMap[envMeta.env]
                            if (!nextEnv) {
                                echo "${envMeta.env}: End of promotion chain — no next MR needed."
                                return
                            }

                            if (envMeta.healthStatus != 'HEALTHY') {
                                echo "${envMeta.env}: Deployment NOT healthy (${envMeta.healthStatus}) — skipping next promotion MR."
                                return
                            }

                            def imageTag = envMeta.imageTag ?: ''
                            if (!imageTag || imageTag == 'unknown') {
                                echo "${envMeta.env}: No image tag found — skipping next promotion MR."
                                return
                            }

                            def targetLabel = nextEnv.targetEnv == 'production' ? 'PROD' : nextEnv.targetEnv.toUpperCase()
                            def serviceName = envMeta.service ?: pipelineConfig.appName
                            echo "=== ${serviceName}/${envMeta.env} is HEALTHY — creating ${targetLabel} promotion MR ==="

                            // Configure pipelineConfig for the specific service so
                            // createPromotionMR uses the correct overlay path and image name
                            pipelineConfig.configureForService(serviceName)

                            def mrResult = createPromotionMR(
                                imageTag: imageTag,
                                targetEnv: nextEnv.overlayDir,
                                results: results,
                                pipelineConfig: pipelineConfig,
                                gitopsRepo: pipelineConfig.gitopsRepo,
                                gitlabUrl: pipelineConfig.gitlabUrl
                            )

                            envMeta.nextPromotionMR = mrResult
                            if (mrResult.status == 'SUCCESS') {
                                echo "${serviceName} ${targetLabel} promotion MR !${mrResult.mrIid} created"
                            } else {
                                echo "WARNING: Failed to create ${serviceName} ${targetLabel} promotion MR: ${mrResult.error ?: 'unknown'}"
                            }
                        }
                    }
                }
            }
        }

        post {
            success {
                script {
                    setGitLabPipelineStatus('success', results.mergeCommit, pipelineConfig)
                }
            }
            failure {
                script {
                    setGitLabPipelineStatus('failed', results.mergeCommit, pipelineConfig)
                }
            }
            always {
                script {
                    // Build summary report
                    def summary = buildPromotionReport(results, pipelineConfig)
                    echo summary

                    // Post report as comment on the merge commit in GitLab
                    reportPromotionToGitLab(results, pipelineConfig)
                }
                cleanWs()
            }
        }
    }
}

/**
 * Build a human-readable promotion report
 */
def buildPromotionReport(Map results, def pipelineConfig) {
    def sb = new StringBuilder()
    sb.append("\n=== Promotion Pipeline Summary ===\n")
    sb.append("  Triggered by: ${results.triggeredBy ?: 'unknown'}\n")
    sb.append("  Commit: ${results.mergeCommit ?: 'unknown'}\n")

    if (results.syncResults) {
        sb.append("\n  Environment Results:\n")
        results.syncResults.each { envMeta ->
            def icon = (envMeta.healthStatus == 'HEALTHY') ? 'PASS' : 'WARN'
            sb.append("  [${icon}] ${envMeta.env}")
            sb.append("  | Image: ${envMeta.deployedImage ?: '?'}")
            sb.append("  | Pods: ${envMeta.readyPods ?: '?'}")
            sb.append("  | Health: ${envMeta.healthStatus ?: '?'}")
            sb.append("  | Approver: ${envMeta.approver}\n")
        }
    } else {
        sb.append("\n  No environments were synced.\n")
    }
    sb.append("==================================\n")
    return sb.toString()
}

/**
 * Report promotion results to GitLab as a commit comment
 * This creates a visible audit trail on the merge commit
 */
def reportPromotionToGitLab(Map results, def pipelineConfig) {
    try {
        def gitlabUrl = pipelineConfig.gitlabUrl
        // app-gitops is project ID 7
        def gitopsProjectId = '7'
        def commitSha = results.mergeCommit ?: ''

        if (!commitSha || commitSha == 'unknown') {
            echo "No commit SHA available — skipping GitLab report"
            return
        }

        // Build markdown comment
        def sb = new StringBuilder()
        sb.append("## :rocket: Promotion Pipeline — Build #${env.BUILD_NUMBER}\n\n")
        sb.append("**Triggered by:** ${results.triggeredBy ?: 'webhook'}\n\n")

        if (results.syncResults && !results.syncResults.isEmpty()) {
            sb.append("| Environment | Image | Pods | Health | Approver |\n")
            sb.append("|-------------|-------|------|--------|----------|\n")
            results.syncResults.each { envMeta ->
                def icon = (envMeta.healthStatus == 'HEALTHY') ? ':white_check_mark:' : ':warning:'
                sb.append("| ${icon} ${envMeta.env} | `${envMeta.deployedImage ?: '?'}` | ${envMeta.readyPods ?: '?'} | ${envMeta.healthStatus ?: '?'} | ${envMeta.approver} |\n")
            }
            sb.append("\n### Routes\n")
            results.syncResults.each { envMeta ->
                if (envMeta.route) {
                    sb.append("- **${envMeta.env}**: https://${envMeta.route}\n")
                }
            }

            // Show next promotion MR if created
            def nextMRs = results.syncResults.findAll { it.nextPromotionMR?.status == 'SUCCESS' }
            if (nextMRs) {
                sb.append("\n### Next Promotion\n")
                nextMRs.each { envMeta ->
                    def mr = envMeta.nextPromotionMR
                    sb.append("- **${mr.targetEnv == 'production' ? 'PROD' : mr.targetEnv.toUpperCase()}** promotion MR created: !${mr.mrIid}\n")
                }
            }
        } else {
            sb.append("No overlay changes detected — no environments synced.\n")
        }

        sb.append("\n> [View Pipeline](${env.BUILD_URL})")

        def body = sb.toString()

        withCredentials([string(credentialsId: 'gitlab-api-token', variable: 'GITLAB_TOKEN')]) {
            // Post comment on the merge commit
            def response = sh(
                script: """
                    curl -sk -X POST \
                        -H "PRIVATE-TOKEN: \${GITLAB_TOKEN}" \
                        -H "Content-Type: application/json" \
                        "${gitlabUrl}/api/v4/projects/${gitopsProjectId}/repository/commits/${commitSha}/comments" \
                        -d '${groovy.json.JsonOutput.toJson([note: body])}' \
                        -o /dev/null -w "%{http_code}"
                """,
                returnStdout: true
            ).trim()
            echo "GitLab commit comment posted: HTTP ${response}"
        }
    } catch (Exception e) {
        echo "WARNING: Failed to post GitLab report — ${e.message} (non-blocking)"
    }
}

/**
 * Post pipeline status to GitLab via Commit Status API.
 * Uses POST /api/v4/projects/:id/statuses/:sha with explicit project ID,
 * ref, and pipeline name so it shows correctly in GitLab's commit/pipeline UI.
 *
 * GitLab commit status API: https://docs.gitlab.com/ee/api/commits.html#post-the-build-status-to-a-commit
 * state: pending, running, success, failed, canceled
 */
def setGitLabPipelineStatus(String state, String commitSha, def pipelineConfig) {
    try {
        if (!commitSha || commitSha == 'unknown') {
            echo "No commit SHA — skipping GitLab pipeline status"
            return
        }

        def gitlabUrl = pipelineConfig.gitlabUrl
        def gitopsProjectId = '7'  // app-gitops project
        def buildUrl = env.BUILD_URL ?: ''
        def buildNum = env.BUILD_NUMBER ?: '?'

        // Build description based on state
        def description = ''
        switch (state) {
            case 'running':
                description = "Promotion pipeline running (Build #${buildNum})"
                break
            case 'success':
                description = "Promotion deployed successfully (Build #${buildNum})"
                break
            case 'failed':
                description = "Promotion pipeline failed (Build #${buildNum})"
                break
            default:
                description = "Promotion pipeline ${state} (Build #${buildNum})"
        }

        withCredentials([string(credentialsId: 'gitlab-api-token', variable: 'GITLAB_TOKEN')]) {
            def response = sh(
                script: """
                    curl -sf -X POST \
                        -H "PRIVATE-TOKEN: \${GITLAB_TOKEN}" \
                        "${gitlabUrl}/api/v4/projects/${gitopsProjectId}/statuses/${commitSha}" \
                        -d "state=${state}" \
                        -d "name=jenkins/promotion" \
                        -d "ref=main" \
                        -d "target_url=${buildUrl}" \
                        -d "description=${description}" \
                        -o /dev/null -w "%{http_code}" 2>/dev/null || echo "000"
                """,
                returnStdout: true
            ).trim()
            echo "GitLab pipeline status '${state}' posted: HTTP ${response}"
        }
    } catch (Exception e) {
        echo "WARNING: Failed to set GitLab pipeline status — ${e.message} (non-blocking)"
    }
}
