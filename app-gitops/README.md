# app-gitops — Per-Service GitOps Repository

GitOps deployment manifests for the DevSecOps project. Each service has its own
directory with independent base + overlays, so image tags and patches are isolated.

## Directory Structure

```
app-gitops/
├── services/                    # Per-service deployment manifests
│   ├── sampleapi/
│   │   ├── base/                # Deployment, Service, Route
│   │   └── overlays/
│   │       ├── dev/             # 1 replica, own image tag
│   │       ├── sit/             # 2 replicas, own image tag
│   │       ├── uat/             # 2 replicas, own image tag
│   │       └── production/      # 3 replicas, PDB, own image tag
│   └── notificationapi/
│       ├── base/                # Deployment, Service
│       └── overlays/
│           ├── dev/             # 1 replica, own image tag
│           ├── sit/             # 2 replicas, own image tag
│           ├── uat/             # 2 replicas, own image tag
│           └── production/      # 2 replicas, PDB, own image tag
├── infra/                       # Shared infrastructure per environment
│   ├── base/                    # ServiceAccount, PostgreSQL, Redis
│   └── overlays/
│       ├── dev/                 # infra-secret (PG + Redis credentials)
│       ├── sit/                 # infra-secret
│       ├── uat/                 # infra-secret
│       └── production/          # infra-secret + PG resource patch
└── argocd/                      # ArgoCD Application CRDs (12 apps)
    ├── project.yaml             # AppProject: devsecops
    ├── sampleapi-{env}.yaml     # 4 apps (dev/sit/uat/prod)
    ├── notificationapi-{env}.yaml # 4 apps (dev/sit/uat/prod)
    └── infra-{env}.yaml         # 4 apps (dev/sit/uat/prod)
```

## Per-Environment Configuration

Each **service** overlay contains its own `configmap-env.yaml` + `secret-env.yaml`.
Each **infra** overlay contains only `secret-env.yaml` (infra-secret for PG + Redis credentials).

Example per-service config (from `services/sampleapi/overlays/{env}/configmap-env.yaml`):

| Variable | DEV | SIT | UAT | PROD |
|----------|-----|-----|-----|------|
| ASPNETCORE_ENVIRONMENT | Development | Staging | Staging | Production |
| LOGGING__LOGLEVEL__DEFAULT | Debug | Information | Information | Warning |
| FEATURE_SWAGGER_ENABLED | true | true | false | false |

## How Image Tags Are Updated

```bash
# T2 pipeline (updateGitOps.groovy):
cd services/sampleapi/overlays/dev
kustomize edit set image sampleapi=registry/ns/sampleapi:main-a1b2c3d

# T3 pipeline (createPromotionMR.groovy):
cd services/sampleapi/overlays/sit
kustomize edit set image sampleapi=registry/ns/sampleapi:v1.2.0
# Creates MR for approval
```

## Promotion Flow

```
DEV  <- auto-sync from T2 pipeline (per-service ArgoCD app)
 |
SIT  <- T3 creates promotion MR -> approve + merge -> T4 syncs
 |
UAT  <- T4 cascading: creates UAT MR -> approve + merge -> T4 syncs
 |
PROD <- T4 cascading: creates PROD MR -> approve + merge -> T4 syncs -> end
```

## Adding a New Service

1. Copy `services/sampleapi/` to `services/{newservice}/`
2. Update deployment.yaml, service.yaml (name, ports, image)
3. Update overlay kustomization.yaml files (image name)
4. Add `configureForService()` case in PipelineConfig.groovy
5. Copy ArgoCD app CRDs and update paths
6. Create Jenkins jobs in JCasC ConfigMap
