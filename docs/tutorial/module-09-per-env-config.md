# Module 9: Per-Environment Configuration

| | |
|---|---|
| **Track** | Integration |
| **Duration** | ~60 minutes |
| **Difficulty** | Intermediate |
| **Prerequisites** | Modules 4 (GitOps with ArgoCD) and 8 (Three-Trigger Pipeline), running OpenShift cluster with ArgoCD syncing all 4 environments |

## What You Will Learn

By the end of this module you will be able to:

- Explain why application configuration belongs outside the container image
- Create per-service Kustomize ConfigMaps that differ per environment (DEV, SIT, UAT, PROD)
- Create per-service Kustomize Secrets per environment (with a path toward sealed/encrypted secrets)
- Explain the per-service isolation model: each service owns its own ConfigMap, Secret, and ArgoCD app
- Wire a Deployment to consume its own service-specific ConfigMap and Secret via `envFrom`
- Use the .NET `IOptions<T>` pattern to read externalized configuration at runtime
- Change a configuration value in Git and watch ArgoCD deliver it to the running pod

---

## 1. Concepts: Why External Configuration Matters

### The Problem

You have two microservices -- `sampleapi:main-78f28b4` and `notificationapi:main-03a9411`. Each runs in DEV, SIT, UAT, and PROD. But each environment needs different behavior:

| Setting | DEV | SIT | UAT | PROD |
|---------|-----|-----|-----|------|
| Logging level | Debug | Information | Information | Warning |
| Swagger UI | Enabled | Enabled | Disabled | Disabled |
| Database URL | `postgresql.sampleapi-dev.svc` | `postgresql.sampleapi-sit.svc` | `postgresql.sampleapi-uat.svc` | `postgresql.sampleapi-prod.svc` |
| Replicas (SampleApi) | 1 | 2 | 2 | 3 |
| Replicas (NotificationApi) | 1 | 2 | 2 | 2 |
| Forecast location | DEV | SIT | UAT | Production |

If you bake these values into the image, you need four images per service. If one of them has a security fix, you rebuild all four. That is the wrong answer.

### The 12-Factor Answer

[Factor III](https://12factor.net/config) of the Twelve-Factor App methodology says: **store config in the environment.** The image is the artifact. The configuration is the context. Separate them.

In Kubernetes with per-service isolation, that separation looks like this:

```
              +-----------------+                 +-----------------------+
              |  SampleApi      |                 |  NotificationApi      |
              |  Container      |                 |  Container            |
              |  (same image    |                 |  (same image          |
              |   everywhere)   |                 |   everywhere)         |
              +-------+---------+                 +----------+------------+
                      |                                      |
              reads env vars from                    reads env vars from
                      |                                      |
         +------------+------------+            +------------+------------+
         |                         |            |                         |
+--------v---------+  +-----------v------+  +--v-----------------+  +---v------------------+
| ConfigMap        |  | Secret           |  | ConfigMap          |  | Secret               |
| sampleapi-env    |  | sampleapi-secret |  | notificationapi-env|  | notificationapi-     |
|                  |  |                  |  |                    |  |   secret              |
| DATABASE_URL=... |  | DATABASE_PASS=.. |  | REDIS_URL=...      |  | REDIS_PASSWORD=...   |
| LOGGING=Debug    |  | API_KEY=...      |  | LOGGING=Debug      |  |                      |
| SWAGGER=true     |  | JWT_SECRET=...   |  |                    |  |                      |
+------------------+  | REDIS_PASS=...   |  +--------------------+  +----------------------+
                      +------------------+
         ^                     ^                     ^                        ^
         |                     |                     |                        |
   per-service overlay   per-service overlay    per-service overlay     per-service overlay
   services/sampleapi/  services/sampleapi/   services/notificationapi/ services/notificationapi/
   overlays/{env}/      overlays/{env}/       overlays/{env}/           overlays/{env}/
```

### Per-Service Isolation: The Key Design Decision

In a multi-service architecture, each service has its **own** ConfigMap and Secret, managed by its **own** ArgoCD application. This isolation means:

- Updating SampleApi's database password does not require redeploying NotificationApi
- Each service's ArgoCD app syncs independently -- rotating a secret only requires syncing the affected app
- Two services merging to main simultaneously cannot create Git merge conflicts in each other's overlay directories
- Each service's CI pipeline updates only its own `services/{svc}/overlays/dev/kustomization.yaml`

The three secrets per namespace have no overlap:

| Secret | Owner ArgoCD App | Contains | Consumed By |
|--------|-----------------|----------|-------------|
| `infra-secret` | infra-{env} | PG_USER, PG_PASS, PG_DB, REDIS_PASS | PostgreSQL + Redis StatefulSets |
| `sampleapi-secret` | sampleapi-{env} | DB_PASSWORD, API_KEY, JWT_SECRET, REDIS_PASS | SampleApi Deployment (envFrom) |
| `notificationapi-secret` | notificationapi-{env} | REDIS_PASSWORD | NotificationApi Deployment (envFrom) |

### How Kustomize Overlays Deliver Per-Env Config

Kustomize uses a **base + overlay** model. Each service has its own base defining the Deployment, Service, and (optionally) Route once. Each overlay patches in environment-specific values -- namespace, replicas, resource limits, ConfigMap data, and Secret data.

```
app-gitops/
  services/
    sampleapi/
      base/                              <-- shared across ALL environments for this service
        deployment.yaml                  <-- references sampleapi-env ConfigMap + sampleapi-secret
        service.yaml
        route.yaml
        kustomization.yaml
      overlays/
        dev/                             <-- DEV-specific for SampleApi
          kustomization.yaml             <-- sets namespace, image tag
          configmap-env.yaml             <-- sampleapi-env ConfigMap (Debug logging, Swagger on)
          secret-env.yaml                <-- sampleapi-secret (DB password, API key, JWT, Redis)
          patch-deployment.yaml          <-- 1 replica, lower resources
        sit/                             <-- SIT-specific (same structure, different values)
        uat/
        production/                      <-- 3 replicas, Warning logging, PDB
    notificationapi/
      base/                              <-- shared across ALL environments for this service
        deployment.yaml                  <-- references notificationapi-env + notificationapi-secret
        service.yaml
        kustomization.yaml               <-- NO route.yaml (internal-only service)
      overlays/
        dev/                             <-- DEV-specific for NotificationApi
          kustomization.yaml
          configmap-env.yaml             <-- notificationapi-env ConfigMap (own keys)
          secret-env.yaml                <-- notificationapi-secret (Redis password only)
          patch-deployment.yaml
        sit/
        uat/
        production/
  infra/                                 <-- Shared infrastructure (PostgreSQL, Redis)
    base/
      serviceaccount.yaml
      postgresql/
      redis/
      kustomization.yaml
    overlays/
      dev/
        kustomization.yaml
        secret-env.yaml                  <-- infra-secret (PG + Redis credentials)
      sit/
      uat/
      production/
```

> **Key insight:** Each service has its own ConfigMap with a service-specific name (`sampleapi-env`, `notificationapi-env`). The Deployment for each service references only its own ConfigMap and Secret. Kustomize patches the *data* per environment -- the name stays stable within each service.

### How .NET Reads It

ASP.NET Core has a layered configuration system. Values load in order, and later sources override earlier ones:

```
appsettings.json                 <-- lowest priority (defaults)
  v
appsettings.{Environment}.json  <-- environment-specific file
  v
Environment variables            <-- HIGHEST priority (from ConfigMap/Secret)
```

When Kubernetes injects `LOGGING__LOGLEVEL__DEFAULT=Debug` as an environment variable, it overrides the `Logging:LogLevel:Default` value from `appsettings.json`. The double-underscore (`__`) is .NET's convention for representing nested JSON keys as flat environment variable names.

---

## 2. Prerequisites

Before starting, confirm:

- [ ] You have the `app-gitops` repository cloned or available at `/path/to/app-gitops`
- [ ] ArgoCD is running and syncing at least the DEV environment
- [ ] Both `sampleapi` and `notificationapi` are deployed in `sampleapi-dev`
- [ ] PostgreSQL and Redis are running in `sampleapi-dev`
- [ ] You have `kustomize` CLI installed (v5.x)
- [ ] You have `oc` CLI access to the cluster

> **Environment variables:** Before running any commands, source the environment file:
> ```bash
> source ./env.sh
> ```
> This sets `$OC`, `$APPS_DOMAIN`, `$NS_TOOLS`, and all other cluster-specific variables used throughout this module. See `env.sh` for the full variable list.

Verify your starting point:

```bash
# Confirm all services are running in DEV
$OC get pods -n $NS_DEV
# Expected:
# NAME                               READY   STATUS    RESTARTS   AGE
# notificationapi-847754bdb8-vg2xf   1/1     Running   1          28h
# postgresql-0                       1/1     Running   2          46h
# redis-0                            1/1     Running   2          46h
# sampleapi-674bb887c9-lbm9l         1/1     Running   1          26h

# Confirm the per-service ConfigMaps exist
$OC get configmap sampleapi-env -n $NS_DEV -o jsonpath='{.data}' | jq .
# Expected: ConfigMap with DATABASE_URL, LOGGING__LOGLEVEL__DEFAULT, etc.

$OC get configmap notificationapi-env -n $NS_DEV -o jsonpath='{.data}' | jq .
# Expected: ConfigMap with ASPNETCORE_ENVIRONMENT, REDIS_URL, etc.

# Confirm all three secrets exist (no overlap)
$OC get secret sampleapi-secret -n $NS_DEV
$OC get secret notificationapi-secret -n $NS_DEV
$OC get secret infra-secret -n $NS_DEV
# Expected: all three exist

# Confirm the app responds
curl -sk $APP_DEV_URL/healthz
# Expected:
# {"status":"healthy","timestamp":"2026-03-10T05:25:02.3751347Z"}

curl -sk $APP_DEV_URL/api/WeatherForecast/config
# Expected: JSON with Location, ForecastDays, TemperatureUnit values
```

---

## 3. Step 1: Understand the Per-Service Base Deployments

Before creating per-environment overrides, look at how each service's base Deployment references its own ConfigMap and Secret.

### 3.1 Read the SampleApi Base Deployment

Open `app-gitops/services/sampleapi/base/deployment.yaml` and find the `envFrom` block:

```yaml
# app-gitops/services/sampleapi/base/deployment.yaml (relevant section)
spec:
  containers:
    - name: sampleapi
      image: sampleapi              # <-- tag set per-env by kustomize edit set image
      ports:
        - containerPort: 8080
      envFrom:
        - configMapRef:
            name: sampleapi-env     # <-- ALL keys become environment variables
        - secretRef:
            name: sampleapi-secret  # <-- ALL keys become environment variables
```

### 3.2 Read the NotificationApi Base Deployment

Open `app-gitops/services/notificationapi/base/deployment.yaml`:

```yaml
# app-gitops/services/notificationapi/base/deployment.yaml (relevant section)
spec:
  containers:
    - name: notificationapi
      image: notificationapi        # <-- tag set per-env by kustomize edit set image
      ports:
        - containerPort: 8081
      envFrom:
        - configMapRef:
            name: notificationapi-env      # <-- own ConfigMap (NOT sampleapi-env)
        - secretRef:
            name: notificationapi-secret   # <-- own Secret (NOT sampleapi-secret)
```

> **Why separate ConfigMaps per service?** If both services shared one ConfigMap, updating a SampleApi-only setting (like `DATABASE_URL`) would trigger a restart of NotificationApi too. With per-service ConfigMaps, each service is isolated -- you only restart what you changed.

### 3.3 Why `envFrom` Instead of Individual `env` Entries

With `envFrom`, every key in the ConfigMap automatically becomes an environment variable in the container. You do not need to update the Deployment YAML when you add a new config key -- just add it to the ConfigMap overlay and redeploy. This keeps the Deployment generic and the config centralized.

### Verify: Confirm the Bases Build

```bash
cd app-gitops/

# Build SampleApi base
kustomize build services/sampleapi/base/
# Expected: Deployment, Service, Route rendered

# Build NotificationApi base
kustomize build services/notificationapi/base/
# Expected: Deployment, Service rendered (no Route -- internal only)
```

---

## 4. Step 2: Create the DEV ConfigMaps (One Per Service)

Each service has its own ConfigMap in its own overlay directory, containing only the keys that service needs.

### 4.1 Read the SampleApi DEV ConfigMap

Open `app-gitops/services/sampleapi/overlays/dev/configmap-env.yaml`:

```yaml
# app-gitops/services/sampleapi/overlays/dev/configmap-env.yaml
# DEV environment configuration for SampleApi (Rule 3)
# These environment variables override appsettings.json in the .NET app
---
apiVersion: v1
kind: ConfigMap
metadata:
  name: sampleapi-env                                    # <-- per-service name
data:
  ASPNETCORE_ENVIRONMENT: "Development"                  # <-- tells .NET to load appsettings.Development.json
  DOTNET_RUNNING_IN_CONTAINER: "true"
  DATABASE_URL: "Server=postgresql.sampleapi-dev.svc;Database=sampleapi;Port=5432;Username=sampleapi"
  REDIS_URL: "redis.sampleapi-dev.svc:6379"
  NOTIFICATION_API_URL: "http://notificationapi.sampleapi-dev.svc:8081"
  LOGGING__LOGLEVEL__DEFAULT: "Debug"                    # <-- verbose for debugging
  LOGGING__LOGLEVEL__MICROSOFT_ASPNETCORE: "Information"
  FEATURE_SWAGGER_ENABLED: "true"                        # <-- Swagger UI available in DEV
  CORS_ALLOWED_ORIGINS: "https://frontend-dev.apps.example.com"  # <-- replace with your APPS_DOMAIN
  WeatherForecast__Location: "DEV"                       # <-- IOptions<T> override
  WeatherForecast__ForecastDays: "7"                     # <-- 7 days in DEV (5 in base)
  EF_MIGRATE_ON_STARTUP: "true"                          # <-- auto-run EF Core migrations on startup
```

Walk through each key:

| Key | What It Controls | Why This Value in DEV |
|-----|------------------|----------------------|
| `ASPNETCORE_ENVIRONMENT` | .NET environment name, controls which `appsettings.*.json` loads | `Development` enables developer exception pages and detailed errors |
| `DATABASE_URL` | Connection string (without password) | Points to DEV PostgreSQL service (note: `Username=sampleapi` is required by Npgsql) |
| `REDIS_URL` | Redis cache connection | Points to DEV Redis service |
| `NOTIFICATION_API_URL` | Inter-service HTTP call target | SampleApi calls NotificationApi on port 8081 |
| `LOGGING__LOGLEVEL__DEFAULT` | Minimum log level | `Debug` because developers need full visibility |
| `FEATURE_SWAGGER_ENABLED` | Whether Swagger UI is served | `true` because developers need the interactive API docs |
| `WeatherForecast__Location` | Application-specific config via IOptions | `DEV` so the API response shows which environment you hit |
| `WeatherForecast__ForecastDays` | How many days of forecast to return | `7` in DEV for richer test data |
| `EF_MIGRATE_ON_STARTUP` | Whether EF Core runs database migrations at startup | `true` in DEV so schema changes apply automatically |

### 4.2 Read the NotificationApi DEV ConfigMap

Open `app-gitops/services/notificationapi/overlays/dev/configmap-env.yaml`:

```yaml
# app-gitops/services/notificationapi/overlays/dev/configmap-env.yaml
# DEV environment configuration for NotificationApi (Rule 3)
---
apiVersion: v1
kind: ConfigMap
metadata:
  name: notificationapi-env            # <-- own name, NOT sampleapi-env
data:
  ASPNETCORE_ENVIRONMENT: "Development"
  DOTNET_RUNNING_IN_CONTAINER: "true"
  REDIS_URL: "redis.sampleapi-dev.svc:6379"
  LOGGING__LOGLEVEL__DEFAULT: "Debug"
  LOGGING__LOGLEVEL__MICROSOFT_ASPNETCORE: "Information"
```

Notice how much smaller this is. NotificationApi does not need `DATABASE_URL` (it does not talk to PostgreSQL), `NOTIFICATION_API_URL` (it does not call itself), or `FEATURE_SWAGGER_ENABLED` (it is an internal service). Each service carries only the configuration it actually uses.

### 4.3 How Kustomize Wires the ConfigMap

Open `app-gitops/services/sampleapi/overlays/dev/kustomization.yaml`:

```yaml
# app-gitops/services/sampleapi/overlays/dev/kustomization.yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization

namespace: sampleapi-dev                # <-- all resources go into this namespace

resources:
  - ../../base                          # <-- pull in base manifests
  - configmap-env.yaml                  # <-- ConfigMap is a NEW resource (no base ConfigMap)
  - secret-env.yaml                     # <-- Secret is also a NEW resource

patches:
  - path: patch-deployment.yaml         # <-- patches the Deployment from base

images:                                 # <-- image tag, updated by CI pipeline
- name: sampleapi
  newName: image-registry.openshift-image-registry.svc:5000/sampleapi-dev/sampleapi
  newTag: main-78f28b4
```

> **Note on `resources` vs `patches`:** In the per-service structure, each service's base contains only the Deployment, Service, and Route -- there is no base ConfigMap to patch against. The overlay ConfigMap and Secret are both listed under `resources` because they are new resources introduced by the overlay. The deployment patch goes under `patches` because it modifies the Deployment already defined in the base.

### Verify: Build the SampleApi DEV Overlay

```bash
kustomize build services/sampleapi/overlays/dev/
```

Inspect the rendered ConfigMap. It should have all the keys from the overlay:

```bash
# Verify specific values
kustomize build services/sampleapi/overlays/dev/ | grep -A 15 "kind: ConfigMap"
# Expected: LOGGING__LOGLEVEL__DEFAULT: "Debug"
#           FEATURE_SWAGGER_ENABLED: "true"
#           WeatherForecast__Location: "DEV"
#           NOTIFICATION_API_URL: "http://notificationapi.sampleapi-dev.svc:8081"
```

```bash
# Build NotificationApi DEV overlay too
kustomize build services/notificationapi/overlays/dev/ | grep -A 8 "kind: ConfigMap"
# Expected: name: notificationapi-env
#           REDIS_URL: "redis.sampleapi-dev.svc:6379"
#           LOGGING__LOGLEVEL__DEFAULT: "Debug"
```

---

## 5. Step 3: Create the PROD ConfigMaps (Warning Logging, Swagger Off)

Now look at how the same structure produces completely different behavior in production.

### 5.1 Read the SampleApi PROD ConfigMap

Open `app-gitops/services/sampleapi/overlays/production/configmap-env.yaml`:

```yaml
# app-gitops/services/sampleapi/overlays/production/configmap-env.yaml
# PRODUCTION environment configuration for SampleApi (Rule 3)
---
apiVersion: v1
kind: ConfigMap
metadata:
  name: sampleapi-env                   # <-- SAME name as DEV -- the Deployment doesn't care
data:
  ASPNETCORE_ENVIRONMENT: "Production"
  DOTNET_RUNNING_IN_CONTAINER: "true"
  DATABASE_URL: "Server=postgresql.sampleapi-prod.svc;Database=sampleapi;Port=5432;Username=sampleapi"
  REDIS_URL: "redis.sampleapi-prod.svc:6379"
  NOTIFICATION_API_URL: "http://notificationapi.sampleapi-prod.svc:8081"
  LOGGING__LOGLEVEL__DEFAULT: "Warning"                      # <-- only warnings and errors
  LOGGING__LOGLEVEL__MICROSOFT_ASPNETCORE: "Warning"
  FEATURE_SWAGGER_ENABLED: "false"                           # <-- no Swagger in PROD
  CORS_ALLOWED_ORIGINS: "https://frontend-prod.apps.example.com"  # <-- replace with your APPS_DOMAIN
  WeatherForecast__Location: "Production"
  WeatherForecast__ForecastDays: "5"                         # <-- standard 5-day forecast
  EF_MIGRATE_ON_STARTUP: "false"                             # <-- manual migration in PROD (safer)
```

### 5.2 Read the NotificationApi PROD ConfigMap

```yaml
# app-gitops/services/notificationapi/overlays/production/configmap-env.yaml
---
apiVersion: v1
kind: ConfigMap
metadata:
  name: notificationapi-env
data:
  ASPNETCORE_ENVIRONMENT: "Production"
  DOTNET_RUNNING_IN_CONTAINER: "true"
  REDIS_URL: "redis.sampleapi-prod.svc:6379"
  LOGGING__LOGLEVEL__DEFAULT: "Warning"
  LOGGING__LOGLEVEL__MICROSOFT_ASPNETCORE: "Warning"
```

### 5.3 Compare DEV vs PROD Side by Side

```bash
# SampleApi: see the differences between DEV and PROD config
diff services/sampleapi/overlays/dev/configmap-env.yaml \
     services/sampleapi/overlays/production/configmap-env.yaml
```

Expected output (the lines that differ):

```
< ASPNETCORE_ENVIRONMENT: "Development"                 >  ASPNETCORE_ENVIRONMENT: "Production"
< DATABASE_URL: "...sampleapi-dev..."                   >  DATABASE_URL: "...sampleapi-prod..."
< REDIS_URL: "...sampleapi-dev..."                      >  REDIS_URL: "...sampleapi-prod..."
< NOTIFICATION_API_URL: "...sampleapi-dev..."           >  NOTIFICATION_API_URL: "...sampleapi-prod..."
< LOGGING__LOGLEVEL__DEFAULT: "Debug"                   >  LOGGING__LOGLEVEL__DEFAULT: "Warning"
< LOGGING__LOGLEVEL__MICROSOFT_ASPNETCORE: "Information">  LOGGING__LOGLEVEL__MICROSOFT_ASPNETCORE: "Warning"
< FEATURE_SWAGGER_ENABLED: "true"                       >  FEATURE_SWAGGER_ENABLED: "false"
< CORS_ALLOWED_ORIGINS: "...frontend-dev..."            >  CORS_ALLOWED_ORIGINS: "...frontend-prod..."
< WeatherForecast__Location: "DEV"                      >  WeatherForecast__Location: "Production"
< WeatherForecast__ForecastDays: "7"                    >  WeatherForecast__ForecastDays: "5"
< EF_MIGRATE_ON_STARTUP: "true"                         >  EF_MIGRATE_ON_STARTUP: "false"
```

Same image. Same Deployment. Same ConfigMap name. Completely different behavior.

```bash
# NotificationApi: same exercise
diff services/notificationapi/overlays/dev/configmap-env.yaml \
     services/notificationapi/overlays/production/configmap-env.yaml
```

### 5.4 PROD Also Gets Extra Protections

The production `kustomization.yaml` adds a `PodDisruptionBudget` that DEV does not have:

```yaml
# app-gitops/services/sampleapi/overlays/production/kustomization.yaml (excerpt)
resources:
  - ../../base
  - configmap-env.yaml
  - secret-env.yaml
  - pdb.yaml                           # <-- PROD-only: ensures minimum pods stay available during disruptions
```

And the deployment patch sets 3 replicas with pod anti-affinity:

```yaml
# app-gitops/services/sampleapi/overlays/production/patch-deployment.yaml (excerpt)
spec:
  replicas: 3                           # <-- 3 pods for HA
  template:
    spec:
      affinity:
        podAntiAffinity:                # <-- spread across nodes
          preferredDuringSchedulingIgnoredDuringExecution:
            - weight: 100
              podAffinityTerm:
                labelSelector:
                  matchLabels:
                    app: sampleapi
                topologyKey: kubernetes.io/hostname
      containers:
        - name: sampleapi
          resources:
            requests:
              cpu: 500m                 # <-- 5x DEV requests
              memory: 512Mi             # <-- 4x DEV requests
            limits:
              cpu: "2"
              memory: 2Gi
```

### Verify: Build the PROD Overlays

```bash
# SampleApi PROD
kustomize build services/sampleapi/overlays/production/
```

Confirm:
- Namespace is `sampleapi-prod`
- Replicas is 3
- Logging is `Warning`
- Swagger is `false`
- A PodDisruptionBudget exists

```bash
kustomize build services/sampleapi/overlays/production/ | \
  grep -E "(replicas:|LOGGING|SWAGGER|PodDisruptionBudget)"
```

```bash
# NotificationApi PROD (2 replicas, its own PDB)
kustomize build services/notificationapi/overlays/production/ | \
  grep -E "(replicas:|LOGGING|PodDisruptionBudget)"
```

If the PROD environment is already deployed, verify the PDBs on the cluster:

```bash
$OC get pdb -n $NS_PROD
# Expected:
# NAME                  MIN AVAILABLE   MAX UNAVAILABLE   ALLOWED DISRUPTIONS   AGE
# notificationapi-pdb   1               N/A               1                     2d
# sampleapi-pdb         1               N/A               2                     2d
```

> **Reading PDB output**: `sampleapi-pdb` has `ALLOWED DISRUPTIONS: 2` because there are 3 replicas and `minAvailable: 1` (so 2 can be disrupted). `notificationapi-pdb` has `ALLOWED DISRUPTIONS: 1` because there are 2 replicas and `minAvailable: 1`.

---

## 6. Step 4: Create Per-Environment Secrets (Three Secrets, No Overlap)

Secrets follow the same per-service pattern as ConfigMaps, but they hold sensitive data -- database passwords, API keys, JWT signing keys. Each namespace has exactly three secrets with no overlap.

### 6.1 Read the SampleApi DEV Secret

Open `app-gitops/services/sampleapi/overlays/dev/secret-env.yaml`:

```yaml
# app-gitops/services/sampleapi/overlays/dev/secret-env.yaml
# DEV environment secrets for SampleApi (Rule 4)
# IMPORTANT: In production, use ExternalSecrets Operator
# to avoid storing plain-text secrets in Git
---
apiVersion: v1
kind: Secret
metadata:
  name: sampleapi-secret         # <-- referenced by SampleApi Deployment's envFrom.secretRef
type: Opaque
stringData:                       # <-- stringData (not data) so you write plain text
  DATABASE_PASSWORD: "<dev-db-password>"
  API_KEY: "<dev-api-key>"
  JWT_SECRET: "<dev-jwt-secret>"
  REDIS_PASSWORD: "<dev-redis-password>"
```

### 6.2 Read the NotificationApi DEV Secret

Open `app-gitops/services/notificationapi/overlays/dev/secret-env.yaml`:

```yaml
# app-gitops/services/notificationapi/overlays/dev/secret-env.yaml
# DEV environment secrets for NotificationApi (Rule 4)
---
apiVersion: v1
kind: Secret
metadata:
  name: notificationapi-secret   # <-- own Secret, NOT sampleapi-secret
type: Opaque
stringData:
  REDIS_PASSWORD: "<dev-redis-password>"
```

NotificationApi only needs `REDIS_PASSWORD`. It has no database connection, no API key, no JWT. Per-service isolation means each service carries only the secrets it actually uses.

### 6.3 Read the Infrastructure Secret

Open `app-gitops/infra/overlays/dev/secret-env.yaml`:

```yaml
# app-gitops/infra/overlays/dev/secret-env.yaml
# DEV infrastructure secrets (PostgreSQL + Redis only)
---
apiVersion: v1
kind: Secret
metadata:
  name: infra-secret             # <-- consumed by PostgreSQL + Redis StatefulSets
type: Opaque
stringData:
  POSTGRESQL_USER: "sampleapi"
  POSTGRESQL_PASSWORD: "<dev-db-password>"
  POSTGRESQL_DATABASE: "sampleapi"
  REDIS_PASSWORD: "<dev-redis-password>"
```

> **Why three separate secrets?** Each secret is managed by its own ArgoCD application. Rotating the database password requires updating `sampleapi-secret` (for the app) and `infra-secret` (for PostgreSQL), then syncing only those two ArgoCD apps. NotificationApi is completely unaffected -- its ArgoCD app does not even see the change.

### 6.4 The Three-Secret Model Visualized

```
sampleapi-dev namespace
+---------------------------------------------------------------------+
|                                                                     |
|  infra-secret              sampleapi-secret    notificationapi-     |
|  (ArgoCD: infra-dev)       (ArgoCD:            secret               |
|                             sampleapi-dev)     (ArgoCD:             |
|  POSTGRESQL_USER           DATABASE_PASSWORD    notificationapi-    |
|  POSTGRESQL_PASSWORD       API_KEY              dev)                |
|  POSTGRESQL_DATABASE       JWT_SECRET                               |
|  REDIS_PASSWORD            REDIS_PASSWORD      REDIS_PASSWORD       |
|       |                        |                    |               |
|       v                        v                    v               |
|  PostgreSQL              SampleApi             NotificationApi      |
|  Redis                   Deployment            Deployment           |
|  StatefulSets                                                       |
+---------------------------------------------------------------------+
```

### 6.5 About Plain-Text Secrets in Git

Storing real passwords in plain-text YAML committed to Git is not acceptable for SIT, UAT, or PROD. Two common solutions:

1. **SealedSecrets (Bitnami)** -- encrypt the Secret with a cluster-side key. The encrypted YAML is safe to commit. The SealedSecrets controller decrypts it at deploy time.

2. **ExternalSecrets Operator** -- references secrets stored in HashiCorp Vault, AWS Secrets Manager, or Azure Key Vault. The operator creates the Kubernetes Secret at runtime. No secret values ever appear in Git.

For DEV, plain-text values are often acceptable during initial development. For anything beyond DEV, use one of the above approaches.

### Verify: Confirm Secrets Render Correctly

```bash
# SampleApi secret
kustomize build services/sampleapi/overlays/dev/ | grep -A 8 "kind: Secret"
# Expected: Secret with name sampleapi-secret, stringData with DATABASE_PASSWORD, API_KEY, etc.

# NotificationApi secret
kustomize build services/notificationapi/overlays/dev/ | grep -A 5 "kind: Secret"
# Expected: Secret with name notificationapi-secret, stringData with REDIS_PASSWORD only

# Infrastructure secret
kustomize build infra/overlays/dev/ | grep -A 7 "kind: Secret"
# Expected: Secret with name infra-secret, stringData with POSTGRESQL_USER, POSTGRESQL_PASSWORD, etc.
```

---

## 7. Step 5: Understand the .NET IOptions Pattern

The application code is the other half of this story. Configuration only works if the app knows how to read it.

### 7.1 The Options Class

Open `app-source/src/SampleApi/Models/WeatherForecastOptions.cs`:

```csharp
// IOptions<T> class for externalized configuration (Rule 3)
// Values come from appsettings.json, overridden by environment variables
// from Kustomize ConfigMap overlays per environment

namespace SampleApi.Models;

public class WeatherForecastOptions
{
    public string TemperatureUnit { get; set; } = "Celsius";   // default if nothing overrides
    public int ForecastDays { get; set; } = 5;
    public int MinTemperature { get; set; } = -20;
    public int MaxTemperature { get; set; } = 55;
    public string Location { get; set; } = "Default";
}
```

Each property maps to a configuration key. The defaults here are the fallback of last resort -- they apply only if neither `appsettings.json` nor environment variables provide a value.

### 7.2 Binding in Program.cs

Open `app-source/src/SampleApi/Program.cs`:

```csharp
// Configuration precedence: appsettings.json < appsettings.{ENV}.json < env vars (injected by ConfigMap via envFrom)

// --- Configuration binding (Rule 3 -- IOptions pattern) ---
builder.Services.Configure<WeatherForecastOptions>(
    builder.Configuration.GetSection("WeatherForecast"));
```

This single line binds the `WeatherForecast` section of configuration to the `WeatherForecastOptions` class. When the ConfigMap sets `WeatherForecast__Location=DEV`, it overrides `WeatherForecast:Location` in `appsettings.json`.

### 7.3 The Double-Underscore Convention

.NET uses `__` (double underscore) to represent hierarchy in environment variable names:

```
JSON path:              WeatherForecast:ForecastDays
Environment variable:   WeatherForecast__ForecastDays
ConfigMap key:          WeatherForecast__ForecastDays    <-- what you put in the YAML
```

For top-level keys like `Logging:LogLevel:Default`:

```
JSON path:              Logging:LogLevel:Default
Environment variable:   LOGGING__LOGLEVEL__DEFAULT       <-- case-insensitive on Linux
ConfigMap key:          LOGGING__LOGLEVEL__DEFAULT
```

> **Why not use colons?** Colons are not valid in environment variable names on Linux. The double-underscore is .NET's cross-platform escape for the colon separator.

### 7.4 Feature Flags via Configuration

Look at how Swagger is controlled in `Program.cs`:

```csharp
// FEATURE_SWAGGER_ENABLED env var controls whether Swagger UI is available
// DEV: true, SIT: true, UAT: false, PROD: false
var swaggerEnabled = builder.Configuration.GetValue<bool>("FEATURE_SWAGGER_ENABLED",
    builder.Environment.IsDevelopment());    // <-- default: true only in Development

if (swaggerEnabled)
{
    builder.Services.AddEndpointsApiExplorer();
    builder.Services.AddSwaggerGen(c =>
    {
        c.SwaggerDoc("v1", new() { Title = "SampleAPI", Version = "v1" });
    });
}
```

This is not `IOptions<T>` -- it reads a flat key directly. But the principle is the same: the ConfigMap sets `FEATURE_SWAGGER_ENABLED: "true"` in DEV and `"false"` in PROD. The application code is identical. The behavior changes purely from config.

### 7.5 How the Controller Uses IOptions

Open `app-source/src/SampleApi/Controllers/WeatherForecastController.cs`:

```csharp
public WeatherForecastController(
    ILogger<WeatherForecastController> logger,
    IOptions<WeatherForecastOptions> options,     // <-- injected by DI
    AppDbContext dbContext,                        // <-- PostgreSQL (Phase 17)
    CacheService cacheService,                    // <-- Redis cache (Phase 17)
    NotificationClient notificationClient)        // <-- inter-service HTTP (Phase 17)
{
    _logger = logger;
    _options = options.Value;                     // <-- resolved at request time
    _dbContext = dbContext;
    _cacheService = cacheService;
    _notificationClient = notificationClient;
}

[HttpGet]
public async Task<IEnumerable<WeatherForecast>> Get()  // <-- async for DB/cache/notify
{
    _logger.LogInformation(
        "Generating {Days}-day forecast for {Location} in {Unit}",
        _options.ForecastDays, _options.Location, _options.TemperatureUnit);

    // 1. Check Redis cache first, 2. Generate if miss,
    // 3. Persist to PostgreSQL, 4. Cache in Redis, 5. Notify
    // (all dependencies are optional -- failures are handled gracefully)
    // ...
```

The controller does not know or care where `Location` or `ForecastDays` came from. It could be `appsettings.json`, an environment variable, or a mounted ConfigMap. The IOptions abstraction hides the source.

### 7.6 Inter-Service Configuration

SampleApi also uses configuration for inter-service communication. The `NOTIFICATION_API_URL` from the ConfigMap tells SampleApi where to find NotificationApi:

```csharp
// Program.cs -- register HttpClient for inter-service calls
var notificationApiUrl = builder.Configuration.GetValue<string>(
    "NOTIFICATION_API_URL") ?? "http://localhost:8081";

builder.Services.AddHttpClient<NotificationClient>(client =>
{
    client.BaseAddress = new Uri(notificationApiUrl);
    client.Timeout = TimeSpan.FromSeconds(5);
});
```

In DEV, the ConfigMap sets `NOTIFICATION_API_URL` to `http://notificationapi.sampleapi-dev.svc:8081`. In PROD, it becomes `http://notificationapi.sampleapi-prod.svc:8081`. The service name (`notificationapi`) stays the same -- only the namespace portion of the DNS name changes per environment.

### 7.7 The Debug Endpoint

The controller also exposes a `/api/WeatherForecast/config` endpoint that returns the current options:

```csharp
[HttpGet("config")]
public ActionResult<WeatherForecastOptions> GetConfig()
{
    return Ok(_options);    // <-- returns the resolved config as JSON
}
```

This is invaluable for verifying that per-env config is working. You will use it in the verification step.

### Verify: Check the Base appsettings.json

```bash
cat app-source/src/SampleApi/appsettings.json
```

Expected:

```json
{
  "Logging": {
    "LogLevel": {
      "Default": "Information",
      "Microsoft.AspNetCore": "Warning"
    }
  },
  "AllowedHosts": "*",
  "WeatherForecast": {
    "TemperatureUnit": "Celsius",
    "ForecastDays": 5,
    "MinTemperature": -20,
    "MaxTemperature": 55,
    "Location": "Default"
  },
  "FEATURE_SWAGGER_ENABLED": true,
  "CORS_ALLOWED_ORIGINS": "*"
}
```

Notice `Location` is `"Default"` and `ForecastDays` is `5`. In DEV, the ConfigMap overrides these to `"DEV"` and `7`. In PROD, they become `"Production"` and `5`. The app code never changes.

---

## 8. Step 6: Verify Config Injection on a Live Cluster

This is where it all comes together. You will confirm that the same image produces different behavior in different namespaces, driven entirely by per-service ConfigMaps.

### 8.1 Check SampleApi DEV Configuration

```bash
# What the SampleApi ConfigMap contains
$OC get configmap sampleapi-env -n $NS_DEV -o jsonpath='{.data}' | jq .

# Expected output (keys that matter):
# {
#   "DATABASE_URL": "Server=postgresql.sampleapi-dev.svc;Database=sampleapi;Port=5432;Username=sampleapi",
#   "REDIS_URL": "redis.sampleapi-dev.svc:6379",
#   "NOTIFICATION_API_URL": "http://notificationapi.sampleapi-dev.svc:8081",
#   "LOGGING__LOGLEVEL__DEFAULT": "Debug",
#   "FEATURE_SWAGGER_ENABLED": "true",
#   "WeatherForecast__Location": "DEV",
#   "WeatherForecast__ForecastDays": "7"
# }
```

```bash
# What the NotificationApi ConfigMap contains
$OC get configmap notificationapi-env -n $NS_DEV -o jsonpath='{.data}' | jq .

# Expected output:
# {
#   "ASPNETCORE_ENVIRONMENT": "Development",
#   "DOTNET_RUNNING_IN_CONTAINER": "true",
#   "REDIS_URL": "redis.sampleapi-dev.svc:6379",
#   "LOGGING__LOGLEVEL__DEFAULT": "Debug",
#   "LOGGING__LOGLEVEL__MICROSOFT_ASPNETCORE": "Information"
# }
```

```bash
# What the app actually reads (WeatherForecast endpoint shows location from ConfigMap)
curl -sk $APP_DEV_URL/api/WeatherForecast | jq '.[0]'

# Expected:
# {
#   "date": "2026-03-11",
#   "temperatureC": -8,
#   "temperatureF": 18,
#   "summary": "Scorching",
#   "location": "DEV",            <-- from ConfigMap, not appsettings.json's "Default"
#   "temperatureUnit": "Celsius"
# }

# The full response returns multiple forecast days:
curl -sk $APP_DEV_URL/api/WeatherForecast
# Expected (abbreviated):
# [
#   {"date":"2026-03-11","temperatureC":-8,"temperatureF":18,"summary":"Scorching","location":"DEV","temperatureUnit":"Celsius"},
#   {"date":"2026-03-12","temperatureC":40,"temperatureF":103,"summary":"Cool","location":"DEV","temperatureUnit":"Celsius"},
#   {"date":"2026-03-13","temperatureC":-4,"temperatureF":25,"summary":"Chilly","location":"DEV","temperatureUnit":"Celsius"}
# ]
```

```bash
# Swagger UI should be available in DEV
curl -sk -o /dev/null -w "%{http_code}" \
  $APP_DEV_URL/swagger/index.html
# Expected: 200
```

### 8.2 Check All Three Secrets Exist (No Overlap)

```bash
# List all secrets in DEV namespace (filter to our three)
for SECRET in sampleapi-secret notificationapi-secret infra-secret; do
  echo "--- $SECRET ---"
  $OC get secret $SECRET -n $NS_DEV -o jsonpath='{.data}' | jq -r 'keys'
done

# Expected:
# --- sampleapi-secret ---
# ["API_KEY", "DATABASE_PASSWORD", "JWT_SECRET", "REDIS_PASSWORD"]
#
# --- notificationapi-secret ---
# ["REDIS_PASSWORD"]
#
# --- infra-secret ---
# ["POSTGRESQL_DATABASE", "POSTGRESQL_PASSWORD", "POSTGRESQL_USER", "REDIS_PASSWORD"]
```

### 8.3 Check PROD Configuration (If Deployed)

```bash
# SampleApi PROD ConfigMap
$OC get configmap sampleapi-env -n $NS_PROD -o jsonpath='{.data}' | jq .
# Expected: LOGGING=Warning, SWAGGER=false, Location=Production

# What the app returns (location comes from PROD ConfigMap)
curl -sk $APP_PROD_URL/api/WeatherForecast | jq '.[0]'
# Expected:
# {
#   "date": "2026-03-11",
#   "temperatureC": ...,
#   "temperatureF": ...,
#   "summary": "...",
#   "location": "Production",     <-- from PROD ConfigMap (not "DEV")
#   "temperatureUnit": "Celsius"
# }

# PROD has 3 sampleapi replicas + 2 notificationapi + completed backup CronJob:
$OC get pods -n $NS_PROD
# Expected:
# NAME                               READY   STATUS      RESTARTS   AGE
# notificationapi-849fbbfd-pkmcb     1/1     Running     1          26h
# notificationapi-849fbbfd-pww2l     1/1     Running     1          28h
# postgresql-0                       1/1     Running     2          47h
# postgresql-backup-29551800-sq2gz   0/1     Completed   0          3h
# redis-0                            1/1     Running     2          47h
# sampleapi-5c9cbb84d5-n945l         1/1     Running     1          28h
# sampleapi-5c9cbb84d5-sk824         1/1     Running     1          28h
# sampleapi-5c9cbb84d5-x5mhn         1/1     Running     1          26h

# Swagger UI should NOT be available in PROD
curl -sk -o /dev/null -w "%{http_code}" \
  $APP_PROD_URL/swagger/index.html
# Expected: 404
```

### 8.4 Check That Environment Variables Are Injected into Each Pod

```bash
# SampleApi pod
$OC exec -n $NS_DEV deployment/sampleapi -- env | sort | \
  grep -E "(LOGGING|FEATURE|WeatherForecast|DATABASE_URL|REDIS_URL|NOTIFICATION|ASPNETCORE)"

# Expected:
# ASPNETCORE_ENVIRONMENT=Development
# DATABASE_URL=Server=postgresql.sampleapi-dev.svc;Database=sampleapi;Port=5432;Username=sampleapi
# FEATURE_SWAGGER_ENABLED=true
# LOGGING__LOGLEVEL__DEFAULT=Debug
# LOGGING__LOGLEVEL__MICROSOFT_ASPNETCORE=Information
# NOTIFICATION_API_URL=http://notificationapi.sampleapi-dev.svc:8081
# REDIS_URL=redis.sampleapi-dev.svc:6379
# WeatherForecast__ForecastDays=7
# WeatherForecast__Location=DEV
```

```bash
# NotificationApi pod -- should have its OWN config, not SampleApi's
$OC exec -n $NS_DEV deployment/notificationapi -- env | sort | \
  grep -E "(LOGGING|REDIS_URL|ASPNETCORE)"

# Expected:
# ASPNETCORE_ENVIRONMENT=Development
# LOGGING__LOGLEVEL__DEFAULT=Debug
# LOGGING__LOGLEVEL__MICROSOFT_ASPNETCORE=Information
# REDIS_URL=redis.sampleapi-dev.svc:6379

# Confirm NotificationApi does NOT have SampleApi-specific keys
$OC exec -n $NS_DEV deployment/notificationapi -- env | grep DATABASE_URL
# Expected: empty (no output) -- NotificationApi has no database config
```

Every key from each service's ConfigMap is an environment variable inside that service's container -- and only that service's container. The .NET runtime reads them automatically.

---

## 9. Step 7: Change a Config Value and Watch ArgoCD Deliver It

This step demonstrates the full loop: change config in Git, ArgoCD detects the drift, syncs the change, and the app picks up the new value.

### 9.1 Change the Forecast Location in DEV

Edit `app-gitops/services/sampleapi/overlays/dev/configmap-env.yaml` and change one value:

```yaml
  WeatherForecast__Location: "DEV-v2"    # <-- changed from "DEV" to "DEV-v2"
```

### 9.2 Commit and Push

```bash
cd app-gitops/
git add services/sampleapi/overlays/dev/configmap-env.yaml
git commit -m "config: update DEV forecast location to DEV-v2"
git push origin main
```

### 9.3 Wait for ArgoCD to Sync

DEV is configured with auto-sync, so ArgoCD will detect the change within ~3 minutes. You can also force it:

```bash
argocd app sync sampleapi-dev \
  --server openshift-gitops-server.openshift-gitops.svc:443 \
  --insecure --grpc-web
argocd app wait sampleapi-dev --health --timeout 120 \
  --server openshift-gitops-server.openshift-gitops.svc:443 \
  --insecure --grpc-web
```

> **Note:** Only `sampleapi-dev` needs to sync. The NotificationApi ArgoCD app (`notificationapi-dev`) is unaffected because the change was in `services/sampleapi/overlays/dev/`, not `services/notificationapi/overlays/dev/`.

### 9.4 Verify the Change

> **Important:** ConfigMap changes do not trigger a pod restart by default. The pod must be restarted for the new environment variables to take effect.

```bash
# Restart the SampleApi pod to pick up the new ConfigMap
$OC rollout restart deployment/sampleapi -n $NS_DEV
$OC rollout status deployment/sampleapi -n $NS_DEV

# Verify the new value
curl -sk $APP_DEV_URL/api/WeatherForecast/config | jq .location
# Expected: "DEV-v2"

# Verify NotificationApi was NOT affected (no restart needed)
$OC get pods -n $NS_DEV -l app=notificationapi -o jsonpath='{.items[0].status.startTime}'
# Expected: timestamp from before your change -- the pod was NOT restarted
```

> **Why is a restart required?** Environment variables are injected when the container starts. Changing a ConfigMap updates the Kubernetes resource, but the running container still has the old values in its process environment. A pod restart creates a new container that reads the updated ConfigMap. For hot-reload without restart, mount the ConfigMap as a volume and use `IOptionsMonitor<T>` instead of `IOptions<T>` -- but that is a more advanced pattern.

---

## 10. Step 8: Promote Configuration Through SIT and UAT

Promotion in GitOps means updating the overlay in a higher environment and syncing. The configuration is already in place for each service -- you just change the image tag.

### 10.1 SIT Overlay Structure (Per-Service)

The SIT overlay is structurally identical to DEV but with different values. Each service has its own SIT overlay:

```yaml
# services/sampleapi/overlays/sit/configmap-env.yaml -- key differences from DEV:
data:
  ASPNETCORE_ENVIRONMENT: "Staging"                    # <-- not Development
  DATABASE_URL: "Server=postgresql.sampleapi-sit.svc;Database=sampleapi;Port=5432;Username=sampleapi"
  REDIS_URL: "redis.sampleapi-sit.svc:6379"
  NOTIFICATION_API_URL: "http://notificationapi.sampleapi-sit.svc:8081"
  LOGGING__LOGLEVEL__DEFAULT: "Information"            # <-- less verbose than Debug
  FEATURE_SWAGGER_ENABLED: "true"                      # <-- still on for testing
  WeatherForecast__Location: "SIT"
  WeatherForecast__ForecastDays: "5"                   # <-- back to standard
  EF_MIGRATE_ON_STARTUP: "false"                       # <-- manual migration (not auto like DEV)
```

```yaml
# services/notificationapi/overlays/sit/configmap-env.yaml
data:
  ASPNETCORE_ENVIRONMENT: "Staging"
  DOTNET_RUNNING_IN_CONTAINER: "true"
  REDIS_URL: "redis.sampleapi-sit.svc:6379"
  LOGGING__LOGLEVEL__DEFAULT: "Information"
  LOGGING__LOGLEVEL__MICROSOFT_ASPNETCORE: "Warning"
```

### 10.2 Promote an Image to SIT

To deploy the same SampleApi image from DEV into SIT, update the image tag in the SampleApi SIT overlay:

```bash
cd app-gitops/

# Update the SampleApi image tag in the SIT overlay
cd services/sampleapi/overlays/sit/
kustomize edit set image \
  sampleapi=image-registry.openshift-image-registry.svc:5000/sampleapi-dev/sampleapi:main-78f28b4
cd ../../../../

git add services/sampleapi/overlays/sit/kustomization.yaml
git commit -m "promote: sampleapi main-78f28b4 to SIT"
git push origin main
```

> **Note:** You are updating only `services/sampleapi/overlays/sit/`. The NotificationApi SIT overlay is untouched. Each service promotes independently.

### 10.3 Sync SIT (Manual)

SIT is configured for manual sync. ArgoCD will show it as `OutOfSync` but will not deploy automatically:

```bash
# Check status -- should show OutOfSync
argocd app get sampleapi-sit \
  --server openshift-gitops-server.openshift-gitops.svc:443 \
  --insecure --grpc-web | grep "Sync Status"

# Manual sync (requires Team Lead approval in a real workflow)
argocd app sync sampleapi-sit \
  --server openshift-gitops-server.openshift-gitops.svc:443 \
  --insecure --grpc-web
argocd app wait sampleapi-sit --health --timeout 120 \
  --server openshift-gitops-server.openshift-gitops.svc:443 \
  --insecure --grpc-web
```

### 10.4 Verify SIT Has Different Config

```bash
curl -sk $APP_SIT_URL/api/WeatherForecast/config | jq .
# Expected: location=SIT, forecastDays=5

# Compare with DEV
curl -sk $APP_DEV_URL/api/WeatherForecast/config | jq .
# Expected: location=DEV-v2, forecastDays=7
```

Same image, same code, different behavior. That is the power of external configuration.

---

## 11. Recap

Here is what you built and why:

```
                         Git commit
                            |
                            v
services/sampleapi/overlays/dev/configmap-env.yaml        ----+
services/sampleapi/overlays/dev/secret-env.yaml           ----|
services/sampleapi/overlays/dev/patch-deployment.yaml     ----|    sampleapi-dev
services/sampleapi/overlays/dev/kustomization.yaml        ----|    ArgoCD app
                                                               |
services/notificationapi/overlays/dev/configmap-env.yaml  ----+
services/notificationapi/overlays/dev/secret-env.yaml     ----|    notificationapi-dev
services/notificationapi/overlays/dev/patch-deployment.yaml---|    ArgoCD app
services/notificationapi/overlays/dev/kustomization.yaml  ----|
                                                               |
infra/overlays/dev/secret-env.yaml                        ----|    infra-dev
infra/overlays/dev/kustomization.yaml                     ----|    ArgoCD app
                                                               |
                                                               v
                                                         kustomize build
                                                         (per service)
                                                               |
                                                               v
                                                       rendered manifests
                                                               |
                                                         ArgoCD sync
                                                         (per service)
                                                               |
                                                               v
                                                    Per-service ConfigMap +
                                                    Per-service Secret +
                                                    Deployment in namespace
                                                               |
                                                         envFrom injection
                                                               |
                                                               v
                                                     .NET reads env vars
                                                     via IOptions<T> pattern
                                                               |
                                                               v
                                                   Each service behaves differently
                                                   per environment -- same image
```

Key principles:

1. **One image, many environments.** The container image is built once and promoted unchanged. Only the configuration varies.

2. **Per-service isolation.** Each service has its own ConfigMap (`sampleapi-env`, `notificationapi-env`), its own Secret (`sampleapi-secret`, `notificationapi-secret`), and its own ArgoCD application. Changing one service's config does not affect the other.

3. **Three secrets, no overlap.** `infra-secret` for databases, `sampleapi-secret` for SampleApi credentials, `notificationapi-secret` for NotificationApi credentials. Each is managed by a separate ArgoCD app.

4. **Config is code.** ConfigMaps and Secrets live in Git alongside the deployment manifests. Changes are reviewed, committed, and auditable.

5. **Kustomize overlays per service, per environment.** Each overlay directory (`services/{svc}/overlays/{env}/`) provides environment-specific data for that service. No duplication of Deployment YAML.

6. **`envFrom` keeps Deployments generic.** Adding a new config key means editing only the ConfigMap overlay. The Deployment YAML does not change.

7. **Double-underscore for hierarchy.** .NET maps `WeatherForecast__Location` to `WeatherForecast:Location` in its configuration tree.

---

## 12. Common Mistakes

### Mistake 1: Sharing a ConfigMap Across Services

```yaml
# WRONG -- both services reading the same ConfigMap
# services/notificationapi/base/deployment.yaml
envFrom:
  - configMapRef:
      name: sampleapi-env         # <-- ERROR: NotificationApi should use its own ConfigMap

# CORRECT -- each service has its own ConfigMap
envFrom:
  - configMapRef:
      name: notificationapi-env   # <-- own ConfigMap with only the keys this service needs
```

**Symptom:** Updating a SampleApi-only setting (like `DATABASE_URL`) triggers a restart of NotificationApi too. Or worse, NotificationApi reads a `DATABASE_URL` it does not use and crashes trying to connect.

### Mistake 2: Using `commonLabels` (Deprecated)

```yaml
# WRONG -- deprecated in modern Kustomize
commonLabels:
  app: sampleapi

# CORRECT -- use the labels transformer
labels:
  - pairs:
      app: sampleapi
```

**Symptom:** Warning during `kustomize build`, or label selectors silently added to immutable fields causing deployment failures.

### Mistake 3: Forgetting `envFrom` in the Deployment

```yaml
# WRONG -- individual env entries, must update Deployment for every new key
env:
  - name: DATABASE_URL
    valueFrom:
      configMapKeyRef:
        name: sampleapi-env
        key: DATABASE_URL

# CORRECT -- all keys automatically become env vars
envFrom:
  - configMapRef:
      name: sampleapi-env
  - secretRef:
      name: sampleapi-secret
```

**Symptom:** Adding a key to the ConfigMap has no effect because the Deployment does not reference it.

### Mistake 4: Expecting Hot-Reload Without Pod Restart

ConfigMap changes via `envFrom` require a pod restart. The environment variables are set at container start time and do not update live.

**Fix:** Run `oc rollout restart deployment/sampleapi -n <namespace>` after the ConfigMap changes.

### Mistake 5: Storing Real Secrets in Plain-Text YAML in Git

For anything beyond DEV, use ExternalSecrets Operator (references secrets from Vault, AWS SM, or Azure KV) or SealedSecrets (encrypted YAML safe to commit).

**Symptom:** Your security team finds database passwords in your Git history.

### Mistake 6: Mismatched ConfigMap/Secret Names Between Overlay and Deployment

The SampleApi Deployment references `name: sampleapi-env`. If your overlay defines a ConfigMap named `sampleapi-config` instead, it will not match and the pod will fail to start (or use stale values). Similarly, the NotificationApi Deployment references `name: notificationapi-env` -- not `sampleapi-env`.

**Fix:** Always verify the name in `envFrom` matches the name in the ConfigMap metadata for that specific service.

### Mistake 7: Putting Infrastructure Secrets in Service Overlays

```yaml
# WRONG -- PostgreSQL credentials in the SampleApi overlay
# services/sampleapi/overlays/dev/secret-env.yaml
stringData:
  POSTGRESQL_USER: "sampleapi"           # <-- this belongs in infra-secret
  POSTGRESQL_PASSWORD: "<password>"      # <-- this belongs in infra-secret
  DATABASE_PASSWORD: "<password>"        # <-- this is correct for sampleapi-secret

# CORRECT -- infrastructure credentials in infra overlay
# infra/overlays/dev/secret-env.yaml
stringData:
  POSTGRESQL_USER: "sampleapi"
  POSTGRESQL_PASSWORD: "<password>"
  POSTGRESQL_DATABASE: "sampleapi"
  REDIS_PASSWORD: "<password>"
```

**Symptom:** Rotating the database password requires updating two overlays and syncing three ArgoCD apps instead of two.

---

## 13. Challenge: Add a New Configuration Value

Try this on your own. Add a `RATE_LIMIT_REQUESTS_PER_MINUTE` configuration value that differs per environment for SampleApi:

| Environment | Value |
|-------------|-------|
| DEV | 1000 (generous for testing) |
| SIT | 100 |
| UAT | 60 |
| PROD | 30 |

Steps:

1. Add the key to each SampleApi overlay's `configmap-env.yaml` (all four environments)
2. Commit and push to Git
3. Sync the DEV environment via ArgoCD: `argocd app sync sampleapi-dev`
4. Restart the pod: `$OC rollout restart deployment/sampleapi -n $NS_DEV`
5. Verify the env var is present in the pod: `$OC exec deployment/sampleapi -n $NS_DEV -- env | grep RATE`
6. Verify NotificationApi is NOT affected: `$OC exec deployment/notificationapi -n $NS_DEV -- env | grep RATE` (should return nothing)
7. (Bonus) Add C# code in `Program.cs` to read this value and apply it using ASP.NET rate-limiting middleware

You should NOT need to change any Deployment YAML at all -- `envFrom` handles it automatically. And you should NOT need to touch the NotificationApi overlays -- this is a SampleApi-only setting.

---

## 14. Self-Assessment

Answer these questions to verify your understanding:

1. **Why does each service have its own ConfigMap (`sampleapi-env`, `notificationapi-env`) instead of sharing one?**
   <details><summary>Answer</summary>Per-service isolation means updating one service's config does not affect other services. Each ConfigMap is managed by its own ArgoCD application, so syncing or rolling back is independent. It also prevents one service from accidentally reading another service's settings (like NotificationApi reading a DATABASE_URL it doesn't use).</details>

2. **What does the double-underscore in `LOGGING__LOGLEVEL__DEFAULT` mean?**
   <details><summary>Answer</summary>It represents hierarchy in .NET configuration. <code>LOGGING__LOGLEVEL__DEFAULT</code> maps to the JSON path <code>Logging:LogLevel:Default</code>. Colons are not valid in Linux environment variable names, so .NET uses <code>__</code> as the separator.</details>

3. **You add a new key to the SampleApi DEV ConfigMap and push to Git. ArgoCD syncs. But the app still shows the old value. What happened?**
   <details><summary>Answer</summary>The pod was not restarted. Environment variables from <code>envFrom</code> are injected at container start time. Run <code>oc rollout restart deployment/sampleapi -n sampleapi-dev</code> to pick up the new values.</details>

4. **There are three secrets in each namespace: `infra-secret`, `sampleapi-secret`, and `notificationapi-secret`. Why not just one?**
   <details><summary>Answer</summary>Each secret is managed by a separate ArgoCD application (<code>infra-dev</code>, <code>sampleapi-dev</code>, <code>notificationapi-dev</code>). Rotating the database password requires updating <code>infra-secret</code> (for PostgreSQL) and <code>sampleapi-secret</code> (for the app), then syncing only those two ArgoCD apps. NotificationApi is completely unaffected. This isolation reduces blast radius and simplifies operations.</details>

5. **How would you verify that PROD has Swagger disabled without deploying to PROD?**
   <details><summary>Answer</summary>Run <code>kustomize build services/sampleapi/overlays/production/</code> and inspect the rendered ConfigMap for <code>FEATURE_SWAGGER_ENABLED: "false"</code>. You can also compare overlays with <code>diff services/sampleapi/overlays/dev/configmap-env.yaml services/sampleapi/overlays/production/configmap-env.yaml</code>.</details>

6. **You need to add a Redis password for NotificationApi in UAT. Where does it go?**
   <details><summary>Answer</summary>It goes in <code>services/notificationapi/overlays/uat/secret-env.yaml</code> under <code>stringData</code> as <code>REDIS_PASSWORD</code>. It does NOT go in <code>services/sampleapi/overlays/uat/secret-env.yaml</code> (that is SampleApi's secret) or <code>infra/overlays/uat/secret-env.yaml</code> (that is for PostgreSQL and Redis server credentials). For production, use ExternalSecrets Operator instead of plain-text values in Git.</details>

7. **Two developers are working on different services. Developer A updates `services/sampleapi/overlays/dev/configmap-env.yaml` and Developer B updates `services/notificationapi/overlays/dev/configmap-env.yaml`. Will they get a Git merge conflict?**
   <details><summary>Answer</summary>No. Each service's config is in a separate directory and a separate file. They can merge independently without conflicts. This is a key benefit of per-service isolation in the GitOps repository structure.</details>

---

## 15. What Comes Next

In **Module 10: End-to-End Walkthrough**, you will trace a code change through all four pipeline triggers across both services, verify inter-service communication, and see how service-parameterized Jenkins pipelines let both services build and deploy independently through the full promotion chain (DEV → SIT → UAT → PROD).

---

*Module 9 complete. You now understand how one container image per service serves four environments with different behavior, driven entirely by per-service Kustomize ConfigMaps, Secrets, and the .NET IOptions pattern -- with full isolation between services.*
