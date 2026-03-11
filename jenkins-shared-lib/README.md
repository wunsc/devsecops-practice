# Jenkins Shared Library — DevSecOps Pipeline

## Overview

This shared library contains **all pipeline logic** for the DevSecOps CI/CD workflow.
Jenkins jobs call orchestrators via inline CPS scripts — no Jenkinsfiles exist in any repo.

## Architecture

```
jenkins-shared-lib/
├── vars/                          # Pipeline-callable functions (one per file)
│   ├── checkoutSource.groovy      # Git checkout (branch or tag)
│   ├── checkoutBuildConfig.groovy # Clone build-config repo (Dockerfile, scan configs)
│   ├── buildDotnet.groovy         # dotnet restore + build + publish
│   ├── runUnitTests.groovy        # dotnet test with coverage (opencover + cobertura)
│   ├── scanSonarQube.groovy       # SonarQube SAST + quality gate polling
│   ├── scanDependencyCheck.groovy # OWASP Dependency-Check SCA
│   ├── buildContainerImage.groovy # Podman build (vfs driver, chroot isolation)
│   ├── scanACSImage.groovy        # ACS/StackRox image check + scan
│   ├── scanOWASPZAP.groovy        # OWASP ZAP DAST scan
│   ├── pushToRegistry.groovy      # Push to OCP internal registry
│   ├── signImage.groovy           # Cosign image signing (optional)
│   ├── updateGitOps.groovy        # Update image tag in app-gitops overlay
│   ├── deployToEnvironment.groovy # ArgoCD sync + wait
│   ├── reportToGitLab.groovy      # Report status to GitLab MR
│   ├── commentOnMR.groovy         # Post markdown summary to GitLab MR
│   ├── notifyTeam.groovy          # Slack/email/console notifications
│   ├── createPromotionMR.groovy   # Auto-create GitLab MR for next-env promotion
│   ├── runPerformanceTest.groovy  # k6 load test execution + threshold evaluation
│   ├── pipelineMR.groovy          # T1 orchestrator (MR validation)
│   ├── pipelineMerge.groovy       # T2 orchestrator (merge → build → deploy DEV)
│   ├── pipelineTag.groovy         # T3 orchestrator (tag → release image, NO deploy)
│   └── pipelinePromote.groovy     # T4 orchestrator (GitOps promotion, detects service+env)
├── src/com/devsecops/
│   ├── PipelineConfig.groovy      # Centralized config (all parameterized values)
│   ├── SecurityGate.groovy        # Security gate evaluation logic
│   └── ImageTagger.groovy         # Image tag generation (main-SHA, v1.2.0)
├── resources/com/devsecops/
│   └── notification-template.html # HTML email template
├── README.md
└── CHANGELOG.md
```

## Usage

Jenkins jobs call orchestrators via inline CPS scripts (defined in JCasC):

```groovy
@Library('devsecops-shared-lib@main') _
pipelineMerge()   // T2: merge to main
```

## Function Contract

Every `vars/` function follows this contract:

```groovy
def call(Map config = [:]) {
    // 1. Merge defaults with provided config
    // 2. Wrap secrets in withCredentials blocks
    // 3. Execute with try/catch
    // 4. Return structured result map
    return [status: 'SUCCESS', duration: elapsed, ...]
}
```

## Pipeline Triggers (7 Jenkins Jobs)

| Trigger | Job | Orchestrator | Output |
|---------|-----|-------------|--------|
| T1: MR | sampleapi-mr | `pipelineMR()` | Pass/Fail on GitLab MR |
| T2: Merge | sampleapi-merge | `pipelineMerge()` | Image in registry + DEV deployed |
| T3: Tag | sampleapi-tag | `pipelineTag()` | Release image, NO deploy |
| T1: MR | notificationapi-mr | `pipelineMR(service: 'notificationapi')` | Pass/Fail on GitLab MR |
| T2: Merge | notificationapi-merge | `pipelineMerge(service: 'notificationapi')` | Image in registry + DEV deployed |
| T3: Tag | notificationapi-tag | `pipelineTag(service: 'notificationapi')` | Release image, NO deploy |
| T4: Promote | sampleapi-promote | `pipelinePromote()` | Sync ArgoCD + create next-env MR |

SampleApi jobs omit the `service:` parameter (defaults to `sampleapi`).
NotificationApi jobs pass `service: 'notificationapi'`.
The promote job is shared -- T4 auto-detects which service+env changed via regex on git diff.
