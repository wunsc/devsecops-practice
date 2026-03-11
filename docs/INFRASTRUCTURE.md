# INFRASTRUCTURE.md — Deployed Components

> **Prerequisite:** Source `docs/env.sh` before running any commands in this document:
> ```bash
> source docs/env.sh
> alias oc="$OC"    # Optional: lets you use bare 'oc' in troubleshooting commands below
> ```
> All `$OC`, `$APPS_DOMAIN`, `$NS_*`, `$GITLAB_URL`, `$JENKINS_URL`, etc. variables come from that file.
> Troubleshooting blocks use bare `oc` for readability — substitute `$OC` if you haven't set the alias.

## Phase 1: Namespaces & RBAC

### Namespaces

| Namespace | Purpose | Quota (CPU/Mem) | Status |
|-----------|---------|-----------------|--------|
| devsecops-tools | Jenkins, SonarQube | 16 CPU / 32Gi | Active |
| devsecops-gitlab | GitLab CE, PostgreSQL, Redis | 8 CPU / 24Gi | Active |
| sampleapi-dev | DEV environment | 6 CPU / 12Gi | Active |
| sampleapi-sit | SIT environment | 8 CPU / 16Gi | Active |
| sampleapi-uat | UAT environment | 8 CPU / 16Gi | Active |
| sampleapi-prod | PROD environment | 12 CPU / 24Gi | Active |

### Service Accounts

| SA Name | Namespace | Purpose |
|---------|-----------|---------|
| jenkins-sa | devsecops-tools | Jenkins pipeline execution |
| sampleapi-sa | sampleapi-{dev,sit,uat,prod} | Application workload identity |

### Cluster Roles

| Role | Permissions | Bound To |
|------|------------|----------|
| argocd-deployer | Full deploy perms (Deployment, Service, Route, ConfigMap, Secret, etc.) | openshift-gitops-argocd-application-controller in app namespaces |
| jenkins-deployer | Build, image push, deploy perms | jenkins-sa in all app namespaces + tools |

### Network Policies (per app namespace)

| Policy | Effect |
|--------|--------|
| default-deny-ingress | Block all ingress by default |
| allow-from-openshift-ingress | Allow router traffic to app=sampleapi |
| allow-monitoring | Allow Prometheus scraping |
| allow-same-namespace | Allow intra-namespace communication |
| allow-from-argocd | Allow ArgoCD to manage resources |

### Troubleshooting
```bash
# Check namespaces
oc get ns -l team=devsecops
# Check RBAC
oc auth can-i create deployments --as=system:serviceaccount:devsecops-tools:jenkins-sa -n sampleapi-dev
# Check network policies
oc get netpol -n sampleapi-dev
```

## Phase 2: GitLab CE

### Components

| Component | Image | Namespace | Storage | Status |
|-----------|-------|-----------|---------|--------|
| GitLab CE | gitlab/gitlab-ce:17.0.0-ce.0 | devsecops-gitlab | 50Gi data + 5Gi logs | Running |
| PostgreSQL 15 | registry.redhat.io/rhel9/postgresql-15:latest | devsecops-gitlab | 10Gi | Running |
| Redis 7 | redis:7-alpine | devsecops-gitlab | 5Gi | Running |

### Access

| Service | URL | Auth |
|---------|-----|------|
| GitLab UI | `$GITLAB_URL` | root / `$OC get secret gitlab-root-password -n $NS_GITLAB -o jsonpath='{.data.GITLAB_ROOT_PASSWORD}' \| base64 -d` |
| GitLab API | `$GITLAB_URL/api/v4/` | PAT: `$OC get secret gitlab-token -n $NS_TOOLS -o jsonpath='{.data.token}' \| base64 -d` |

### Repositories

| Repo | GitLab ID | Path |
|------|-----------|------|
| app-source | 1 | devsecops/app-source |
| build-config | 2 | devsecops/build-config |
| jenkins-shared-lib | 3 | devsecops/jenkins-shared-lib |
| app-gitops | 4 | devsecops/app-gitops |
| notificationapi-source | 5 | devsecops/notificationapi-source |

### SCC
- `anyuid` granted to `default` SA in `devsecops-gitlab` (GitLab CE requires root for internal services)

### Key Config Notes
- Puma on port 8181, Nginx on port 8080
- `puma['listen'] = '0.0.0.0'` for all-interface binding
- Probes use `exec curl localhost:8181` (Nginx/Puma return 404 via pod IP due to Host header)
- Encryption keys (`db_key_base`, `secret_key_base`, `otp_key_base`) stored in `gitlab-rails-secrets` Secret

### Troubleshooting
```bash
# Check pods
oc get pods -n devsecops-gitlab
# GitLab logs
oc logs -n devsecops-gitlab deploy/gitlab-ce --tail=50
# Test readiness from inside
oc exec -n devsecops-gitlab deploy/gitlab-ce -- curl -sf http://localhost:8181/-/readiness
# PostgreSQL connectivity
oc exec -n devsecops-gitlab gitlab-postgresql-0 -- pg_isready -U gitlab -d gitlabhq_production
```

## Phase 3: SonarQube CE

### Components

| Component | Image | Namespace | Storage | Status |
|-----------|-------|-----------|---------|--------|
| SonarQube CE | sonarqube:26.3.0-community | devsecops-tools | 10Gi data + 2Gi extensions | Running |
| PostgreSQL 15 | registry.redhat.io/rhel9/postgresql-15:latest | devsecops-tools | 10Gi | Running |

### Access

| Service | URL | Auth |
|---------|-----|------|
| SonarQube UI | `$SONARQUBE_URL` | admin / `$OC get secret sonarqube-token -n $NS_TOOLS -o jsonpath='{.data.password}' \| base64 -d` |
| SonarQube API | `$SONARQUBE_URL/api/` | Token: `$OC get secret sonarqube-token -n $NS_TOOLS -o jsonpath='{.data.text}' \| base64 -d` |

### SCC
- `privileged` granted to `default` SA in `devsecops-tools` (for sysctl init container)

### Troubleshooting
```bash
oc get pods -n devsecops-tools
oc logs -n devsecops-tools deploy/sonarqube --tail=50
curl -sk $SONARQUBE_URL/api/system/status
```

### Quality Gate: DevSecOps Standard (Default)

| Condition | Metric | Threshold |
|-----------|--------|-----------|
| New Code Coverage | new_coverage | < 80% FAIL |
| New Violations | new_violations | > 0 FAIL |
| New Security Hotspots Reviewed | new_security_hotspots_reviewed | < 100% FAIL |
| New Duplicated Lines | new_duplicated_lines_density | > 3% FAIL |
| New Security Rating | new_security_rating | worse than A FAIL |
| New Reliability Rating | new_reliability_rating | worse than A FAIL |
| New Maintainability Rating | new_maintainability_rating | worse than A FAIL |

## Phase 4: Registry Configuration

### OCP Internal Registry
- **Registry:** `image-registry.openshift-image-registry.svc:5000`
- **Image:** `image-registry.openshift-image-registry.svc:5000/sampleapi-dev/sampleapi:<tag>`
- **Jenkins SA:** `system:image-builder` + `system:image-pusher` in sampleapi-dev
- **Cross-namespace pull:** SIT/UAT/PROD granted `system:image-puller` from sampleapi-dev

### Current Image Tags
| Tag | Image | Source | Date |
|-----|-------|--------|------|
| sampleapi:main-65c93b5 | sampleapi | T2 merge pipeline build #30 | 2026-03-08 |
| notificationapi:main-b5c593e | notificationapi | T2 merge pipeline build #2 | 2026-03-08 |
| sampleapi:v1.4.1 | sampleapi | T3 tag pipeline build #15 | 2026-03-07 |

## Phase 5: Red Hat ACS (StackRox)

### Components

| Component | Namespace | Replicas | Status |
|-----------|-----------|----------|--------|
| Central | stackrox | 1 | Running |
| Scanner | stackrox | 2 | Running |
| ScannerV4 Indexer | stackrox | 2 | Running |
| ScannerV4 Matcher | stackrox | 2 | Running |
| Sensor | stackrox | 1 | Running |
| Admission Controller | stackrox | 3 | Running |
| Collector (DaemonSet) | stackrox | 6 | Running |

### Access

| Service | URL | Auth |
|---------|-----|------|
| ACS Central UI | `$ACS_URL` | admin / `$OC get secret central-htpasswd -n stackrox -o jsonpath='{.data.password}' \| base64 -d` |

### Key Configuration
- **centralEndpoint:** `central.stackrox.svc:443` (NOT `central-stackrox.stackrox.svc:443`)
- **Image Integration:** OCP internal registry added (`image-registry.openshift-image-registry.svc:5000`, insecure=true)
- **Custom Policies:** Block Critical CVEs (Build), Block Untrusted Registries (Deploy), Detect Crypto Mining (Runtime), Detect Reverse Shell (Runtime)

### Troubleshooting
```bash
oc get pods -n stackrox
# Check sensor connection
oc logs -n stackrox deploy/sensor --tail=20
# Check admission controller
oc logs -n stackrox deploy/admission-control --tail=20
# Verify centralEndpoint
oc get securedcluster -n stackrox -o jsonpath='{.items[0].spec.centralEndpoint}'
```

## Phase 6: OpenShift GitOps (ArgoCD)

### Components

| Component | Namespace | Status |
|-----------|-----------|--------|
| ArgoCD Server | openshift-gitops | Running |
| ArgoCD Repo Server | openshift-gitops | Running |
| ArgoCD Application Controller | openshift-gitops | Running |
| ArgoCD Redis | openshift-gitops | Running |

### Access

| Service | URL | Auth |
|---------|-----|------|
| ArgoCD UI | `$ARGOCD_URL` | admin / `$OC get secret openshift-gitops-cluster -n $NS_GITOPS -o jsonpath='{.data.admin\.password}' \| base64 -d` |
| ArgoCD Internal | `openshift-gitops-server.openshift-gitops.svc:443` | Used by Jenkins agent pods |

### ArgoCD Applications (12 — Per-Service Per-Environment)

#### Service Apps (8)
| Application | Namespace | Path | Sync Policy | Status |
|-------------|-----------|------|-------------|--------|
| sampleapi-dev | sampleapi-dev | services/sampleapi/overlays/dev | Automated | Synced/Healthy |
| sampleapi-sit | sampleapi-sit | services/sampleapi/overlays/sit | Manual | Synced/Healthy |
| sampleapi-uat | sampleapi-uat | services/sampleapi/overlays/uat | Manual | Synced/Healthy |
| sampleapi-prod | sampleapi-prod | services/sampleapi/overlays/production | Manual | Synced/Healthy |
| notificationapi-dev | sampleapi-dev | services/notificationapi/overlays/dev | Automated | Synced/Healthy |
| notificationapi-sit | sampleapi-sit | services/notificationapi/overlays/sit | Manual | Synced/Healthy |
| notificationapi-uat | sampleapi-uat | services/notificationapi/overlays/uat | Manual | Synced/Healthy |
| notificationapi-prod | sampleapi-prod | services/notificationapi/overlays/production | Manual | Synced/Healthy |

#### Infrastructure Apps (4)
| Application | Namespace | Path | Sync Policy | Status |
|-------------|-----------|------|-------------|--------|
| infra-dev | sampleapi-dev | infra/overlays/dev | Automated | Synced/Healthy |
| infra-sit | sampleapi-sit | infra/overlays/sit | Manual | Synced/Healthy |
| infra-uat | sampleapi-uat | infra/overlays/uat | Manual | Synced/Healthy |
| infra-prod | sampleapi-prod | infra/overlays/production | Manual | Synced/Healthy |

### Troubleshooting
```bash
# Check ArgoCD apps
oc get applications -n openshift-gitops
# App details
oc get application sampleapi-dev -n openshift-gitops -o jsonpath='{.status.sync.status} {.status.health.status}'
# ArgoCD logs
oc logs -n openshift-gitops deploy/openshift-gitops-server --tail=20
```

## Phase 7: Jenkins

### Components

| Component | Namespace | Status |
|-----------|-----------|--------|
| Jenkins Controller | devsecops-tools | 1/1 Running |
| Jenkins Agent Image | devsecops-tools | Built (ImageStream tag: latest) |

### Access

| Service | URL | Auth |
|---------|-----|------|
| Jenkins UI | `$JENKINS_URL` | admin / DevSec0ps-Jenkins-2024 (set via JCasC) |

### Jenkins Jobs

| Job | Trigger | Source Repo | Shared Lib Function |
|-----|---------|-------------|---------------------|
| sampleapi-mr | GitLab MR webhook | app-source (ID=1) | pipelineMR() |
| sampleapi-merge | Push to main webhook | app-source (ID=1) | pipelineMerge() |
| sampleapi-tag | Tag push webhook | app-source (ID=1) | pipelineTag() |
| sampleapi-promote | Push to main webhook | app-gitops (ID=4) | pipelinePromote() |
| notificationapi-mr | GitLab MR webhook | notificationapi-source (ID=5) | pipelineMR(service: 'notificationapi') |
| notificationapi-merge | Push to main webhook | notificationapi-source (ID=5) | pipelineMerge(service: 'notificationapi') |
| notificationapi-tag | Tag push webhook | notificationapi-source (ID=5) | pipelineTag(service: 'notificationapi') |

### Agent Image Tools
| Tool | Version |
|------|---------|
| .NET SDK | 8.0.418 |
| Podman | 5.6.0 |
| gitleaks | 8.18.4 |
| kustomize | v5.4.2 |
| dependency-check | 12.1.0 |
| roxctl | 4.5.4 |
| cosign | 2.2.4 |

### Troubleshooting
```bash
# Jenkins controller logs
oc logs -n devsecops-tools deploy/jenkins --tail=50
# Check agent image
oc get is jenkins-agent-devsecops -n devsecops-tools
# Rebuild agent
oc start-build jenkins-agent-devsecops -n devsecops-tools --follow
# Check running agent pods
oc get pods -n devsecops-tools -l jenkins=slave
```

## Deployed Application Environments

### Per-Environment Resource Ownership (Phase 19)

Each environment has 3 ArgoCD apps managing isolated resources:

#### infra-{env} manages:
| Resource | Name | Keys/Purpose |
|----------|------|-------------|
| Secret | `infra-secret` | POSTGRESQL_USER, POSTGRESQL_PASSWORD, POSTGRESQL_DATABASE, REDIS_PASSWORD |
| ServiceAccount | `sampleapi-sa` | Workload identity |
| StatefulSet | `postgresql` | PostgreSQL 16 (reads creds from `infra-secret`) |
| StatefulSet | `redis` | Redis 7 (reads password from `infra-secret`) |
| Service | `postgresql`, `redis` | ClusterIP for DB + cache |

#### sampleapi-{env} manages:
| Resource | Name | Keys/Purpose |
|----------|------|-------------|
| ConfigMap | `sampleapi-env` | DATABASE_URL, REDIS_URL, NOTIFICATION_API_URL, logging, features, WeatherForecast |
| Secret | `sampleapi-secret` | DATABASE_PASSWORD, API_KEY, JWT_SECRET, REDIS_PASSWORD |
| Deployment | `sampleapi` | App pods (envFrom: sampleapi-env + sampleapi-secret) |
| Service | `sampleapi` | ClusterIP |
| Route | `sampleapi` | External HTTPS |

#### notificationapi-{env} manages:
| Resource | Name | Keys/Purpose |
|----------|------|-------------|
| ConfigMap | `notificationapi-env` | ASPNETCORE_ENVIRONMENT, REDIS_URL, logging levels |
| Secret | `notificationapi-secret` | REDIS_PASSWORD |
| Deployment | `notificationapi` | App pods (envFrom: notificationapi-env + notificationapi-secret) |
| Service | `notificationapi` | ClusterIP (port 8081) |

### sampleapi-dev (1 replica)

| Resource | Name | Status |
|----------|------|--------|
| Deployment | sampleapi | 1/1 Ready |
| Service | sampleapi | ClusterIP |
| Route | sampleapi | `https://sampleapi-${NS_DEV}.${APPS_DOMAIN}` |
| ConfigMap | sampleapi-env | ASPNETCORE_ENVIRONMENT=Development, Logging=Debug, Swagger=true |
| Secret | sampleapi-secret | DATABASE_PASSWORD, API_KEY, JWT_SECRET, REDIS_PASSWORD |
| ServiceAccount | sampleapi-sa | Workload identity |

### sampleapi-sit (2 replicas)

| Resource | Name | Status |
|----------|------|--------|
| Deployment | sampleapi | 2/2 Ready |
| Service | sampleapi | ClusterIP |
| Route | sampleapi | `https://sampleapi-${NS_SIT}.${APPS_DOMAIN}` |
| ConfigMap | sampleapi-env | ASPNETCORE_ENVIRONMENT=Staging, Logging=Information, Swagger=true |
| Secret | sampleapi-secret | DATABASE_PASSWORD, API_KEY, JWT_SECRET, REDIS_PASSWORD |

### sampleapi-uat (2 replicas)

| Resource | Name | Status |
|----------|------|--------|
| Deployment | sampleapi | 2/2 Ready |
| Service | sampleapi | ClusterIP |
| Route | sampleapi | `https://sampleapi-${NS_UAT}.${APPS_DOMAIN}` |
| ConfigMap | sampleapi-env | ASPNETCORE_ENVIRONMENT=Staging, Logging=Information, Swagger=false |
| Secret | sampleapi-secret | DATABASE_PASSWORD, API_KEY, JWT_SECRET, REDIS_PASSWORD |

### sampleapi-prod (3 replicas + PDB)

| Resource | Name | Status |
|----------|------|--------|
| Deployment | sampleapi | 3/3 Ready |
| Service | sampleapi | ClusterIP |
| Route | sampleapi | `https://sampleapi-${NS_PROD}.${APPS_DOMAIN}` |
| ConfigMap | sampleapi-env | ASPNETCORE_ENVIRONMENT=Production, Logging=Warning, Swagger=false |
| Secret | sampleapi-secret | DATABASE_PASSWORD, API_KEY, JWT_SECRET, REDIS_PASSWORD |
| PodDisruptionBudget | sampleapi-pdb | minAvailable=1 |

## Phase 17: Multi-Service Infrastructure (DEV)

### Components (sampleapi-dev namespace)

| Component | Image | Storage | Status |
|-----------|-------|---------|--------|
| PostgreSQL 16 | registry.redhat.io/rhel9/postgresql-16:latest | 10Gi PVC | 1/1 Running |
| Redis 7 | registry.redhat.io/rhel9/redis-7:latest | 5Gi PVC | 1/1 Running |
| NotificationApi | OCP internal registry notificationapi:main-b5c593e | None | 1/1 Running |

### Service Endpoints (DEV)

| Service | Type | Port | Internal DNS |
|---------|------|------|--------------|
| postgresql | ClusterIP | 5432 | postgresql.sampleapi-dev.svc |
| redis | ClusterIP | 6379 | redis.sampleapi-dev.svc |
| notificationapi | ClusterIP | 8081 | notificationapi.sampleapi-dev.svc |
| sampleapi | ClusterIP | 8080 | sampleapi.sampleapi-dev.svc |

### SonarQube Projects

| Project Key | Name | Quality Gate | Status |
|-------------|------|-------------|--------|
| sampleapi | sampleapi | DevSecOps Standard | Active |
| notificationapi | notificationapi | DevSecOps Standard (default) | Active |

### Application Health (DEV)

| Endpoint | Response |
|----------|----------|
| SampleApi /healthz | `{"status":"healthy"}` |
| SampleApi /readyz | `{"status":"ready","checks":{"postgresql":{"status":"healthy"},"redis":{"status":"healthy"}}}` |
| SampleApi /api/WeatherForecast | 7 forecasts, Location=DEV |
| NotificationApi /healthz | `{"status":"healthy"}` |
| NotificationApi /readyz | 200 OK, Redis healthy |

### Probes Configuration

| Component | Liveness | Readiness | Startup |
|-----------|----------|-----------|---------|
| SampleApi | GET /healthz:8080, timeout=3s | GET /readyz:8080, timeout=10s | GET /healthz:8080, failureThreshold=30 |
| NotificationApi | GET /healthz:8081, timeout=3s | GET /readyz:8081, timeout=10s | GET /healthz:8081, failureThreshold=30 |
| PostgreSQL | exec pg_isready, timeout=3s | exec pg_isready, timeout=3s | N/A |
| Redis | exec redis-cli ping, timeout=3s | exec redis-cli ping, timeout=3s | N/A |

## Phase 19 (Observability): Logging — LokiStack

### Components

| Component | Namespace | Replicas | Status |
|-----------|-----------|----------|--------|
| Loki Operator | openshift-logging | 1 | Succeeded (v6.2) |
| Cluster Logging Operator | openshift-logging | 1 | Succeeded (v6.2) |
| LokiStack (1x.demo) | openshift-logging | 8+ pods | Running |
| Vector Collectors (DaemonSet) | openshift-logging | 4 | Running |

### Access

| Service | URL | Auth |
|---------|-----|------|
| Loki Gateway | `https://logging-loki-gateway-http.openshift-logging.svc:8080/api/logs/v1/application/` | SA Bearer token |
| LogQL (via Grafana) | Grafana → Explore → Loki datasource | Grafana admin |

### Storage
- S3 backend via ODF ObjectBucketClaim (`loki-bucket`)
- NooBaa manages the actual S3 bucket on ODF storage

### Key Configuration
- **LokiStack size:** `1x.demo` (suitable for dev/POC)
- **S3 secret:** `logging-loki-s3` (reformatted from OBC — field names differ)
- **TLS trust:** `loki-ca-bundle` ConfigMap (service-CA cert for NooBaa S3)
- **ClusterLogForwarder:** Vector collectors → LokiStack gateway (with service-CA trust)
- **Log tenants:** `application` (container logs), `infrastructure` (node logs)

### Troubleshooting
```bash
# Check LokiStack pods
oc get pods -n openshift-logging -l app.kubernetes.io/managed-by=lokistack-controller
# Check Vector collectors
oc get pods -n openshift-logging -l app.kubernetes.io/component=collector
# Check Loki gateway health
oc exec -n openshift-logging deploy/logging-loki-gateway-http -- curl -sf http://localhost:3100/ready
# Check log ingestion (LogQL via gateway)
oc exec -n openshift-logging deploy/logging-loki-gateway-http -- curl -sf 'http://localhost:3100/loki/api/v1/labels'
# Check Vector collector logs
oc logs -n openshift-logging -l app.kubernetes.io/component=collector --tail=20
```

## Phase 20 (Observability): Monitoring & Alerting

### Components

| Component | Namespace | Count | Status |
|-----------|-----------|-------|--------|
| ServiceMonitor (sampleapi) | sampleapi-{dev,sit,uat,prod} | 4 | Active |
| ServiceMonitor (notificationapi) | sampleapi-{dev,sit,uat,prod} | 4 | Active |
| ServiceMonitor (postgresql) | sampleapi-{dev,sit,uat,prod} | 4 | Active |
| ServiceMonitor (redis) | sampleapi-{dev,sit,uat,prod} | 4 | Active |
| PrometheusRule (app alerts) | sampleapi-{dev,sit,uat,prod} | 4 | Active |
| PrometheusRule (SLO alerts) | sampleapi-{dev,sit,uat,prod} | 4 | Active |
| PrometheusRule (infra alerts) | sampleapi-{dev,sit,uat,prod} | 4 | Active |
| PrometheusRule (pipeline) | devsecops-tools | 1 | Active |
| AlertmanagerConfig | sampleapi-dev, sampleapi-prod | 2 | Active |

### Key Configuration
- **User workload monitoring:** Enabled via `cluster-monitoring-config` ConfigMap in `openshift-monitoring`
- **SLO target:** 99.9% availability (error budget = 0.1%)
- **Burn rate windows:** 1h, 6h, 3d (multi-window alerting)
- **Alert severity levels:** critical (>14.4x burn), warning (>6x burn), info (>1x burn)
- **AlertmanagerConfig receiver:** webhook (placeholder URL — configure Slack/PagerDuty integration)

### Troubleshooting
```bash
# Check ServiceMonitors
oc get servicemonitor -n sampleapi-dev
# Check PrometheusRules
oc get prometheusrule -n sampleapi-dev
# Check AlertmanagerConfig
oc get alertmanagerconfig -n sampleapi-dev
# Check Prometheus targets (via Thanos Querier)
oc exec -n openshift-monitoring deploy/thanos-querier -- curl -sf 'http://localhost:9090/api/v1/targets' | python3 -m json.tool | head -30
# Check firing alerts
oc exec -n openshift-monitoring deploy/thanos-querier -- curl -sf 'http://localhost:9090/api/v1/alerts' | python3 -m json.tool | head -30
```

## Phase 21 (Observability): Grafana Dashboards

### Components

| Component | Namespace | Version | Status |
|-----------|-----------|---------|--------|
| Grafana Operator | devsecops-tools | v5.22.0 | Succeeded |
| Grafana Instance | devsecops-tools | v12.3.3 | Running |
| Grafana SA | devsecops-tools | N/A | Active |

### Access

| Service | URL | Auth |
|---------|-----|------|
| Grafana UI | `https://grafana-route-${NS_TOOLS}.${APPS_DOMAIN}` | admin / DevSec0ps-Grafana-2024 |

### Datasources

| Name | Type | URL | Auth |
|------|------|-----|------|
| Prometheus | prometheus | `https://thanos-querier.openshift-monitoring.svc.cluster.local:9091` | SA Bearer token (grafana-sa) |
| Loki | loki | `https://logging-loki-gateway-http.openshift-logging.svc:8080/api/logs/v1/application/` | SA Bearer token (grafana-sa) |

### Dashboards

| Dashboard | UID | Panels | Content |
|-----------|-----|--------|---------|
| App Health & Performance | devsecops-app | 8 | HTTP request rate, error rate, latency P99, pod restarts |
| Log Analytics | devsecops-logs | 5 | Log volume timeline, error log stream, LogQL explorer |
| Pipeline Execution | devsecops-pipeline | 6 | Build duration, success rate, stage timing |
| Infrastructure Resources | devsecops-infrastructure | 6 | CPU/memory by namespace, quota usage %, PVC %, pod status pie |
| SLO Burn Rate & Error Budget | devsecops-slo | 8 | Error budget gauge, 1h/6h/3d burn rate, error ratio history with SLO threshold |

### RBAC

| ClusterRoleBinding | ClusterRole | Subject |
|--------------------|-------------|---------|
| grafana-cluster-monitoring-view | cluster-monitoring-view | grafana-sa (devsecops-tools) |
| grafana-logging-view | cluster-logging-application-view | grafana-sa (devsecops-tools) |

### Key Configuration
- **Grafana Operator v5.22 `valuesFrom` bug:** `valuesFrom` with `secretKeyRef` for `secureJsonData` does NOT work. Token must be injected directly via shell expansion when applying datasource CRDs
- **Loki ClusterRole:** `cluster-logging-application-view` must be created manually (Logging 6.x does not auto-create it)
- **Dashboard variables:** All dashboards use `${datasource}` template variable pointing to Prometheus; Log Analytics uses Loki datasource

### Troubleshooting
```bash
# Check Grafana pod
oc get pods -n devsecops-tools -l app=grafana
# Check Grafana operator logs
oc logs -n devsecops-tools deploy/grafana-operator-controller-manager --tail=20
# Check datasource status
oc get grafanadatasource -n devsecops-tools
# Check dashboard status
oc get grafanadashboard -n devsecops-tools
# Verify SA token validity
TOKEN=$(oc create token grafana-sa -n devsecops-tools --duration=1h)
curl -sk -H "Authorization: Bearer $TOKEN" https://thanos-querier.openshift-monitoring.svc.cluster.local:9091/api/v1/query?query=up
# Verify Loki access
curl -sk -H "Authorization: Bearer $TOKEN" https://logging-loki-gateway-http.openshift-logging.svc:8080/api/logs/v1/application/loki/api/v1/labels
# Re-inject datasource token (if operator overwrites it)
TOKEN=$(oc create token grafana-sa -n devsecops-tools --duration=8760h)
# Then re-apply datasource CRDs with the token embedded
```

### Multi-Service Health Check (DEV)
```bash
# Check all DEV pods
$OC get pods -n $NS_DEV

# Check PostgreSQL connectivity
$OC exec postgresql-0 -n $NS_DEV -- pg_isready -h localhost -p 5432

# Check Redis connectivity
$OC exec redis-0 -n $NS_DEV -- redis-cli -a "$REDIS_PASSWORD" ping

# Check app health
SAMPLE_HOST=$($OC get route sampleapi -n $NS_DEV -o jsonpath='{.spec.host}')
curl -sk "https://$SAMPLE_HOST/readyz" | python3 -m json.tool

# Check NotificationApi from within cluster
$OC exec deployment/sampleapi -n $NS_DEV -- wget -qO- http://notificationapi.$NS_DEV.svc:8081/healthz

# Check PostgreSQL logs
$OC logs postgresql-0 -n $NS_DEV --tail=20

# Check Redis logs
$OC logs redis-0 -n $NS_DEV --tail=20
```

---

## Phase 22: Distributed Tracing (OpenTelemetry + Tempo)

### Tempo Operator
- **Version:** v0.20.0-1
- **Namespace:** openshift-tempo-operator
- **Installed via:** OLM Subscription (`tempo-product` from redhat-operators)
- **Manages:** TempoStack CRDs

### TempoStack
- **Name:** tempo
- **Namespace:** openshift-tempo
- **Tempo Version:** 2.10.0
- **Mode:** Managed, openshift tenancy with gateway
- **Tenants:** dev, sit, uat, prod (one per environment)
- **Storage:** S3 via ODF NooBaa (OBC `tempo-bucket` from Phase 19)
- **S3 Secret:** `tempo-s3` (reformatted from OBC keys)
- **TLS Trust:** `tempo-ca-bundle` ConfigMap (service-CA injected)
- **Retention:** 48h
- **Components:** compactor (1), distributor (1), ingester (1), querier (1), query-frontend (1), gateway (1 with 2 containers: gateway + OPA)
- **Gateway Service:** `tempo-tempo-gateway.openshift-tempo.svc` (gRPC: 8090, HTTP: 8080, internal: 8081)

### OpenTelemetry Operator
- **Version:** v0.144.0-1
- **Namespace:** openshift-opentelemetry-operator
- **Installed via:** OLM Subscription (`opentelemetry-product` from redhat-operators)
- **Manages:** OpenTelemetryCollector, Instrumentation CRDs

### OTel Collector
- **Name:** otel-collector
- **Namespace:** openshift-tempo
- **Mode:** Deployment (1 replica)
- **Service:** `otel-collector-collector.openshift-tempo.svc` (gRPC: 4317, HTTP: 4318)
- **Pipelines:** traces (→routing connector→4 per-tenant otlphttp exporters→gateway), metrics (→debug), logs (→debug)
- **Routing:** `k8s.namespace.name` attribute → tenant (sampleapi-dev→dev, sampleapi-sit→sit, etc.)
- **Auth:** Bearer token (collector SA token) to Tempo gateway

### Instrumentation CRs
- **Name:** dotnet-instrumentation
- **Namespaces:** sampleapi-dev, sampleapi-sit, sampleapi-uat, sampleapi-prod
- **Image:** `ghcr.io/open-telemetry/opentelemetry-operator/autoinstrumentation-dotnet:1.9.0` (community, Red Hat operator doesn't ship .NET image)
- **Protocol:** gRPC (OTEL_EXPORTER_OTLP_PROTOCOL=grpc)
- **Sampler:** parentbased_traceidratio at 1.0 (100%)

### Grafana Integration
- **Datasource:** `tempo-datasource` — Tempo gateway on `https://tempo-tempo-gateway.openshift-tempo.svc:8080/api/traces/v1/dev/tempo`
- **Datasource UID:** `f315522d-dda8-49ca-b509-1a31f01d7b57` (auto-generated, referenced in dashboard JSON)
- **Auth:** Bearer token (grafana-sa) via `httpHeaderName1`/`secureJsonData`
- **Dashboard:** `traces-dashboard` — Distributed Tracing Explorer (uid: `devsecops-traces`)
- **Correlations:** trace-to-metric (Prometheus UID `a28ca1f5-4ea7-4126-8443-e54310c76d57`), trace-to-log (Loki UID `c0afa6e7-bea8-42a7-9718-af275465a46e`)

### Cluster Observability Operator
- **Version:** v1.3.1
- **Namespace:** openshift-observability-operator
- **Installed via:** OLM Subscription (`cluster-observability-operator` from redhat-operators)
- **Purpose:** Provides distributed tracing UI plugin for OpenShift Console, replacing deprecated Jaeger UI
- **UIPlugin:** `distributed-tracing` (type: DistributedTracing)
- **Console Plugin:** `distributed-tracing-console-plugin` — auto-registered and enabled
- **Access:** OpenShift Console → Observe → Traces

### Troubleshooting
```bash
# Check TempoStack status
oc get tempostack -n openshift-tempo

# Check all Tempo/OTel pods
oc get pods -n openshift-tempo

# Check collector logs for trace reception
oc logs deployment/otel-collector-collector -n openshift-tempo --tail=20

# Check collector span metrics (received vs exported)
oc run curl-test --rm -i --restart=Never --image=registry.access.redhat.com/ubi9/ubi-minimal -n openshift-tempo -- \
  curl -s http://otel-collector-collector-monitoring.openshift-tempo.svc:8888/metrics | grep -E "otelcol_receiver_accepted_spans|otelcol_exporter_sent_spans"

# Check if auto-instrumentation is injected in app pod
oc get pod -l app=sampleapi -n sampleapi-dev -o jsonpath='{.items[0].spec.initContainers[*].name}'
# Expected: opentelemetry-auto-instrumentation-dotnet

# Query Tempo for traces
oc run tempo-test --rm -i --restart=Never --image=registry.access.redhat.com/ubi9/ubi-minimal -n openshift-tempo -- \
  curl -s "http://tempo-tempo-query-frontend.openshift-tempo.svc:3200/api/search?limit=5"

# Check Grafana Tempo datasource health
GRAFANA_URL="https://grafana-route-${NS_TOOLS}.${APPS_DOMAIN}"
curl -sk -u admin:DevSec0ps-Grafana-2024 "${GRAFANA_URL}/api/datasources/uid/f315522d-dda8-49ca-b509-1a31f01d7b57/health"

# Check UIPlugin status
oc get uiplugin distributed-tracing
oc get consoleplugin distributed-tracing-console-plugin

# Verify OTEL env vars in pod
oc get pod -l app=sampleapi -n sampleapi-dev -o jsonpath='{.items[0].spec.containers[0].env[*].name}' | tr ' ' '\n' | grep OTEL
```

---

## Phase 23: Performance Testing as Quality Gate

### k6 in Jenkins Agent Image

| Component | Version | Location | Status |
|-----------|---------|----------|--------|
| k6 | v0.54.0 | Jenkins agent image (Dockerfile.agent rebuild #17) | Installed |

### k6 Test Scripts

| Script | Path | Purpose |
|--------|------|---------|
| load-test.js | build-config/tests/performance/load-test.js | Main quality gate (p95<800ms, p99<2s, errors<1%) |
| load-test-multi.js | build-config/tests/performance/load-test-multi.js | Multi-service test (SampleApi + NotificationApi) |
| stress-test.js | build-config/tests/performance/stress-test.js | Diagnostic stress test (ramp to 200 VUs) |
| soak-test.js | build-config/tests/performance/soak-test.js | Long-duration stability test (30m sustained) |
| helpers/checks.js | build-config/tests/performance/helpers/checks.js | Reusable response validation |
| helpers/auth.js | build-config/tests/performance/helpers/auth.js | Authentication helpers (placeholder) |

### Pipeline Integration

| File | Path | Change |
|------|------|--------|
| runPerformanceTest.groovy | jenkins-shared-lib/vars/runPerformanceTest.groovy | k6 execution + threshold evaluation + parseSummary via jq |
| pipelineTag.groovy | jenkins-shared-lib/vars/pipelineTag.groovy | Performance Test stage after DAST, before Sign |
| createPromotionMR.groovy | jenkins-shared-lib/vars/createPromotionMR.groovy | Perf test metrics (p90, p95, max, error_rate, total_requests) in MR description |

### Grafana Dashboard

| Dashboard | UID | Panels | Content |
|-----------|-----|--------|---------|
| k6 Performance | devsecops-k6 | TBD | Server-side metrics during load tests, request rate, latency, error rate |

**Total Grafana dashboards:** 7 (App Health, Log Analytics, Pipeline, Infrastructure, SLO, Traces, k6 Performance)

### Quality Gate Thresholds

| Metric | Threshold | Action on Breach |
|--------|-----------|-----------------|
| HTTP p95 latency | < 800ms | Pipeline FAILURE (k6 exit 99) |
| HTTP p99 latency | < 2000ms | Pipeline FAILURE (abortOnFail, 30s delay) |
| HTTP error rate | < 1% | Pipeline FAILURE (abortOnFail) |

### Troubleshooting
```bash
# Verify k6 is installed in agent
oc exec deploy/jenkins -n devsecops-tools -- k6 version
# Expected: k6 v0.54.0

# Run k6 manually from agent pod
oc exec deploy/jenkins -n devsecops-tools -- \
  k6 run --env BASE_URL=https://sampleapi-${NS_DEV}.${APPS_DOMAIN} \
  build-config/tests/performance/load-test.js --duration 30s --vus 10

# Check k6-summary.json in Jenkins build artifacts
# Jenkins → sampleapi-tag → Build #17 → Artifacts → k6-summary.json

# k6 exit code 99 = threshold breached = pipeline fails
# k6 exit code 0 = all thresholds passed
```

---

## Phase 24: Production Readiness — Backup & Compliance

### PostgreSQL Backup CronJob
- **Namespace:** sampleapi-prod
- **Schedule:** Daily 02:00 UTC
- **Image:** `registry.redhat.io/rhel9/postgresql-16:latest`
- **Storage:** 5Gi PVC (`postgresql-backup-pvc`)
- **Retention:** 7 backups
- **Verify:** `oc get cronjob postgresql-backup -n sampleapi-prod`
- **Manual trigger:** `oc create job pg-backup-test --from=cronjob/postgresql-backup -n sampleapi-prod`

### GitLab Backup CronJob
- **Namespace:** devsecops-gitlab
- **Schedule:** Daily 03:00 UTC
- **Method:** `gitlab-backup create` via oc exec into GitLab pod
- **Storage:** GitLab data PVC (`/var/opt/gitlab/backups/`)
- **Retention:** 7 backups
- **Verify:** `oc get cronjob gitlab-backup -n devsecops-gitlab`

### ACS Compliance Scan CronJob
- **Namespace:** devsecops-tools
- **Schedule:** Weekly Sunday 02:00 UTC
- **Image:** `registry.redhat.io/advanced-cluster-security/rhacs-roxctl-rhel8:4.7`
- **Method:** `roxctl central compliance trigger`
- **Verify:** `oc get cronjob acs-compliance-scan -n devsecops-tools`

### Troubleshoot
```bash
# Check backup job logs
oc logs job/<job-name> -n sampleapi-prod

# Check PVC usage
oc exec -it $(oc get pvc postgresql-backup-pvc -n sampleapi-prod -o jsonpath='{.spec.volumeName}') -- df -h /backups

# PostgreSQL version mismatch: pg_dump version must match server version
# Server is v16 — use postgresql-16 image (NOT postgresql-15)
```
