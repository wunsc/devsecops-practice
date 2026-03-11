# Changelog

All notable changes to the DevSecOps Jenkins Shared Library.

## [1.10.0] - 2026-03-08

### Fixed
- `updateGitOps.groovy` — Added `git pull --rebase` retry loop (3 attempts) for concurrent T2 push race condition when two services merge simultaneously
- `pipelinePromote.groovy` — Use `gitlabBefore..gitlabAfter` webhook diff range instead of `HEAD~1..HEAD` to correctly detect changes when multiple promotion MRs merge in rapid succession
- `pipelinePromote.groovy` — Increased git clone depth from 2 to 10 to handle sequential merges

### Verified
- Full E2E test: T1/T2/T3/T4 for both sampleapi and notificationapi running concurrently with complete isolation
- Cascading promotion chain: T3 tag → SIT MR → T4 sync → UAT MR → T4 sync → PROD MR → T4 sync (per-service)
- Per-service GitOps paths: `services/{svc}/overlays/{env}/` with own ConfigMap and Secret

## [1.9.0] - 2026-03-08

### Changed
- **Per-service ConfigMaps and Secrets** — Each service now owns its own ConfigMap and Secret in its overlay directory
- NotificationApi deployment envFrom changed from `sampleapi-env`/`sampleapi-secret` → `notificationapi-env`/`notificationapi-secret`
- PostgreSQL + Redis StatefulSets changed from `sampleapi-secret` → `infra-secret` for infrastructure credentials
- Infra overlay `secret-env.yaml` renamed from `sampleapi-secret` to `infra-secret`, keeping only PG + Redis creds
- Removed shared `configmap-app.yaml` from infra base (ConfigMaps now per-service)

## [1.8.0] - 2026-03-08

### Changed
- **Per-service GitOps paths** — All pipeline functions updated for new `services/{svc}/overlays/{env}/` directory structure
- `updateGitOps.groovy` — Overlay path changed from `overlays/${env}` to `services/${appName}/overlays/${env}`
- `pipelineMerge.groovy` — ArgoCD app name uses `activeServiceName` (e.g., `sampleapi-dev` not `${appName}-dev`)
- `createPromotionMR.groovy` — Branch name and overlay path use `services/${activeImage}/overlays/${targetEnv}`
- `pipelinePromote.groovy` — Change detection uses regex `services/([^/]+)/overlays/([^/]+)/` to extract service name + environment from git diff
- `pipelinePromote.groovy` — Added explicit DEV skip (`if (envDir == 'dev') return`) since DEV is auto-synced by ArgoCD
- `PipelineConfig.groovy` — Added `getGitopsServicePath()` method returning `"services/${this.activeServiceName}"`

## [1.7.0] - 2026-03-08

### Added
- Multi-service build support with service-parameterized pipelines
- `PipelineConfig.configureForService(serviceName)` — sets `activeSourceRepo`, `activeImageName`, `activeBuildArgs`
- `PipelineConfig.activeServiceName`, `activeImageName`, `activeSourceRepo`, `activeBuildArgs` fields
- `PipelineConfig.notificationApiImageName` and `getNotificationApiImageRef()` for second service
- `PipelineConfig.getActiveImageRef()` — returns image ref for the active service
- `updateGitOps.groovy` — `additionalImages` parameter for updating multiple image tags in one commit

### Changed
- `pipelineMerge.groovy` — Service-parameterized: each service has its own repo and Jenkins job
  - Accepts `service` parameter: `pipelineMerge(service: 'sampleapi')` or `pipelineMerge(service: 'notificationapi')`
  - Uses `configureForService()` to set active repo, image name, and build args
  - Single build/push/scan pipeline per invocation (no dual-build stages)
- `pipelineTag.groovy` — Service-parameterized: same pattern as pipelineMerge
  - Accepts `service` parameter: `pipelineTag(service: 'sampleapi')` or `pipelineTag(service: 'notificationapi')`
  - Single build/push/ACS scan per invocation
- `createPromotionMR.groovy` — Updates only the active service's image tag in the promotion branch
  - Uses `pipelineConfig.activeImageName` (set by `configureForService`)

## [1.6.0] - 2026-03-08

### Changed
- `buildContainerImage.groovy` — Added `buildArgs` map parameter for `--build-arg` flags
  - Supports parameterized Dockerfile: `buildArgs: [PROJECT_NAME: 'NotificationApi', APP_PORT: '8081']`
  - Each entry generates a `--build-arg KEY=VALUE` flag for podman build

## [1.5.0] - 2026-03-07

### Changed
- `pipelineTag.groovy` — Replaced `updateGitlabCommitStatus` plugin with direct GitLab Commit Status API
  - Posts to app-source (project 1) with `ref={tagName}` and `name=jenkins/tag-pipeline`
  - Resolves commit SHA via `git rev-parse HEAD` after checkout (fixes annotated tag SHA issue)
- `pipelinePromote.groovy` — Already using direct GitLab Commit Status API (unchanged)
  - Posts to app-gitops (project 4) with `ref=main` and `name=jenkins/promotion`
- `createPromotionMR.groovy` — Posts commit status on source branch HEAD commit
  - Status appears in MR Pipelines tab (not just at bottom of MR page)
  - Handles re-tag: deletes existing remote branch before creating new one
  - Handles no-change overlay: `git commit --allow-empty` when tag already matches

### Fixed
- Tag pipeline commit SHA: `env.gitlabAfter` for annotated tags contains tag object SHA, not commit SHA
- MR Pipelines tab empty: was only posting status on merge commit, now also posts on source branch commit
- Promotion branch conflict on re-tag: deletes remote branch `promote/{tag}-to-{env}` before push
- `devsecops-tools` quota: increased from 8CPU/16Gi to 16CPU/32Gi (T3 + ZAP sidecar exceeded limit)

## [1.4.0] - 2026-03-07

### Added
- `createPromotionMR.groovy` — Auto-creates GitLab MR in app-gitops for environment promotion
  - Creates branch `promote/{tag}-to-{env}`, updates overlay image tag
  - T3 context: MR description has full scan results (build, tests, SAST, SCA, ACS, DAST)
  - T4 context: MR description has previous environment deployment status
  - Lead just reviews results, clicks Approve + Merge
- Cascading promotion chain: SIT healthy → auto-create UAT MR → UAT healthy → auto-create PROD MR

### Changed
- `pipelineTag.groovy` — Added "Create Promotion MR" stage (auto-creates SIT promotion MR)
- `pipelinePromote.groovy` — Added "Create Next Promotion MR" stage (cascading UAT/PROD MRs)
  - Commit comment now includes next promotion MR reference
  - Added GitLab commit status reporting (`promotion: running/success/failed`)

## [1.3.0] - 2026-03-07

### Added
- OWASP ZAP DAST scanning in T3 (tag) pipeline via sidecar container
  - ZAP runs as daemon on port 8090 in a sidecar alongside the jnlp agent
  - `scanOWASPZAP.groovy` rewritten to use ZAP REST API (spider → passive scan → reports)
  - HTML + JSON reports archived as build artifacts
  - Returns structured results: high/medium/low/info alert counts

### Changed
- `pipelineTag.groovy` — Uses `kubernetes { inheritFrom 'devsecops-agent' }` with ZAP sidecar
  - Added `securityContext.runAsUser: 0` at pod level (fixes podman subuid conflict)
  - ZAP sidecar resources: 200m/256Mi requests, 500m/1Gi limits (fits within namespace quota)
  - DAST target derived from gitlabUrl (correct route format: `{appName}-{appName}-dev.{domain}`)
- `scanSonarQube.groovy` — Added `projectVersion`, `analysisMode`, `branchName`, `mrIid` parameters for scan separation
- `pipelineMR.groovy` — Passes MR context to SonarQube: `projectVersion=MR-{iid}-{branch}`
- `pipelineMerge.groovy` — Passes merge context to SonarQube: `projectVersion=main-{sha}`

## [1.2.0] - 2026-03-07

### Added
- `pipelinePromote.groovy` — T4 orchestrator for GitOps promotion pipeline
  - Triggered by push to main on app-gitops repo (after MR merge)
  - Detects changed overlays via `git diff HEAD~1 HEAD`
  - Sequential ArgoCD sync (SIT → UAT → PROD order)
  - Post-deploy verification (pods, health, route, image tag)
  - Reports results to GitLab commit as markdown comment
  - Full audit trail (who approved MR, who merged, pipeline result)
- `commentOnMR.groovy` — Posts markdown summary comment on GitLab MR via Notes API

### Changed
- `reportToGitLab.groovy` — Uses `updateGitlabCommitStatus` (GitLab plugin) as primary method with API fallback
- `pipelineMR.groovy` — Added `gitLabConnection('gitlab')` option, commit status updates at start/end
- `jenkins-deployer` ClusterRole — Added `route.openshift.io/routes` and `services` read access for post-deploy verification

## [1.1.0] - 2026-03-07

### Fixed
- `PipelineConfig.initFromEnv` — Changed parameter type from `Map env` to `def env` (Jenkins `env` is `EnvActionImpl`)
- `checkoutSource.groovy` — Accept explicit `gitUrl` parameter (env.GITLAB_URL not set in agent)
- `checkoutBuildConfig.groovy` — Accept explicit `gitUrl` parameter
- `updateGitOps.groovy` — Changed `.replaceAll()` to `.replace()` (regex backreference issue with `${}`)
- `scanSonarQube.groovy` — Added `-u "$SONAR_TOKEN:"` auth for quality gate polling API
- `scanDependencyCheck.groovy` — Scan `src/` only, `--disableOssIndex`, `--disableAssembly`, exclude build artifacts, 10-min timeout
- `scanACSImage.groovy` — Removed `|| true` (swallowed errors), removed `--token` flag (uses ROX_API_TOKEN env var), added stderr capture
- `deployToEnvironment.groovy` — Use internal ArgoCD ClusterIP `openshift-gitops-server.openshift-gitops.svc:443` with `--insecure --grpc-web`
- `buildContainerImage.groovy` — Use `--storage-driver=vfs` and `--isolation=chroot` for Podman on OpenShift
- All orchestrators — Pass `gitUrl` explicitly to checkout functions, GIT_COMMIT fallback to `git rev-parse HEAD`

## [1.0.0] - 2026-03-06

### Added
- Initial release with complete pipeline functions
- 18 vars/ functions covering checkout, build, test, scan, deploy, notify
- 3 orchestrators: pipelineMR, pipelineMerge, pipelineTag
- 3 src/ classes: PipelineConfig, SecurityGate, ImageTagger
- HTML notification template

### Architecture
- All functions accept Map config parameter with sensible defaults
- All functions return structured result maps [status, duration, ...]
- Secrets accessed via withCredentials blocks only
- CPS-compatible with @NonCPS annotations on utility methods
