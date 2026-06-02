# Module 16B: Distributed Tracing Deep Dive

| | |
|---|---|
| **Track** | Supply Chain & Multi-Language |
| **Duration** | ~75 minutes |
| **Difficulty** | Intermediate |
| **Prerequisites** | Module 13 (Grafana basics), Module 16 (Java services running) |

> **Environment variables:** Before running any commands, source the environment file:
> ```bash
> source ./env.sh
> ```
> This sets `$OC`, `$APPS_DOMAIN`, `$NS_TOOLS`, `$NS_DEV`, `$NS_JAVA_DEV`, `$NS_TEMPO`, `$NS_LOGGING`, `$OTEL_COLLECTOR_ENDPOINT`, `$GRAFANA_URL`, and all other cluster-specific variables used throughout this module. See `env.sh` for the full variable list.

---

## What You'll Learn

By the end of this module you will be able to:

1. Explain why single-service traces are useless and what spans, traces, and baggage actually mean in a 5-service architecture.
2. Deploy the full tracing stack: Tempo Operator, OpenTelemetry Operator, Cluster Observability Operator, TempoStack, and OTel Collector.
3. Instrument .NET services two ways: manual SDK (SampleApi) and auto-instrumentation via Instrumentation CR (NotificationApi).
4. Instrument Java services using the OpenTelemetry Java agent (`-javaagent` in Dockerfile).
5. Explore cross-service traces in Grafana and the OpenShift Console tracing UI plugin.
6. Build a Grafana dashboard with latency heatmap, error rate, span count, and trace search panels.
7. Correlate traces with logs using the trace ID that flows through all 5 services.

---

## Prerequisites

Confirm the following before starting:

```bash
# Module 13 complete — Grafana is running with Prometheus + Loki datasources
$OC get pods -n $NS_TOOLS -l app=grafana
# Expected: 1/1 Running

# Module 16 complete — all 5 services are running
$OC get pods -n $NS_DEV -l app=sampleapi
# Expected: 1 pod Running

$OC get pods -n $NS_DEV -l app=notificationapi
# Expected: 1 pod Running

$OC get pods -n $NS_JAVA_DEV -l app=order-service
# Expected: 1 pod Running

$OC get pods -n $NS_JAVA_DEV -l app=inventory-service
# Expected: 1 pod Running

$OC get pods -n $NS_JAVA_DEV -l app=payment-service
# Expected: 1 pod Running

# Grafana route is accessible
curl -sk -o /dev/null -w "%{http_code}" "$GRAFANA_URL/login"
# Expected: 200
```

If any of these fail, go back to the relevant module and resolve the issue before continuing.

---

## 1. Concepts: Distributed Tracing with 5 Services (10 min)

### The Problem: Single-Service Traces Are Useless

With one service, a "trace" is just a fancy log entry. It tells you the request took 245ms and returned 200. You already knew that from Prometheus metrics.

Distributed tracing becomes valuable when a request crosses service boundaries. Consider what happens when a user places an order in our architecture:

```
User ──► OrderService ──► InventoryService (check stock)
                     └──► PaymentService   (charge card)
                     └──► SampleApi ──► NotificationApi (send confirmation)
```

The user sees "order placed in 1.2 seconds." But WHERE did that time go?

- 50ms in OrderService validating the request?
- 400ms in InventoryService querying the database?
- 600ms in PaymentService calling the payment gateway?
- 150ms in NotificationApi sending an email?

Without distributed tracing, you are blind. You see five sets of logs, five sets of metrics, and zero causal relationship between them. Every troubleshooting session starts with "which service is the problem?" and devolves into finger-pointing.

> **Why this matters:** In a banking or telco environment with 20+ microservices, a single user complaint ("my transfer is slow") requires tracing one request through multiple services, databases, and message queues. Distributed tracing makes that path visible as a single waterfall diagram, turning hours of log correlation into seconds of visual inspection.

### Traces, Spans, and Context Propagation

Three concepts drive everything in this module:

| Concept | What It Is | Analogy |
|---------|-----------|---------|
| **Trace** | The full journey of one request across all services | A package tracking number that follows a parcel from warehouse to doorstep |
| **Span** | One unit of work within a trace (one HTTP call, one DB query, one function) | One leg of the journey: warehouse → truck → sorting facility → delivery van → doorstep |
| **Context Propagation** | Passing the trace ID from service to service via HTTP headers (`traceparent`) | The tracking label stuck on the parcel — every handler scans it and logs their handling time |

A trace is a tree of spans. The root span is the initial request (user hits OrderService). Each downstream call creates a child span. The parent-child relationship tells you what called what and how long each step took.

```
Trace ID: abc123
├── [root] OrderService.placeOrder          0ms ─────────────────── 1200ms
│   ├── [child] InventoryService.checkStock    50ms ──── 450ms
│   ├── [child] PaymentService.charge          460ms ────────── 1060ms
│   └── [child] SampleApi.notify               1070ms ─── 1190ms
│       └── [child] NotificationApi.send           1080ms ── 1180ms
```

The `traceparent` HTTP header carries the trace ID and parent span ID between services. The W3C Trace Context standard defines its format: `00-{traceId}-{parentSpanId}-{flags}`. When OrderService calls InventoryService, it includes this header. InventoryService extracts it, creates a child span, and logs under the same trace ID. No application code changes are needed when using auto-instrumentation or the Java agent -- they intercept HTTP calls and inject/extract the header automatically.

### OpenTelemetry Architecture

OpenTelemetry (OTel) is the CNCF standard for telemetry collection. It has three components in our stack:

```
                        ┌──────────────────────────────────────────────────────────┐
  .NET SampleApi ──┐    │                   OTel Collector                         │
                   │    │  ┌───────────┐   ┌──────────────┐   ┌────────────────┐  │
  .NET Notification│    │  │ Receivers │   │  Processors  │   │   Exporters    │  │
  Api ─────────────┤    │  │           │   │              │   │                │  │
                   ├───►│  │ otlp:4317 ├──►│ batch        ├──►│ otlphttp/dev   │  │
  Java Order ──────┤    │  │ (gRPC)    │   │ k8sattributes│   │ otlphttp/sit   │  │
  Service          │    │  │           │   │ routing      │   │ otlphttp/uat   │  │
                   │    │  └───────────┘   └──────────────┘   │ otlphttp/prod  │  │
  Java Inventory ──┤    │                                     │ otlphttp/java  │  │
  Service          │    │                                     └───────┬────────┘  │
                   │    └─────────────────────────────────────────────┼────────────┘
  Java Payment ────┘                                                 │
  Service                                                            │ per-tenant
                                                                     │ Bearer token
                                                              ┌──────▼───────────┐
                                                              │   TempoStack     │
                                                              │   Gateway (OPA)  │
                                                              │                  │
                                                              │   Tenants:       │
                                                              │   dev, sit, uat, │
                                                              │   prod, javaapp  │
                                                              └──────┬───────────┘
                                                                     │
                                                              ┌──────▼───────────┐
                                                              │   ODF NooBaa S3  │
                                                              │   (trace storage)│
                                                              └──────────────────┘
```

The Collector is the central hub. All 5 services send traces to its gRPC endpoint on port 4317. The Collector batches them, enriches them with Kubernetes metadata (pod name, namespace, node), and routes them to the correct Tempo tenant based on the `k8s.namespace.name` resource attribute. This routing is critical -- without it, traces from all environments would land in a single tenant with no isolation.

### .NET vs Java Instrumentation

Not all instrumentation is the same. This module demonstrates three approaches:

| Service | Language | Method | How It Works | Code Changes? |
|---------|----------|--------|-------------|---------------|
| SampleApi | .NET | Manual SDK | `OpenTelemetry` NuGet packages added to `Program.cs`, explicit `TracerProvider` setup | Yes -- 10 lines in `Program.cs` |
| NotificationApi | .NET | Auto-instrumentation | OTel Operator injects an init container via `Instrumentation` CR + pod annotation | No -- just an annotation |
| OrderService | Java | Agent | `-javaagent:/app/opentelemetry-javaagent.jar` JVM argument in Dockerfile | No code changes, Dockerfile only |
| InventoryService | Java | Agent | Same agent approach | No code changes, Dockerfile only |
| PaymentService | Java | Agent | Same agent approach | No code changes, Dockerfile only |

> **Why this matters:** In real enterprises, you rarely get to modify application code. The Java agent and .NET auto-instrumentation approaches let you add tracing to existing services without touching a single line of business logic. The manual SDK approach gives finer control (custom spans, attributes, events) but requires developer buy-in.

---

## 2. Deploy the Tracing Stack (15 min)

This section deploys six components in order. Each depends on the one before it, so follow the sequence exactly.

```
Step 2.1: Install 3 operators     (Tempo, OTel, Cluster Observability)
Step 2.2: Create Tempo S3 secret  (reformatted from OBC)
Step 2.3: Deploy TempoStack       (multi-tenant trace storage)
Step 2.4: Configure RBAC          (write for Collector, read for Grafana/Console)
Step 2.5: Deploy OTel Collector   (receives traces, routes to tenants)
Step 2.6: Enable Console UI       (Observe > Traces in OCP Console)
```

### Step 2.1: Install the Three Operators

**Why:** Without these operators, the `TempoStack`, `OpenTelemetryCollector`, and `UIPlugin` CRDs do not exist on the cluster. Everything else depends on them.

We need three operators from Red Hat's OperatorHub:

| Operator | What It Manages | Namespace |
|----------|----------------|-----------|
| Tempo Operator | `TempoStack` CRs (trace storage backend) | `openshift-tempo-operator` |
| Red Hat build of OpenTelemetry | `OpenTelemetryCollector` and `Instrumentation` CRs | `openshift-opentelemetry-operator` |
| Cluster Observability Operator | `UIPlugin` CRs (Console tracing UI) | `openshift-observability-operator` |

Create the operator subscriptions:

```yaml
# file: infra/phase22/tempo-operator-subscription.yaml
# -----------------------------------------------------
# Installs the Tempo Operator from Red Hat's OperatorHub.
# The operator watches all namespaces for TempoStack CRs.
apiVersion: operators.coreos.com/v1alpha1
kind: Subscription
metadata:
  name: tempo-product
  namespace: openshift-tempo-operator          # ← operator namespace (auto-created by OLM)
spec:
  channel: stable
  installPlanApproval: Automatic
  name: tempo-product                          # ← Red Hat's product name in OperatorHub
  source: redhat-operators                     # ← Red Hat catalog (not community)
  sourceNamespace: openshift-marketplace
```

```yaml
# file: infra/phase22/otel-operator-subscription.yaml
# -----------------------------------------------------
# Installs the Red Hat build of OpenTelemetry.
# Provides: OpenTelemetryCollector, Instrumentation CRDs.
apiVersion: operators.coreos.com/v1alpha1
kind: Subscription
metadata:
  name: opentelemetry-product
  namespace: openshift-opentelemetry-operator  # ← operator namespace
spec:
  channel: stable
  installPlanApproval: Automatic
  name: opentelemetry-product                  # ← Red Hat's product name
  source: redhat-operators
  sourceNamespace: openshift-marketplace
```

```yaml
# file: infra/phase22/cluster-observability-operator.yaml
# --------------------------------------------------------
# Installs the Cluster Observability Operator.
# Provides: UIPlugin CRD (adds Traces tab to OCP Console).
# NOTE: This operator requires its own Namespace + OperatorGroup.
apiVersion: v1
kind: Namespace
metadata:
  name: openshift-observability-operator       # ← must exist before Subscription
---
apiVersion: operators.coreos.com/v1
kind: OperatorGroup
metadata:
  name: observability-operator-group
  namespace: openshift-observability-operator
spec:
  upgradeStrategy: Default
---
apiVersion: operators.coreos.com/v1alpha1
kind: Subscription
metadata:
  name: cluster-observability-operator
  namespace: openshift-observability-operator
spec:
  channel: stable
  installPlanApproval: Automatic
  name: cluster-observability-operator
  source: redhat-operators
  sourceNamespace: openshift-marketplace
```

Apply all three:

```bash
# Install Tempo Operator
$OC apply -f infra/phase22/tempo-operator-subscription.yaml

# Install OpenTelemetry Operator
$OC apply -f infra/phase22/otel-operator-subscription.yaml

# Install Cluster Observability Operator (includes Namespace + OperatorGroup)
$OC apply -f infra/phase22/cluster-observability-operator.yaml

# Wait for all three operators to reach Succeeded
echo "Waiting for Tempo Operator..."
while ! $OC get csv -n openshift-tempo-operator 2>/dev/null | grep -q Succeeded; do
  sleep 10; echo "  waiting..."
done
echo "Tempo Operator: Succeeded"

echo "Waiting for OTel Operator..."
while ! $OC get csv -n openshift-opentelemetry-operator 2>/dev/null | grep -q Succeeded; do
  sleep 10; echo "  waiting..."
done
echo "OTel Operator: Succeeded"

echo "Waiting for Cluster Observability Operator..."
while ! $OC get csv -n openshift-observability-operator 2>/dev/null | grep -q Succeeded; do
  sleep 10; echo "  waiting..."
done
echo "Cluster Observability Operator: Succeeded"

echo "All three operators installed"
```

#### Verify

```bash
# Confirm all three CSVs show Succeeded
$OC get csv -n openshift-tempo-operator | grep tempo
$OC get csv -n openshift-opentelemetry-operator | grep opentelemetry
$OC get csv -n openshift-observability-operator | grep observability
# Expected: all three show Succeeded in the PHASE column

# Confirm CRDs are registered
$OC get crd | grep -E 'tempostack|opentelemetrycollector|instrumentations|uiplugin'
# Expected:
#   instrumentations.opentelemetry.io
#   opentelemetrycollectors.opentelemetry.io
#   tempostacks.tempo.grafana.com
#   uiplugins.observability.openshift.io
```

> **Why this matters:** Each CRD corresponds to a capability. Without `tempostacks.tempo.grafana.com`, you cannot create a TempoStack. Without `instrumentations.opentelemetry.io`, you cannot auto-instrument .NET pods. The operators register these CRDs on install. If a CRD is missing, the operator install failed -- check `$OC get csv -A | grep -i fail`.

---

### Step 2.2: Create the Tempo S3 Secret

**Why:** TempoStack stores traces in S3-compatible object storage. On OpenShift with ODF, an ObjectBucketClaim (OBC) provisions a NooBaa bucket. But the OBC-generated Secret has AWS-style field names (`AWS_ACCESS_KEY_ID`), while TempoStack expects different names (`access_key_id`, `access_key_secret`, `bucket`, `endpoint`). We reformat.

First, confirm the OBC is bound:

```bash
$OC get obc tempo-bucket -n $NS_TEMPO
# Expected: STATUS=Bound
# If not Bound, the OBC was not created. Go back to the infrastructure setup (Phase 19).
```

Extract the OBC credentials and create the TempoStack-compatible secret:

```bash
# Extract OBC-generated credentials
BUCKET_NAME=$($OC get configmap tempo-bucket -n $NS_TEMPO \
  -o jsonpath='{.data.BUCKET_NAME}')                       # ← bucket name from ConfigMap
BUCKET_HOST=$($OC get configmap tempo-bucket -n $NS_TEMPO \
  -o jsonpath='{.data.BUCKET_HOST}')                       # ← S3 endpoint host
BUCKET_PORT=$($OC get configmap tempo-bucket -n $NS_TEMPO \
  -o jsonpath='{.data.BUCKET_PORT}')                       # ← S3 endpoint port
ACCESS_KEY=$($OC get secret tempo-bucket -n $NS_TEMPO \
  -o jsonpath='{.data.AWS_ACCESS_KEY_ID}' | base64 -d)     # ← S3 access key
SECRET_KEY=$($OC get secret tempo-bucket -n $NS_TEMPO \
  -o jsonpath='{.data.AWS_SECRET_ACCESS_KEY}' | base64 -d) # ← S3 secret key

# Create reformatted secret with names TempoStack expects
$OC create secret generic tempo-s3 -n $NS_TEMPO \
  --from-literal=access_key_id="$ACCESS_KEY" \
  --from-literal=access_key_secret="$SECRET_KEY" \
  --from-literal=bucket="$BUCKET_NAME" \
  --from-literal=endpoint="https://${BUCKET_HOST}:${BUCKET_PORT}"

echo "Bucket: $BUCKET_NAME"
echo "Endpoint: https://${BUCKET_HOST}:${BUCKET_PORT}"
```

NooBaa uses a self-signed TLS certificate. TempoStack needs to trust it:

```bash
# Create a CA bundle ConfigMap — OpenShift auto-injects the service-CA cert
# when the annotation is present. This lets TempoStack validate NooBaa's TLS.
$OC create configmap tempo-ca-bundle -n $NS_TEMPO
$OC annotate configmap tempo-ca-bundle -n $NS_TEMPO \
  service.beta.openshift.io/inject-cabundle=true           # ← THIS IS KEY
```

#### Verify

```bash
# Confirm the secret has the right keys
$OC get secret tempo-s3 -n $NS_TEMPO -o jsonpath='{.data}' | jq -r 'keys'
# Expected: ["access_key_id","access_key_secret","bucket","endpoint"]

# Confirm CA bundle was injected
$OC get configmap tempo-ca-bundle -n $NS_TEMPO -o jsonpath='{.data}' | head -c 60
# Expected: starts with "-----BEGIN CERTIFICATE-----"
```

---

### Step 2.3: Deploy TempoStack

**Why:** TempoStack is the trace storage and query backend. It runs in multi-tenant mode with `openshift` tenancy, meaning each tenant (dev, sit, uat, prod, javaapp) has isolated trace storage. The gateway performs SubjectAccessReview authorization -- this is what both Grafana and the OpenShift Console tracing UI require to query traces.

```yaml
# file: infra/phase22/tempostack.yaml
# -------------------------------------
# TempoStack CR — multi-tenant distributed tracing backend.
#
# Key design decisions:
#   - openshift tenancy mode: gateway uses OCP SubjectAccessReview for auth
#   - 5 tenants: one per environment + one for Java services
#   - S3 storage via ODF NooBaa (tempo-s3 secret)
#   - 48h retention: traces older than 48h are automatically deleted
#   - CA bundle: trusts NooBaa's self-signed TLS certificate
#   - jaegerQuery: enables Jaeger-compatible API for Grafana Tempo datasource
apiVersion: tempo.grafana.com/v1alpha1
kind: TempoStack
metadata:
  name: tempostack
  namespace: openshift-tempo                   # ← all Tempo components live here
spec:
  storage:
    secret:
      name: tempo-s3                           # ← the S3 secret from Step 2.2
      type: s3                                 # ← storage backend type
    tls:
      caName: tempo-ca-bundle                  # ← CA for NooBaa TLS verification
  template:
    queryFrontend:
      jaegerQuery:
        enabled: true                          # ← THIS IS KEY — enables Jaeger API
        ingress:
          type: route                          # ← creates an OpenShift Route for external access
    gateway:
      enabled: true                            # ← gateway handles multi-tenant auth
  tenants:
    mode: openshift                            # ← THIS IS KEY — uses OCP RBAC for tenant auth
    authentication:
      - tenantName: dev                        # ← tenant for sampleapi-dev namespace
        tenantId: dev
      - tenantName: sit                        # ← tenant for sampleapi-sit namespace
        tenantId: sit
      - tenantName: uat                        # ← tenant for sampleapi-uat namespace
        tenantId: uat
      - tenantName: prod                       # ← tenant for sampleapi-prod namespace
        tenantId: prod
      - tenantName: javaapp                    # ← tenant for all javaapp-* namespaces
        tenantId: javaapp
  retention:
    global:
      traces: 48h                              # ← auto-delete traces older than 48 hours
```

Apply and wait:

```bash
$OC apply -f infra/phase22/tempostack.yaml

echo "Waiting for TempoStack pods (takes 1-2 minutes)..."
sleep 30
$OC get pods -n $NS_TEMPO -l app.kubernetes.io/managed-by=tempo-operator
```

Expected output (6-8 pods after 1-2 minutes):

```
NAME                                     READY   STATUS    RESTARTS   AGE
tempo-tempostack-compactor-0             1/1     Running   0          90s
tempo-tempostack-distributor-xxx-yyy     1/1     Running   0          90s
tempo-tempostack-gateway-xxx-yyy         2/2     Running   0          90s    # ← 2/2 = gateway + OPA sidecar
tempo-tempostack-ingester-0              1/1     Running   0          90s
tempo-tempostack-querier-xxx-yyy         1/1     Running   0          90s
tempo-tempostack-query-frontend-xxx-yyy  1/1     Running   0          90s
```

> **Why this matters:** The gateway pod shows `2/2` because it runs two containers: the gateway itself and an OPA (Open Policy Agent) sidecar that evaluates tenant authorization. If the OPA sidecar crashes with "missing tenant mappings," check that your `tenants.authentication[]` entries are not empty.

#### Verify

```bash
$OC get tempostack tempostack -n $NS_TEMPO -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}'
# Expected: True

# Confirm the gateway route was created (for external Jaeger UI access)
$OC get route -n $NS_TEMPO -l app.kubernetes.io/component=gateway
# Expected: a route like tempo-tempostack-gateway
```

---

### Step 2.4: Configure RBAC for Tempo Gateway

**Why:** The gateway uses the `tempo.grafana.com` API group for authorization. Without RBAC, neither the OTel Collector can write traces nor Grafana/Console can read them. Two ClusterRoles are needed:

| ClusterRole | Who Uses It | Permission |
|-------------|------------|------------|
| `tempostack-traces-write` | OTel Collector ServiceAccount | `create` on `traces` resource |
| `tempostack-traces-read` | Grafana SA, Console users, developers | `get` on `traces` resource |

```yaml
# file: infra/phase22/tempo-tenant-rbac.yaml
# --------------------------------------------
# RBAC for the TempoStack gateway.
# The gateway performs SubjectAccessReview checks against these roles.
# Without them:
#   - Collector gets 403 when writing traces
#   - Grafana gets empty results when querying traces
#   - Console shows "Forbidden" in the Traces tab

# -- ClusterRole: Write traces (for OTel Collector) --
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: tempostack-traces-write
rules:
  - apiGroups: ["tempo.grafana.com"]
    resources: ["dev", "sit", "uat", "prod", "javaapp"]  # ← one resource per tenant
    resourceNames: ["traces"]
    verbs: ["create"]                                      # ← THIS IS KEY — write permission
---
# -- ClusterRoleBinding: Collector SA can write --
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: tempostack-traces-write-collector
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: tempostack-traces-write
subjects:
  - kind: ServiceAccount
    name: otel-collector-collector             # ← SA auto-created by OTel Operator
    namespace: openshift-tempo
---
# -- ClusterRole: Read traces (for Grafana + Console) --
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: tempostack-traces-read
rules:
  - apiGroups: ["tempo.grafana.com"]
    resources: ["dev", "sit", "uat", "prod", "javaapp"]
    resourceNames: ["traces"]
    verbs: ["get"]                                         # ← read permission
---
# -- ClusterRoleBinding: Grafana SA can read --
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: tempostack-traces-read-grafana
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: tempostack-traces-read
subjects:
  - kind: ServiceAccount
    name: grafana-sa                           # ← same SA used for Prometheus datasource
    namespace: devsecops-tools
---
# -- ClusterRoleBinding: Authenticated users can read (for Console UI) --
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: tempostack-traces-read-users
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: tempostack-traces-read
subjects:
  - apiGroup: rbac.authorization.k8s.io
    kind: Group
    name: system:authenticated                 # ← all logged-in OCP users can read traces
```

Apply:

```bash
$OC apply -f infra/phase22/tempo-tenant-rbac.yaml
```

#### Verify

```bash
# Grafana can read traces
$OC auth can-i get traces \
  --as=system:serviceaccount:devsecops-tools:grafana-sa \
  --subresource="" --api-group=tempo.grafana.com
# Expected: yes

# Collector can write traces (SA may not exist yet — that is OK)
$OC auth can-i create traces \
  --as=system:serviceaccount:openshift-tempo:otel-collector-collector \
  --subresource="" --api-group=tempo.grafana.com 2>/dev/null && echo "yes" || echo "SA not yet created (OK — created in next step)"
```

---

### Step 2.5: Deploy the OTel Collector

**Why:** The Collector is the central hub of the tracing pipeline. All 5 services send OTLP traces to its gRPC receiver on port 4317. The Collector batches spans, enriches them with Kubernetes metadata (pod name, namespace, node), and routes them to the correct Tempo tenant based on the `k8s.namespace.name` resource attribute. Without the Collector, traces have nowhere to go.

The routing logic maps namespaces to tenants:

| Namespace | Tenant | Gateway Endpoint |
|-----------|--------|-----------------|
| `sampleapi-dev` | dev | `.../api/traces/v1/dev/tempo` |
| `sampleapi-sit` | sit | `.../api/traces/v1/sit/tempo` |
| `sampleapi-uat` | uat | `.../api/traces/v1/uat/tempo` |
| `sampleapi-prod` | prod | `.../api/traces/v1/prod/tempo` |
| `javaapp-dev` | javaapp | `.../api/traces/v1/javaapp/tempo` |
| `javaapp-sit` | javaapp | `.../api/traces/v1/javaapp/tempo` |
| `javaapp-uat` | javaapp | `.../api/traces/v1/javaapp/tempo` |
| `javaapp-prod` | javaapp | `.../api/traces/v1/javaapp/tempo` |

```yaml
# file: infra/phase22/otel-collector.yaml
# -----------------------------------------
# OpenTelemetryCollector CR — central trace collection and routing.
#
# Architecture:
#   Receivers:  OTLP gRPC on port 4317 (all apps send here)
#   Processors: batch (performance), k8sattributes (enrich), routing (per-tenant)
#   Exporters:  One OTLP HTTP exporter per tenant → TempoStack gateway
#
# WHY OTLP HTTP (not gRPC) to the gateway:
#   The gateway requires Bearer token auth. gRPC with Bearer tokens causes
#   "credentials require transport level security" errors unless mutual TLS
#   is configured. OTLP HTTP with an Authorization header avoids this.
apiVersion: opentelemetry.io/v1beta1
kind: OpenTelemetryCollector
metadata:
  name: otel-collector
  namespace: openshift-tempo                   # ← lives alongside TempoStack
spec:
  mode: deployment                             # ← single replica (use daemonset for production scale)
  observability:
    metrics:
      enableMetrics: true                      # ← expose collector health metrics
  config:
    receivers:
      otlp:
        protocols:
          grpc:
            endpoint: 0.0.0.0:4317             # ← THIS IS KEY — apps send traces here
          http:
            endpoint: 0.0.0.0:4318             # ← alternative HTTP receiver

    processors:
      batch:
        send_batch_size: 10000                 # ← batch for performance
        timeout: 10s

      k8sattributes:                           # ← enrich spans with k8s metadata
        extract:
          metadata:
            - k8s.namespace.name               # ← THIS IS KEY — used for tenant routing
            - k8s.pod.name
            - k8s.deployment.name
            - k8s.node.name
        passthrough: false
        pod_association:
          - sources:
              - from: resource_attribute
                name: k8s.pod.ip
          - sources:
              - from: connection

      routing:                                 # ← route traces to per-tenant exporters
        default_exporters:
          - otlphttp/dev                       # ← fallback tenant if namespace is unknown
        table:
          # .NET services — per-environment routing
          - statement: route() where resource.attributes["k8s.namespace.name"] == "sampleapi-dev"
            exporters: [otlphttp/dev]
          - statement: route() where resource.attributes["k8s.namespace.name"] == "sampleapi-sit"
            exporters: [otlphttp/sit]
          - statement: route() where resource.attributes["k8s.namespace.name"] == "sampleapi-uat"
            exporters: [otlphttp/uat]
          - statement: route() where resource.attributes["k8s.namespace.name"] == "sampleapi-prod"
            exporters: [otlphttp/prod]
          # Java services — all environments route to javaapp tenant
          - statement: route() where resource.attributes["k8s.namespace.name"] == "javaapp-dev"
            exporters: [otlphttp/javaapp]
          - statement: route() where resource.attributes["k8s.namespace.name"] == "javaapp-sit"
            exporters: [otlphttp/javaapp]
          - statement: route() where resource.attributes["k8s.namespace.name"] == "javaapp-uat"
            exporters: [otlphttp/javaapp]
          - statement: route() where resource.attributes["k8s.namespace.name"] == "javaapp-prod"
            exporters: [otlphttp/javaapp]

    exporters:
      # One exporter per tenant — each targets a different gateway path
      otlphttp/dev:
        endpoint: https://tempo-tempostack-gateway.openshift-tempo.svc:8080/api/traces/v1/dev
        tls:
          insecure: false
          insecure_skip_verify: true           # ← gateway uses internal cert
        headers:
          Authorization: "Bearer <COLLECTOR_SA_TOKEN>"  # ← replaced at deploy time

      otlphttp/sit:
        endpoint: https://tempo-tempostack-gateway.openshift-tempo.svc:8080/api/traces/v1/sit
        tls:
          insecure: false
          insecure_skip_verify: true
        headers:
          Authorization: "Bearer <COLLECTOR_SA_TOKEN>"

      otlphttp/uat:
        endpoint: https://tempo-tempostack-gateway.openshift-tempo.svc:8080/api/traces/v1/uat
        tls:
          insecure: false
          insecure_skip_verify: true
        headers:
          Authorization: "Bearer <COLLECTOR_SA_TOKEN>"

      otlphttp/prod:
        endpoint: https://tempo-tempostack-gateway.openshift-tempo.svc:8080/api/traces/v1/prod
        tls:
          insecure: false
          insecure_skip_verify: true
        headers:
          Authorization: "Bearer <COLLECTOR_SA_TOKEN>"

      otlphttp/javaapp:
        endpoint: https://tempo-tempostack-gateway.openshift-tempo.svc:8080/api/traces/v1/javaapp
        tls:
          insecure: false
          insecure_skip_verify: true
        headers:
          Authorization: "Bearer <COLLECTOR_SA_TOKEN>"

    service:
      pipelines:
        traces:
          receivers: [otlp]                    # ← receive from apps
          processors: [batch, k8sattributes, routing]  # ← enrich + route
          exporters: [otlphttp/dev, otlphttp/sit, otlphttp/uat, otlphttp/prod, otlphttp/javaapp]
```

Apply the Collector, then inject the Bearer token:

```bash
# Apply the collector CR (starts the pod + creates the SA)
$OC apply -f infra/phase22/otel-collector.yaml

# Wait for the SA to be created by the OTel Operator
sleep 15

# Generate a long-lived token for the Collector SA
COLLECTOR_TOKEN=$($OC create token otel-collector-collector \
  -n $NS_TEMPO --duration=8760h)               # ← 1-year token

# Patch the collector config with the real token
$OC get opentelemetrycollector otel-collector -n $NS_TEMPO -o json | \
  sed "s|<COLLECTOR_SA_TOKEN>|${COLLECTOR_TOKEN}|g" | \
  $OC apply -f -

# Wait for the collector pod to restart with updated config
$OC rollout status deployment/otel-collector-collector \
  -n $NS_TEMPO --timeout=60s
# Expected: deployment "otel-collector-collector" successfully rolled out
```

> **Why this matters:** The `<COLLECTOR_SA_TOKEN>` placeholder pattern is intentional. In a GitOps workflow, the YAML lives in Git without secrets. At deploy time, a script or Sealed Secret replaces the placeholder. Never commit bearer tokens to Git.

#### Verify

```bash
# Collector pod is running
$OC get pods -n $NS_TEMPO -l app.kubernetes.io/name=otel-collector-collector
# Expected: 1 pod, 1/1 Running

# Check collector logs for errors
$OC logs deploy/otel-collector-collector -n $NS_TEMPO --tail=20
# Expected: no "connection refused", "unauthorized", or "403" errors
# Good signs: "Everything is ready" or "Exporter started"

# Confirm the Collector Service exists (apps connect to this)
$OC get svc otel-collector-collector -n $NS_TEMPO
# Expected:
#   NAME                       TYPE        CLUSTER-IP     PORT(S)
#   otel-collector-collector   ClusterIP   172.x.x.x     4317/TCP,4318/TCP
```

> **Quick win checkpoint:** You now have a working tracing backend. The Collector is listening on port 4317, TempoStack is storing traces in S3, and the gateway is enforcing multi-tenant access. The next steps instrument the applications to actually send traces.

---

### Step 2.6: Enable Console Tracing UI

**Why:** OpenShift Console integration gives platform engineers and developers a native "Observe > Traces" tab without needing separate Grafana access. The Cluster Observability Operator provides this via the `UIPlugin` CRD.

```yaml
# file: infra/phase22/uiplugin-distributed-tracing.yaml
# -------------------------------------------------------
# Enables the distributed tracing UI plugin in the OpenShift Console.
# After applying, users see: Observe → Traces in the left sidebar.
# The plugin queries the TempoStack gateway using the logged-in user's token.
apiVersion: observability.openshift.io/v1alpha1
kind: UIPlugin
metadata:
  name: distributed-tracing                    # ← fixed name required by the operator
spec:
  type: DistributedTracing                     # ← THIS IS KEY — enables the Traces tab
  logging:
    lokiStack:
      name: logging-loki                       # ← enables trace-to-log correlation
```

Apply:

```bash
$OC apply -f infra/phase22/uiplugin-distributed-tracing.yaml
```

#### Verify

```bash
$OC get uiplugin distributed-tracing
# Expected: no error conditions in the output

# In the OpenShift Console: Observe → Traces
# Select a tenant (dev/sit/uat/prod/javaapp) to see traces
# (No traces yet — we have not instrumented the apps)
```

> **Why this matters:** The Console UI plugin means developers do not need Grafana credentials to view traces. They log in with their OpenShift credentials and see traces scoped to namespaces they have access to. This is the path of least resistance for adoption.

---

## 3. Instrument .NET Services (15 min)

Now that the tracing backend is running, we configure the .NET services to send traces to the OTel Collector. We demonstrate two approaches side by side to compare their trade-offs.

### Instrumentation Overview

```
SampleApi (.NET)                    NotificationApi (.NET)
┌──────────────────────┐            ┌──────────────────────────────────┐
│ Program.cs:          │            │ Deployment annotation:           │
│   AddOpenTelemetry() │            │   inject-dotnet="true"           │
│   .WithTracing(b => {│            │                                  │
│     b.AddAspNet...   │            │ OTel Operator injects:           │
│     b.AddHttpClient  │            │   init container with .NET agent │
│     b.AddOtlp...     │            │   env vars (OTEL_*)              │
│   })                 │            │   shared volume mount             │
│                      │            │                                  │
│ MANUAL SDK           │            │ AUTO-INSTRUMENTATION             │
│ (full control)       │            │ (zero code changes)              │
└──────────┬───────────┘            └──────────────┬───────────────────┘
           │ OTLP gRPC :4317                       │ OTLP gRPC :4317
           └───────────────┬───────────────────────┘
                           ▼
                    OTel Collector
```

### Step 3.1: Configure OTEL Environment Variables for SampleApi

**Why:** SampleApi uses the manual OpenTelemetry SDK. The SDK reads its configuration from environment variables -- endpoint, protocol, service name, and resource attributes. These live in the ConfigMap that the Kustomize overlay already manages.

Add the OTEL variables to the SampleApi ConfigMap in each environment overlay. Here is the DEV overlay as the example:

```yaml
# file: app-gitops/services/sampleapi/overlays/dev/configmap-env.yaml
# (add these entries to the existing ConfigMap data section)
# -----------------------------------------------------------------
# OTEL configuration for SampleApi — tells the .NET SDK where to send traces.
#
# These variables are read by OpenTelemetry.Extensions.Hosting in Program.cs.
# The SDK auto-discovers them — no code changes needed beyond the initial setup.
apiVersion: v1
kind: ConfigMap
metadata:
  name: sampleapi-env
data:
  # ... existing entries (ASPNETCORE_ENVIRONMENT, etc.) ...

  # ── OpenTelemetry Configuration ──
  OTEL_EXPORTER_OTLP_ENDPOINT: "http://otel-collector-collector.openshift-tempo.svc:4317"  # ← THIS IS KEY — Collector gRPC endpoint
  OTEL_EXPORTER_OTLP_PROTOCOL: "grpc"              # ← must match Collector receiver protocol
  OTEL_SERVICE_NAME: "sampleapi"                    # ← appears as service.name in traces
  OTEL_RESOURCE_ATTRIBUTES: "k8s.namespace.name=sampleapi-dev"  # ← THIS IS KEY — drives tenant routing
  OTEL_TRACES_EXPORTER: "otlp"                      # ← export traces via OTLP
  OTEL_METRICS_EXPORTER: "none"                     # ← disable OTel metrics (we use Prometheus)
  OTEL_LOGS_EXPORTER: "none"                        # ← disable OTel logs (we use Loki)
```

> **Why this matters:** The `OTEL_RESOURCE_ATTRIBUTES` value `k8s.namespace.name=sampleapi-dev` is what the Collector's routing processor uses to decide which Tempo tenant receives these traces. If you set it wrong (or omit it), traces land in the default tenant and you lose environment isolation.

Repeat for SIT, UAT, and PROD overlays, changing only `OTEL_RESOURCE_ATTRIBUTES`:

```bash
# SIT:  OTEL_RESOURCE_ATTRIBUTES: "k8s.namespace.name=sampleapi-sit"
# UAT:  OTEL_RESOURCE_ATTRIBUTES: "k8s.namespace.name=sampleapi-uat"
# PROD: OTEL_RESOURCE_ATTRIBUTES: "k8s.namespace.name=sampleapi-prod"
```

Apply the updated ConfigMap:

```bash
$OC apply -f app-gitops/services/sampleapi/overlays/dev/configmap-env.yaml

# Restart to pick up new env vars
$OC rollout restart deployment sampleapi -n $NS_DEV
$OC rollout status deployment/sampleapi -n $NS_DEV --timeout=60s
# Expected: deployment "sampleapi" successfully rolled out
```

#### Verify SampleApi OTEL Config

```bash
# Confirm the env vars are set in the running pod
$OC exec deploy/sampleapi -n $NS_DEV -- env | grep OTEL
# Expected:
#   OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector-collector.openshift-tempo.svc:4317
#   OTEL_EXPORTER_OTLP_PROTOCOL=grpc
#   OTEL_SERVICE_NAME=sampleapi
#   OTEL_RESOURCE_ATTRIBUTES=k8s.namespace.name=sampleapi-dev
#   OTEL_TRACES_EXPORTER=otlp
#   OTEL_METRICS_EXPORTER=none
#   OTEL_LOGS_EXPORTER=none
```

---

### Step 3.2: Create the Instrumentation CR for NotificationApi Auto-Instrumentation

**Why:** NotificationApi uses auto-instrumentation -- zero code changes. The OTel Operator injects a .NET tracing agent into the pod via an init container. You just create an `Instrumentation` CR and annotate the Deployment.

> **Why this matters:** Auto-instrumentation is the fastest path to traces for existing .NET services. It intercepts HTTP calls, database queries, and gRPC calls automatically. You sacrifice some control (no custom spans or attributes) in exchange for zero developer effort. For most services, this is the right trade-off.

```yaml
# file: infra/phase22/otel-instrumentation.yaml
# -----------------------------------------------
# Instrumentation CR — configures auto-instrumentation for .NET apps.
#
# When a Deployment in this namespace has the annotation:
#   instrumentation.opentelemetry.io/inject-dotnet: "true"
# the OTel Operator automatically:
#   1. Adds an init container that copies the .NET agent to a shared volume
#   2. Sets OTEL_* env vars in the app container
#   3. Sets CORECLR_ENABLE_PROFILING=1 to activate the agent
#
# NOTE: Red Hat's OTel Operator does NOT ship a .NET auto-instrumentation
# image. We use the upstream community image from ghcr.io.
apiVersion: opentelemetry.io/v1alpha1
kind: Instrumentation
metadata:
  name: devsecops-instrumentation
  # namespace: applied per-namespace below      # ← one CR per namespace
spec:
  exporter:
    endpoint: http://otel-collector-collector.openshift-tempo.svc:4317  # ← Collector gRPC
  propagators:
    - tracecontext                             # ← W3C Trace Context (traceparent header)
    - baggage                                  # ← W3C Baggage (propagate custom key-value pairs)
  dotnet:
    image: ghcr.io/open-telemetry/opentelemetry-operator/autoinstrumentation-dotnet:1.9.0  # ← THIS IS KEY
    env:
      - name: OTEL_EXPORTER_OTLP_PROTOCOL
        value: grpc                            # ← .NET default is http/protobuf; override to grpc
      - name: OTEL_TRACES_EXPORTER
        value: otlp
      - name: OTEL_METRICS_EXPORTER
        value: none                            # ← disable — we use Prometheus
      - name: OTEL_LOGS_EXPORTER
        value: none                            # ← disable — we use Loki
```

Apply the Instrumentation CR to each app namespace:

```bash
# Create the Instrumentation CR in each namespace
for NS in $NS_DEV $NS_SIT $NS_UAT $NS_PROD; do
  echo "Creating Instrumentation CR in $NS..."
  $OC apply -f infra/phase22/otel-instrumentation.yaml -n $NS
done
```

Now annotate the NotificationApi Deployment to trigger auto-injection. In a GitOps workflow, add this annotation to the Kustomize overlay patch:

```yaml
# file: app-gitops/services/notificationapi/overlays/dev/patch-deployment.yaml
# (add to the existing deployment patch)
# --------------------------------------------------------------------------
# Annotation that tells the OTel Operator to inject .NET auto-instrumentation.
# The operator watches for this annotation and mutates the pod spec.
apiVersion: apps/v1
kind: Deployment
metadata:
  name: notificationapi
spec:
  template:
    metadata:
      annotations:
        instrumentation.opentelemetry.io/inject-dotnet: "true"  # ← THIS IS KEY
```

Apply and restart:

```bash
# Apply the updated patch (or annotate directly for quick testing)
$OC annotate deployment notificationapi -n $NS_DEV \
  instrumentation.opentelemetry.io/inject-dotnet="true" --overwrite

# Restart to trigger the init container injection
$OC rollout restart deployment notificationapi -n $NS_DEV
$OC rollout status deployment/notificationapi -n $NS_DEV --timeout=90s
# Expected: deployment "notificationapi" successfully rolled out
```

> **Why this matters:** The restart is essential. The OTel Operator's webhook only mutates pods at creation time. Existing pods are not modified. After adding the annotation, you must delete or restart the pods for the init container to be injected.

#### Verify Auto-Instrumentation Injection

```bash
# Check that the init container was injected into NotificationApi
$OC get pod -n $NS_DEV -l app=notificationapi \
  -o jsonpath='{.items[0].spec.initContainers[*].name}'
# Expected: opentelemetry-auto-instrumentation-dotnet

# Confirm the CORECLR profiling env var is set (proves agent is active)
$OC exec deploy/notificationapi -n $NS_DEV -- env | grep CORECLR
# Expected: CORECLR_ENABLE_PROFILING=1

# Check the OTEL env vars injected by the operator
$OC exec deploy/notificationapi -n $NS_DEV -- env | grep OTEL_EXPORTER
# Expected:
#   OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector-collector.openshift-tempo.svc:4317
#   OTEL_EXPORTER_OTLP_PROTOCOL=grpc
```

Also add the OTEL_RESOURCE_ATTRIBUTES to the NotificationApi ConfigMap so the Collector knows which tenant to route to:

```yaml
# file: app-gitops/services/notificationapi/overlays/dev/configmap-env.yaml
# (add to existing ConfigMap)
apiVersion: v1
kind: ConfigMap
metadata:
  name: notificationapi-env
data:
  # ... existing entries ...
  OTEL_SERVICE_NAME: "notificationapi"         # ← distinct service name in traces
  OTEL_RESOURCE_ATTRIBUTES: "k8s.namespace.name=sampleapi-dev"  # ← same namespace = same tenant
```

Apply and restart:

```bash
$OC apply -f app-gitops/services/notificationapi/overlays/dev/configmap-env.yaml
$OC rollout restart deployment notificationapi -n $NS_DEV
```

---

### Step 3.3: Generate Test Traces from .NET Services

**Why:** Before instrumenting the Java services, verify the .NET tracing pipeline works end-to-end. Send requests through SampleApi (which calls NotificationApi) and confirm traces appear in Tempo.

```bash
# Send 15 requests — each creates a trace with SampleApi → NotificationApi spans
APP_DEV_URL="https://${APP_NAME}-${NS_DEV}.${APPS_DOMAIN}"
for i in $(seq 1 15); do
  curl -sk "$APP_DEV_URL/api/WeatherForecast" -o /dev/null -w "Request $i: HTTP %{http_code}\n"
  sleep 1
done
# Expected: 15 lines of "Request N: HTTP 200"
```

#### Verify Traces in Tempo

```bash
# Query the Tempo gateway for traces in the dev tenant
GATEWAY_HOST=$($OC get route -n $NS_TEMPO \
  -l app.kubernetes.io/component=gateway \
  -o jsonpath='{.items[0].spec.host}')
GRAFANA_TOKEN=$($OC create token grafana-sa -n $NS_TOOLS --duration=1h)

curl -sk -H "Authorization: Bearer $GRAFANA_TOKEN" \
  "https://${GATEWAY_HOST}/api/traces/v1/dev/tempo/api/search?limit=5" | jq '.traces | length'
# Expected: a number > 0 (traces found!)
```

If you see `0`, check the Collector logs for errors:

```bash
$OC logs deploy/otel-collector-collector -n $NS_TEMPO --tail=30 | grep -i error
```

Common causes: expired Collector token (re-run `$OC create token`), missing RBAC (re-apply `tempo-tenant-rbac.yaml`), or wrong `OTEL_EXPORTER_OTLP_ENDPOINT` in the ConfigMap.

> **Quick win checkpoint:** If you see traces in the Tempo query response, the .NET tracing pipeline is fully operational: App -> Collector -> Gateway -> Tempo -> S3. The hardest integration work is done.

---

## 4. Instrument Java Services (10 min)

The three Java services (OrderService, InventoryService, PaymentService) use the OpenTelemetry Java agent -- a JAR file that attaches to the JVM and automatically instruments HTTP clients, REST frameworks (Spring Boot), JDBC connections, and more. No application code changes are needed.

### Step 4.1: Java Agent in the Dockerfile

**Why:** The Java agent is a JAR file added to the container image at build time via Dockerfile. At runtime, the JVM loads it via `-javaagent`. This is the standard approach for Java services -- it instruments Spring Boot, JAX-RS, JDBC, and HTTP clients automatically.

Here is the relevant section from the Java service Dockerfile:

```dockerfile
# file: order-service/Dockerfile (relevant section)
# ---------------------------------------------------
# Multi-stage build: Stage 2 — runtime image with OTel agent
FROM registry.access.redhat.com/ubi9/openjdk-17-runtime:1.20

# Copy the built application
COPY --from=build /app/target/*.jar /app/app.jar

# Download the OpenTelemetry Java agent
# The agent auto-instruments Spring Boot, JDBC, HTTP clients, gRPC, etc.
ADD https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.10.0/opentelemetry-javaagent.jar /app/opentelemetry-javaagent.jar

# Set the JAVA_TOOL_OPTIONS to load the agent on JVM startup
# ← THIS IS KEY — the JVM reads this env var before main() runs
ENV JAVA_TOOL_OPTIONS="-javaagent:/app/opentelemetry-javaagent.jar"

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

> **Why this matters:** `JAVA_TOOL_OPTIONS` is a JVM-standard environment variable. Every JVM checks it before `main()` runs and applies the arguments it contains. By setting it in the Dockerfile, the agent activates in every environment (DEV through PROD) without any changes to `ENTRYPOINT` or deployment manifests. If you need to disable the agent in a specific environment, override the env var with an empty value in the ConfigMap.

The same pattern applies to all three Java services. Their Dockerfiles are identical in the agent section.

### Step 4.2: Configure OTEL Environment Variables for Java Services

**Why:** The Java agent reads its configuration from environment variables, just like the .NET SDK. These variables tell it where the Collector is, what service name to report, and which exporters to use.

Add the OTEL variables to each Java service's ConfigMap. Here is OrderService in the `javaapp-dev` namespace:

```yaml
# file: app-gitops/services/order-service/overlays/dev/configmap-env.yaml
# (add to existing ConfigMap)
# -----------------------------------------------------------------------
# OTEL configuration for OrderService — read by the Java agent at startup.
apiVersion: v1
kind: ConfigMap
metadata:
  name: order-service-env
data:
  # ... existing entries (SPRING_PROFILES_ACTIVE, etc.) ...

  # ── OpenTelemetry Java Agent Configuration ──
  OTEL_EXPORTER_OTLP_ENDPOINT: "http://otel-collector-collector.openshift-tempo.svc:4317"  # ← Collector gRPC
  OTEL_EXPORTER_OTLP_PROTOCOL: "grpc"
  OTEL_SERVICE_NAME: "order-service"           # ← appears in trace waterfall
  OTEL_RESOURCE_ATTRIBUTES: "k8s.namespace.name=javaapp-dev"  # ← THIS IS KEY — routes to javaapp tenant
  OTEL_TRACES_EXPORTER: "otlp"
  OTEL_METRICS_EXPORTER: "none"                # ← disable — we use Prometheus/Micrometer
  OTEL_LOGS_EXPORTER: "none"                   # ← disable — we use Loki
  OTEL_JAVAAGENT_EXCLUDE_URLS: "/actuator/health,/actuator/info,/healthz"  # ← THIS IS KEY — exclude health checks from traces
```

> **Why this matters:** `OTEL_JAVAAGENT_EXCLUDE_URLS` is critical. Without it, every 10-second health check from Kubernetes creates a trace. With 3 services and 3 probes each (liveness, readiness, startup), that is 54 traces per minute of pure noise. Excluding health endpoints keeps your trace storage clean and your query results relevant.

Repeat for InventoryService and PaymentService, changing only `OTEL_SERVICE_NAME`:

```bash
# inventory-service: OTEL_SERVICE_NAME: "inventory-service"
# payment-service:   OTEL_SERVICE_NAME: "payment-service"
```

Apply and restart all three Java services:

```bash
# Apply ConfigMaps
$OC apply -f app-gitops/services/order-service/overlays/dev/configmap-env.yaml
$OC apply -f app-gitops/services/inventory-service/overlays/dev/configmap-env.yaml
$OC apply -f app-gitops/services/payment-service/overlays/dev/configmap-env.yaml

# Restart all three to pick up new env vars
for SVC in order-service inventory-service payment-service; do
  $OC rollout restart deployment $SVC -n $NS_JAVA_DEV
done

# Wait for all to be ready
for SVC in order-service inventory-service payment-service; do
  $OC rollout status deployment/$SVC -n $NS_JAVA_DEV --timeout=90s
done
# Expected: all three show "successfully rolled out"
```

#### Verify Java Agent Activation

```bash
# Check that the Java agent is loaded (appears in JVM startup logs)
$OC logs deploy/order-service -n $NS_JAVA_DEV --tail=30 | grep -i "opentelemetry"
# Expected: "[otel.javaagent] ... opentelemetry-javaagent - version: 2.10.0"

# Confirm OTEL env vars
$OC exec deploy/order-service -n $NS_JAVA_DEV -- env | grep OTEL
# Expected:
#   OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector-collector.openshift-tempo.svc:4317
#   OTEL_SERVICE_NAME=order-service
#   OTEL_RESOURCE_ATTRIBUTES=k8s.namespace.name=javaapp-dev
#   OTEL_JAVAAGENT_EXCLUDE_URLS=/actuator/health,/actuator/info,/healthz

# Verify agent excludes health endpoints (send a health check, should NOT create a trace)
curl -sk "$ORDER_SVC_DEV_URL/actuator/health"
# This should NOT create a trace (excluded by OTEL_JAVAAGENT_EXCLUDE_URLS)
```

### Step 4.3: Generate Cross-Service Traces

**Why:** The real value of distributed tracing is seeing a request flow across multiple services. OrderService calls InventoryService and PaymentService, creating a multi-span trace.

```bash
# Send requests through the Java service call chain
# OrderService → InventoryService (check stock) + PaymentService (charge)
for i in $(seq 1 10); do
  curl -sk "$ORDER_SVC_DEV_URL/api/orders" \
    -H "Content-Type: application/json" \
    -d '{"item":"widget","quantity":1}' \
    -o /dev/null -w "Order $i: HTTP %{http_code}\n"
  sleep 1
done
# Expected: 10 lines of "Order N: HTTP 200" (or 201)
```

#### Verify Java Traces in Tempo

```bash
# Query the javaapp tenant for traces
curl -sk -H "Authorization: Bearer $GRAFANA_TOKEN" \
  "https://${GATEWAY_HOST}/api/traces/v1/javaapp/tempo/api/search?limit=5&service.name=order-service" | jq '.traces | length'
# Expected: a number > 0

# Check that traces have multiple spans (cross-service)
TRACE_ID=$(curl -sk -H "Authorization: Bearer $GRAFANA_TOKEN" \
  "https://${GATEWAY_HOST}/api/traces/v1/javaapp/tempo/api/search?limit=1&service.name=order-service" | jq -r '.traces[0].traceID')

curl -sk -H "Authorization: Bearer $GRAFANA_TOKEN" \
  "https://${GATEWAY_HOST}/api/traces/v1/javaapp/tempo/api/traces/${TRACE_ID}" | jq '.batches | length'
# Expected: 3 (one batch per service: order, inventory, payment)
```

> **Quick win checkpoint:** If the trace has 3 batches, you have working cross-service distributed tracing across all Java services. Combined with the .NET traces from Step 3, all 5 services in your architecture are now instrumented.

---

## 5. Explore Traces in Grafana and OCP Console (15 min)

With all 5 services sending traces, it is time to explore them visually. We will use both Grafana and the OpenShift Console to demonstrate different workflows.

### Step 5.1: Add the Tempo Datasource to Grafana

**Why:** Grafana needs a Tempo datasource to query and visualize traces. Module 13 set up Prometheus and Loki datasources. Now we add the third pillar -- traces.

```yaml
# file: infra/phase22/grafana-datasource-tempo.yaml
# ---------------------------------------------------
# Connects Grafana to the TempoStack gateway for distributed trace queries.
#
# Key details:
#   - URL ends with /tempo — Grafana appends /api/search, /api/traces, etc.
#   - X-Scope-OrgID header would select the tenant, but with openshift mode
#     we use the gateway path instead (/api/traces/v1/{tenant}/tempo)
#   - tracesToMetrics links trace spans to Prometheus metrics
#   - nodeGraph enables the service map visualization
#   - tracesToLogs enables jumping from a span to its corresponding log entry
apiVersion: grafana.integreatly.org/v1beta1
kind: GrafanaDatasource
metadata:
  name: tempo-datasource
  namespace: devsecops-tools
spec:
  instanceSelector:
    matchLabels:
      dashboards: "grafana"                    # ← must match Grafana CR label
  datasource:
    name: Tempo                                # ← referenced in dashboard panels
    type: tempo
    uid: tempo                                 # ← fixed UID for dashboard references
    access: proxy                              # ← Grafana backend proxies requests
    # Gateway URL for the DEV tenant (default view).
    # To query other tenants, change "dev" to "sit", "uat", "prod", or "javaapp"
    url: "https://tempo-tempostack-gateway.openshift-tempo.svc:8080/api/traces/v1/dev/tempo"
    jsonData:
      httpHeaderName1: Authorization
      tlsSkipVerify: true                      # ← gateway uses internal cert
      tracesToMetrics:
        datasourceUid: prometheus               # ← THIS IS KEY — link traces to metrics
      tracesToLogs:
        datasourceUid: loki                     # ← link traces to logs by trace ID
        filterByTraceID: true
        filterBySpanID: false
        lokiSearch: true
      nodeGraph:
        enabled: true                          # ← THIS IS KEY — service map visualization
      serviceMap:
        datasourceUid: prometheus               # ← service map uses Prometheus for rates/errors
    secureJsonData:
      httpHeaderValue1: "Bearer ${GRAFANA_TOKEN}"  # ← replace with real token before applying
```

Apply:

```bash
# Get the Grafana SA token
GRAFANA_TOKEN=$($OC create token grafana-sa -n $NS_TOOLS --duration=8760h)

# Substitute and apply
export GRAFANA_TOKEN
envsubst < infra/phase22/grafana-datasource-tempo.yaml | $OC apply -f -
```

#### Verify Datasource

```bash
# Check the datasource CRD
$OC get grafanadatasource tempo-datasource -n $NS_TOOLS
# Expected:
#   NAME                NO MATCHING INSTANCES   LAST RESYNC   AGE
#   tempo-datasource                            39s           1m

# Test via Grafana API
GRAFANA_HOST=$($OC get routes -n $NS_TOOLS -l app=grafana -o jsonpath='{.items[0].spec.host}')
GRAFANA_ADMIN_PASS="<your-grafana-admin-password>"   # ← Replace with your Grafana admin password
curl -sk -u "admin:${GRAFANA_ADMIN_PASS}" \
  "https://${GRAFANA_HOST}/api/datasources" | jq '.[] | select(.type=="tempo") | .name'
# Expected: "Tempo"

# Test the datasource connection via Grafana UI:
# 1. Open Grafana → Connections → Data sources → Tempo
# 2. Click "Save & Test"
# Expected: "Data source connected and target found."
```

> **Why this matters:** If "Save & Test" fails with "Bad Gateway" or "Forbidden," the problem is almost always RBAC. Check that the `grafana-sa` ServiceAccount has the `tempostack-traces-read` ClusterRoleBinding (Step 2.4). The second most common cause is a wrong URL path -- the path must end with `/tempo`.

---

### Step 5.2: Search Traces in Grafana Explore

**Why:** The Explore tab in Grafana is where you search for traces interactively. Unlike dashboards (which show pre-configured views), Explore lets you query ad-hoc.

Generate some fresh traces first:

```bash
# .NET services — SampleApi → NotificationApi
for i in $(seq 1 5); do
  curl -sk "$APP_DEV_URL/api/WeatherForecast" -o /dev/null
  sleep 1
done

# Java services — OrderService → InventoryService + PaymentService
for i in $(seq 1 5); do
  curl -sk "$ORDER_SVC_DEV_URL/api/orders" \
    -H "Content-Type: application/json" \
    -d '{"item":"widget","quantity":1}' -o /dev/null
  sleep 1
done
```

Now open Grafana and follow these steps:

1. Navigate to **Explore** (compass icon in the left sidebar)
2. Select the **Tempo** datasource from the dropdown at the top
3. In the **Search** tab, set:
   - **Service Name**: `sampleapi` (or `order-service` for Java traces)
   - **Span Name**: leave empty (shows all spans)
   - **Min Duration**: leave empty
   - **Max Duration**: leave empty
   - **Limit**: 20
4. Click **Run Query**
5. You should see a list of traces with their durations

Click on any trace to open the **waterfall view**:

```
Waterfall View (what you will see):
───────────────────────────────────────────────────────
 sampleapi                 ████████████████████        245ms
   GET /api/WeatherForecast  ██████████████████        230ms
     HTTP GET notificationapi  ██████████████          180ms
       notificationapi           ████████████          175ms
         POST /api/notifications   ████████            150ms
───────────────────────────────────────────────────────
```

The waterfall shows:
- **Root span** at the top (SampleApi receiving the request)
- **Child spans** indented below (SampleApi calling NotificationApi)
- **Bar length** proportional to duration (longer = slower)
- **Service color coding** (each service gets a distinct color)

> **Why this matters:** In the waterfall above, you can see that NotificationApi took 175ms out of a total 245ms request. That is 71% of the total time. If you were debugging a slow request, you would know immediately where to investigate -- without looking at a single log line.

---

### Step 5.3: Read Cross-Service Traces

**Why:** A cross-service trace tells a complete story. For the Java services, a single order request creates spans in three services.

In Grafana Explore with the Tempo datasource:

1. Set **Service Name**: `order-service`
2. Click **Run Query**
3. Click on a trace with 3+ spans

Expected waterfall for a Java cross-service trace:

```
Waterfall View (OrderService → InventoryService + PaymentService):
────────────────────────────────────────────────────────────────────
 order-service                     ████████████████████████   380ms
   POST /api/orders                  ██████████████████████   370ms
     HTTP GET inventory-service        ████████████           140ms
       inventory-service                 ██████████           130ms
         GET /api/inventory/check          ████████           120ms
     HTTP POST payment-service                 ████████████  190ms
       payment-service                          ██████████   180ms
         POST /api/payments/charge                ████████   170ms
────────────────────────────────────────────────────────────────────
```

Key observations:

- InventoryService and PaymentService spans may overlap if OrderService calls them concurrently (async pattern)
- If they are sequential, PaymentService starts after InventoryService finishes
- The total trace duration tells you the user-perceived latency
- Each span shows its own duration, HTTP method, status code, and service name

---

### Step 5.4: Trace-Log Correlation via Trace ID

**Why:** Traces tell you WHERE time was spent. Logs tell you WHY. Combining them -- clicking a trace span and jumping directly to the log entry for that span -- eliminates the context-switching that kills investigation speed.

The trace ID is automatically injected into structured logs when OpenTelemetry is active. In Grafana:

1. Open a trace in the waterfall view
2. Click on a specific span (e.g., `order-service POST /api/orders`)
3. Look for the **Logs** link/button in the span detail panel
4. If `tracesToLogs` is configured in the datasource (Step 5.1), clicking it opens Explore with a Loki query pre-filled:

```
{kubernetes_namespace_name="javaapp-dev"} | json | trace_id="abc123def456..."
```

This query filters logs to only those entries that belong to this specific trace. Instead of searching through thousands of log lines, you see exactly the 5-10 lines that correspond to this one request.

If the automatic link is not available, you can manually correlate:

```bash
# Get a trace ID from the Tempo search
TRACE_ID=$(curl -sk -H "Authorization: Bearer $GRAFANA_TOKEN" \
  "https://${GATEWAY_HOST}/api/traces/v1/dev/tempo/api/search?limit=1&service.name=sampleapi" | \
  jq -r '.traces[0].traceID')
echo "Trace ID: $TRACE_ID"

# Search logs for this trace ID
# In Grafana Explore → Loki datasource:
# {kubernetes_namespace_name="sampleapi-dev"} |= "<paste trace ID here>"
```

> **Why this matters:** In a production incident with 5 microservices, the investigation workflow becomes: (1) find the slow trace in Grafana, (2) identify the slow span, (3) click to see the logs for that exact span. What used to take 30 minutes of `grep` and timestamp correlation now takes 30 seconds.

---

### Step 5.5: Explore Traces in the OpenShift Console

**Why:** Not everyone has Grafana access. The OpenShift Console tracing UI (enabled by the UIPlugin in Step 2.6) gives developers a native trace exploration experience.

1. Open the **OpenShift Console** in your browser
2. Navigate to **Observe > Traces** (this tab exists because of the UIPlugin)
3. Select a **TempoStack**: `openshift-tempo / tempostack`
4. Select a **Tenant**: `dev` (for .NET services) or `javaapp` (for Java services)
5. Click **Run Query**
6. You should see traces matching the same results as Grafana

The Console UI is simpler than Grafana -- it shows the trace list and waterfall, but does not have the rich dashboard, correlation, or node graph features. It is ideal for quick lookups by developers who need to see "what happened to my request" without leaving the Console.

#### Verify Console Tracing

```bash
# Confirm the UIPlugin is active
$OC get uiplugin distributed-tracing -o jsonpath='{.status.conditions[?(@.type=="Available")].status}'
# Expected: True (or the UIPlugin exists without error)

# Open Console: Observe → Traces → select tempostack → select tenant → Run Query
# Expected: traces appear in the results
```

> **Why this matters:** The Console tracing UI is the lowest barrier to entry. Developers do not need Grafana credentials, do not need to know the Tempo API, and do not need to configure anything. They log in with their OCP credentials and see traces scoped to their namespace permissions.

---

## 6. Build the Traces Dashboard (10 min)

The Explore tab is great for ad-hoc investigation. But for ongoing monitoring, you need a dashboard that shows tracing health at a glance: Are traces flowing? What is the overall latency distribution? Which services have the highest error rate?

### Step 6.1: Apply the Traces Dashboard

**Why:** This dashboard provides a persistent view of distributed tracing health. It answers the questions that Explore cannot: trends over time, cross-service comparisons, and system-wide latency patterns.

The dashboard has four panels:

| Panel | Type | What It Shows |
|-------|------|---------------|
| **Latency Heatmap** | Heatmap | Distribution of trace durations over time -- reveals patterns like "latency spikes every hour" |
| **Error Rate by Service** | Timeseries | Percentage of spans with error status, per service -- pinpoints the service causing errors |
| **Span Count by Service** | Timeseries | Volume of spans per service over time -- shows traffic patterns and detects drops |
| **Trace Search** | Table | Recent traces with service name, duration, and span count -- quick access to specific traces |

```yaml
# file: infra/phase22/grafana-dashboard-traces.yaml
# ---------------------------------------------------
# Distributed Tracing dashboard — visualizes trace health across all 5 services.
#
# This dashboard uses two datasources:
#   - Tempo: for trace search (table panel)
#   - Prometheus: for trace-derived metrics (if span metrics are enabled)
#
# Template variables:
#   $tempo_datasource: selects which Tempo datasource (useful for multi-tenant switching)
#   $service: filters by service name
#
# The dashboard focuses on operational health, not individual trace debugging.
# For debugging, use Grafana Explore → Tempo (Step 5.2).
apiVersion: grafana.integreatly.org/v1beta1
kind: GrafanaDashboard
metadata:
  name: traces-dashboard
  namespace: devsecops-tools
  labels:
    app: grafana
    team: devsecops
spec:
  instanceSelector:
    matchLabels:
      dashboards: "grafana"                    # ← must match Grafana CR label
  json: |
    {
      "annotations": { "list": [] },
      "editable": true,
      "graphTooltip": 1,
      "panels": [
        {
          "collapsed": false,
          "gridPos": { "h": 1, "w": 24, "x": 0, "y": 0 },
          "id": 100,
          "title": "Trace Overview",
          "type": "row"
        },
        {
          "title": "Trace Latency Heatmap",
          "description": "Distribution of trace durations over time. Hot spots indicate latency clusters. Vertical bands mean periodic slow requests.",
          "type": "heatmap",
          "gridPos": { "h": 10, "w": 12, "x": 0, "y": 1 },
          "id": 1,
          "datasource": { "type": "prometheus", "uid": "prometheus" },
          "targets": [
            {
              "expr": "sum(increase(traces_spanmetrics_latency_bucket{service=~\"$service\"}[5m])) by (le)",
              "legendFormat": "{{le}}",
              "format": "heatmap"
            }
          ],
          "fieldConfig": {
            "defaults": {
              "custom": {
                "hideFrom": { "legend": false, "tooltip": false, "viz": false },
                "scaleDistribution": { "type": "log", "log": 2 }
              },
              "color": {
                "mode": "scheme",
                "exponentPositive": 0.5,
                "fill": "dark-orange",
                "scheme": "Oranges",
                "steps": 64
              }
            }
          },
          "options": {
            "calculate": true,
            "yAxis": {
              "unit": "ms",
              "axisPlacement": "left"
            },
            "rowsFrame": {
              "layout": "auto"
            },
            "showValue": "never",
            "tooltip": {
              "show": true,
              "yHistogram": true
            },
            "cellGap": 1
          }
        },
        {
          "title": "Error Rate by Service",
          "description": "Percentage of spans with error=true status. A spike here means a specific service is failing — correlate with the trace search panel below.",
          "type": "timeseries",
          "gridPos": { "h": 10, "w": 12, "x": 12, "y": 1 },
          "id": 2,
          "datasource": { "type": "prometheus", "uid": "prometheus" },
          "targets": [
            {
              "expr": "100 * sum(rate(traces_spanmetrics_calls_total{service=~\"$service\", status_code=\"STATUS_CODE_ERROR\"}[5m])) by (service) / sum(rate(traces_spanmetrics_calls_total{service=~\"$service\"}[5m])) by (service)",
              "legendFormat": "{{service}}"
            }
          ],
          "fieldConfig": {
            "defaults": {
              "unit": "percent",
              "min": 0,
              "max": 100,
              "custom": {
                "drawStyle": "line",
                "fillOpacity": 15,
                "lineWidth": 2,
                "pointSize": 5,
                "showPoints": "auto"
              },
              "thresholds": {
                "mode": "absolute",
                "steps": [
                  { "color": "green", "value": null },
                  { "color": "yellow", "value": 1 },
                  { "color": "red", "value": 5 }
                ]
              }
            }
          }
        },
        {
          "collapsed": false,
          "gridPos": { "h": 1, "w": 24, "x": 0, "y": 11 },
          "id": 101,
          "title": "Span Throughput",
          "type": "row"
        },
        {
          "title": "Span Count by Service",
          "description": "Volume of trace spans per service over time. A sudden drop means a service stopped sending traces (possible crash or misconfiguration). A spike means increased traffic.",
          "type": "timeseries",
          "gridPos": { "h": 8, "w": 12, "x": 0, "y": 12 },
          "id": 3,
          "datasource": { "type": "prometheus", "uid": "prometheus" },
          "targets": [
            {
              "expr": "sum(rate(traces_spanmetrics_calls_total{service=~\"$service\"}[5m])) by (service)",
              "legendFormat": "{{service}}"
            }
          ],
          "fieldConfig": {
            "defaults": {
              "unit": "ops",
              "custom": {
                "drawStyle": "line",
                "fillOpacity": 20,
                "lineWidth": 2,
                "stacking": { "mode": "none" },
                "showPoints": "never"
              }
            }
          }
        },
        {
          "title": "Avg Span Duration by Service",
          "description": "Average span duration per service. Compare services to identify the slowest link in the chain. Spikes indicate latency regressions.",
          "type": "timeseries",
          "gridPos": { "h": 8, "w": 12, "x": 12, "y": 12 },
          "id": 4,
          "datasource": { "type": "prometheus", "uid": "prometheus" },
          "targets": [
            {
              "expr": "sum(rate(traces_spanmetrics_latency_sum{service=~\"$service\"}[5m])) by (service) / sum(rate(traces_spanmetrics_latency_count{service=~\"$service\"}[5m])) by (service)",
              "legendFormat": "{{service}} avg"
            }
          ],
          "fieldConfig": {
            "defaults": {
              "unit": "ms",
              "custom": {
                "drawStyle": "line",
                "fillOpacity": 10,
                "lineWidth": 2
              }
            }
          }
        },
        {
          "collapsed": false,
          "gridPos": { "h": 1, "w": 24, "x": 0, "y": 20 },
          "id": 102,
          "title": "Trace Search",
          "type": "row"
        },
        {
          "title": "Recent Traces",
          "description": "Search and explore recent traces. Click a trace ID to open the waterfall view. Use this panel for quick investigation without switching to Explore.",
          "type": "table",
          "gridPos": { "h": 10, "w": 24, "x": 0, "y": 21 },
          "id": 5,
          "datasource": { "type": "tempo", "uid": "tempo" },
          "targets": [
            {
              "queryType": "search",
              "serviceName": "$service",
              "limit": 20
            }
          ],
          "options": {
            "showHeader": true,
            "sortBy": [
              {
                "displayName": "Duration",
                "desc": true
              }
            ]
          },
          "fieldConfig": {
            "overrides": [
              {
                "matcher": { "id": "byName", "options": "Trace ID" },
                "properties": [
                  {
                    "id": "links",
                    "value": [
                      {
                        "title": "View trace",
                        "url": "/explore?orgId=1&left=%7B%22datasource%22:%22tempo%22,%22queries%22:%5B%7B%22queryType%22:%22traceql%22,%22query%22:%22${__value.text}%22%7D%5D%7D",
                        "targetBlank": true
                      }
                    ]
                  }
                ]
              },
              {
                "matcher": { "id": "byName", "options": "Duration" },
                "properties": [
                  { "id": "unit", "value": "ns" },
                  { "id": "custom.displayMode", "value": "color-background" },
                  {
                    "id": "thresholds",
                    "value": {
                      "mode": "absolute",
                      "steps": [
                        { "color": "green", "value": null },
                        { "color": "yellow", "value": 500000000 },
                        { "color": "red", "value": 2000000000 }
                      ]
                    }
                  }
                ]
              }
            ]
          }
        }
      ],
      "refresh": "30s",
      "schemaVersion": 39,
      "tags": ["devsecops", "tracing", "distributed-tracing", "tempo"],
      "templating": {
        "list": [
          {
            "name": "service",
            "label": "Service",
            "type": "custom",
            "query": "sampleapi,notificationapi,order-service,inventory-service,payment-service",
            "includeAll": true,
            "allValue": ".*",
            "current": { "text": "All", "value": "$__all" }
          }
        ]
      },
      "time": { "from": "now-1h", "to": "now" },
      "timepicker": {},
      "timezone": "browser",
      "title": "Distributed Tracing",
      "uid": "devsecops-traces",
      "version": 1
    }
```

### Understanding the Key Panels

Before you apply, take a moment to understand what each panel tells you.

**Latency Heatmap (top left)** -- This panel shows the distribution of trace durations over time as a color-coded grid. The X-axis is time, the Y-axis is latency (logarithmic scale), and the color intensity shows the number of traces at that latency. Key patterns:

- **Horizontal band at the bottom**: most requests are fast (good)
- **Vertical spike**: a burst of slow requests at a specific time (investigate what changed)
- **Bimodal pattern (two horizontal bands)**: two populations of requests -- some fast, some slow. Usually indicates a cache hit/miss pattern or a slow downstream dependency
- **Gradual upward drift**: latency increasing over time (memory leak, connection pool exhaustion, or growing data set)

**Error Rate by Service (top right)** -- Percentage of spans with error status, per service. The threshold coloring gives immediate signal: green below 1%, yellow below 5%, red above 5%. A single service turning red while others stay green tells you exactly where the problem is.

**Span Count by Service (bottom left)** -- Volume of spans per service. A sudden drop to zero means a service stopped sending traces (possible crash, network issue, or misconfigured OTEL env var). A spike means increased traffic. This panel is your "are traces flowing?" health check.

**Recent Traces (bottom row, full width)** -- A table showing the most recent traces with service name, duration, span count, and trace ID. Click a trace ID to jump to the waterfall view in Explore. Duration cells are color-coded: green under 500ms, yellow under 2s, red above 2s.

Apply the dashboard:

```bash
$OC apply -f infra/phase22/grafana-dashboard-traces.yaml
```

#### Verify

```bash
# Check the CRD was created
$OC get grafanadashboard traces-dashboard -n $NS_TOOLS
# Expected:
#   NAME                NO MATCHING INSTANCES   LAST RESYNC   AGE
#   traces-dashboard                            39s           1m

# In Grafana UI: Dashboards → "Distributed Tracing" should appear
# Select "All" in the Service dropdown to see all 5 services
# The "Recent Traces" table should show traces from your curl commands
```

### Step 6.2: Add a Second Tempo Datasource for Java Services (Optional)

**Why:** The default Tempo datasource points to the `dev` tenant (for .NET services). To view Java service traces, you need either a second datasource pointing to the `javaapp` tenant, or you switch the URL in the existing datasource.

For a production setup with two datasources:

```yaml
# file: infra/phase22/grafana-datasource-tempo-javaapp.yaml
# ----------------------------------------------------------
# Second Tempo datasource for the javaapp tenant.
# Allows the Traces dashboard to query Java service traces.
apiVersion: grafana.integreatly.org/v1beta1
kind: GrafanaDatasource
metadata:
  name: tempo-datasource-javaapp
  namespace: devsecops-tools
spec:
  instanceSelector:
    matchLabels:
      dashboards: "grafana"
  datasource:
    name: Tempo (Java)                         # ← distinct name from the .NET datasource
    type: tempo
    uid: tempo-javaapp                         # ← distinct UID
    access: proxy
    url: "https://tempo-tempostack-gateway.openshift-tempo.svc:8080/api/traces/v1/javaapp/tempo"
    jsonData:
      httpHeaderName1: Authorization
      tlsSkipVerify: true
      tracesToMetrics:
        datasourceUid: prometheus
      nodeGraph:
        enabled: true
    secureJsonData:
      httpHeaderValue1: "Bearer ${GRAFANA_TOKEN}"
```

Apply:

```bash
envsubst < infra/phase22/grafana-datasource-tempo-javaapp.yaml | $OC apply -f -
```

Now in Grafana Explore, switch between the two Tempo datasources to view traces from .NET or Java services independently.

---

## What Just Happened?

Here is a summary of everything you built in this module and how the pieces connect:

```
Full Tracing Architecture (5 services):

.NET SampleApi ──────┐                          ┌──── Grafana (Tempo datasource)
  (manual SDK)       │                          │       ├── Explore → Waterfall
                     │                          │       └── Dashboard → Heatmap, Error Rate
.NET NotificationApi─┤                          │
  (auto-instrument)  │                          ├──── OCP Console (UIPlugin)
                     ├──► OTel Collector ──►────┤       └── Observe → Traces
Java OrderService ───┤    (gRPC :4317)          │
  (agent)            │    routing by            ├──── Tempo Gateway (OPA auth)
                     │    k8s.namespace          │       ├── /api/traces/v1/dev/...
Java InventoryService┤                          │       ├── /api/traces/v1/sit/...
  (agent)            │                          │       ├── /api/traces/v1/uat/...
                     │                          │       ├── /api/traces/v1/prod/...
Java PaymentService──┘                          │       └── /api/traces/v1/javaapp/...
  (agent)                                       │
                                                └──── TempoStack → S3 (ODF NooBaa)
```

| Component | Namespace | Purpose |
|-----------|-----------|---------|
| Tempo Operator | `openshift-tempo-operator` | Manages TempoStack CRs |
| OTel Operator | `openshift-opentelemetry-operator` | Manages Collector and Instrumentation CRs |
| Cluster Observability Operator | `openshift-observability-operator` | Provides Console tracing UI plugin |
| TempoStack | `openshift-tempo` | Stores traces in S3, serves queries via gateway |
| OTel Collector | `openshift-tempo` | Receives OTLP traces, routes to per-tenant Tempo endpoints |
| Instrumentation CRs | `sampleapi-dev` (and sit/uat/prod) | Auto-inject .NET tracing agent into NotificationApi |
| UIPlugin | Cluster-scoped | Adds "Traces" tab to OCP Console |
| Tempo Grafana Datasource | `devsecops-tools` | Connects Grafana to Tempo for trace queries |
| Traces Dashboard | `devsecops-tools` | Visualizes tracing health: latency, errors, throughput |
| RBAC (write + read) | Cluster-scoped | Collector writes traces, Grafana/Console reads traces |

The chain is: **App emits span -> OTel Collector batches + routes -> Tempo Gateway authenticates -> TempoStack stores in S3 -> Grafana/Console queries and visualizes**. Every link uses standard protocols (OTLP, HTTP, gRPC) and standard auth (ServiceAccount tokens, SubjectAccessReview).

---

## Common Mistakes

### 1. Traces show up in Tempo but with only one span

**Cause:** Context propagation is broken. The calling service sends the `traceparent` header, but the receiving service ignores it (does not extract the trace ID and create a child span).

**Why:** This happens when the receiving service does not have OpenTelemetry instrumentation active. The request arrives with a `traceparent` header, but the service treats it as a regular header and creates an unrelated new trace.

**Fix:** Confirm instrumentation is active in BOTH services:

```bash
# For .NET auto-instrumented services, check the init container
$OC get pod -n $NS_DEV -l app=notificationapi \
  -o jsonpath='{.items[0].spec.initContainers[*].name}'
# Expected: includes "opentelemetry-auto-instrumentation-dotnet"

# For Java services, check the agent log
$OC logs deploy/order-service -n $NS_JAVA_DEV --tail=30 | grep "opentelemetry-javaagent"
# Expected: agent version line in the startup log
```

### 2. Collector logs show "403 Forbidden" when writing to Tempo

**Cause:** The Collector ServiceAccount does not have the `tempostack-traces-write` ClusterRoleBinding.

**Fix:** Re-apply the RBAC from Step 2.4:

```bash
$OC apply -f infra/phase22/tempo-tenant-rbac.yaml

# Verify write permission
$OC auth can-i create traces \
  --as=system:serviceaccount:openshift-tempo:otel-collector-collector \
  --subresource="" --api-group=tempo.grafana.com
# Expected: yes
```

### 3. Grafana Tempo datasource shows "Bad Gateway" on Save & Test

**Cause:** The gateway URL path is wrong. The most common mistakes:
- Missing `/tempo` at the end: `...v1/dev` instead of `...v1/dev/tempo`
- Wrong tenant name: `...v1/default` instead of `...v1/dev`
- Using port 3200 (Tempo internal) instead of port 8080 (gateway)

**Fix:** The correct URL format is:

```
https://tempo-tempostack-gateway.openshift-tempo.svc:8080/api/traces/v1/{tenant}/tempo
                                                                         ^^^^^^        ^^^^^
                                                                      tenant name    must be /tempo
```

### 4. Java agent creates traces for health check endpoints

**Cause:** `OTEL_JAVAAGENT_EXCLUDE_URLS` is not set or is misspelled. Health checks run every 10 seconds, creating 54 traces per minute of noise.

**Fix:** Add the env var to the ConfigMap:

```yaml
OTEL_JAVAAGENT_EXCLUDE_URLS: "/actuator/health,/actuator/info,/healthz"
```

Then restart the deployment to pick up the change.

### 5. Auto-instrumentation init container not injected after adding annotation

**Cause:** The OTel Operator's mutating webhook only runs at pod creation time. Adding an annotation to an existing Deployment does not retroactively inject the init container into running pods.

**Fix:** Restart the Deployment after adding the annotation:

```bash
$OC rollout restart deployment notificationapi -n $NS_DEV
```

### 6. TempoStack gateway OPA sidecar crashes with "missing tenant mappings"

**Cause:** The `tenants.authentication[]` array in the TempoStack CR is empty or has entries without `tenantName` and `tenantId`.

**Fix:** Verify the TempoStack CR has all tenant entries:

```bash
$OC get tempostack tempostack -n $NS_TEMPO \
  -o jsonpath='{.spec.tenants.authentication[*].tenantName}'
# Expected: dev sit uat prod javaapp
```

### 7. Console shows "Instances without multi-tenancy are not supported"

**Cause:** The TempoStack does not have `tenants.mode: openshift` and the gateway enabled. The Console UI plugin requires multi-tenant mode with the OpenShift authentication method.

**Fix:** Verify the TempoStack CR has both settings:

```bash
$OC get tempostack tempostack -n $NS_TEMPO \
  -o jsonpath='{.spec.tenants.mode}'
# Expected: openshift

$OC get tempostack tempostack -n $NS_TEMPO \
  -o jsonpath='{.spec.template.gateway.enabled}'
# Expected: true
```

---

## Challenge: Add Custom Spans to SampleApi

SampleApi uses the manual OpenTelemetry SDK, which means you can add custom spans for business-critical operations. Add a custom span around the call to NotificationApi that includes the notification type as a span attribute.

In `Program.cs` or the controller that calls NotificationApi:

```csharp
using System.Diagnostics;

// Create an ActivitySource (OTel span factory) — do this once, at class level
private static readonly ActivitySource Tracer = new("SampleApi.Notifications");

// In the method that calls NotificationApi:
using var activity = Tracer.StartActivity("SendNotification");
activity?.SetTag("notification.type", "weather-update");    // custom attribute
activity?.SetTag("notification.recipient", userId);          // business context

try
{
    var response = await _httpClient.PostAsync(notificationUrl, content);
    activity?.SetTag("notification.status", response.StatusCode.ToString());
}
catch (Exception ex)
{
    activity?.SetStatus(ActivityStatusCode.Error, ex.Message);
    throw;
}
```

After deploying this change:

1. Send requests to SampleApi
2. Open Grafana Explore with the Tempo datasource
3. Search for `service.name = sampleapi`
4. Open a trace and look for the `SendNotification` span
5. Verify the custom attributes (`notification.type`, `notification.recipient`) appear in the span detail panel

This demonstrates the advantage of the manual SDK approach -- you get business context inside your traces, not just HTTP method and status code.

---

## Self-Assessment

Before moving on, verify you can answer these questions:

1. **Why does the OTel Collector route traces to different Tempo tenants based on `k8s.namespace.name`?**

   Expected answer: Multi-tenant routing provides environment isolation. Traces from `sampleapi-dev` go to the `dev` tenant and traces from `sampleapi-prod` go to the `prod` tenant. Without this, a Grafana user querying "dev" traces would also see production traces, violating environment boundaries and making investigation noisy.

2. **What is the difference between .NET auto-instrumentation (Instrumentation CR + annotation) and the Java agent (`-javaagent` in Dockerfile)?**

   Expected answer: Both achieve the same result (automatic tracing without code changes), but through different mechanisms. The .NET auto-instrumentation uses the OTel Operator to inject an init container at pod creation time -- the agent is loaded via CLR profiling APIs. The Java agent is baked into the container image at build time and loaded via `JAVA_TOOL_OPTIONS` JVM argument. The .NET approach is more dynamic (no image rebuild needed), while the Java approach is more predictable (the agent version is pinned in the Dockerfile).

3. **Why is `OTEL_JAVAAGENT_EXCLUDE_URLS` critical for Java services on Kubernetes?**

   Expected answer: Kubernetes sends liveness, readiness, and startup probes to health check endpoints every 10-15 seconds. Without excluding these URLs, each probe creates a trace. With 3 services and 3 probes each, that is ~54 traces per minute of noise -- health checks dominate the trace search results and inflate storage costs without providing useful information.

4. **A trace shows that OrderService took 380ms total, InventoryService took 130ms, and PaymentService took 180ms. Where did the remaining 70ms go?**

   Expected answer: The 70ms is the time spent in OrderService itself -- request parsing, validation, orchestration logic, serialization, and the overhead of making the downstream HTTP calls (connection setup, DNS resolution, TLS handshake). This is the "service self-time" visible in the waterfall as the gap between the parent span and child spans.

5. **You click "Save & Test" on the Tempo datasource in Grafana and it says "Bad Gateway." What are the three most likely causes?**

   Expected answer: (1) Missing RBAC -- the `grafana-sa` ServiceAccount does not have the `tempostack-traces-read` ClusterRoleBinding. (2) Wrong URL path -- the URL does not end with `/tempo` or uses the wrong tenant name. (3) Expired or missing bearer token -- the `secureJsonData.httpHeaderValue1` field has a placeholder or an expired token.

6. **Why does the TempoStack use `tenants.mode: openshift` instead of `static` mode?**

   Expected answer: OpenShift mode integrates with the platform's SubjectAccessReview authorization. This means the gateway checks whether the calling ServiceAccount (or user) has permission to read/write traces for a specific tenant using standard Kubernetes RBAC. Static mode would require managing API keys manually. OpenShift mode leverages the existing RBAC infrastructure, and the Console UI plugin specifically requires it to function.

---

## Next Module Preview

**Module 17: Supply Chain Security with RHTAS and RHTPA** -- You have traces flowing through all 5 services. But can you prove those services are what they claim to be? Module 17 introduces Red Hat Trusted Artifact Signer (Sigstore/Cosign on OCP) and Red Hat Trusted Profile Analyzer (Trustify) to sign container images, generate SBOMs, and verify the software supply chain. You will integrate image signing and SBOM verification into the T2 and T3 pipelines so that every deployed image has a cryptographic proof of origin.
