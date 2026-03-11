# DevSecOps on OpenShift -- Documentation

Production-grade DevSecOps CI/CD for .NET 8 microservices on Red Hat OpenShift 4.x.
Trunk-Based Development with 3+1 pipeline triggers, GitOps promotion across 4 environments
(DEV, SIT, UAT, PROD), and a full observability stack.

---

## What's Deployed

```
Platform Stack
--------------
OpenShift 4.x            Container platform (3 master + 3 worker nodes)
Jenkins                  CI pipeline orchestration (shared library architecture)
GitLab CE                Source control, merge requests, webhooks
ArgoCD                   GitOps deployment engine (12 ArgoCD Applications)
SonarQube CE             SAST and code quality gates
ACS (StackRox)           Container image scanning, admission control, runtime security
OWASP Dependency-Check   SCA (dependency CVE scanning)
OWASP ZAP                DAST (dynamic application security testing)
Podman                   Rootless container image builds
LokiStack + Vector       Centralized log aggregation (S3 backend via ODF)
Prometheus + Alertmanager  Metrics, SLO-based alerting, ServiceMonitors
Grafana                  Dashboards (app health, pipeline, SLO, logs, traces)
Tempo + OpenTelemetry    Distributed tracing with auto-instrumentation
k6                       Performance testing as pipeline quality gate

Application Services (per environment: DEV / SIT / UAT / PROD)
--------------------------------------------------------------
SampleApi (.NET 8)       Primary API with PostgreSQL + Redis + health checks
NotificationApi (.NET 8) Internal microservice (inter-service communication)
PostgreSQL 16            Persistent data store (StatefulSet)
Redis 7                  Caching and pub/sub (StatefulSet)
```

### Pipeline Triggers

| Trigger | Git Event | What Happens |
|---------|-----------|--------------|
| T1 (MR) | Merge request opened | Unit test, SAST, SCA -- report pass/fail on MR |
| T2 (Merge) | MR merged to main | Same + build image + ACS scan + deploy DEV |
| T3 (Tag) | Version tag pushed | Same + DAST + perf test + release image (no deploy) |
| T4 (Promote) | GitOps repo updated | Detect changed service+env, sync ArgoCD, create next MR |

### GitOps Promotion Flow

```
DEV (auto-sync) --> SIT (manual, Team Lead MR) --> UAT (manual, QA Lead MR) --> PROD (manual, CAB MR)
```

---

## Directory Structure

```
docs/
  README.md ............... You are here
  tutorial/ ............... 17-module hands-on learning path
    README.md ............. Tutorial curriculum and module index
    module-01 through 15 .. Individual modules (Tell-Show-Do-Verify)
                            (includes Module 2B and Module 9B)
  screenshot/ ............. 23 screenshots from live cluster
  ARCHITECTURE.md ......... Platform architecture and component diagrams
  INFRASTRUCTURE.md ....... Every deployed component (name, version, namespace, URL)
```

---

## Tutorial

The tutorial is organized into 5 tracks with 17 modules totaling ~22 hours.
See [tutorial/README.md](tutorial/README.md) for the full curriculum.

| Track | Modules | Duration | Focus |
|-------|---------|----------|-------|
| Foundation | 1-4 (incl. 2B) | ~6h | OpenShift, containers, GitLab/registry, Jenkins, ArgoCD |
| Security | 5-7 | ~4h | SAST, container security, DAST |
| Integration | 8-10 (incl. 9B) | ~5h15m | 3-trigger pipeline, per-env config, multi-service, E2E walkthrough |
| Observability | 11-13 | ~4h15m | Logging, monitoring/alerting, Grafana + tracing |
| Advanced | 14-15 | ~3h | Performance testing, production hardening |

---

## Reference

| Document | What It Covers |
|----------|----------------|
| [ARCHITECTURE.md](ARCHITECTURE.md) | High-level architecture, pipeline flow diagrams, repository layout |
| [INFRASTRUCTURE.md](INFRASTRUCTURE.md) | Every component: name, version, namespace, route URL, how to access, how to troubleshoot |
