// jenkins-shared-lib/vars/pipelineMerge.groovy
// T2 Orchestrator: Merge to Main Pipeline
// Triggered by push to main → build, scan, push image, deploy DEV
//
// SERVICE-PARAMETERIZED:
//   Each service has its own repo and its own Jenkins job.
//   The job's inline CPS script passes a config map identifying the service:
//     pipelineMerge(service: 'sampleapi')
//     pipelineMerge(service: 'notificationapi')
//   No change detection needed — each repo triggers only its own pipeline.
//
// KNOWN FIXES:
//   - Push image BEFORE ACS scan (ACS pulls from registry)
//   - Image tag format: main-<short-sha>
//   - gitLabConnection('gitlab') required for updateGitlabCommitStatus
//   - commentOnMR with pipelineType:'merge' looks up MR IID from commit SHA
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
            timeout(time: 60, unit: 'MINUTES')
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
                        echo "=== T2: Merge to Main Pipeline ==="
                        echo "  Service: ${serviceName}"
                        echo "  Source repo: ${pipelineConfig.activeSourceRepo}"
                    }
                }
            }

            stage('Checkout Source') {
                steps {
                    script {
                        results.checkout = checkoutSource(branch: 'main', gitUrl: pipelineConfig.activeSourceRepo)
                        if (results.checkout.status == 'FAILURE') {
                            error "Checkout failed: ${results.checkout.error}"
                        }
                        def gitCommit = env.GIT_COMMIT ?: sh(script: 'git rev-parse HEAD', returnStdout: true).trim()
                        results.commitSha = gitCommit
                        pipelineConfig.imageTag = com.devsecops.ImageTagger.forMerge(gitCommit)
                        echo "Image tag: ${pipelineConfig.imageTag}"
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
                        if (pipelineConfig.activeLanguage == 'java') {
                            results.build = buildJava(project: '.')
                        } else {
                            results.build = buildDotnet(project: '.')
                        }
                        if (results.build.status == 'FAILURE') {
                            error "Build failed: ${results.build.error}"
                        }
                    }
                }
            }

            stage('Unit Tests') {
                steps {
                    script {
                        if (pipelineConfig.activeLanguage == 'java') {
                            results.unitTests = runJavaTests(project: '.')
                        } else {
                            results.unitTests = runUnitTests(project: '.')
                        }
                    }
                }
            }

            stage('SonarQube Analysis') {
                steps {
                    script {
                        def shortSha = results.commitSha ? results.commitSha.take(7) : 'unknown'
                        if (pipelineConfig.activeLanguage == 'java') {
                            results.sonarqube = scanSonarQubeJava(
                                projectKey: pipelineConfig.sonarProjectKey,
                                sonarUrl: pipelineConfig.sonarUrl,
                                project: '.',
                                projectVersion: "main-${shortSha}"
                            )
                        } else {
                            results.sonarqube = scanSonarQube(
                                projectKey: pipelineConfig.sonarProjectKey,
                                sonarUrl: pipelineConfig.sonarUrl,
                                project: '.',
                                projectVersion: "main-${shortSha}",
                                analysisMode: 'merge',
                                branchName: 'main'
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
                        results.sbom = generateSBOM(
                            project: '.',
                            language: pipelineConfig.activeLanguage,
                            serviceName: serviceName,
                            imageTag: pipelineConfig.imageTag
                        )
                        if (results.sbom.status == 'FAILURE') {
                            error "SBOM gate FAILED: ${results.sbom.gateResult} " +
                                  "(Critical: ${results.sbom.critical ?: 0}, High: ${results.sbom.high ?: 0})"
                        }
                    }
                }
            }

            stage('Build Container Image') {
                steps {
                    script {
                        def dockerfilePath = pipelineConfig.activeLanguage == 'java' ? 'build-config/Dockerfile.java' : 'build-config/Dockerfile'
                        results.imageBuild = buildContainerImage(
                            dockerfile: dockerfilePath,
                            imageRef: pipelineConfig.getActiveImageRef(),
                            buildArgs: pipelineConfig.activeBuildArgs
                        )
                        if (results.imageBuild.status == 'FAILURE') {
                            error "Image build failed: ${results.imageBuild.error}"
                        }
                    }
                }
            }

            // KNOWN FIX: Push BEFORE ACS scan — ACS pulls from registry
            stage('Push to Registry') {
                steps {
                    script {
                        results.push = pushToRegistry(
                            imageRef: pipelineConfig.getActiveImageRef()
                        )
                        if (results.push.status == 'FAILURE') {
                            error "Push failed: ${results.push.error}"
                        }
                    }
                }
            }

            stage('Sign Image') {
                steps {
                    script {
                        def sbom = results.sbom?.sbomFile ?: ''
                        results.signImage = signImage(
                            imageRef: pipelineConfig.getActiveImageRef(),
                            sbomFile: sbom
                        )
                        if (results.signImage.status == 'FAILURE') {
                            error "Image signing failed: ${results.signImage.error ?: 'unknown'}"
                        }
                    }
                }
            }

            stage('Verify Image') {
                steps {
                    script {
                        results.verifyImage = verifyImage(
                            imageRef: pipelineConfig.getActiveImageRef()
                        )
                        if (results.verifyImage.status == 'FAILURE') {
                            error "Image verification failed — signature invalid"
                        }
                    }
                }
            }

            stage('ACS Image Scan') {
                steps {
                    script {
                        results.acsScan = scanACSImage(
                            imageRef: pipelineConfig.getActiveImageRef(),
                            acsUrl: pipelineConfig.acsUrl
                        )
                    }
                }
            }

            stage('Update GitOps') {
                steps {
                    script {
                        results.gitops = updateGitOps(
                            environment: 'dev',
                            imageRef: pipelineConfig.getActiveImageRef(),
                            appName: pipelineConfig.activeImageName,
                            gitopsRepo: pipelineConfig.gitopsRepo
                        )
                        if (results.gitops.status == 'FAILURE') {
                            error "GitOps update failed: ${results.gitops.error}"
                        }
                    }
                }
            }

            stage('Deploy to DEV') {
                steps {
                    script {
                        results.deploy = deployToEnvironment(
                            app: "${pipelineConfig.activeServiceName}-dev",
                            argocdServer: pipelineConfig.argocdServer
                        )
                    }
                }
            }
        }

        post {
            success {
                script {
                    updateGitlabCommitStatus name: 'jenkins-ci', state: 'success'
                    commentOnMR(
                        status: 'SUCCESS',
                        results: results,
                        pipelineConfig: pipelineConfig,
                        pipelineType: 'merge',
                        gitlabUrl: pipelineConfig.gitlabUrl,
                        projectId: pipelineConfig.gitlabProjectId,
                        commitSha: results.commitSha
                    )
                    notifyTeam(
                        message: "Merge pipeline SUCCESS — ${serviceName} ${pipelineConfig.imageTag} deployed to DEV",
                        status: 'SUCCESS'
                    )
                }
            }
            failure {
                script {
                    updateGitlabCommitStatus name: 'jenkins-ci', state: 'failed'
                    commentOnMR(
                        status: 'FAILURE',
                        results: results,
                        pipelineConfig: pipelineConfig,
                        pipelineType: 'merge',
                        gitlabUrl: pipelineConfig.gitlabUrl,
                        projectId: pipelineConfig.gitlabProjectId,
                        commitSha: results.commitSha
                    )
                    notifyTeam(message: "Merge pipeline FAILED for ${serviceName}", status: 'FAILURE')
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
