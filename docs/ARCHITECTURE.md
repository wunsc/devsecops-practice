# Architecture — DevSecOps Platform on OpenShift

## High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                              OpenShift 4.20.15                                  │
│                    6 nodes (3 master + 3 worker), AWS ap-southeast-1            │
│                                                                                 │
│  ┌─────────────────────────────────────────────────────────────────────────────┐ │
│  │                          CI/CD PIPELINE FLOW                                │ │
│  │                                                                             │ │
│  │  Developer → GitLab CE → Jenkins (shared lib) → Build/Scan/Test            │ │
│  │                 │              │                     │                       │ │
│  │                 │              ▼                     ▼                       │ │
│  │                 │         SonarQube (SAST)     ACS/StackRox (Image)         │ │
│  │                 │         Dep-Check (SCA)      OWASP ZAP (DAST)            │ │
│  │                 │         k6 (Perf Test)                                    │ │
│  │                 │              │                                             │ │
│  │                 │              ▼                                             │ │
│  │                 │     OCP Internal Registry                                 │ │
│  │                 │              │                                             │ │
│  │                 │              ▼                                             │ │
│  │                 └──→ app-gitops (Kustomize) → ArgoCD → Deploy               │ │
│  │                                                                             │ │
│  └─────────────────────────────────────────────────────────────────────────────┘ │
│                                                                                 │
│  ┌───────────────────────────────────────────────────────────────────────────┐   │
│  │                       APPLICATION ENVIRONMENTS                            │   │
│  │                                                                           │   │
│  │  sampleapi-dev (1r)  → sampleapi-sit (2r) → sampleapi-uat (2r)          │   │
│  │       │                      │                    │                       │   │
│  │       │ auto-sync            │ manual             │ manual               │   │
│  │       │                      │                    │                       │   │
│  │       └──────────────────────┴────────────────────┴──→ sampleapi-prod (3r)│   │
│  │                                                                           │   │
│  │  Each namespace contains:                                                 │   │
│  │    SampleApi (.NET 8) → NotificationApi (.NET 8)                          │   │
│  │    PostgreSQL 16 (StatefulSet) + Redis 7 (StatefulSet)                    │   │
│  └───────────────────────────────────────────────────────────────────────────┘   │
│                                                                                 │
│  ┌───────────────────────────────────────────────────────────────────────────┐   │
│  │                       OBSERVABILITY STACK                                 │   │
│  │                                                                           │   │
│  │  Logging:  Vector → LokiStack → OCP Console / Grafana                    │   │
│  │  Metrics:  Prometheus UWM → Thanos Querier → Grafana                     │   │
│  │  Tracing:  OTel Auto-Instr → OTel Collector → TempoStack → Console      │   │
│  │  Alerting: PrometheusRules → Alertmanager → Slack/Email                  │   │
│  │  Dashboards: Grafana Operator v5 → 7 dashboards (GitOps-managed)         │   │
│  └───────────────────────────────────────────────────────────────────────────┘   │
│                                                                                 │
└─────────────────────────────────────────────────────────────────────────────────┘
```

## Repository Architecture (5 Repos)

| Repo | Purpose | Key Contents |
|------|---------|-------------|
| **app-source** (ID=1) | SampleApi application code | .NET 8 Web API, EF Core, Redis, HttpClient |
| **notificationapi-source** (ID=5) | NotificationApi code | .NET 8 minimal API, Redis pub/sub |
| **build-config** (ID=2) | Build & scan configs | Dockerfile, sonar config, k6 tests, ZAP config |
| **jenkins-shared-lib** (ID=3) | All pipeline logic | 22 vars/ functions, 3 src/ classes |
| **app-gitops** (ID=4) | GitOps manifests | Kustomize base + overlays, 12 ArgoCD apps |

## Pipeline Architecture (3 Triggers + 1 Promotion)

| Trigger | Event | Stages | Output |
|---------|-------|--------|--------|
| T1 (MR) | MR opened | Build → Test → SAST → SCA | Pass/Fail on GitLab MR |
| T2 (Merge) | Push to main | Build → Test → SAST → SCA → Image → ACS → Push → Deploy DEV | Running app in DEV |
| T3 (Tag) | Tag pushed | Build → Test → SAST → SCA → Image → ACS Strict → DAST → Perf → Sign → Push | Release image (no deploy) |
| T4 (Promote) | GitOps MR merged | Detect service+env → ArgoCD sync → Create next-env MR | Deployed to SIT/UAT/PROD |

## Namespace Map

| Namespace | Purpose | Key Resources |
|-----------|---------|---------------|
| devsecops-tools | CI/CD tools | Jenkins, SonarQube, Grafana |
| devsecops-gitlab | Source control | GitLab CE, PostgreSQL, Redis |
| stackrox | Container security | ACS Central, Scanner |
| openshift-gitops | GitOps | ArgoCD server |
| openshift-logging | Logging | LokiStack, Vector collectors |
| openshift-tempo | Tracing | TempoStack, OTel Collector |
| openshift-storage | Object storage | ODF/NooBaa (S3 for Loki+Tempo) |
| sampleapi-{dev,sit,uat,prod} | App environments | SampleApi, NotificationApi, PG, Redis |

## Security Architecture

```
┌──────────────────────────────────────────────────────────────────────┐
│                        DEFENSE IN DEPTH                              │
│                                                                      │
│  Layer 1: Source Code                                                │
│    └── gitleaks (pre-commit) → SonarQube SAST → Dep-Check SCA       │
│                                                                      │
│  Layer 2: Container Image                                            │
│    └── Multi-stage Dockerfile (non-root) → ACS Image Scan            │
│                                                                      │
│  Layer 3: Runtime                                                    │
│    └── ACS Admission Controller → NetworkPolicies → RBAC             │
│                                                                      │
│  Layer 4: Application                                                │
│    └── OWASP ZAP DAST → k6 Performance Gate                         │
│                                                                      │
│  Layer 5: Monitoring                                                 │
│    └── Prometheus Alerts → ACS Runtime Policies → Log Correlation    │
└──────────────────────────────────────────────────────────────────────┘
```

## Data Flow

```
User Request → OpenShift Router → SampleApi Pod
                                      │
                    ┌─────────────────┼──────────────────┐
                    ▼                 ▼                   ▼
              PostgreSQL          Redis Cache       NotificationApi
              (read/write)        (get/set)         (HTTP POST)
                                                         │
                                                         ▼
                                                    Redis Pub/Sub
```

## Component Versions

| Component | Version | Operator |
|-----------|---------|----------|
| OpenShift | 4.20.15 | - |
| GitLab CE | 17.0 | Manual deployment |
| Jenkins | 2.x (OCP image) | Manual deployment |
| SonarQube | 26.3.0 | Manual deployment |
| ACS/StackRox | 4.7 | rhacs-operator |
| ArgoCD | 2.x | openshift-gitops-operator |
| Loki Operator | 6.2 | loki-operator |
| Cluster Logging | 6.2 | cluster-logging |
| Tempo Operator | 0.20.0-1 | tempo-product |
| OTel Operator | 0.144.0-1 | opentelemetry-product |
| Grafana Operator | 5.22.0 | grafana-operator |
| Cluster Observability | 1.3.1 | cluster-observability-operator |
| .NET Runtime | 8.0 | - |
| PostgreSQL | 16 (RHEL9) | - |
| Redis | 7 (RHEL9) | - |
