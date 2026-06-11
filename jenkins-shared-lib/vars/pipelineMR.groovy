// jenkins-shared-lib/vars/pipelineMR.groovy
// T1 Orchestrator: Merge Request Validation Pipeline
// Triggered by GitLab MR webhook → validates feature branch
// Stages: checkout → build → test → SonarQube → Dependency-Check → report to GitLab
//
// SERVICE-PARAMETERIZED:
//   Each service has its own repo and its own Jenkins job.
//   The job's inline CPS script passes a config map identifying the service:
//     pipelineMR(service: 'sampleapi')
//     pipelineMR(service: 'notificationapi')
//   No change detection needed — each repo triggers only its own pipeline.
//
// KNOWN FIXES:
//   - gitLabConnection('gitlab') required for updateGitlabCommitStatus to work
//   - env.gitlabMergeRequestLastCommit available before checkout (from webhook payload)
//   - gitlabBuilds(builds: [...]) shows individual stage statuses in GitLab MR
//   - gitlabUrl must be passed explicitly to reportToGitLab (env.GITLAB_URL not set)
def call(Map config = [:]) {
    def pipelineConfig = new com.devsecops.PipelineConfig()
    def results = [:]

    // Service name from job config — determines what to build
    def serviceName = config.service ?: 'sampleapi'

    pipeline {
        agent { label 'devsecops-agent' }

        environment {
            APP_NAME = "${pipelineConfig.appName}"
            GITLAB_PROJECT_ID = "${pipelineConfig.gitlabProjectId}"
        }

        options {
            timestamps()
            ansiColor('xterm')
            timeout(time: 30, unit: 'MINUTES')
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
                        echo "=== T1: MR Validation Pipeline ==="
                        echo "  Service: ${serviceName}"
                        echo "  Source repo: ${pipelineConfig.activeSourceRepo}"
                        echo "  Branch: ${env.gitlabSourceBranch ?: env.GIT_BRANCH ?: 'unknown'}"
                        echo "  MR Commit: ${env.gitlabMergeRequestLastCommit ?: 'N/A'}"
                        echo "  MR IID: ${env.gitlabMergeRequestIid ?: 'N/A'}"

                        // Report running status to GitLab MR
                        // Uses env.gitlabMergeRequestLastCommit (available from webhook, no checkout needed)
                        updateGitlabCommitStatus name: 'jenkins-ci', state: 'running'
                    }
                }
            }

            stage('Checkout Source') {
                steps {
                    script {
                        def branch = env.gitlabSourceBranch ?: env.GIT_BRANCH ?: 'main'
                        results.checkout = checkoutSource(branch: branch, gitUrl: pipelineConfig.activeSourceRepo)
                        if (results.checkout.status == 'FAILURE') {
                            error "Checkout failed: ${results.checkout.error}"
                        }
                        results.commitSha = env.GIT_COMMIT ?: sh(script: 'git rev-parse HEAD', returnStdout: true).trim()
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
                        if (pipelineConfig.activeLanguage == 'java') { results.build = buildJava(project: '.') } else { results.build = buildDotnet(project: '.') }
                        if (results.build.status == 'FAILURE') {
                            error "Build failed: ${results.build.error}"
                        }
                    }
                }
            }

            stage('Unit Tests') {
                steps {
                    script {
                        if (pipelineConfig.activeLanguage == 'java') { results.unitTests = runJavaTests(project: '.') } else { results.unitTests = runUnitTests(project: '.') }
                    }
                }
            }

            stage('SonarQube Analysis') {
                steps {
                    script {
                        // T1: Tag scan with MR context so SonarQube shows this as
                        // a separate version from main branch scans.
                        // projectVersion = "MR-{iid}-{branchName}" for clear identification
                        def branch = env.gitlabSourceBranch ?: env.GIT_BRANCH ?: 'unknown'
                        def mrIid = env.gitlabMergeRequestIid ?: ''
                        def versionLabel = mrIid ? "MR-${mrIid}-${branch}" : "branch-${branch}"

                        if (pipelineConfig.activeLanguage == 'java') {
                            results.sonarqube = scanSonarQubeJava(
                                projectKey: pipelineConfig.sonarProjectKey,
                                sonarUrl: pipelineConfig.sonarUrl,
                                project: '.',
                                projectVersion: versionLabel
                            )
                        } else {
                            results.sonarqube = scanSonarQube(
                                projectKey: pipelineConfig.sonarProjectKey,
                                sonarUrl: pipelineConfig.sonarUrl,
                                project: '.',
                                projectVersion: versionLabel,
                                analysisMode: 'mr',
                                branchName: branch,
                                mrIid: mrIid
                            )
                        }
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

            stage('Generate SBOM') {
                steps {
                    script {
                        // T1: Generate SBOM + upload to Trustify for VEX enrichment
                        // Lenient thresholds for MR validation — strict enforcement on T2/T3
                        results.sbom = generateSBOM(
                            project: '.',
                            language: pipelineConfig.activeLanguage,
                            serviceName: serviceName,
                            imageTag: "mr-${env.gitlabMergeRequestIid ?: 'unknown'}",
                            maxCritical: 0,
                            maxHigh: 10
                        )
                        if (results.sbom.status == 'FAILURE') {
                            unstable "SBOM vulnerability gate: ${results.sbom.gateResult} " +
                                     "(Critical: ${results.sbom.critical ?: 0}, High: ${results.sbom.high ?: 0})"
                        }
                    }
                }
            }
        }

        post {
            success {
                script {
                    // Update commit status — green checkmark on MR
                    updateGitlabCommitStatus name: 'jenkins-ci', state: 'success'
                    // Post summary comment on the MR for maintainer review
                    commentOnMR(
                        status: 'SUCCESS',
                        results: results,
                        pipelineConfig: pipelineConfig,
                        gitlabUrl: pipelineConfig.gitlabUrl,
                        projectId: pipelineConfig.gitlabProjectId
                    )
                    notifyTeam(message: 'MR validation passed', status: 'SUCCESS')
                }
            }
            failure {
                script {
                    // Update commit status — red X on MR
                    updateGitlabCommitStatus name: 'jenkins-ci', state: 'failed'
                    // Post failure comment with details on what went wrong
                    commentOnMR(
                        status: 'FAILURE',
                        results: results,
                        pipelineConfig: pipelineConfig,
                        gitlabUrl: pipelineConfig.gitlabUrl,
                        projectId: pipelineConfig.gitlabProjectId
                    )
                    notifyTeam(message: 'MR validation FAILED', status: 'FAILURE')
                }
            }
            always {
                cleanWs()
            }
        }
    }
}
