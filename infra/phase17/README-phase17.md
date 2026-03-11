# Phase 17: NetworkPolicies for PostgreSQL, Redis, NotificationApi + NotificationApi Dockerfile

## What Phase 17 Adds

Phase 17 introduces network segmentation and build configuration for three new
services that will be deployed alongside the existing SampleApi:

- **PostgreSQL** -- relational database used by SampleApi for persistent storage
- **Redis** -- in-memory store used by SampleApi (caching) and NotificationApi (pub/sub)
- **NotificationApi** -- .NET 8.0 microservice that handles notifications, called by SampleApi

### Network Security Design

The existing Phase 1 `default-deny-ingress` policy blocks all ingress by default.
Phase 17 adds granular allow rules following the principle of least privilege:

| Policy | Target Pod | Source Pod(s) | Port |
|--------|-----------|---------------|------|
| allow-app-to-postgresql | postgresql | sampleapi only | 5432 |
| allow-app-to-redis | redis | sampleapi, notificationapi | 6379 |
| allow-app-to-notificationapi | notificationapi | sampleapi only | 8081 |
| deny-postgresql-egress | postgresql | (egress deny, DNS only) | 53 |
| deny-redis-egress | redis | (egress deny, DNS only) | 53 |
| allow-monitoring-newservices | notificationapi, postgresql, redis | monitoring namespace | 8081, 5432, 6379, 9187, 9121 |
| allow-router-to-notificationapi | notificationapi | openshift-ingress | 8081 |

### Traffic Flow

```
Internet --> OCP Router --> sampleapi:8080
                        --> notificationapi:8081 (if Route exists)

sampleapi:8080 --> postgresql:5432  (DB queries)
sampleapi:8080 --> redis:6379       (caching)
sampleapi:8080 --> notificationapi:8081 (send notifications)

notificationapi:8081 --> redis:6379 (pub/sub)
notificationapi:8081 -X-> postgresql:5432 (DENIED -- no DB access needed)

postgresql:5432 -X-> (anything except DNS)
redis:6379      -X-> (anything except DNS)
```

## File Inventory

```
infra/phase17/
├── networkpolicies/
│   ├── allow-app-to-postgresql.yaml        # 4 YAML docs (one per namespace)
│   ├── allow-app-to-redis.yaml             # 4 YAML docs
│   ├── allow-app-to-notification.yaml      # 4 YAML docs
│   ├── deny-db-egress.yaml                 # 8 YAML docs (4 postgresql + 4 redis)
│   ├── allow-monitoring-newservices.yaml    # 4 YAML docs
│   └── allow-router-notification.yaml      # 4 YAML docs
└── README-phase17.md                       # This file

build-config/
└── Dockerfile                              # Single parameterized multi-stage .NET 8.0 build
                                            # Uses --build-arg PROJECT_NAME / SOLUTION_NAME / APP_PORT
                                            # Works for both SampleApi and NotificationApi
```

## Execution Order

All NetworkPolicies are additive and can be applied in any order. The recommended
sequence groups them logically:

```bash
# 1. Apply ingress allow rules (who can talk to whom)
$OC apply -f infra/phase17/networkpolicies/allow-app-to-postgresql.yaml
$OC apply -f infra/phase17/networkpolicies/allow-app-to-redis.yaml
$OC apply -f infra/phase17/networkpolicies/allow-app-to-notification.yaml

# 2. Apply egress deny rules (lock down database pods)
$OC apply -f infra/phase17/networkpolicies/deny-db-egress.yaml

# 3. Apply monitoring and router access
$OC apply -f infra/phase17/networkpolicies/allow-monitoring-newservices.yaml
$OC apply -f infra/phase17/networkpolicies/allow-router-notification.yaml
```

Or apply all at once:

```bash
$OC apply -f infra/phase17/networkpolicies/
```

## Verify Commands

```bash
# List all network policies in each namespace
for NS in sampleapi-dev sampleapi-sit sampleapi-uat sampleapi-prod; do
  echo "=== $NS ==="
  $OC get netpol -n $NS
done

# Verify specific policies exist
$OC get netpol allow-app-to-postgresql -n sampleapi-dev
$OC get netpol allow-app-to-redis -n sampleapi-dev
$OC get netpol allow-app-to-notificationapi -n sampleapi-dev
$OC get netpol deny-postgresql-egress -n sampleapi-dev
$OC get netpol deny-redis-egress -n sampleapi-dev
$OC get netpol allow-monitoring-newservices -n sampleapi-dev
$OC get netpol allow-router-to-notificationapi -n sampleapi-dev

# Verify policy details (inspect ingress/egress rules)
$OC describe netpol allow-app-to-postgresql -n sampleapi-dev
$OC describe netpol deny-postgresql-egress -n sampleapi-dev

# Verify parameterized Dockerfile builds for NotificationApi (from notificationapi-source context)
# podman build -f build-config/Dockerfile --build-arg PROJECT_NAME=NotificationApi --build-arg SOLUTION_NAME=NotificationApi --build-arg APP_PORT=8081 .
```

## Prerequisites

- Phase 1 namespaces and `default-deny-ingress` policies must be applied first
- Pod labels must match: `app: sampleapi`, `app: notificationapi`, `app: postgresql`, `app: redis`
- The NotificationApi project must exist in its own repo `notificationapi-source/` (GitLab project ID=5)
  before building the container image with the parameterized Dockerfile

## Label Conventions

All Phase 17 resources use these labels:

| Label | Value | Purpose |
|-------|-------|---------|
| `team` | `devsecops` | Ownership/grouping |
| `policy` | `allow-app-to-postgresql`, etc. | Policy category for filtering |

Pod selector labels expected on workloads:

| Label | Value | Applied To |
|-------|-------|-----------|
| `app` | `sampleapi` | SampleApi Deployment |
| `app` | `notificationapi` | NotificationApi Deployment |
| `app` | `postgresql` | PostgreSQL StatefulSet |
| `app` | `redis` | Redis StatefulSet |
