// jenkins-shared-lib/vars/pipelineTag.groovy
// T3 Orchestrator: Tag/Release Pipeline
// Triggered by git tag push → full validation + DAST + release-ready image
// NO deploy — image only (promotion is via GitOps MR)
//
// SERVICE-PARAMETERIZED:
//   Each service has its own repo and its own Jenkins job.
//   The job's inline CPS script passes a config map identifying the service:
//     pipelineTag(service: 'sampleapi')
//     pipelineTag(service: 'notificationapi')
//   The tag is pushed on a specific service's repo, triggering only that job.
//
// KNOWN FIXES:
//   - Use checkoutSource with tag param (inline CPS has no SCM config)
//   - TAG_NAME derived from env.gitlabBranch or GIT_BRANCH
//   - cosign signing gracefully skips if cosign-signing-key not found
//
// ZAP DAST:
//   Uses a ZAP sidecar container in the agent pod. The sidecar runs ZAP in
//   daemon mode on localhost:8090. scanOWASPZAP.groovy communicates with it
//   via ZAP's REST API from the jnlp container.
//   inheritFrom 'devsecops-agent' keeps all existing tools (dotnet, podman, etc.)
//   and adds the ZAP container alongside.
//
// PERFORMANCE TEST (Phase 23):
//   After DAST, runs k6 load test against DEV endpoint as a quality gate.
//   k6 exits 99 if thresholds breach → pipeline fails → image NOT pushed.
//   Thresholds: p95<800ms, p99<2000ms, error rate<1%.
def call(Map config = [:]) {
    def pipelineConfig = new com.devsecops.PipelineConfig()
    def results = [:]

    // Service name from job config — determines what to build
    def serviceName = config.service ?: 'sampleapi'

    pipeline {
        agent {
            kubernetes {
                inheritFrom 'devsecops-agent'
                yaml """
apiVersion: v1
kind: Pod
spec:
  securityContext:
    runAsUser: 0
  containers:
  - name: zap
    image: ghcr.io/zaproxy/zaproxy:stable
    command:
    - zap.sh
    args:
    - -daemon
    - -host
    - '0.0.0.0'
    - -port
    - '8090'
    - -config
    - api.addrs.addr.name=.*
    - -config
    - api.addrs.addr.regex=true
    - -config
    - api.disablekey=true
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

        environment {
            APP_NAME = "${pipelineConfig.appName}"
            GITLAB_PROJECT_ID = "${pipelineConfig.gitlabProjectId}"
        }

        options {
            timestamps()
            ansiColor('xterm')
            timeout(time: 90, unit: 'MINUTES')
            disableConcurrentBuilds()
            gitLabConnection('gitlab')
        }

        stages {
            stage('Initialize') {
                steps {
                    script {
                        pipelineConfig.initFromEnv(env)
                        // Configure for the target service
                        pipelineConfig.configureForService(serviceName)
                        // Update env after configureForService (environment block evaluates too early)
                        env.GITLAB_PROJECT_ID = pipelineConfig.gitlabProjectId

                        // KNOWN FIX: Get tag name from GitLab webhook env vars or GIT_BRANCH
                        // GitLab plugin sets gitlabBranch for push/tag events (e.g., "v1.0.0" or "refs/tags/v1.0.0")
                        def rawTag = env.gitlabBranch ?: env.GIT_BRANCH ?: env.TAG_NAME ?: ''
                        def tagName = com.devsecops.ImageTagger.forTag(rawTag)
                        pipelineConfig.imageTag = tagName
                        echo "=== T3: Tag/Release Pipeline ==="
                        echo "  Service: ${serviceName}"
                        echo "  Source repo: ${pipelineConfig.activeSourceRepo}"
                        echo "  Tag: ${tagName}"
                        echo "  Raw ref: ${rawTag}"

                        if (!com.devsecops.ImageTagger.isValidSemver(tagName)) {
                            echo "WARNING: Tag '${tagName}' is not a valid semver format"
                        }

                        // Commit SHA is resolved after checkout (gitlabAfter for
                        // annotated tags is the tag object SHA, not the commit SHA).
                        // We'll post 'running' status after checkout completes.
                        results.commitSha = ''
                    }
                }
            }

            stage('Checkout Source') {
                steps {
                    script {
                        // Always use checkoutSource with tag — checkout scm doesn't work
                        // for inline CPS jobs (no SCM config). checkoutSource uses
                        // +refs/tags/* refspec when tag parameter is provided.
                        results.checkout = checkoutSource(tag: pipelineConfig.imageTag, gitUrl: pipelineConfig.activeSourceRepo)
                        if (results.checkout.status == 'FAILURE') {
                            error "Checkout failed: ${results.checkout.error}"
                        }

                        // Now that source is checked out, resolve actual commit SHA
                        // (gitlabAfter for annotated tags = tag object SHA, not commit)
                        results.commitSha = sh(script: 'git rev-parse HEAD', returnStdout: true).trim()
                        echo "  Commit SHA: ${results.commitSha}"

                        // Report running status to GitLab via direct API
                        setGitLabPipelineStatus('running', results.commitSha, pipelineConfig)
                    }
                }
            }

            stage('Checkout Build Config') {
                steps {
                    script {
                        results.buildConfig = checkoutBuildConfig(gitUrl: pipelineConfig.buildConfigRepo)
                    }
                }
            }

            stage('Build') {
                steps {
                    script {
                        results.build = buildDotnet(project: '.')
                        if (results.build.status == 'FAILURE') {
                            error "Build failed: ${results.build.error}"
                        }
                    }
                }
            }

            stage('Unit Tests') {
                steps {
                    script {
                        results.unitTests = runUnitTests(project: '.')
                    }
                }
            }

            stage('SonarQube Analysis') {
                steps {
                    script {
                        // T3: Tag scan with version tag so SonarQube identifies this
                        // as a release scan, separate from MR and main branch scans.
                        // projectVersion = tag name (e.g., "v1.1.0")
                        results.sonarqube = scanSonarQube(
                            projectKey: pipelineConfig.sonarProjectKey,
                            sonarUrl: pipelineConfig.sonarUrl,
                            project: '.',
                            projectVersion: pipelineConfig.imageTag,
                            analysisMode: 'tag'
                        )
                        if (results.sonarqube.status == 'FAILURE') {
                            unstable "SonarQube quality gate failed: ${results.sonarqube.gateStatus ?: results.sonarqube.error}"
                        }
                    }
                }
            }

            stage('Dependency Check') {
                steps {
                    script {
                        def suppressionFile = fileExists('build-config/dependency-check-suppression.xml') ?
                            'build-config/dependency-check-suppression.xml' : ''
                        results.dependencyCheck = scanDependencyCheck(
                            project: 'src/',
                            suppressionFile: suppressionFile
                        )
                    }
                }
            }

            stage('Build Container Image') {
                steps {
                    script {
                        results.imageBuild = buildContainerImage(
                            dockerfile: 'build-config/Dockerfile',
                            imageRef: pipelineConfig.getActiveImageRef(),
                            buildArgs: pipelineConfig.activeBuildArgs
                        )
                        if (results.imageBuild.status == 'FAILURE') {
                            error "Image build failed: ${results.imageBuild.error}"
                        }
                    }
                }
            }

            // Push BEFORE ACS scan — ACS pulls from registry
            stage('Push to Registry') {
                steps {
                    script {
                        results.push = pushToRegistry(
                            imageRef: pipelineConfig.getActiveImageRef(),
                            additionalTags: ['latest-release']
                        )
                        if (results.push.status == 'FAILURE') {
                            error "Push failed: ${results.push.error}"
                        }
                    }
                }
            }

            stage('ACS Image Scan (Strict)') {
                steps {
                    script {
                        results.acsScan = scanACSImage(
                            imageRef: pipelineConfig.getActiveImageRef(),
                            acsUrl: pipelineConfig.acsUrl,
                            strict: true
                        )
                    }
                }
            }

            stage('OWASP ZAP DAST') {
                steps {
                    script {
                        // DAST against DEV endpoint — scans the running application
                        // Route format: {routeName}-{namespace}.{domain}
                        def appsDomain = pipelineConfig.gitlabUrl
                            .replace('https://gitlab-devsecops-gitlab.', '')
                        def devRoute = "https://${pipelineConfig.appName}-${pipelineConfig.appName}-dev.${appsDomain}"
                        echo "DAST target: ${devRoute}"

                        results.owaspZap = scanOWASPZAP(
                            target: devRoute,
                            scanType: 'baseline',
                            zapPort: 8090
                        )

                        if (results.owaspZap.high > 0) {
                            unstable("ZAP found ${results.owaspZap.high} high-severity alerts")
                        }
                    }
                }
            }

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

            stage('Sign Image') {
                steps {
                    script {
                        // KNOWN FIX: Gracefully skips if cosign-signing-key not configured
                        results.signImage = signImage(
                            imageRef: pipelineConfig.getActiveImageRef()
                        )
                    }
                }
            }

            stage('Create Promotion MR') {
                steps {
                    script {
                        // Automatically create an MR in app-gitops to promote this
                        // release to SIT. The MR description contains the full T3
                        // pipeline summary so the approver has all scan results.
                        // After approval + merge, T4 pipeline handles ArgoCD sync.
                        results.promotionMR = createPromotionMR(
                            imageTag: pipelineConfig.imageTag,
                            targetEnv: 'sit',
                            results: results,
                            pipelineConfig: pipelineConfig,
                            gitopsRepo: pipelineConfig.gitopsRepo,
                            gitlabUrl: pipelineConfig.gitlabUrl
                        )
                    }
                }
            }
        }

        // NO updateGitOps, NO deployToEnvironment — T3 produces image only
        // Promotion MR created automatically — lead approves + merges → T4 syncs ArgoCD

        post {
            success {
                script {
                    setGitLabPipelineStatus('success', results.commitSha, pipelineConfig)
                    def mrInfo = results.promotionMR?.mrIid ? " — Promotion MR !${results.promotionMR.mrIid} created" : ''
                    notifyTeam(
                        message: "Tag pipeline SUCCESS — ${serviceName} ${pipelineConfig.imageTag} ready for promotion${mrInfo}",
                        status: 'SUCCESS'
                    )
                }
            }
            failure {
                script {
                    setGitLabPipelineStatus('failed', results.commitSha, pipelineConfig)
                    notifyTeam(message: "Tag pipeline FAILED for ${serviceName} ${pipelineConfig.imageTag}", status: 'FAILURE')
                }
            }
            unstable {
                script {
                    setGitLabPipelineStatus('success', results.commitSha, pipelineConfig)
                    notifyTeam(
                        message: "Tag pipeline UNSTABLE for ${serviceName} ${pipelineConfig.imageTag} (optional stages failed)",
                        status: 'UNSTABLE'
                    )
                }
            }
            always {
                script {
                    def gateResult = com.devsecops.SecurityGate.evaluate(results, pipelineConfig)
                    echo com.devsecops.SecurityGate.formatReport(gateResult)
                }
                cleanWs()
            }
        }
    }
}

/**
 * Post pipeline status to GitLab via Commit Status API.
 * Posts to app-source project (ID from pipelineConfig.gitlabProjectId)
 * so the status appears on the tag commit in GitLab's pipeline UI.
 *
 * state: pending, running, success, failed, canceled
 */
def setGitLabPipelineStatus(String state, String commitSha, def pipelineConfig) {
    try {
        if (!commitSha || commitSha == 'unknown') {
            echo "No commit SHA — skipping GitLab pipeline status"
            return
        }

        def gitlabUrl = pipelineConfig.gitlabUrl
        def projectId = pipelineConfig.gitlabProjectId ?: '1'  // app-source project
        def buildUrl = env.BUILD_URL ?: ''
        def buildNum = env.BUILD_NUMBER ?: '?'
        def tagName = pipelineConfig.imageTag ?: ''

        def description = ''
        switch (state) {
            case 'running':
                description = "Tag pipeline running for ${tagName} (Build #${buildNum})"
                break
            case 'success':
                description = "Tag pipeline passed — ${tagName} ready for promotion (Build #${buildNum})"
                break
            case 'failed':
                description = "Tag pipeline failed for ${tagName} (Build #${buildNum})"
                break
            default:
                description = "Tag pipeline ${state} for ${tagName} (Build #${buildNum})"
        }

        withCredentials([string(credentialsId: 'gitlab-api-token', variable: 'GITLAB_TOKEN')]) {
            def response = sh(
                script: """
                    curl -sf -X POST \
                        -H "PRIVATE-TOKEN: \${GITLAB_TOKEN}" \
                        "${gitlabUrl}/api/v4/projects/${projectId}/statuses/${commitSha}" \
                        -d "state=${state}" \
                        -d "name=jenkins/tag-pipeline" \
                        -d "ref=${tagName}" \
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
