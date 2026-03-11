# Module 13: Grafana Dashboards and Distributed Tracing

**Track:** Observability
**Duration:** ~90 minutes
**Prerequisites:** Module 11 (Logging with LokiStack), Module 12 (Monitoring and Alerting)

> **Environment variables:** Before running any commands, source the environment file:
> ```bash
> source ./env.sh
> ```
> This sets `$OC`, `$APPS_DOMAIN`, `$NS_TOOLS`, and all other cluster-specific variables used throughout this module. See `env.sh` for the full variable list.

---

## What You'll Learn

By the end of this module you will be able to:

1. Deploy the Grafana Operator (community v5) on OpenShift via OLM.
2. Create a Grafana instance managed by the operator.
3. Connect three datasources using CRDs: Thanos Querier (Prometheus), Loki (logs), and Tempo (traces).
4. Define dashboards as code using the `GrafanaDashboard` custom resource.
5. Build an application health dashboard with request rate, error rate, latency percentiles, and pod restarts.
6. Build a CI/CD pipeline dashboard with build duration, success/failure rate, and deployment tracking.
7. Build a traces explorer dashboard that visualizes distributed request flows across SampleApi and NotificationApi.
8. Manage dashboards through GitOps so they are version-controlled, reviewable, and reproducible.

---

## Why This Matters

OpenShift ships with a built-in monitoring stack -- Prometheus, Thanos, and a minimal dashboard view in the console under Observe. That gets you 80% of the way. But it does not give you:

- **Custom dashboards** that combine application metrics, pipeline metrics, and logs on a single screen.
- **Team-specific views** where developers see request latency while SREs see pod resource pressure.
- **Dashboards as code** that survive cluster rebuilds, live in Git, and go through merge request review.

Grafana fills that gap. And the Grafana Operator makes it declarative -- you define a `GrafanaDashboard` CRD, the operator reconciles it into a running Grafana instance, and ArgoCD can sync it like any other Kubernetes resource.

The result is a single pane of glass: app health on the left, pipeline status in the middle, logs on the right. When a T2 merge pipeline deploys to DEV and latency spikes, you see it all in one place instead of jumping between Jenkins, the OCP console, and `oc logs`.

---

## Concepts

### Grafana Architecture on OpenShift

```
                                    +--------------------------+
                                    |   Grafana Operator (v5)  |
                                    |   (watches CRDs)         |
                                    +---+----------+-----------+
                                        |          |
                                  reconciles    reconciles
                                        |          |
                              +---------+--+   +---+--------------+
                              |  Grafana   |   | GrafanaDashboard |
                              |  Instance  |   | CRD(s)           |
                              +-----+------+   +------------------+
                                    |
                          reads datasources
                         /                   \
            +-----------+--------+    +-------+-----------+
            | GrafanaDatasource  |    | GrafanaDatasource |
            | (Thanos/Prometheus)|    | (Loki/Logs)       |
            +--------------------+    +-------------------+
                     |                         |
     thanos-querier.openshift-     logging-loki-gateway-http.
     monitoring.svc:9091           openshift-logging.svc:8080
```

Three CRDs drive everything:

| CRD | What It Does |
|-----|-------------|
| `Grafana` | Creates a Grafana server pod with persistent storage, auth config, and route |
| `GrafanaDatasource` | Connects Grafana to a metrics or logs backend (Thanos, Loki, etc.) |
| `GrafanaDashboard` | Loads a dashboard JSON into Grafana -- the operator keeps it in sync |

The operator watches these CRDs and reconciles continuously. If someone edits a dashboard in the Grafana UI, the operator overwrites it back to the CRD definition on the next reconcile. This is the "dashboards as code" guarantee -- Git is the source of truth, not the UI.

### Datasource Connections

Three datasources matter for this project:

**Thanos Querier** -- This is the unified Prometheus query endpoint on OpenShift. It federates metrics from both platform monitoring (`openshift-monitoring`) and user workload monitoring (`openshift-user-workload-monitoring`). Your ServiceMonitors from Module 12 feed metrics here. Endpoint: `https://thanos-querier.openshift-monitoring.svc:9091`.

**Loki** -- The OpenShift Logging stack with LokiStack makes application logs queryable via LogQL (Module 11). Endpoint: `https://logging-loki-gateway-http.openshift-logging.svc:8080/api/logs/v1/application/`. The URL includes the `/api/logs/v1/application/` tenant path because LokiStack runs in multi-tenant mode -- without it, queries return empty or 404.

**Tempo** -- The distributed tracing backend stores traces collected by OpenTelemetry auto-instrumentation. With two services (SampleApi calling NotificationApi), you can trace a request across service boundaries and see exactly where latency comes from. The TempoStack runs in multi-tenant mode with a gateway. Endpoint: `https://tempo-tempo-gateway.openshift-tempo.svc:8080/api/traces/v1/{tenant}/tempo` (where `{tenant}` is dev, sit, uat, or prod).

> **Note:** All three datasources require bearer token authentication. The Grafana ServiceAccount needs `ClusterRoleBinding` to `cluster-monitoring-view` (for Thanos), and `tempostack-traces-read` (for Tempo gateway). This is the single most common mistake people make -- they get the datasource URL right but forget the RBAC, and every query returns empty.

---

## Step 1: Install the Grafana Operator

**Why first:** Without the operator, the `Grafana`, `GrafanaDatasource`, and `GrafanaDashboard` CRDs do not exist on the cluster. Everything else depends on this.

The community Grafana Operator v5 is available through OperatorHub. It installs cluster-wide but watches specific namespaces.

Create the operator subscription:

```yaml
# file: infra/phase21/grafana-operator-subscription.yaml
# -------------------------------------------------------
# Installs Grafana Operator v5 from OperatorHub (community).
# The operator deploys into the grafana-operator namespace but
# can watch all namespaces (or a specific list via operand watch).
apiVersion: operators.coreos.com/v1alpha1
kind: Subscription
metadata:
  name: grafana-operator
  namespace: devsecops-tools
spec:
  channel: v5
  installPlanApproval: Automatic
  name: grafana-operator
  source: community-operators
  sourceNamespace: openshift-marketplace
```

Apply it:

```bash
$OC apply -f infra/phase21/grafana-operator-subscription.yaml
```

### Verify

Wait for the operator pod to reach `Running`:

```bash
# Watch the CSV (ClusterServiceVersion) until it says Succeeded
$OC get csv -n devsecops-tools -w
# Expected output (after 1-2 minutes):
#   NAME                        DISPLAY            PHASE
#   grafana-operator.v5.x.x     Grafana Operator   Succeeded

# Confirm the operator pod is running
$OC get pods -n devsecops-tools -l app.kubernetes.io/name=grafana-operator
# Expected: 1/1 Running
```

Once you see `Succeeded` on the CSV and the pod is running, the three CRDs are registered:

```bash
$OC get crd | grep grafana
# Expected:
#   grafanadashboards.grafana.integreatly.org
#   grafanadatasources.grafana.integreatly.org
#   grafanas.grafana.integreatly.org
```

> **Quick win checkpoint:** You have not deployed Grafana itself yet, but the operator is ready to reconcile CRDs. The hardest part (OLM subscription) is done.

---

## Step 2: Create a Grafana Instance

**Why:** The operator manages Grafana instances declaratively. Instead of `helm install grafana` or a raw Deployment, you create a `Grafana` CR and the operator handles the pod, service, persistent storage, and OpenShift route.

```yaml
# file: infra/phase21/grafana-instance.yaml
# -------------------------------------------
# Creates a Grafana server instance managed by the Grafana Operator.
# The operator creates a Deployment, Service, and (optionally) an Ingress/Route.
# On OpenShift, we add a Route annotation so the operator creates an edge-terminated route.
apiVersion: grafana.integreatly.org/v1beta1
kind: Grafana
metadata:
  name: grafana                          # <-- instance name
  namespace: devsecops-tools
  labels:
    dashboards: "grafana"                # <-- label selector for dashboards (important later)
    app: grafana
    team: devsecops
spec:
  # -- Route configuration (OpenShift-specific) --
  route:
    spec:
      tls:
        termination: edge                # <-- TLS termination at the router
        insecureEdgeTerminationPolicy: Redirect
  # -- Grafana server configuration --
  config:
    log:
      mode: console
      level: info
    security:
      admin_user: admin                  # <-- initial admin username
      admin_password: DevSec0ps-Grafana-2024  # <-- initial admin password (change after first login)
    auth:
      disable_login_form: "false"        # <-- allow admin login form
      disable_signout_menu: "false"
    auth.anonymous:
      enabled: "false"                   # <-- require login
    server:
      root_url: "https://grafana-route-devsecops-tools.${APPS_DOMAIN}"  # <-- Replace ${APPS_DOMAIN} before applying
  # -- Persistent storage (survives pod restarts) --
  persistentVolumeClaim:
    spec:
      accessModes:
        - ReadWriteOnce
      resources:
        requests:
          storage: 1Gi
      storageClassName: gp3-csi            # <-- matches cluster default StorageClass
  # -- Deployment overrides --
  deployment:
    spec:
      replicas: 1
      template:
        spec:
          containers:
            - name: grafana
              resources:
                requests:
                  cpu: 100m
                  memory: 256Mi
                limits:
                  cpu: 500m
                  memory: 512Mi
```

> **Note:** Replace `${APPS_DOMAIN}` in the YAML with your actual apps domain before applying, or pipe through `envsubst`.

Apply it:

```bash
envsubst < infra/phase21/grafana-instance.yaml | $OC apply -f -
```

### Verify

```bash
# Wait for the Grafana pod
$OC wait --for=condition=ready pod -l app=grafana -n devsecops-tools --timeout=120s

# Get the route URL
GRAFANA_URL=$($OC get route grafana-route -n devsecops-tools -o jsonpath='{.spec.host}' 2>/dev/null)
# Note: the operator may name the route differently. List all routes:
$OC get routes -n devsecops-tools
# Pick the one pointing to the grafana service.

# Test access
curl -sk -o /dev/null -w "%{http_code}" "https://${GRAFANA_URL}/login"
# Expected: 200
```

Open the Grafana URL in your browser. Log in with `admin` / `DevSec0ps-Grafana-2024`. You should see an empty Grafana instance with no datasources and no dashboards. That is correct -- we will add those next as CRDs.

---

## Step 3: Connect the Prometheus/Thanos Datasource

**Why:** Grafana cannot display metrics without a datasource. On OpenShift, Thanos Querier is the single endpoint that federates platform metrics (kube-state-metrics, cAdvisor) and user workload metrics (your ServiceMonitors from Module 12). All of the PromQL queries in our dashboard JSON files target this endpoint.

### 3a: Grant Grafana Access to Thanos

Thanos Querier requires a bearer token. The Grafana ServiceAccount needs the `cluster-monitoring-view` ClusterRole so it can read metrics across all namespaces.

```yaml
# file: infra/phase21/grafana-sa.yaml + grafana-sa-clusterrolebinding.yaml
# ----------------------------------------
# Grants the Grafana ServiceAccount read access to the cluster monitoring stack.
# Without this, every Prometheus query from Grafana returns empty results.
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: grafana-cluster-monitoring-view
  labels:
    team: devsecops
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: cluster-monitoring-view          # <-- built-in OCP role for reading metrics
subjects:
  - kind: ServiceAccount
    name: grafana-sa                     # <-- the SA created for Grafana
    namespace: devsecops-tools
```

Apply it:

```bash
$OC apply -f infra/phase21/grafana-sa.yaml
$OC apply -f infra/phase21/grafana-sa-clusterrolebinding.yaml
```

Now extract the bearer token. The Grafana Operator creates a ServiceAccount, but on OCP 4.11+ (with bound tokens), you need to create a long-lived token secret explicitly:

```bash
# Create a long-lived token for the Grafana SA
# (On OCP 4.11+, SA tokens are bound and short-lived by default)
$OC create token grafana-sa -n devsecops-tools --duration=8760h > /tmp/grafana-sa-token.txt

# Or create a Secret that auto-populates a token:
cat <<EOF | $OC apply -f -
apiVersion: v1
kind: Secret
metadata:
  name: grafana-sa-token
  namespace: devsecops-tools
  annotations:
    kubernetes.io/service-account.name: grafana-sa
type: kubernetes.io/service-account-token
EOF

# Wait a moment, then extract:
GRAFANA_TOKEN=$($OC get secret grafana-sa-token -n devsecops-tools -o jsonpath='{.data.token}' | base64 -d)
echo "Token length: ${#GRAFANA_TOKEN}"
# Expected: a long JWT string (length > 100)
```

### 3b: Create the Datasource CRD

```yaml
# file: infra/phase21/grafana-datasource-prometheus.yaml
# -------------------------------------------------------
# Connects Grafana to Thanos Querier for Prometheus metrics.
#
# Key points:
#   - URL is the cluster-internal Thanos Querier service (port 9091)
#   - Authentication uses a bearer token from the Grafana SA
#   - TLS verification is skipped because we use the internal service CA
#   - This datasource is named "Prometheus" and typed "prometheus" to match
#     the dashboard JSON $datasource variable (type: "datasource", query: "prometheus")
apiVersion: grafana.integreatly.org/v1beta1
kind: GrafanaDatasource
metadata:
  name: prometheus-datasource
  namespace: devsecops-tools
spec:
  instanceSelector:
    matchLabels:
      dashboards: "grafana"              # <-- must match the Grafana CR label
  datasource:
    name: Prometheus                     # <-- name referenced in dashboard JSON panels
    type: prometheus
    access: proxy                        # <-- Grafana backend proxies requests (not browser)
    url: "https://thanos-querier.openshift-monitoring.svc.cluster.local:9091"
    isDefault: true                      # <-- default datasource for new panels
    editable: false
    jsonData:
      httpHeaderName1: Authorization
      timeInterval: "30s"                # <-- matches ServiceMonitor scrape interval
      tlsSkipVerify: true                # <-- internal service, skip CA verification
    secureJsonData:
      httpHeaderValue1: "Bearer ${GRAFANA_TOKEN}"  # <-- replace with actual token
```

> **Important:** Replace `${GRAFANA_TOKEN}` with the actual token value before applying. In production, use a Secret reference or ExternalSecret operator instead of embedding the token in the CR.

A more robust approach is to store the token in an OCP Secret and reference it:

```bash
# Store the token as a secret
$OC create secret generic grafana-datasource-credentials -n devsecops-tools \
  --from-literal=PROMETHEUS_TOKEN="${GRAFANA_TOKEN}"
```

Then in the datasource CR, use the `valuesFrom` field (Grafana Operator v5 supports this):

```yaml
# Alternative: reference token from a Secret (preferred for production)
apiVersion: grafana.integreatly.org/v1beta1
kind: GrafanaDatasource
metadata:
  name: prometheus-datasource
  namespace: devsecops-tools
spec:
  instanceSelector:
    matchLabels:
      dashboards: "grafana"
  valuesFrom:
    - targetPath: "datasource.secureJsonData.httpHeaderValue1"
      valueFrom:
        secretKeyRef:
          name: grafana-datasource-credentials
          key: PROMETHEUS_TOKEN
  datasource:
    name: Prometheus
    type: prometheus
    uid: prometheus
    access: proxy
    url: "https://thanos-querier.openshift-monitoring.svc.cluster.local:9091"
    isDefault: true
    editable: false
    jsonData:
      httpHeaderName1: Authorization
      timeInterval: "30s"
      tlsSkipVerify: true
    secureJsonData:
      httpHeaderValue1: "Bearer pending"  # <-- overridden by valuesFrom
```

Apply (substitute the token placeholder before applying):

```bash
export GRAFANA_TOKEN=$(cat /tmp/grafana-sa-token.txt)
envsubst < infra/phase21/grafana-datasource-prometheus.yaml | $OC apply -f -
```

### Verify

```bash
# Check the datasource was created
$OC get grafanadatasource -n devsecops-tools
# Expected output:
#   NAME                    NO MATCHING INSTANCES   LAST RESYNC   AGE
#   prometheus-datasource                           39s           44h

# Test via Grafana UI:
# 1. Open Grafana -> Connections -> Data sources -> Prometheus
# 2. Click "Save & Test"
# Expected: "Successfully queried the Prometheus API"

# Or test via API:
GRAFANA_URL=$($OC get route grafana-route -n devsecops-tools -o jsonpath='{.spec.host}' 2>/dev/null || $OC get routes -n devsecops-tools -o jsonpath='{.items[0].spec.host}')
curl -sk -u admin:DevSec0ps-Grafana-2024 \
  "https://${GRAFANA_URL}/api/datasources" | jq '.[].name'
# Expected: "Prometheus"
```

If "Save & Test" fails with "Bad Gateway" or empty results, check the RBAC binding from Step 3a. The most common cause is a missing or expired token.

---

## Step 4: Connect the Loki Datasource (Optional)

**Why:** Metrics tell you WHAT is happening (error rate spiked). Logs tell you WHY (a NullReferenceException in the payment handler). Combining both in one Grafana dashboard eliminates context-switching.

> **Prerequisite:** The OpenShift Logging operator must be installed with a LokiStack backend. If your cluster uses Elasticsearch instead of Loki, or has no logging operator at all, skip this step. The dashboards in Steps 5-6 will work fine without it -- you just will not have the logs panel.

```yaml
# file: infra/phase21/grafana-datasource-loki.yaml
# --------------------------------------------------
# Connects Grafana to OpenShift Logging's Loki gateway for log queries.
# Only applicable if LokiStack is deployed (OpenShift Logging 6.x).
apiVersion: grafana.integreatly.org/v1beta1
kind: GrafanaDatasource
metadata:
  name: loki-datasource
  namespace: devsecops-tools
spec:
  instanceSelector:
    matchLabels:
      dashboards: "grafana"
  datasource:
    name: Loki                           # <-- referenced in log panels
    type: loki
    access: proxy
    # NOTE: The URL must include the tenant path /api/logs/v1/application/
    # because LokiStack runs in multi-tenant mode. Without the tenant path,
    # queries return empty results or 404 errors.
    url: "https://logging-loki-gateway-http.openshift-logging.svc:8080/api/logs/v1/application/"
    editable: false
    jsonData:
      httpHeaderName1: Authorization
      tlsSkipVerify: true
      maxLines: 5000
    secureJsonData:
      httpHeaderValue1: "Bearer ${GRAFANA_TOKEN}"  # <-- same SA token works
```

Apply (substitute the token placeholder, same as Step 3):

```bash
envsubst < infra/phase21/grafana-datasource-loki.yaml | $OC apply -f -
```

Verify the same way as Step 3 (check `$OC get grafanadatasource -n devsecops-tools` and test via Grafana UI).

---

## Step 4b: Deploy the Distributed Tracing Infrastructure

**Why:** With two services (SampleApi calling NotificationApi via HTTP), a slow response could originate in either service, the network, PostgreSQL, or Redis. Metrics tell you *that* p99 spiked. Traces tell you *where* -- down to the individual span. Before we can connect a Tempo datasource to Grafana, we need the entire tracing backend running: operators, trace storage, a collector to receive spans, auto-instrumentation to inject tracing into the .NET apps, and RBAC so the gateway authorizes queries.

This step deploys six components:

| Component | What It Does |
|-----------|-------------|
| **Tempo Operator** | Manages TempoStack CRs (trace storage backend) |
| **OTel Operator** | Manages OpenTelemetryCollector and Instrumentation CRs |
| **Cluster Observability Operator** | Provides the Console tracing UI plugin (Observe > Traces) |
| **TempoStack** | Stores traces in S3 (ODF NooBaa), serves queries via gateway |
| **OTel Collector** | Receives OTLP traces from apps, routes to per-tenant Tempo endpoints |
| **Instrumentation CRs** | Auto-inject .NET tracing agent into app pods (no code changes) |

```
App Pod (.NET)                OTel Collector              TempoStack
┌──────────────┐  OTLP gRPC  ┌─────────────┐  OTLP HTTP  ┌──────────────┐
│ SampleApi    │────:4317────>│  routing     │────:8080───>│  Gateway     │
│ (auto-instr) │              │  connector   │  per-tenant │  (openshift  │
└──────────────┘              │              │  Bearer tok │   tenancy)   │
┌──────────────┐              │  dev ──> /dev│             │              │
│ Notification │────:4317────>│  sit ──> /sit│             │  Ingester    │
│ Api          │              │  uat ──> /uat│             │  Compactor   │
└──────────────┘              │  prod ──>/prd│             │  Querier     │
                              └─────────────┘             └──────┬───────┘
                                                                 │ S3
                                                          ┌──────▼───────┐
                                                          │ ODF NooBaa   │
                                                          │ (tempo-bucket│
                                                          │  OBC)        │
                                                          └──────────────┘
```

> **Prerequisite:** The OBC `tempo-bucket` must be in Bound state (created in Module 11 / Phase 19). Verify with:
> ```bash
> $OC get obc tempo-bucket -n openshift-tempo
> # Expected: STATUS=Bound
> ```

### 4b.1 Install the Three Operators

We need three operators. All three are installed from Red Hat's OperatorHub (stable channel).

```bash
# Install Tempo Operator
$OC apply -f infra/phase22/tempo-operator-subscription.yaml

# Install OpenTelemetry Operator
$OC apply -f infra/phase22/otel-operator-subscription.yaml

# Install Cluster Observability Operator (provides Console tracing UI)
$OC apply -f infra/phase22/cluster-observability-operator.yaml

# Wait for all three operators to reach Succeeded
echo "Waiting for Tempo Operator..."
while ! $OC get csv -n openshift-tempo-operator 2>/dev/null | grep -q Succeeded; do
  sleep 10; echo "  waiting..."
done

echo "Waiting for OTel Operator..."
while ! $OC get csv -n openshift-opentelemetry-operator 2>/dev/null | grep -q Succeeded; do
  sleep 10; echo "  waiting..."
done

echo "Waiting for Cluster Observability Operator..."
while ! $OC get csv -n openshift-observability-operator 2>/dev/null | grep -q Succeeded; do
  sleep 10; echo "  waiting..."
done

echo "All three operators installed"
```

**Verify:**

```bash
$OC get csv -n openshift-tempo-operator | grep tempo
$OC get csv -n openshift-opentelemetry-operator | grep opentelemetry
$OC get csv -n openshift-observability-operator | grep observability
# Expected: all three show Succeeded
```

### 4b.2 Create the Tempo S3 Secret

The OBC `tempo-bucket` auto-generated a Secret with AWS-style field names (`AWS_ACCESS_KEY_ID`). TempoStack expects different names (`access_key_id`, `access_key_secret`, `bucket`, `endpoint`). We reformat:

```bash
# Extract OBC-generated credentials
BUCKET_NAME=$($OC get configmap tempo-bucket -n openshift-tempo -o jsonpath='{.data.BUCKET_NAME}')
BUCKET_HOST=$($OC get configmap tempo-bucket -n openshift-tempo -o jsonpath='{.data.BUCKET_HOST}')
BUCKET_PORT=$($OC get configmap tempo-bucket -n openshift-tempo -o jsonpath='{.data.BUCKET_PORT}')
ACCESS_KEY=$($OC get secret tempo-bucket -n openshift-tempo -o jsonpath='{.data.AWS_ACCESS_KEY_ID}' | base64 -d)
SECRET_KEY=$($OC get secret tempo-bucket -n openshift-tempo -o jsonpath='{.data.AWS_SECRET_ACCESS_KEY}' | base64 -d)

# Create reformatted secret for TempoStack
$OC create secret generic tempo-s3 -n openshift-tempo \
  --from-literal=access_key_id="$ACCESS_KEY" \
  --from-literal=access_key_secret="$SECRET_KEY" \
  --from-literal=bucket="$BUCKET_NAME" \
  --from-literal=endpoint="https://${BUCKET_HOST}:${BUCKET_PORT}"
```

NooBaa uses a self-signed TLS certificate. TempoStack needs to trust it. Create a service-CA ConfigMap:

```bash
# Create a CA bundle ConfigMap — OpenShift auto-injects the service-CA cert
$OC create configmap tempo-ca-bundle -n openshift-tempo
$OC annotate configmap tempo-ca-bundle -n openshift-tempo \
  service.beta.openshift.io/inject-cabundle=true
```

**Verify:**

```bash
$OC get secret tempo-s3 -n openshift-tempo -o jsonpath='{.data}' | jq -r 'keys'
# Expected: ["access_key_id","access_key_secret","bucket","endpoint"]

$OC get configmap tempo-ca-bundle -n openshift-tempo -o jsonpath='{.data}' | head -c 100
# Expected: starts with "-----BEGIN CERTIFICATE-----" (service-CA cert injected)
```

### 4b.3 Deploy TempoStack

The TempoStack CR uses `openshift` tenancy mode with four tenants (one per environment). The gateway performs SubjectAccessReview authorization -- this is what the OpenShift Console tracing UI requires.

```bash
$OC apply -f infra/phase22/tempostack.yaml

echo "Waiting for TempoStack pods (takes 1-2 minutes)..."
sleep 30
$OC get pods -n openshift-tempo -l app.kubernetes.io/managed-by=tempo-operator
# Expected: compactor, distributor, ingester, querier, query-frontend,
#           gateway (with opa sidecar container) — all Running
```

> **Gotcha:** If the gateway OPA sidecar crashes with "missing tenant mappings", check that the `tenants.authentication[]` entries in `tempostack.yaml` are not empty. Each tenant must have a `tenantName` and `tenantId`.

**Verify:**

```bash
$OC get tempostack tempo -n openshift-tempo
# Expected: STATUS shows Ready (may take 1-2 minutes)
```

### 4b.4 Create RBAC for Tempo Gateway

The gateway uses the `tempo.grafana.com` API group for authorization. Two ClusterRoles are needed:

- **tempostack-traces-write** -- lets the OTel Collector write traces to all tenants
- **tempostack-traces-read** -- lets Grafana and Console users query traces

```bash
$OC apply -f infra/phase22/tempo-tenant-rbac.yaml
```

**Verify:**

```bash
# Collector can write traces (after collector SA exists)
$OC auth can-i create traces \
  --as=system:serviceaccount:openshift-tempo:otel-collector-collector \
  --subresource="" --api-group=tempo.grafana.com 2>/dev/null || echo "SA not yet created (OK — created by OTel Collector step)"

# Grafana can read traces
$OC auth can-i get traces \
  --as=system:serviceaccount:devsecops-tools:grafana-sa \
  --subresource="" --api-group=tempo.grafana.com
# Expected: yes
```

### 4b.5 Deploy the OTel Collector

The Collector receives OTLP traces from all app pods on gRPC port 4317 and routes them to the correct Tempo tenant based on the `k8s.namespace.name` resource attribute:

| Namespace | Tenant | Gateway Endpoint |
|-----------|--------|-----------------|
| `sampleapi-dev` | dev | `.../api/traces/v1/dev` |
| `sampleapi-sit` | sit | `.../api/traces/v1/sit` |
| `sampleapi-uat` | uat | `.../api/traces/v1/uat` |
| `sampleapi-prod` | prod | `.../api/traces/v1/prod` |

Before applying, generate a long-lived SA token for the Collector to authenticate with the Tempo gateway:

```bash
# Generate SA token (the SA is auto-created by the OTel Operator when we apply the CR)
# We need to apply first, then patch with the token

# Apply the collector CR (it will start but fail to auth until we inject the token)
$OC apply -f infra/phase22/otel-collector.yaml

# Wait for the SA to be created
sleep 10

# Generate the token
COLLECTOR_TOKEN=$($OC create token otel-collector-collector -n openshift-tempo --duration=8760h)

# Patch the collector config with the real token
$OC get opentelemetrycollector otel-collector -n openshift-tempo -o json | \
  sed "s|<COLLECTOR_SA_TOKEN>|${COLLECTOR_TOKEN}|g" | \
  $OC apply -f -

# Wait for the collector pod to restart with the updated config
$OC rollout status deployment/otel-collector-collector -n openshift-tempo --timeout=60s
```

> **Gotcha:** Using gRPC with Bearer token auth causes "credentials require transport level security" errors. That is why the Collector uses OTLP HTTP exporters (`otlphttp/dev`, etc.) to the gateway port 8080, not gRPC.

**Verify:**

```bash
$OC get pods -n openshift-tempo -l app.kubernetes.io/name=otel-collector-collector
# Expected: 1 pod Running

# Check collector logs for errors
$OC logs deploy/otel-collector-collector -n openshift-tempo --tail=20
# Expected: no "connection refused" or "unauthorized" errors
```

### 4b.6 Create Auto-Instrumentation CRs

The OTel Operator can auto-inject a .NET tracing agent into application pods via an init container. This requires zero application code changes -- you just annotate the Deployment.

> **Note:** Red Hat's OTel Operator does NOT ship a .NET auto-instrumentation image. We use the upstream community image `ghcr.io/open-telemetry/opentelemetry-operator/autoinstrumentation-dotnet:1.9.0`.

Create an Instrumentation CR in each app namespace:

```bash
for NS in sampleapi-dev sampleapi-sit sampleapi-uat sampleapi-prod; do
  $OC apply -f infra/phase22/otel-instrumentation.yaml -n $NS
done
```

Then add the auto-inject annotation to the Deployments. In a GitOps workflow, you add this to the Kustomize overlay patch. For quick testing:

```bash
# Annotate deployments to trigger auto-instrumentation injection
for SVC in sampleapi notificationapi; do
  $OC annotate deployment $SVC -n sampleapi-dev \
    instrumentation.opentelemetry.io/inject-dotnet="true" --overwrite
done

# Restart pods to pick up the init container
$OC rollout restart deployment sampleapi -n sampleapi-dev
$OC rollout restart deployment notificationapi -n sampleapi-dev
```

> **Important:** The .NET SDK defaults to `http/protobuf` for OTLP. The Instrumentation CR sets `OTEL_EXPORTER_OTLP_PROTOCOL=grpc` to match the Collector's gRPC receiver on port 4317.

**Verify:**

```bash
# Check instrumentation CRs exist
$OC get instrumentation -n sampleapi-dev
# Expected: dotnet-instrumentation

# Check that init container was injected
$OC get pod -n sampleapi-dev -l app=sampleapi \
  -o jsonpath='{.items[0].spec.initContainers[*].name}'
# Expected: includes "opentelemetry-auto-instrumentation-dotnet"
```

### 4b.7 Enable Console Tracing UI

The Cluster Observability Operator adds a "Traces" tab under Observe in the OpenShift Console. Create the UIPlugin CR:

```bash
$OC apply -f infra/phase22/uiplugin-distributed-tracing.yaml
```

**Verify:**

```bash
$OC get uiplugin distributed-tracing
# Expected: exists with no error conditions

# In the OpenShift Console: Observe → Traces
# Select a tenant (dev/sit/uat/prod) to see traces
```

> **Gotcha:** The Console shows "Instances without multi-tenancy are not supported" if TempoStack does not have `tenants.mode: openshift` and the gateway enabled. Both are set in our `tempostack.yaml`.

### 4b.8 Generate Test Traces

```bash
# Send requests through SampleApi → NotificationApi call chain
for i in $(seq 1 15); do
  curl -sk $APP_DEV_URL/api/WeatherForecast
  sleep 1
done

# Verify traces arrived in Tempo via the gateway
GATEWAY_HOST=$($OC get route tempo-tempo-gateway -n openshift-tempo -o jsonpath='{.spec.host}')
GRAFANA_TOKEN=$($OC create token grafana-sa -n devsecops-tools --duration=1h)
curl -sk -H "Authorization: Bearer $GRAFANA_TOKEN" \
  "https://${GATEWAY_HOST}/api/traces/v1/dev/tempo/api/search?limit=5" | jq '.traces | length'
# Expected: a number > 0 (traces found)
```

If you see traces in the output, the entire tracing pipeline is working: .NET app → OTel Collector → Tempo gateway → Tempo storage. Now we can connect Grafana to query these traces.

---

## Step 4c: Connect the Tempo Datasource (Distributed Tracing)

**Why:** Now that the tracing infrastructure is running (Step 4b), we connect Grafana so we can visualize traces alongside metrics and logs -- the "three pillars" of observability in a single dashboard.

The TempoStack gateway performs authentication via SubjectAccessReview. Each environment (dev, sit, uat, prod) is a separate tenant.

```yaml
# file: infra/phase22/grafana-datasource-tempo.yaml
# Connects Grafana to the Tempo gateway for distributed trace queries.
# Each tenant (dev/sit/uat/prod) has its own traces.
apiVersion: grafana.integreatly.org/v1beta1
kind: GrafanaDatasource
metadata:
  name: tempo-datasource
  namespace: devsecops-tools
spec:
  instanceSelector:
    matchLabels:
      dashboards: "grafana"
  datasource:
    name: Tempo
    type: tempo
    uid: tempo
    access: proxy
    # Gateway URL for the DEV tenant (default view).
    # Users can switch tenants by changing the datasource URL in Explore.
    url: "https://tempo-tempo-gateway.openshift-tempo.svc:8080/api/traces/v1/dev/tempo"
    jsonData:
      httpHeaderName1: Authorization
      tlsSkipVerify: true                  # <-- gateway uses self-signed cert
      tracesToMetrics:
        datasourceUid: prometheus           # <-- link traces to metrics
      nodeGraph:
        enabled: true                      # <-- service map visualization
    secureJsonData:
      httpHeaderValue1: "Bearer ${GRAFANA_TOKEN}"  # <-- same SA token (needs traces-read RBAC)
```

> **Key gotcha:** The gateway URL path must end with `/tempo`. Grafana appends `/api/search`, `/api/echo`, etc. after the base URL. Getting this path wrong is the most common Tempo datasource failure.

Apply (substitute the token placeholder) and verify:

```bash
envsubst < infra/phase22/grafana-datasource-tempo.yaml | $OC apply -f -

# Generate some traces
for i in $(seq 1 10); do
  curl -sk $APP_DEV_URL/api/WeatherForecast
done

# In Grafana: Explore > select Tempo datasource > Search > service.name=SampleApi
# You should see trace spans showing SampleApi -> NotificationApi call chain
```

---

## Step 5: Create the Application Dashboard (GrafanaDashboard CRD)

**Why:** This is the core deliverable. Instead of importing JSON through the Grafana UI (which does not survive pod restarts or cluster rebuilds), you declare the dashboard as a Kubernetes custom resource. The operator reconciles it into Grafana automatically.

The dashboard JSON we are using comes from `infra/phase21/grafana-dashboard-app.yaml` (generated in Phase 21). It contains panels for:

- **Top row (stat panels):** Available replicas, pod restarts (1h), request rate, error rate %, p99 latency, uptime
- **HTTP Traffic row:** Request rate by status code (timeseries), response latency p50/p95/p99 (timeseries)
- **Resource Usage row:** CPU usage by pod with limits overlay, memory usage by pod with limits overlay
- **Bottom row:** Network I/O (RX/TX bytes per second), pod restart history

All panels use a `$namespace` template variable so you can switch between DEV, SIT, UAT, and PROD from a single dropdown.

### The CRD Wrapper

The `GrafanaDashboard` CRD wraps the raw JSON. You can either inline the JSON or reference a ConfigMap. Inlining is simpler for GitOps:

```yaml
# file: infra/phase21/grafana-dashboard-app.yaml
# ----------------------------------------------------
# Declares the Application Health dashboard as a Kubernetes resource.
# The Grafana Operator watches this CRD and loads the JSON into the
# Grafana instance matched by instanceSelector.
#
# This dashboard follows the RED method (Rate, Errors, Duration) --
# the three golden signals for request-driven services.
#
# We inline the JSON so a single `oc apply` or ArgoCD sync deploys the dashboard.
apiVersion: grafana.integreatly.org/v1beta1
kind: GrafanaDashboard
metadata:
  name: app-health-dashboard
  namespace: devsecops-tools
  labels:
    app: grafana
    team: devsecops
spec:
  instanceSelector:
    matchLabels:
      dashboards: "grafana"              # <-- must match the Grafana CR label
  json: |
    {
      "annotations": { "list": [] },
      "editable": true,
      "fiscalYearStartMonth": 0,
      "graphTooltip": 1,
      "id": null,
      "links": [],
      "liveNow": false,
      "panels": [
        {
          "collapsed": false,
          "gridPos": { "h": 1, "w": 24, "x": 0, "y": 0 },
          "id": 100,
          "title": "Request Rate & Errors",
          "type": "row"
        },
        {
          "title": "Request Rate (req/s)",
          "type": "timeseries",
          "gridPos": { "h": 8, "w": 8, "x": 0, "y": 1 },
          "id": 1,
          "datasource": { "type": "prometheus", "uid": "${datasource}" },
          "targets": [
            {
              "expr": "sum(rate(http_request_duration_seconds_count{namespace=\"$namespace\", job=\"$service\"}[5m]))",
              "legendFormat": "Total req/s"
            }
          ],
          "fieldConfig": {
            "defaults": {
              "unit": "reqps",
              "custom": { "drawStyle": "line", "fillOpacity": 10 }
            }
          }
        },
        {
          "title": "Error Rate (%)",
          "type": "timeseries",
          "gridPos": { "h": 8, "w": 8, "x": 8, "y": 1 },
          "id": 2,
          "datasource": { "type": "prometheus", "uid": "${datasource}" },
          "targets": [
            {
              "expr": "100 * sum(rate(http_request_duration_seconds_count{namespace=\"$namespace\", job=\"$service\", status_code=~\"5..\"}[5m])) / sum(rate(http_request_duration_seconds_count{namespace=\"$namespace\", job=\"$service\"}[5m]))",
              "legendFormat": "5xx error %"
            }
          ],
          "fieldConfig": {
            "defaults": {
              "unit": "percent",
              "custom": { "drawStyle": "line", "fillOpacity": 10 },
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
          "title": "Error Count (5xx)",
          "type": "stat",
          "gridPos": { "h": 8, "w": 8, "x": 16, "y": 1 },
          "id": 3,
          "datasource": { "type": "prometheus", "uid": "${datasource}" },
          "targets": [
            {
              "expr": "sum(increase(http_request_duration_seconds_count{namespace=\"$namespace\", job=\"$service\", status_code=~\"5..\"}[$__range]))",
              "legendFormat": "5xx errors"
            }
          ],
          "fieldConfig": {
            "defaults": {
              "thresholds": {
                "mode": "absolute",
                "steps": [
                  { "color": "green", "value": null },
                  { "color": "red", "value": 1 }
                ]
              }
            }
          }
        },
        {
          "collapsed": false,
          "gridPos": { "h": 1, "w": 24, "x": 0, "y": 9 },
          "id": 101,
          "title": "Latency Distribution",
          "type": "row"
        },
        {
          "title": "Request Duration p50 / p95 / p99",
          "type": "timeseries",
          "gridPos": { "h": 8, "w": 16, "x": 0, "y": 10 },
          "id": 4,
          "datasource": { "type": "prometheus", "uid": "${datasource}" },
          "targets": [
            {
              "expr": "histogram_quantile(0.50, sum(rate(http_request_duration_seconds_bucket{namespace=\"$namespace\", job=\"$service\"}[5m])) by (le))",
              "legendFormat": "p50"
            },
            {
              "expr": "histogram_quantile(0.95, sum(rate(http_request_duration_seconds_bucket{namespace=\"$namespace\", job=\"$service\"}[5m])) by (le))",
              "legendFormat": "p95"
            },
            {
              "expr": "histogram_quantile(0.99, sum(rate(http_request_duration_seconds_bucket{namespace=\"$namespace\", job=\"$service\"}[5m])) by (le))",
              "legendFormat": "p99"
            }
          ],
          "fieldConfig": {
            "defaults": {
              "unit": "s",
              "custom": { "drawStyle": "line", "fillOpacity": 5 }
            }
          }
        },
        {
          "title": "Avg Request Duration",
          "type": "stat",
          "gridPos": { "h": 8, "w": 8, "x": 16, "y": 10 },
          "id": 5,
          "datasource": { "type": "prometheus", "uid": "${datasource}" },
          "targets": [
            {
              "expr": "sum(rate(http_request_duration_seconds_sum{namespace=\"$namespace\", job=\"$service\"}[5m])) / sum(rate(http_request_duration_seconds_count{namespace=\"$namespace\", job=\"$service\"}[5m]))",
              "legendFormat": "avg"
            }
          ],
          "fieldConfig": { "defaults": { "unit": "s" } }
        },
        {
          "collapsed": false,
          "gridPos": { "h": 1, "w": 24, "x": 0, "y": 18 },
          "id": 102,
          "title": "Pod Health",
          "type": "row"
        },
        {
          "title": "Running Pods",
          "type": "stat",
          "gridPos": { "h": 4, "w": 8, "x": 0, "y": 19 },
          "id": 6,
          "datasource": { "type": "prometheus", "uid": "${datasource}" },
          "targets": [
            {
              "expr": "count(kube_pod_status_ready{namespace=\"$namespace\", condition=\"true\", pod=~\"$service.*\"})",
              "legendFormat": "Ready pods"
            }
          ]
        },
        {
          "title": "Pod Restarts (last 1h)",
          "type": "stat",
          "gridPos": { "h": 4, "w": 8, "x": 8, "y": 19 },
          "id": 7,
          "datasource": { "type": "prometheus", "uid": "${datasource}" },
          "targets": [
            {
              "expr": "sum(increase(kube_pod_container_status_restarts_total{namespace=\"$namespace\", pod=~\"$service.*\"}[1h]))",
              "legendFormat": "Restarts"
            }
          ],
          "fieldConfig": {
            "defaults": {
              "thresholds": {
                "mode": "absolute",
                "steps": [
                  { "color": "green", "value": null },
                  { "color": "red", "value": 1 }
                ]
              }
            }
          }
        },
        {
          "title": "CPU Usage",
          "type": "timeseries",
          "gridPos": { "h": 8, "w": 12, "x": 0, "y": 23 },
          "id": 8,
          "datasource": { "type": "prometheus", "uid": "${datasource}" },
          "targets": [
            {
              "expr": "sum(rate(container_cpu_usage_seconds_total{namespace=\"$namespace\", pod=~\"$service.*\", container!=\"\"}[5m])) by (pod)",
              "legendFormat": "{{pod}}"
            }
          ],
          "fieldConfig": { "defaults": { "unit": "short", "custom": { "drawStyle": "line" } } }
        },
        {
          "title": "Memory Usage",
          "type": "timeseries",
          "gridPos": { "h": 8, "w": 12, "x": 12, "y": 23 },
          "id": 9,
          "datasource": { "type": "prometheus", "uid": "${datasource}" },
          "targets": [
            {
              "expr": "sum(container_memory_working_set_bytes{namespace=\"$namespace\", pod=~\"$service.*\", container!=\"\"}) by (pod)",
              "legendFormat": "{{pod}}"
            }
          ],
          "fieldConfig": { "defaults": { "unit": "bytes", "custom": { "drawStyle": "line" } } }
        }
      ],
      "refresh": "30s",
      "schemaVersion": 39,
      "tags": ["devsecops", "app-health", "RED"],
      "templating": {
        "list": [
          {
            "name": "datasource",
            "type": "datasource",
            "query": "prometheus",
            "current": { "text": "Prometheus", "value": "Prometheus" }
          },
          {
            "name": "namespace",
            "type": "custom",
            "query": "sampleapi-dev,sampleapi-sit,sampleapi-uat,sampleapi-prod",
            "current": { "text": "sampleapi-dev", "value": "sampleapi-dev" }
          },
          {
            "name": "service",
            "type": "custom",
            "query": "sampleapi,notificationapi",
            "current": { "text": "sampleapi", "value": "sampleapi" }
          }
        ]
      },
      "time": { "from": "now-1h", "to": "now" },
      "timepicker": {},
      "timezone": "browser",
      "title": "App Health (RED Method)",
      "uid": "devsecops-app-health",
      "version": 1
    }
```

### Understanding the Key Panels

Before you apply, take a moment to understand what each section does.

**Template variables (`$namespace`, `$service`, `$datasource`)** -- The `templating.list` block defines three dropdowns at the top of the dashboard. The `$namespace` dropdown lists all four environments (sampleapi-dev through sampleapi-prod). The `$service` dropdown lets you switch between sampleapi and notificationapi. The `$datasource` dropdown selects the Prometheus datasource (useful if you have multiple). These variables drive every panel via `namespace="$namespace"` and `job="$service"` in the PromQL.

**Datasource references** -- Each panel uses `"datasource": { "type": "prometheus", "uid": "${datasource}" }` instead of a hardcoded datasource name. The `${datasource}` variable resolves to whichever Prometheus datasource is selected in the dropdown. This makes the dashboard portable across Grafana instances.

**Rate and Errors row (top)** -- Three panels following the RED method: request rate (timeseries), error rate percentage (timeseries with threshold coloring), and a stat panel counting total 5xx errors over the dashboard time range. The threshold colors give instant signal: green under 1% errors, yellow under 5%, red above 5%.

**Latency Distribution row (middle)** -- A timeseries with three lines (p50, p95, p99) plus a stat panel showing the average. If p50 is low but p99 is high, you have outlier requests. If all three are high, the service is uniformly slow.

**Pod Health row (bottom)** -- Running pod count, restart count, CPU usage by pod, and memory usage by pod. When CPU approaches limits, you know to scale or increase resource limits in the Kustomize overlay.

Apply the dashboard:

```bash
$OC apply -f infra/phase21/grafana-dashboard-app.yaml
```

### Verify

```bash
# Check the CRD was created
$OC get grafanadashboard -n devsecops-tools
# Expected output (dashboards accumulate as you apply them; after all steps you will see):
#   NAME                       NO MATCHING INSTANCES   LAST RESYNC   AGE
#   app-health-dashboard                               38s           44h
#   infrastructure-dashboard                           38s           44h
#   pipeline-dashboard                                 38s           44h
#   slo-dashboard                                      38s           44h
#   logs-dashboard                                     38s           44h
#   traces-dashboard                                   38s           28h
#   k6-performance-dashboard                           38s           26h
# At this point you should see at least: app-health-dashboard

# Check the operator reconciled it (look at status conditions)
$OC get grafanadashboard app-health-dashboard -n devsecops-tools -o jsonpath='{.status}' | jq .
# Expected: no error conditions

# Verify in the Grafana UI:
# 1. Open Grafana -> Dashboards
# 2. You should see "App Health (RED Method)"
# 3. Open it -> select a namespace and service from the dropdowns
# 4. If the app is running and ServiceMonitors are active, you should see data
```

If panels show "No data," check:
1. Is user workload monitoring enabled? (`oc get pods -n openshift-user-workload-monitoring`)
2. Are ServiceMonitors deployed? (`oc get servicemonitor -n sampleapi-dev`)
3. Does the app expose `/metrics`? (kube-state-metrics panels like "Available Replicas" work regardless)

---

## Step 6: Create the Pipeline Dashboard

**Why:** Application dashboards tell you if the app is healthy. Pipeline dashboards tell you if your delivery process is healthy. A 15-minute average build time creeping to 25 minutes is a signal that your test suite or image build needs optimization -- long before developers start complaining.

This dashboard uses Jenkins Prometheus plugin metrics (`jenkins_builds_*`, `jenkins_job_*`). If the Jenkins Prometheus plugin is not installed, these panels will show "No data." The deployment tracking panels (bottom section) use kube-state-metrics and work regardless.

```yaml
# file: infra/phase21/grafana-dashboard-pipeline.yaml
# --------------------------------------------------------
# CI/CD Pipeline dashboard as a GrafanaDashboard CRD.
# Tracks Jenkins build metrics, success/failure rates, queue depth,
# and Jenkins infrastructure health.
#
# Requires: Jenkins Prometheus Metrics plugin for build metrics.
# The Jenkins Health row uses kube-state-metrics (always available).
apiVersion: grafana.integreatly.org/v1beta1
kind: GrafanaDashboard
metadata:
  name: pipeline-dashboard
  namespace: devsecops-tools
  labels:
    app: grafana
    team: devsecops
spec:
  instanceSelector:
    matchLabels:
      dashboards: "grafana"
  json: |
    {
      "annotations": { "list": [] },
      "editable": true,
      "panels": [
        {
          "collapsed": false,
          "gridPos": { "h": 1, "w": 24, "x": 0, "y": 0 },
          "id": 100,
          "title": "Build Overview",
          "type": "row"
        },
        {
          "title": "Builds (Last 24h)",
          "type": "stat",
          "gridPos": { "h": 6, "w": 6, "x": 0, "y": 1 },
          "id": 1,
          "datasource": { "type": "prometheus", "uid": "${datasource}" },
          "targets": [
            {
              "expr": "sum(increase(jenkins_builds_total[24h]))",
              "legendFormat": "Total builds"
            }
          ]
        },
        {
          "title": "Success Rate (24h)",
          "type": "gauge",
          "gridPos": { "h": 6, "w": 6, "x": 6, "y": 1 },
          "id": 2,
          "datasource": { "type": "prometheus", "uid": "${datasource}" },
          "targets": [
            {
              "expr": "100 * sum(increase(jenkins_builds_total{result=\"SUCCESS\"}[24h])) / sum(increase(jenkins_builds_total[24h]))",
              "legendFormat": "Success %"
            }
          ],
          "fieldConfig": {
            "defaults": {
              "unit": "percent",
              "min": 0, "max": 100,
              "thresholds": {
                "mode": "absolute",
                "steps": [
                  { "color": "red", "value": null },
                  { "color": "yellow", "value": 70 },
                  { "color": "green", "value": 90 }
                ]
              }
            }
          }
        },
        {
          "title": "Failed Builds (24h)",
          "type": "stat",
          "gridPos": { "h": 6, "w": 6, "x": 12, "y": 1 },
          "id": 3,
          "datasource": { "type": "prometheus", "uid": "${datasource}" },
          "targets": [
            {
              "expr": "sum(increase(jenkins_builds_total{result=\"FAILURE\"}[24h]))",
              "legendFormat": "Failures"
            }
          ],
          "fieldConfig": {
            "defaults": {
              "thresholds": {
                "mode": "absolute",
                "steps": [
                  { "color": "green", "value": null },
                  { "color": "red", "value": 1 }
                ]
              }
            }
          }
        },
        {
          "title": "Queue Size",
          "type": "stat",
          "gridPos": { "h": 6, "w": 6, "x": 18, "y": 1 },
          "id": 4,
          "datasource": { "type": "prometheus", "uid": "${datasource}" },
          "targets": [
            {
              "expr": "jenkins_queue_size_value",
              "legendFormat": "Queued"
            }
          ],
          "fieldConfig": {
            "defaults": {
              "thresholds": {
                "mode": "absolute",
                "steps": [
                  { "color": "green", "value": null },
                  { "color": "yellow", "value": 3 },
                  { "color": "red", "value": 10 }
                ]
              }
            }
          }
        },
        {
          "collapsed": false,
          "gridPos": { "h": 1, "w": 24, "x": 0, "y": 7 },
          "id": 101,
          "title": "Build Trends",
          "type": "row"
        },
        {
          "title": "Build Results Over Time",
          "type": "timeseries",
          "gridPos": { "h": 8, "w": 12, "x": 0, "y": 8 },
          "id": 5,
          "datasource": { "type": "prometheus", "uid": "${datasource}" },
          "targets": [
            {
              "expr": "sum(increase(jenkins_builds_total{result=\"SUCCESS\"}[1h]))",
              "legendFormat": "Success"
            },
            {
              "expr": "sum(increase(jenkins_builds_total{result=\"FAILURE\"}[1h]))",
              "legendFormat": "Failure"
            },
            {
              "expr": "sum(increase(jenkins_builds_total{result=\"UNSTABLE\"}[1h]))",
              "legendFormat": "Unstable"
            }
          ],
          "fieldConfig": {
            "defaults": { "custom": { "drawStyle": "bars", "fillOpacity": 80, "stacking": { "mode": "normal" } } },
            "overrides": [
              { "matcher": { "id": "byName", "options": "Success" }, "properties": [{ "id": "color", "value": { "fixedColor": "green" } }] },
              { "matcher": { "id": "byName", "options": "Failure" }, "properties": [{ "id": "color", "value": { "fixedColor": "red" } }] },
              { "matcher": { "id": "byName", "options": "Unstable" }, "properties": [{ "id": "color", "value": { "fixedColor": "yellow" } }] }
            ]
          }
        },
        {
          "title": "Build Duration (avg)",
          "type": "timeseries",
          "gridPos": { "h": 8, "w": 12, "x": 12, "y": 8 },
          "id": 6,
          "datasource": { "type": "prometheus", "uid": "${datasource}" },
          "targets": [
            {
              "expr": "jenkins_builds_duration_milliseconds_summary{quantile=\"0.5\"} / 1000",
              "legendFormat": "p50"
            },
            {
              "expr": "jenkins_builds_duration_milliseconds_summary{quantile=\"0.99\"} / 1000",
              "legendFormat": "p99"
            }
          ],
          "fieldConfig": {
            "defaults": { "unit": "s", "custom": { "drawStyle": "line" } }
          }
        },
        {
          "collapsed": false,
          "gridPos": { "h": 1, "w": 24, "x": 0, "y": 16 },
          "id": 102,
          "title": "Jenkins Health",
          "type": "row"
        },
        {
          "title": "Jenkins Controller Pods",
          "type": "stat",
          "gridPos": { "h": 4, "w": 8, "x": 0, "y": 17 },
          "id": 7,
          "datasource": { "type": "prometheus", "uid": "${datasource}" },
          "targets": [
            {
              "expr": "kube_deployment_status_available_replicas{namespace=\"devsecops-tools\", deployment=\"jenkins\"}",
              "legendFormat": "Available"
            }
          ]
        },
        {
          "title": "Agent Pods Running",
          "type": "stat",
          "gridPos": { "h": 4, "w": 8, "x": 8, "y": 17 },
          "id": 8,
          "datasource": { "type": "prometheus", "uid": "${datasource}" },
          "targets": [
            {
              "expr": "count(kube_pod_info{namespace=\"devsecops-tools\", pod=~\".*agent.*\"})",
              "legendFormat": "Active agents"
            }
          ]
        },
        {
          "title": "Jenkins CPU Usage",
          "type": "timeseries",
          "gridPos": { "h": 8, "w": 24, "x": 0, "y": 21 },
          "id": 9,
          "datasource": { "type": "prometheus", "uid": "${datasource}" },
          "targets": [
            {
              "expr": "sum(rate(container_cpu_usage_seconds_total{namespace=\"devsecops-tools\", pod=~\"jenkins.*\", container!=\"\"}[5m])) by (pod)",
              "legendFormat": "{{pod}}"
            }
          ],
          "fieldConfig": { "defaults": { "unit": "short", "custom": { "drawStyle": "line" } } }
        }
      ],
      "refresh": "1m",
      "schemaVersion": 39,
      "tags": ["devsecops", "pipeline", "jenkins"],
      "templating": {
        "list": [
          {
            "name": "datasource",
            "type": "datasource",
            "query": "prometheus",
            "current": { "text": "Prometheus", "value": "Prometheus" }
          }
        ]
      },
      "time": { "from": "now-24h", "to": "now" },
      "title": "Pipeline Execution",
      "uid": "devsecops-pipeline"
    }
```

Apply:

```bash
$OC apply -f infra/phase21/grafana-dashboard-pipeline.yaml
```

### Verify

```bash
$OC get grafanadashboard -n devsecops-tools
# Expected output (you should now see both dashboards):
#   NAME                       NO MATCHING INSTANCES   LAST RESYNC   AGE
#   app-health-dashboard                               38s           44h
#   pipeline-dashboard                                 38s           44h

# In Grafana UI: Dashboards -> "Pipeline Execution" should appear
# The "Jenkins Health" row (bottom) will show data immediately because
# kube-state-metrics is always available. The build metrics panels require the
# Jenkins Prometheus Metrics plugin.
```

### Understanding the Pipeline Dashboard Panels

**Build Overview (top row)** -- Four stat panels covering volume and quality:
- Builds (Last 24h): how active is the team?
- Success Rate gauge (24h window): the "SLO" for your CI pipeline. Red below 70%, green above 90%.
- Failed Builds: red if any failures occurred. Zero failures = green.
- Queue Size: how many builds are waiting. Yellow at 3+, red at 10+ (indicates agent scaling issues).

**Build Trends (middle row)** -- Two timeseries panels:
- Build Results Over Time: stacked bar chart with color-coded results (green=success, red=failure, yellow=unstable). Shows patterns like "failures cluster on Friday afternoons."
- Build Duration: p50 and p99 duration trends. If p99 is creeping up, your pipeline needs optimization.

**Jenkins Health (bottom row)** -- Three panels tracking the Jenkins infrastructure itself:
- Jenkins Controller Pods: is the controller running?
- Agent Pods Running: how many ephemeral agents are currently active?
- Jenkins CPU Usage: timeseries showing CPU consumption by Jenkins pods. Useful for capacity planning.

---

## Step 7: Deploy Dashboards via GitOps

**Why:** Applying dashboards with `oc apply` works for initial setup, but it does not give you version control, peer review, or rollback. Storing `GrafanaDashboard` CRDs in the `app-gitops` repository and syncing them through ArgoCD completes the "dashboards as code" loop.

### 7a: Add Dashboard CRDs to the GitOps Repository

Create a dedicated directory in the gitops repo:

```
app-gitops/
  monitoring/
    grafana/
      kustomization.yaml
      grafana-instance.yaml
      grafana-rbac.yaml
      grafana-datasource-prometheus.yaml
      grafana-datasource-loki.yaml
      grafana-datasource-tempo.yaml
      grafana-dashboard-app-crd.yaml
      grafana-dashboard-pipeline-crd.yaml
      grafana-dashboard-infrastructure-crd.yaml
      grafana-dashboard-slo-crd.yaml
      grafana-dashboard-logs-crd.yaml
      grafana-dashboard-traces-crd.yaml
```

The `kustomization.yaml`:

```yaml
# file: app-gitops/monitoring/grafana/kustomization.yaml
# -------------------------------------------------------
# Kustomize resource list for Grafana observability stack.
# ArgoCD syncs this directory to manage dashboards as code.
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
namespace: devsecops-tools
resources:
  - grafana-instance.yaml
  - grafana-rbac.yaml
  - grafana-datasource-prometheus.yaml
  - grafana-datasource-loki.yaml
  - grafana-datasource-tempo.yaml
  - grafana-dashboard-app-crd.yaml
  - grafana-dashboard-pipeline-crd.yaml
  - grafana-dashboard-infrastructure-crd.yaml
  - grafana-dashboard-slo-crd.yaml
  - grafana-dashboard-logs-crd.yaml
  - grafana-dashboard-traces-crd.yaml
```

### 7b: Create an ArgoCD Application for Grafana

```yaml
# file: app-gitops/argocd/app-grafana.yaml
# ------------------------------------------
# ArgoCD Application that syncs all Grafana resources (instance, datasources,
# dashboards) from the gitops repo. Auto-sync keeps dashboards in sync with Git.
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: grafana-observability
  namespace: openshift-gitops
  labels:
    team: devsecops
    app.kubernetes.io/part-of: observability
spec:
  project: devsecops
  source:
    repoURL: ${GITLAB_URL}/devsecops/app-gitops.git  # <-- Replace with your GitLab URL
    path: monitoring/grafana
    targetRevision: main
  destination:
    server: https://kubernetes.default.svc
    namespace: devsecops-tools
  syncPolicy:
    automated:                           # <-- auto-sync: push to Git = deploy to cluster
      prune: true                        # <-- remove resources deleted from Git
      selfHeal: true                     # <-- revert manual changes in the cluster
    syncOptions:
      - CreateNamespace=true
```

### 7c: The GitOps Workflow for Dashboard Changes

Once this is in place, the workflow for updating a dashboard is:

1. A developer edits `grafana-dashboard-app-crd.yaml` in a feature branch (e.g., adding a new panel).
2. They open a merge request in GitLab.
3. A teammate reviews the JSON diff -- "this new PromQL expression looks correct."
4. On merge to main, ArgoCD auto-syncs.
5. The Grafana Operator picks up the updated `GrafanaDashboard` CR.
6. The dashboard updates in Grafana within the `resyncPeriod` (5 minutes).

No manual Grafana UI clicks. No "who changed that panel last week?" questions. Git history is the audit trail.

### Verify the Full Chain

```bash
# Push the monitoring/grafana directory to GitLab (via the gitops repo)
cd /path/to/app-gitops
git add monitoring/grafana/
git commit -m "feat: add Grafana dashboards as code"
git push origin main

# Apply the ArgoCD Application
$OC apply -f app-gitops/argocd/app-grafana.yaml

# Watch ArgoCD sync
argocd app get grafana-observability
# Expected: Synced, Healthy

# Verify all datasources
$OC get grafanadatasource -n devsecops-tools
# Expected output:
#   NAME                    NO MATCHING INSTANCES   LAST RESYNC   AGE
#   loki-datasource                                 39s           44h
#   prometheus-datasource                           39s           44h
#   tempo-datasource                                39s           28h

# Verify all dashboards
$OC get grafanadashboard -n devsecops-tools
# Expected output:
#   NAME                       NO MATCHING INSTANCES   LAST RESYNC   AGE
#   app-health-dashboard                               38s           44h
#   infrastructure-dashboard                           38s           44h
#   k6-performance-dashboard                           38s           26h
#   logs-dashboard                                     38s           44h
#   pipeline-dashboard                                 38s           44h
#   slo-dashboard                                      38s           44h
#   traces-dashboard                                   38s           28h

# Open Grafana and confirm all dashboards are visible with data
GRAFANA_URL=$($OC get routes -n devsecops-tools -l app=grafana -o jsonpath='{.items[0].spec.host}')
echo "https://${GRAFANA_URL}"
```

---

## Recap

Here is what you built in this module, and why each piece matters:

| What | Why | How |
|------|-----|-----|
| Grafana Operator v5 | Manages Grafana instances and dashboards declaratively via CRDs | OLM Subscription in `devsecops-tools` namespace |
| Grafana Instance | Provides the visualization UI with persistent state | `Grafana` CR with route and auth config |
| RBAC for Thanos + Tempo | Grafana needs read access to metrics and traces | `ClusterRoleBinding` to `cluster-monitoring-view` + `tempostack-traces-read` |
| Prometheus Datasource | Connects Grafana to Thanos Querier (port 9091) with bearer auth | `GrafanaDatasource` CR with SA token |
| Loki Datasource | Connects Grafana to log backend for LogQL queries | `GrafanaDatasource` CR pointing to Loki gateway |
| Tempo Datasource | Connects Grafana to distributed tracing backend | `GrafanaDatasource` CR pointing to Tempo gateway per tenant |
| Application Dashboard | App health: replicas, errors, latency, CPU, memory | `GrafanaDashboard` CR with inline JSON |
| Pipeline Dashboard | CI/CD health: build status, duration, success rate | `GrafanaDashboard` CR with inline JSON |
| Infrastructure Dashboard | Namespace resource usage, PVC, quotas | `GrafanaDashboard` CR with inline JSON |
| SLO Burn Rate Dashboard | Error budget remaining, burn rate windows. **Depends on recording rules from Module 12** (`sampleapi:http_error_ratio:rate5m`, `sampleapi:http_error_ratio:rate1h`, `sampleapi:http_error_ratio:rate3d`, `sampleapi:slo_target`) defined in `prometheus-rules-slo.yaml`. Panels show "No data" if those PrometheusRules are not applied. | `GrafanaDashboard` CR with inline JSON |
| Log Analytics Dashboard | Log volume, error patterns, top errors | `GrafanaDashboard` CR with inline JSON |
| Traces Explorer Dashboard | Distributed trace visualization, service map | `GrafanaDashboard` CR with inline JSON |
| GitOps integration | Version-controlled, reviewable, auto-synced dashboards | ArgoCD Application pointing to `monitoring/grafana/` |

The chain is: Git commit --> ArgoCD sync --> Grafana Operator reconcile --> Dashboard visible in Grafana. Every link is declarative and auditable.

---

## Common Mistakes

### 1. Empty query results despite Thanos URL being correct

**Cause:** Missing `ClusterRoleBinding` for the Grafana ServiceAccount. Thanos Querier requires authentication.

**Fix:** Ensure the `cluster-monitoring-view` binding exists (Step 3a). Verify with:

```bash
$OC auth can-i get pods --as=system:serviceaccount:devsecops-tools:grafana-sa --all-namespaces
# If this returns "no", the binding is missing or the SA name is wrong.
```

Also check that the SA name in the binding matches the actual SA created by the operator:

```bash
$OC get sa -n devsecops-tools | grep grafana
# Look for the SA name -- it may be "grafana-sa" or "grafana-<instance-name>-sa"
```

### 2. Datasource name or UID mismatch

**Cause:** The `GrafanaDatasource` uses a `name` or auto-generated `uid` that does not match what the dashboard JSON expects. Our dashboards use a `$datasource` template variable (`"datasource": { "type": "prometheus", "uid": "${datasource}" }`) to avoid hardcoding, but the variable must resolve to an existing datasource of type `prometheus`.

**Fix:** Ensure the `GrafanaDatasource` CRD has `type: prometheus` and `name: Prometheus`. The dashboard's `$datasource` template variable queries for datasources of type `prometheus` and presents them in a dropdown. If you renamed the datasource to something else (e.g., `Thanos`), the variable will still find it as long as the type is correct.

### 3. Dashboard JSON syntax errors

**Cause:** Invalid JSON in the `spec.json` field of the `GrafanaDashboard` CR. A single trailing comma or unescaped quote breaks the entire dashboard.

**Fix:** Validate the JSON before applying:

```bash
# Extract the json field and validate
$OC get grafanadashboard app-health-dashboard -n devsecops-tools -o jsonpath='{.spec.json}' | jq . > /dev/null
# If jq exits with an error, the JSON is malformed.

# Or validate before applying:
python3 -m json.tool < grafana-dashboard-app.json > /dev/null
```

### 4. Token expiration

**Cause:** On OCP 4.11+, `oc create token` generates bound tokens with a default 1-hour expiry. The Grafana datasource stops working after an hour.

**Fix:** Either use `--duration=8760h` (1 year) when creating the token, or create a `kubernetes.io/service-account-token` Secret (shown in Step 3a) which generates a non-expiring token.

### 5. Grafana Operator reconciles over manual UI edits

**Cause:** Someone edits a dashboard panel in the Grafana UI. On the next `resyncPeriod` (5 minutes), the operator overwrites the change with the CRD definition.

**This is working as intended.** The CRD is the source of truth. To make permanent changes, edit the CRD YAML in Git and let ArgoCD sync it. If you need to prototype in the Grafana UI first, export the JSON when you are done and paste it into the CRD.

### 6. `instanceSelector` does not match

**Cause:** The `GrafanaDashboard` or `GrafanaDatasource` has an `instanceSelector.matchLabels` that does not match any `Grafana` CR's labels.

**Fix:** Ensure the `Grafana` CR's `metadata.labels` includes `dashboards: "grafana"` and that every `GrafanaDatasource` and `GrafanaDashboard` has `instanceSelector.matchLabels.dashboards: "grafana"` to match. The operator uses this label selector to determine which Grafana instance should receive the dashboard.

---

## Challenge: Add a Custom Panel

Add a new panel to the application dashboard that shows **HTTP request duration heatmap** -- a visualization that reveals latency distribution patterns that percentile lines miss.

Requirements:

1. Panel type: `heatmap`
2. PromQL query: `sum(increase(http_request_duration_seconds_bucket{namespace="$namespace", job="$service"}[5m])) by (le)`
3. Grid position: below the "Memory Usage" panel (y: 31)
4. Size: full width (w: 24, h: 8)
5. Y-axis unit: seconds
6. Color scheme: green-to-red (low-to-high density)

Hints:
- The `heatmap` panel type uses `"calculate": true` in `options` to bucket the histogram data.
- You need `"format": "time_series"` in the target, not `"format": "heatmap"` -- Grafana handles the conversion.
- Add the panel JSON to `grafana-dashboard-app-crd.yaml`, commit, push, and watch ArgoCD sync it.

Verify your panel works by sending traffic to the application (`curl` in a loop) and checking that the heatmap lights up:

```bash
# Generate some traffic to see data in the heatmap
for i in $(seq 1 100); do
  curl -sk $APP_DEV_URL/api/WeatherForecast > /dev/null
  sleep 0.1
done
```

---

## Self-Assessment

Answer these questions to confirm you have internalized the material:

1. **Why does Grafana need a `ClusterRoleBinding` to `cluster-monitoring-view`, and what happens if you skip it?**
   Expected answer: Thanos Querier requires authentication. Without the binding, the Grafana ServiceAccount's bearer token has no permission to query metrics, and every panel returns empty results.

2. **What is the difference between importing a dashboard JSON through the Grafana UI and creating a `GrafanaDashboard` CRD?**
   Expected answer: UI imports are stored in Grafana's internal SQLite/PostgreSQL database and are lost on pod restart (unless persistent storage is configured). CRD-based dashboards are reconciled by the operator from the Kubernetes resource, making them declarative, version-controlled, and resilient to pod restarts.

3. **If a developer edits a dashboard panel in the Grafana UI, what happens after 5 minutes?**
   Expected answer: The Grafana Operator's reconcile loop (set by `resyncPeriod: 5m`) detects drift between the CRD definition and the live dashboard, then overwrites the UI change with the CRD JSON. The CRD (and therefore Git) is the source of truth.

4. **Why does the application dashboard use a `$namespace` template variable instead of hardcoding `sampleapi-dev`?**
   Expected answer: The same dashboard serves all four environments (DEV/SIT/UAT/PROD). The dropdown lets you compare metrics across environments or focus on one. This avoids duplicating four nearly-identical dashboards.

5. **The pipeline dashboard shows "No data" for the Jenkins build panels but the Jenkins Health panels work fine. What is the most likely cause?**
   Expected answer: The Jenkins Prometheus Metrics plugin is not installed. Build metrics (`jenkins_builds_total`, `jenkins_builds_duration_milliseconds_summary`, `jenkins_queue_size_value`) are exposed by this plugin. The Jenkins Health panels use `kube_deployment_status_available_replicas` and `kube_pod_info` from kube-state-metrics, which is always available.

6. **You want to add a Loki logs panel to the application dashboard. What two things must be true for it to work?**
   Expected answer: (a) The OpenShift Logging operator must be installed with a LokiStack backend, and (b) a `GrafanaDatasource` CRD for Loki must be created pointing to `https://logging-loki-gateway-http.openshift-logging.svc:8080/api/logs/v1/application/` (including the tenant path) with a valid bearer token.

---

## What's Next

**Module 14: Performance Testing as Quality Gate** -- You have dashboards that show latency and throughput. But how do you generate realistic load to populate those dashboards and find breaking points before production does? Module 14 introduces load testing with k6, integrating test results into the pipeline dashboard, and setting performance budgets that fail the pipeline when p99 latency exceeds your SLO.
