# DevSecOps on OpenShift -- Production-Grade CI/CD Platform

Production-grade DevSecOps CI/CD for .NET 8 microservices on Red Hat OpenShift 4.x.
Trunk-Based Development with 3+1 pipeline triggers, GitOps promotion across 4 environments
(DEV / SIT / UAT / PROD), and a full observability stack (logging, monitoring, tracing).

**Built and deployed on a real OpenShift cluster -- all 24 phases completed and verified.**

---

## Architecture Overview

In production, these are 5 separate GitLab repositories. This GitHub repo consolidates
them for reference:

| Directory | GitLab Repo | Purpose |
|-----------|-------------|---------|
| `services/sampleapi/` | app-source | .NET 8 primary API (EF Core + Redis + health checks) |
| `services/notificationapi/` | notificationapi-source | .NET 8 internal microservice (POST /api/Notify) |
| `build-config/` | build-config | Dockerfile, sonar config, ZAP config, k6 perf tests |
| `jenkins-shared-lib/` | jenkins-shared-lib | Pipeline logic: 22 vars/ functions including 4 orchestrators (T1/T2/T3/T4) |
| `app-gitops/` | app-gitops | Kustomize base + per-env overlays, 12 ArgoCD Application CRDs |

```
  Pipeline Triggers (Trunk-Based Development)
  +--------+-------------------+------------------------------------------------------+
  | T1: MR | MR opened/updated | Unit test + SAST + SCA --> report pass/fail to MR    |
  | T2:    | MR merged to main | Same + build image + ACS scan + deploy to DEV        |
  | T3:    | Version tag push  | Same + DAST + perf test + release image (NO deploy)  |
  | T4:    | GitOps MR merged  | Detect service+env changed --> sync ArgoCD --> next  |
  +--------+-------------------+------------------------------------------------------+

  Promotion Flow
  DEV (auto) --> SIT (Team Lead MR) --> UAT (QA Lead MR) --> PROD (CAB MR)
```

```
  Application Architecture (per environment)
  +-----------------------------------------------------------------------+
  |                     sampleapi-{env} namespace                         |
  |                                                                       |
  |   +-------------+      +------------------+      +----------------+   |
  |   | SampleApi   |----->| NotificationApi  |      | PostgreSQL 16  |   |
  |   | (.NET 8)    |      | (.NET 8)         |      | (StatefulSet)  |   |
  |   | Port 8080   |      | Port 8081        |      | Port 5432      |   |
  |   +------+------+      +--------+---------+      +----------------+   |
  |          |                       |                                     |
  |          +-----------+-----------+                                     |
  |                      |                                                 |
  |               +------+------+                                          |
  |               | Redis 7     |                                          |
  |               | (StatefulSet)|                                         |
  |               | Port 6379   |                                          |
  |               +-------------+                                          |
  +-----------------------------------------------------------------------+
```

---

## Platform Stack

| Category | Tool | Purpose |
|----------|------|---------|
| **Platform** | OpenShift 4.x | Container platform (3 master + 3 worker) |
| **CI** | Jenkins + Shared Library | Pipeline orchestration, 7 jobs, inline CPS |
| **Source** | GitLab CE | Repositories, merge requests, webhooks |
| **CD** | ArgoCD (OpenShift GitOps) | 12 Applications, auto-sync DEV, manual others |
| **SAST** | SonarQube CE v26 | Code quality gates (dotnet-sonarscanner) |
| **SCA** | OWASP Dependency-Check | Dependency CVE scanning |
| **DAST** | OWASP ZAP | Dynamic security testing (T3 sidecar) |
| **Image Security** | ACS / StackRox | Image scan, admission control, runtime |
| **Registry** | OCP Internal Registry | Image storage, cross-namespace pull |
| **Build** | Podman | Rootless container builds |
| **Logging** | LokiStack + Vector | Centralized logs, LogQL queries |
| **Monitoring** | Prometheus + Alertmanager | Metrics, SLO burn-rate alerting |
| **Dashboards** | Grafana Operator v5 | 7 dashboards (app, pipeline, SLO, logs, infrastructure, traces, k6) |
| **Tracing** | Tempo + OpenTelemetry | Distributed tracing, auto-instrumentation |
| **Perf Testing** | k6 | Load testing as pipeline quality gate |

---

## Screenshots

| Jenkins 7 Jobs | ArgoCD 12 Apps | SonarQube |
|:-:|:-:|:-:|
| ![Jenkins](docs/screenshot/jenkins-dashboard-7-jobs.png) | ![ArgoCD](docs/screenshot/argocd-12-apps-top.png) | ![SonarQube](docs/screenshot/sonarqube-projects-dashboard.png) |

| SampleApi T2 Pipeline | NotificationApi Tag Pipeline | GitOps Promotion |
|:-:|:-:|:-:|
| ![T2](docs/screenshot/jenkins-sampleapi-merge-stage.png) | ![T3](docs/screenshot/jenkins-notificationapi-tag-stage.png) | ![T4](docs/screenshot/jenkins-gitops-promotion-stage.png) |

| GitLab MR Pipeline | Promotion MR Detail | GitLab 5 Repos |
|:-:|:-:|:-:|
| ![MR](docs/screenshot/gitlab-mr-t1-pipeline-passed.png) | ![Promote](docs/screenshot/gitlab-promotion-mr-detail-top.png) | ![Repos](docs/screenshot/gitlab-devsecops-group-5-repos.png) |

---

## Repository Structure

```
devsecops-project/
  app-gitops/            GitOps manifests (Kustomize + 12 ArgoCD Application CRDs)
    services/sampleapi/    Per-service base + overlays (dev/sit/uat/production)
    services/notificationapi/
    infra/                 Shared infra (PostgreSQL, Redis, ServiceAccount)
    argocd/                12 Application CRDs

  build-config/          Build & scan configurations
    Dockerfile             Single parameterized multi-stage build
    sonar-project.properties
    tests/performance/     k6 load test scripts (load, stress, soak, multi-service)

  jenkins-shared-lib/    ALL pipeline logic (no Jenkinsfiles in app repos)
    vars/                  18 pipeline functions + 4 orchestrators (22 total)
    src/                   PipelineConfig, SecurityGate, ImageTagger classes

  services/              Application source code
    sampleapi/             .NET 8 Web API (EF Core + Redis + NotificationClient)
    notificationapi/       .NET 8 internal microservice (POST /api/Notify)

  infra/                 Infrastructure manifests (phases 1-24)
    phase1/                Namespaces, RBAC, NetworkPolicies, quotas
    phase2/                GitLab CE
    phase3/                SonarQube CE
    phase4/                Registry configuration (pull secrets, image-pusher RBAC)
    phase5/                ACS / StackRox
    phase6/                ArgoCD (OpenShift GitOps)
    phase7/                Jenkins + custom agent image
    phase12/               Jenkins job definitions, webhook setup
    phase13/               Security policies (ACS, SonarQube, gitleaks)
    phase14/               Basic monitoring enablement
    phase15/               Execution runbook and validation scripts
    phase17/               Multi-service NetworkPolicies
    phase19/               Logging (LokiStack, Vector, ClusterLogForwarder)
    phase20/               Monitoring (ServiceMonitor, PrometheusRule, AlertmanagerConfig)
    phase21/               Grafana dashboards (Operator v5, datasources, 5 dashboards)
    phase22/               Distributed tracing (Tempo, OTel Collector, auto-instrumentation)
    phase23/               Performance testing (k6 pipeline integration, 1 dashboard)
    phase24/               Production hardening (chaos tests, backups, compliance)

  docs/                  Documentation & tutorials
    tutorial/              17-module hands-on learning path (~22 hours)
    screenshot/            23 screenshots from live cluster
    ARCHITECTURE.md        Platform architecture
    INFRASTRUCTURE.md      Every deployed component
```

---

## Tutorial

17 modules organized into 5 tracks (~22 hours total). Each module follows
**Tell -- Show -- Do -- Verify** pedagogy.

| Track | Modules | Duration | Topics |
|-------|---------|----------|--------|
| **Foundation** | 1-4 (incl. 2B) | ~6h | OpenShift fundamentals, containers, GitLab, Jenkins, ArgoCD |
| **Security** | 5-7 | ~4h | SAST (SonarQube), container security (ACS), DAST (ZAP) |
| **Integration** | 8-10 (incl. 9B) | ~5h15m | 3-trigger pipeline, per-env config, multi-service, E2E |
| **Observability** | 11-13 | ~4h15m | LokiStack logging, Prometheus alerting, Grafana + tracing |
| **Advanced** | 14-15 | ~3h | k6 performance testing, production hardening |

See [docs/tutorial/README.md](docs/tutorial/README.md) for the full curriculum.

---

## Key Design Decisions

- **App repo stays clean** -- zero CI/CD files in application repositories
- **Single parameterized Dockerfile** -- `--build-arg PROJECT_NAME` works for any .NET service
- **Per-service GitOps isolation** -- each service has own ConfigMap, Secret, ArgoCD app, image tag
- **Service-parameterized pipelines** -- `pipelineMerge(service: 'notificationapi')` reuses all logic
- **Auto-instrumentation for tracing** -- no app code changes, OTel Operator injects .NET agent
- **SLO-based alerting** -- multi-window burn-rate alerts instead of threshold-based

---

## Documentation

| Document | Description |
|----------|-------------|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Platform architecture, component diagrams, data flows |
| [docs/INFRASTRUCTURE.md](docs/INFRASTRUCTURE.md) | Every component: name, version, namespace, URL, access |
| [docs/tutorial/README.md](docs/tutorial/README.md) | Tutorial curriculum and module index (17 modules) |

---

## License

This project is for educational and sharing purposes.
