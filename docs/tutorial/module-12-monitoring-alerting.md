# Module 12: Monitoring and Alerting

**Track:** Observability
**Duration:** ~75 minutes
**Difficulty:** Intermediate

---

## What You'll Learn

By the end of this module you will be able to:

1. Explain why OpenShift ships with Prometheus but still requires explicit opt-in for user workload monitoring.
2. Enable user workload monitoring on an OpenShift cluster.
3. Instrument a .NET application to expose Prometheus metrics via `prometheus-net`.
4. Create a `ServiceMonitor` that tells Prometheus where to scrape your app.
5. Write `PrometheusRule` alert definitions grounded in SLI/SLO/error-budget thinking.
6. Verify that metrics flow end-to-end and that alerts fire when thresholds are breached.

---

## Prerequisites

> **Environment variables:** Before running any commands, source the environment file:
> ```bash
> source ./env.sh
> ```
> This sets `$OC`, `$APPS_DOMAIN`, `$NS_TOOLS`, and all other cluster-specific variables used throughout this module. See `env.sh` for the full variable list.

Before starting this module you need:

| Requirement | How to verify |
|---|---|
| Cluster admin access to OpenShift 4.x | `$OC whoami` returns a cluster-admin user |
| The SampleApi application deployed in `sampleapi-dev` | `$OC get deployment sampleapi -n $NS_DEV` shows READY |
| The SampleApi application is healthy | `curl -sk ${APP_DEV_URL}/healthz` returns `{"status":"healthy","timestamp":"2026-03-10T05:25:02.3751347Z"}` |
| The `sampleapi` Service with a port named `http` on 8080 | `$OC get svc sampleapi -n $NS_DEV -o jsonpath='{.spec.ports[0].name}'` returns `http` |
| `oc` CLI available | `$OC version` |
| Familiarity with YAML and PromQL basics | Modules 1-11 completed |

---

## Part 1: Concepts -- Why Before How

### 1.1 The Problem Monitoring Solves

You deployed SampleApi in earlier modules and added logging in Module 11. It is running. But "running" answers exactly one question: is the process alive? It tells you nothing about:

- How many requests per second is it handling?
- What percentage of those requests are errors?
- How long do users wait for a response?
- Is memory creeping toward the OOM limit?

Without answers to these questions, you are flying blind. You find out your app is broken when a user calls you, not when a metric crosses a threshold. Monitoring turns reactive firefighting into proactive engineering.

### 1.2 OpenShift Monitoring Architecture

OpenShift ships with a full Prometheus stack in the `openshift-monitoring` namespace. Out of the box it monitors the platform itself -- API server latency, etcd health, node CPU, kubelet status. It does **not** monitor your applications.

The architecture has two layers:

```
+---------------------------------------------------------------+
|  openshift-monitoring  (platform)                              |
|                                                                |
|  Prometheus  ----scrapes---->  kube-state-metrics               |
|              ----scrapes---->  node-exporter                    |
|              ----scrapes---->  API server, etcd, kubelet        |
|                                                                |
|  Alertmanager  <----fires----  PrometheusRule (platform alerts) |
+---------------------------------------------------------------+
         |
         |  enableUserWorkload: true    <-- you flip this switch
         v
+---------------------------------------------------------------+
|  openshift-user-workload-monitoring  (your apps)               |
|                                                                |
|  prometheus-user-workload  ----scrapes---->  your pods          |
|                                              /metrics endpoint |
|                                                                |
|  thanos-ruler  <----fires----  PrometheusRule (your alerts)    |
+---------------------------------------------------------------+
```

The critical insight: **user workload monitoring is disabled by default**. Until you flip the `enableUserWorkload` switch, Prometheus will never look at your application pods. This is a deliberate design choice -- Red Hat does not want user workloads generating unbounded cardinality that could destabilize the platform monitoring stack.

### 1.3 SLI, SLO, and Error Budgets -- The Mental Model

Before writing alert rules, you need a framework for deciding *what* to alert on and *when*. Random threshold alerts ("CPU > 80%!") create noise. SLO-based alerts create signal.

**SLI (Service Level Indicator)** -- a measurable property of your service that users care about. Three golden signals:

| SLI | What It Measures | PromQL Example |
|---|---|---|
| Availability | Fraction of requests that succeed (non-5xx) | `1 - (rate(http_request_duration_seconds_count{status_code=~"5.."}[5m]) / rate(http_request_duration_seconds_count[5m]))` |
| Latency | How long requests take (p50, p90, p99) | `histogram_quantile(0.99, sum(rate(http_request_duration_seconds_bucket[5m])) by (le))` |
| Throughput | Requests per second | `sum(rate(http_request_duration_seconds_count[5m]))` |

**SLO (Service Level Objective)** -- a target value for an SLI, expressed as a percentage over a time window.

Example: "99.9% of requests will return a non-5xx response, measured over a rolling 30-day window."

**Error Budget** -- the amount of failure your SLO permits. This is the key idea. An SLO of 99.9% over 30 days means:

```
Total minutes in 30 days:   30 * 24 * 60 = 43,200 minutes
Allowed downtime:           43,200 * 0.001 = 43.2 minutes
                            ^^^^^^^^^^^^^^^^
                            That is your error budget.
```

| SLO Target | Monthly Error Budget | Meaning |
|---|---|---|
| 99% | 7.2 hours | Generous. A bad deploy can take an hour to fix. |
| 99.9% | 43.2 minutes | Tight. You need automated rollback. |
| 99.95% | 21.6 minutes | Very tight. Canary deploys are mandatory. |
| 99.99% | 4.3 minutes | Extreme. Active-active multi-region. |

For our SampleApi in DEV, 99.9% is a reasonable starting point.

### 1.4 Multi-Window Burn Rate Alerting

Naive alerting says "fire if error rate > 0.1% for 5 minutes." The problem: a brief spike at 3am that self-resolves will page you. A slow leak that burns 40% of your monthly budget over a week will not.

Multi-window burn rate alerting fixes this by asking: "At the current rate of errors, how fast are we burning through our monthly error budget?"

**Burn rate** = (observed error rate) / (SLO-permitted error rate)

For a 99.9% SLO, the permitted error rate is 0.1%. If your actual error rate is 1%, your burn rate is 10x -- you would exhaust a 30-day error budget in 3 days.

The standard approach uses two windows per alert to catch both fast burns and slow leaks:

| Alert | Burn Rate | Short Window | Long Window | Catches |
|---|---|---|---|---|
| Critical (page) | 14.4x | 1 hour | 5 minutes | Fast burn: budget gone in 2 days |
| Warning (ticket) | 6x | 6 hours | 30 minutes | Medium burn: budget gone in 5 days |
| Info (dashboard) | 1x | 3 days | 6 hours | Slow leak: budget gone in 30 days |

Both windows must be breaching simultaneously. This eliminates false alarms from brief spikes (long window catches it) and delayed alerts from slow leaks (short window catches it).

> **Callout -- Why two windows?**
> The short window ensures you detect the problem quickly. The long window ensures it is sustained enough to be real. Neither alone is sufficient. A 5-minute spike that recovers should not page anyone at 3am.

---

## Part 2: Hands-On Steps

### Step 1: Enable User Workload Monitoring (~10 minutes)

**Why this step matters:** Without this, the Prometheus instance in `openshift-monitoring` has no mandate to look at pods in your application namespaces. You are telling the cluster "yes, I want you to monitor user workloads too."

Create the ConfigMap that flips the switch:

```yaml
# file: infra/phase20/user-workload-monitoring.yaml
# Enables OpenShift User Workload Monitoring (Prometheus for user namespaces)
#
# By default, OCP only monitors platform components (openshift-* namespaces).
# This ConfigMap enables monitoring for user workloads (sampleapi-dev/sit/uat/prod).
---
apiVersion: v1
kind: ConfigMap
metadata:
  name: cluster-monitoring-config
  namespace: openshift-monitoring           # <-- must be this namespace, not yours
data:
  config.yaml: |
    # This single line is the magic switch.
    # It causes the cluster monitoring operator to deploy
    # prometheus-user-workload and thanos-ruler in a new namespace.
    enableUserWorkload: true

    # Platform Prometheus retention and resources.
    # These are optional but recommended to prevent unbounded disk usage.
    prometheusK8s:
      retention: 15d                        # <-- how long to keep platform metrics
      resources:
        requests:
          cpu: 200m
          memory: 1Gi
        limits:
          cpu: "2"
          memory: 4Gi
      # Persistent storage for platform Prometheus (survives pod restarts).
      # Without this, a pod restart loses all metric history.
      volumeClaimTemplate:
        spec:
          storageClassName: gp3-csi         # <-- adjust to your cluster's storage class
          resources:
            requests:
              storage: 40Gi

---
# Optional: tune the user workload Prometheus instance separately.
# This lives in a different namespace from the platform one above.
apiVersion: v1
kind: ConfigMap
metadata:
  name: user-workload-monitoring-config
  namespace: openshift-user-workload-monitoring  # <-- auto-created by the operator
data:
  config.yaml: |
    prometheus:
      retention: 15d                        # <-- how long to keep YOUR app metrics
      resources:
        requests:
          cpu: 100m
          memory: 512Mi
        limits:
          cpu: "1"
          memory: 2Gi
      # Persistent storage for user workload Prometheus
      volumeClaimTemplate:
        spec:
          storageClassName: gp3-csi
          resources:
            requests:
              storage: 20Gi

    # Thanos Ruler evaluates your PrometheusRule alert definitions
    thanosRuler:
      resources:
        requests:
          cpu: 50m
          memory: 128Mi
        limits:
          cpu: 200m
          memory: 512Mi
```

Apply it:

```bash
# The phase14 file provides a basic version (no PVC, minimal resources).
# The phase20 file shown above adds persistent storage and production-grade resources.
# Either file enables user workload monitoring; phase20 is recommended.
$OC apply -f infra/phase20/user-workload-monitoring.yaml
```

> **Callout -- Cluster-admin required.**
> The `cluster-monitoring-config` ConfigMap lives in `openshift-monitoring`. Only cluster-admin can modify it. This is intentional -- enabling user workload monitoring has cluster-wide resource implications.

#### Verify Step 1

Wait approximately 60 seconds for the monitoring operator to reconcile, then check:

```bash
# New pods should appear in the user-workload-monitoring namespace
$OC get pods -n openshift-user-workload-monitoring
```

Expected output:

```
NAME                                   READY   STATUS    RESTARTS   AGE
prometheus-operator-56cf464b4d-gffzl   2/2     Running   8          3d
prometheus-user-workload-0             6/6     Running   24         3d
prometheus-user-workload-1             6/6     Running   6          26h
thanos-ruler-user-workload-0           4/4     Running   16         3d
thanos-ruler-user-workload-1           4/4     Running   16         3d
```

If you see `Pending` pods, check for resource pressure:

```bash
$OC describe pod prometheus-user-workload-0 -n openshift-user-workload-monitoring | grep -A5 Events
```

The most common cause of `Pending` is insufficient memory on worker nodes. The user workload Prometheus requests 512Mi by default.

```bash
# Quick win: confirm Prometheus is up and accepting queries
$OC exec -n openshift-user-workload-monitoring prometheus-user-workload-0 -c prometheus \
  -- curl -s http://localhost:9090/-/healthy
# Expected: "Prometheus Server is Healthy."
```

---

### Step 2: Instrument the .NET App with prometheus-net (~15 minutes)

**Why this step matters:** Prometheus works on a pull model -- it scrapes an HTTP endpoint on your application. Your app must expose a `/metrics` endpoint that returns metrics in the Prometheus exposition format. The `prometheus-net` NuGet package does this with two lines of code.

Without instrumentation, Prometheus can still collect infrastructure metrics (CPU, memory, network) from cAdvisor and kube-state-metrics. But you will not get request rates, error counts, or latency histograms -- the metrics that actually tell you if your *application* is healthy.

#### 2a. Add the NuGet package

In your `SampleApi.csproj`, add the `prometheus-net.AspNetCore` package:

```xml
<!-- app-source/src/SampleApi/SampleApi.csproj (showing relevant packages only) -->
<!-- The actual file also includes Npgsql.EntityFrameworkCore.PostgreSQL, StackExchange.Redis, -->
<!-- OpenTelemetry packages, and other Phase 17/22 additions. The prometheus-net package below -->
<!-- is the one that matters for this module. -->
<Project Sdk="Microsoft.NET.Sdk.Web">

  <PropertyGroup>
    <TargetFramework>net8.0</TargetFramework>
    <Nullable>enable</Nullable>
    <ImplicitUsings>enable</ImplicitUsings>
    <AssemblyName>SampleApi</AssemblyName>
    <RootNamespace>SampleApi</RootNamespace>
  </PropertyGroup>

  <ItemGroup>
    <PackageReference Include="Microsoft.Extensions.Diagnostics.HealthChecks" Version="8.0.0" />
    <PackageReference Include="Swashbuckle.AspNetCore" Version="6.5.0" />
    <!-- ... other Phase 17/22 packages omitted for clarity ... -->
    <!-- Prometheus metrics exporter for ASP.NET Core -->
    <!-- Automatically collects: http_request_duration_seconds (histogram+count), http_requests_in_progress -->
    <PackageReference Include="prometheus-net.AspNetCore" Version="8.2.1" />
  </ItemGroup>

</Project>
```

The `prometheus-net.AspNetCore` package provides:

| Metric | Type | What It Measures |
|---|---|---|
| `http_request_duration_seconds` | Histogram | Request duration distribution (buckets from 1ms to 30s). The `_count` suffix gives total requests; the `_bucket` suffix gives per-bucket counts for quantile computation. Labeled by `method`, `controller`, `action`, `status_code`. |
| `http_requests_in_progress` | Gauge | Number of requests currently being processed |
| `dotnet_collection_count_total` | Counter | .NET garbage collection count by generation |
| `process_cpu_seconds_total` | Counter | CPU time consumed by the process |
| `process_working_set_bytes` | Gauge | Memory used by the process |

> **Key insight:** `prometheus-net` v8.x uses `http_request_duration_seconds` as the primary metric. The `_count` sub-metric (`http_request_duration_seconds_count`) acts as a request counter, and the `_bucket` sub-metric provides the histogram data for latency quantiles. The `status_code` label (not `code`) distinguishes success from error responses.

These are the exact metrics our ServiceMonitor and PrometheusRules will query.

#### 2b. Wire it into Program.cs

Update `Program.cs` to enable the metrics middleware. The changes are minimal:

```csharp
// app-source/src/SampleApi/Program.cs (simplified -- showing Prometheus-relevant lines only)
// The actual file also includes EF Core (PostgreSQL), Redis, NotificationApi HttpClient,
// and OpenTelemetry configuration from Phases 17 and 22.
using SampleApi.Models;
using Prometheus;                              // <-- ADD: prometheus-net namespace

var builder = WebApplication.CreateBuilder(args);

// --- Configuration binding (Rule 3 -- IOptions pattern) ---
builder.Services.Configure<WeatherForecastOptions>(
    builder.Configuration.GetSection("WeatherForecast"));

// --- Health checks ---
builder.Services.AddHealthChecks();

// --- Controllers ---
builder.Services.AddControllers();

// --- Swagger/OpenAPI (controlled by feature flag) ---
var swaggerEnabled = builder.Configuration.GetValue<bool>("FEATURE_SWAGGER_ENABLED",
    builder.Environment.IsDevelopment());

if (swaggerEnabled)
{
    builder.Services.AddEndpointsApiExplorer();
    builder.Services.AddSwaggerGen(c =>
    {
        c.SwaggerDoc("v1", new() { Title = "SampleAPI", Version = "v1" });
    });
}

// --- CORS ---
var corsOrigins = builder.Configuration.GetValue<string>("CORS_ALLOWED_ORIGINS", "*");
builder.Services.AddCors(options =>
{
    options.AddDefaultPolicy(policy =>
    {
        if (corsOrigins == "*")
        {
            policy.AllowAnyOrigin().AllowAnyMethod().AllowAnyHeader();
        }
        else
        {
            policy.WithOrigins(corsOrigins.Split(','))
                  .AllowAnyMethod()
                  .AllowAnyHeader();
        }
    });
});

var app = builder.Build();

// --- Middleware pipeline ---

if (swaggerEnabled)
{
    app.UseSwagger();
    app.UseSwaggerUI();
}

app.UseCors();

// ADD: Prometheus HTTP request metrics middleware.
// This MUST come before MapControllers so it can wrap every request.
// It records http_request_duration_seconds (histogram with _count and _bucket sub-metrics)
// for every HTTP request that passes through the pipeline.
app.UseHttpMetrics();                          // <-- ADD: records per-request metrics

app.UseAuthorization();

app.MapHealthChecks("/healthz");
app.MapHealthChecks("/readyz");

// ADD: Expose /metrics endpoint for Prometheus to scrape.
// This endpoint returns all registered metrics in Prometheus exposition format.
// It listens on the same port (8080) as the application.
app.MapMetrics();                              // <-- ADD: serves /metrics endpoint

app.MapControllers();

await app.RunAsync();
```

Two lines added, two things they do:

1. `app.UseHttpMetrics()` -- wraps the request pipeline. Every incoming HTTP request is counted and its duration is recorded as a histogram. This is the source of our SLIs.
2. `app.MapMetrics()` -- exposes `/metrics` as an endpoint. Prometheus will scrape this every 30 seconds.

> **Callout -- Middleware ordering matters.**
> `UseHttpMetrics()` must appear before `MapControllers()`. If you place it after, the middleware will not see any requests. Think of ASP.NET Core middleware as a stack of wrappers -- outer wrappers see everything, inner wrappers only see what reaches them.

#### 2c. Build and deploy

After making these changes, rebuild the application and push a new image. In our pipeline, this happens automatically when you merge to main. For a manual test:

```bash
# Build locally to verify it compiles
cd /path/to/app-source
dotnet restore
dotnet build --no-restore
```

If building and deploying through the pipeline (T2 -- merge to main), the new image will be pushed and ArgoCD will sync it to DEV automatically.

#### Verify Step 2

Once the updated pod is running:

```bash
# Port-forward to the pod and hit the metrics endpoint
$OC port-forward deployment/sampleapi 8080:8080 -n $NS_DEV &
PF_PID=$!
sleep 2

curl -s http://localhost:8080/metrics | head -30
# Expected: lines like:
#   # HELP http_request_duration_seconds ...
#   # TYPE http_request_duration_seconds histogram
#   http_request_duration_seconds_count{method="GET",controller="WeatherForecast",action="Get",status_code="200"} 5

# Generate a few requests so there is data
curl -s http://localhost:8080/api/WeatherForecast > /dev/null
curl -s http://localhost:8080/api/WeatherForecast > /dev/null
curl -s http://localhost:8080/api/WeatherForecast > /dev/null

# Check metrics again -- counters should have incremented
curl -s http://localhost:8080/metrics | grep http_request_duration_seconds_count

kill $PF_PID
```

You should see counter values incrementing. If you see an empty response or a 404, check that:
- The `prometheus-net.AspNetCore` package is in the published output (`dotnet publish` includes it).
- `app.MapMetrics()` is present in `Program.cs`.
- The pod is running the updated image (check image tag with `oc get deployment sampleapi -n sampleapi-dev -o jsonpath='{.spec.template.spec.containers[0].image}'`).

---

### Step 3: Create the ServiceMonitor (~10 minutes)

**Why this step matters:** Your app now exposes `/metrics`. Prometheus is running. But Prometheus does not know your app exists. The `ServiceMonitor` is the bridge -- it is a custom resource that says "scrape any Service matching these labels on this port at this path."

```yaml
# file: infra/phase20/servicemonitor-sampleapi.yaml
# Tells Prometheus WHERE to find your app's metrics endpoint.
#
# CRITICAL: This must be applied in the SAME namespace as the Service it targets.
# ServiceMonitor in sampleapi-dev scrapes Service in sampleapi-dev.
# If you put it in openshift-monitoring, it will not work.
---
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: sampleapi-monitor
  labels:
    app: sampleapi
    team: devsecops
spec:
  selector:
    matchLabels:
      app: sampleapi                         # <-- must match your Service's labels
  endpoints:
    - port: http                             # <-- must match Service port NAME, not number
      #       ^^^^
      # This is the port NAME from your Service spec.ports[].name.
      # Our Service defines: { name: http, port: 8080 }.
      # If you write "8080" here instead of "http", Prometheus will reject it.
      # This is the #1 cause of "no targets found" issues.
      path: /metrics                         # <-- where prometheus-net serves metrics
      interval: 30s                          # <-- scrape every 30 seconds
      scrapeTimeout: 10s                     # <-- give up after 10 seconds per scrape
      # Add namespace and pod labels for multi-env queries
      relabelings:
        - sourceLabels: [__meta_kubernetes_namespace]
          targetLabel: namespace
        - sourceLabels: [__meta_kubernetes_pod_name]
          targetLabel: pod
```

Apply it to the DEV namespace:

```bash
$OC apply -f infra/phase20/servicemonitor-sampleapi.yaml -n $NS_DEV
```

> **Callout -- The port name trap.**
> The `port` field in the ServiceMonitor endpoint refers to the **name** of the port in the Service spec, not the port number. Our Service defines `ports: [{name: http, port: 8080}]`. The ServiceMonitor must say `port: http`. Writing `port: "8080"` or `port: 8080` will silently fail -- Prometheus will show 0 targets with no error message. This is the single most common mistake when setting up ServiceMonitors.

For all environments, apply the same ServiceMonitor to each namespace:

```bash
for NS in $NS_DEV $NS_SIT $NS_UAT $NS_PROD; do
  $OC apply -f infra/phase20/servicemonitor-sampleapi.yaml -n $NS
done
```

> **Multi-service note:** In the full project, we have ServiceMonitors for all four workloads in each namespace: SampleApi, NotificationApi, PostgreSQL (via pg_exporter), and Redis (via redis_exporter). The same pattern applies -- create a ServiceMonitor CR per service, matching the Service by label selector and port name. The SampleApi example here teaches the pattern; the other ServiceMonitors follow the same structure.

#### Verify Step 3

Wait about 60 seconds for Prometheus to pick up the new ServiceMonitor, then check targets:

```bash
# Method 1: Check via the OCP Console
# Navigate to: Observe -> Targets
# Filter by namespace: sampleapi-dev
# You should see: serviceMonitor/sampleapi-dev/sampleapi-monitor/0
# State should be: UP

# Method 2: Check via PromQL (CLI)
# Query Prometheus directly for your app's metrics
$OC exec -n openshift-user-workload-monitoring prometheus-user-workload-0 -c prometheus -- \
  curl -s 'http://localhost:9090/api/v1/query?query=up{job="sampleapi",namespace="sampleapi-dev"}' \
  | python3 -m json.tool
```

Expected response (trimmed):

```json
{
  "status": "success",
  "data": {
    "resultType": "vector",
    "result": [
      {
        "metric": {
          "job": "sampleapi",
          "namespace": "sampleapi-dev",
          "instance": "10.128.2.15:8080"
        },
        "value": [1741430400, "1"]
      }
    ]
  }
}
```

The value `"1"` means the target is UP. A value of `"0"` means Prometheus reached the pod but the scrape failed (wrong path, timeout, etc.). No results at all means Prometheus has not discovered the target (wrong labels, wrong namespace, wrong port name).

Troubleshooting if no targets appear:

```bash
# 1. Confirm the Service has the right labels
$OC get svc sampleapi -n $NS_DEV --show-labels
# Must include: app=sampleapi

# 2. Confirm the Service port name
$OC get svc sampleapi -n $NS_DEV -o jsonpath='{.spec.ports[*].name}'
# Must output: http

# 3. Confirm ServiceMonitor is in the same namespace
$OC get servicemonitor -n $NS_DEV
# Expected output:
#   NAME                      AGE
#   notificationapi-monitor   44h
#   postgresql-monitor        44h
#   redis-monitor             44h
#   sampleapi-monitor         3d

# 4. Confirm the app actually serves /metrics
$OC exec deployment/sampleapi -n $NS_DEV -- curl -s http://localhost:8080/metrics | head -5
```

---

### Step 4: Verify Metrics in the OpenShift Console (~10 minutes)

**Why this step matters:** Before writing alert rules, you need to confirm that metrics are flowing and understand what data is available. The OpenShift Console has a built-in PromQL query interface.

#### 4a. Open the Observe UI

1. Navigate to the OpenShift Console.
2. Switch to the **Developer** perspective (dropdown in the top-left).
3. Select **Observe** from the left sidebar.
4. Set the **Project** dropdown to `sampleapi-dev`.

You will see the Metrics tab with a query box.

#### 4b. Run your first queries

Paste each of these PromQL queries into the query box and click **Run Queries**:

**Query 1 -- Request rate (requests per second):**

```promql
sum(rate(http_request_duration_seconds_count{namespace="sampleapi-dev"}[5m]))
```

This calculates the per-second rate of HTTP requests, averaged over 5-minute windows. If you see `0` or `No datapoints found`, generate some traffic first:

```bash
# Generate traffic from outside the cluster
APP_URL=$($OC get route sampleapi -n $NS_DEV -o jsonpath='{.spec.host}')
for i in $(seq 1 50); do
  curl -sk https://$APP_URL/api/WeatherForecast > /dev/null
  sleep 0.5
done
```

**Query 2 -- Error rate (fraction of 5xx responses):**

```promql
sum(rate(http_request_duration_seconds_count{namespace="sampleapi-dev", status_code=~"5.."}[5m]))
/
sum(rate(http_request_duration_seconds_count{namespace="sampleapi-dev"}[5m]))
```

This is your availability SLI inverted -- it shows the fraction of requests that fail. For a healthy app, this should be `0` or `NaN` (if there are no errors at all, division by zero yields NaN).

**Query 3 -- p99 latency:**

```promql
histogram_quantile(0.99,
  sum(rate(http_request_duration_seconds_bucket{namespace="sampleapi-dev"}[5m])) by (le)
)
```

This computes the 99th percentile request duration. For our simple WeatherForecast API, expect values under 50ms. If you see values in the seconds, something is wrong with the app.

**Query 4 -- Memory usage vs limit:**

```promql
container_memory_working_set_bytes{namespace="sampleapi-dev", container="sampleapi"}
/
kube_pod_container_resource_limits{namespace="sampleapi-dev", container="sampleapi", resource="memory"}
```

This shows memory usage as a fraction of the container's memory limit. Our limit is 512Mi. If this ratio approaches 0.85, the app is at risk of OOM kill.

> **Callout -- Rate vs instant values.**
> Counters like `http_request_duration_seconds_count` only go up. The raw value is meaningless ("you've had 47,291 requests since the pod started" -- so what?). Wrapping it in `rate()` converts it to a per-second rate over a time window. Always use `rate()` or `increase()` with counters. Gauges like `process_working_set_bytes` represent a current value and should be queried directly.

---

### Step 5: Create PrometheusRule for SLO-Based Alerts (~20 minutes)

**Why this step matters:** Metrics without alerts are a dashboard no one watches. Alerts without SLO grounding are noise that gets ignored. This step creates alerts tied to your 99.9% availability SLO using the concepts from Part 1.

The PrometheusRule resource defines alert rules that Thanos Ruler evaluates continuously. When a rule's expression is true for the specified duration, it fires an alert to Alertmanager.

```yaml
# COMBINED TEACHING EXAMPLE — shows the key rules from TWO actual files:
#   infra/phase20/prometheus-rules-slo.yaml   (Groups 1-3: recording rules + SLO alerts + latency)
#   infra/phase20/prometheus-rules-infra.yaml  (Groups 4-6: pod health + resource + deployment)
#
# The actual deployment splits these into separate PrometheusRule CRDs with different
# metadata names (sampleapi-slo-alerts and sampleapi-infra-alerts). This combined
# version is for teaching — apply the actual files, not this inline YAML.
#
# Alert philosophy:
#   - Critical alerts page someone -- they mean "act now or users suffer."
#   - Warning alerts create tickets -- they mean "investigate within the shift."
#   - Info alerts go to a dashboard -- they mean "be aware, investigate during business hours."
#   - Pod/resource alerts catch infrastructure problems before they become user-facing.
#
# KEY: prometheus-net v8.x uses http_request_duration_seconds (histogram).
#   The _count sub-metric counts total requests.
#   The status_code label (not "code") distinguishes 2xx from 5xx.
#
# APPLY THE ACTUAL FILES (not this inline YAML):
#   oc apply -f infra/phase20/prometheus-rules-slo.yaml -n sampleapi-dev
#   oc apply -f infra/phase20/prometheus-rules-infra.yaml -n sampleapi-dev
---
apiVersion: monitoring.coreos.com/v1
kind: PrometheusRule
metadata:
  name: sampleapi-slo-alerts           # Actual file uses this name
  labels:
    app: sampleapi
    team: devsecops
    prometheus: user-workload          # Matches actual file label
spec:
  groups:
    # ===========================================================
    # Group 1: SLO Recording Rules
    # (from infra/phase20/prometheus-rules-slo.yaml)
    # ===========================================================
    # Pre-compute error ratios over standard windows so the alert
    # rules below stay simple and readable. Recording rules also
    # power the SLO burn-rate Grafana dashboard (Phase 21).
    - name: sampleapi-slo-recording    # Actual file uses dashes, not dots
      interval: 30s
      rules:
        # 5-minute error ratio (short window for critical alert)
        - record: sampleapi:http_error_ratio:rate5m
          expr: |
            (
              sum(rate(http_request_duration_seconds_count{job="sampleapi", status_code=~"5.."}[5m]))
              /
              sum(rate(http_request_duration_seconds_count{job="sampleapi"}[5m]))
            ) or vector(0)

        # 30-minute error ratio (short window for warning alert)
        - record: sampleapi:http_error_ratio:rate30m
          expr: |
            (
              sum(rate(http_request_duration_seconds_count{job="sampleapi", status_code=~"5.."}[30m]))
              /
              sum(rate(http_request_duration_seconds_count{job="sampleapi"}[30m]))
            ) or vector(0)

        # 1-hour error ratio (long window for critical alert)
        - record: sampleapi:http_error_ratio:rate1h
          expr: |
            (
              sum(rate(http_request_duration_seconds_count{job="sampleapi", status_code=~"5.."}[1h]))
              /
              sum(rate(http_request_duration_seconds_count{job="sampleapi"}[1h]))
            ) or vector(0)

        # 6-hour error ratio (long window for warning alert)
        - record: sampleapi:http_error_ratio:rate6h
          expr: |
            (
              sum(rate(http_request_duration_seconds_count{job="sampleapi", status_code=~"5.."}[6h]))
              /
              sum(rate(http_request_duration_seconds_count{job="sampleapi"}[6h]))
            ) or vector(0)

        # 3-day error ratio (long window for info alert)
        - record: sampleapi:http_error_ratio:rate3d
          expr: |
            (
              sum(rate(http_request_duration_seconds_count{job="sampleapi", status_code=~"5.."}[3d]))
              /
              sum(rate(http_request_duration_seconds_count{job="sampleapi"}[3d]))
            ) or vector(0)

        # SLO target constant (0.001 = 99.9%)
        - record: sampleapi:slo_target
          expr: "0.001"

    # ===========================================================
    # Group 2: SLO Burn-Rate Alerts
    # (from infra/phase20/prometheus-rules-slo.yaml)
    # ===========================================================
    # These alerts use the pre-computed recording rules above.
    # Three tiers: critical (14.4x), warning (6x), info (1x).
    - name: sampleapi-slo-alerts       # Actual file uses dashes, not dots
      rules:
        # --- Critical: Burning 14.4x budget (page someone) ---
        # 2% of monthly budget consumed per hour.
        # At this rate, the entire budget is gone in ~50 hours.
        # Both 1h AND 5m windows must be breaching to fire.
        - alert: SampleApiErrorBudgetBurnCritical
          expr: |
            sampleapi:http_error_ratio:rate1h > (14.4 * 0.001)
            and
            sampleapi:http_error_ratio:rate5m > (14.4 * 0.001)
          for: 2m                          # <-- must sustain for 2 min to avoid flapping
          labels:
            severity: critical
            service: sampleapi
            slo: availability
          annotations:
            summary: "SampleApi error budget burning at 14.4x rate"
            description: >-
              SampleApi error ratio is {{ $value | humanizePercentage }} over the last hour,
              consuming 2% of the monthly error budget per hour.
              At this rate, the entire error budget will be exhausted in ~50 hours.
            runbook_url: "https://docs.internal/runbooks/sampleapi-high-error-rate"

        # --- Warning: Burning 6x budget (create ticket) ---
        # 5% of monthly budget consumed in 6 hours.
        # Both 6h AND 30m windows must be breaching.
        - alert: SampleApiErrorBudgetBurnWarning
          expr: |
            sampleapi:http_error_ratio:rate6h > (6 * 0.001)
            and
            sampleapi:http_error_ratio:rate30m > (6 * 0.001)
          for: 5m
          labels:
            severity: warning
            service: sampleapi
            slo: availability
          annotations:
            summary: "SampleApi error budget burning at 6x rate"
            description: >-
              SampleApi error ratio is {{ $value | humanizePercentage }} over the last 6 hours,
              consuming 5% of the monthly error budget in 6 hours.

        # --- Info: Burning 1x budget (slow leak) ---
        # 10% of monthly budget consumed in 3 days.
        # This is a slow burn -- might be acceptable but worth investigating.
        - alert: SampleApiErrorBudgetBurnSlow
          expr: |
            sampleapi:http_error_ratio:rate3d > (1 * 0.001)
            and
            sampleapi:http_error_ratio:rate6h > (1 * 0.001)
          for: 30m
          labels:
            severity: info
            service: sampleapi
            slo: availability
          annotations:
            summary: "SampleApi error budget slowly burning"
            description: >-
              SampleApi error ratio is {{ $value | humanizePercentage }} over the last 3 days.
              At this rate, the error budget will be exhausted by month-end.

    # ===========================================================
    # Group 3: Latency SLO (still in prometheus-rules-slo.yaml)
    # ===========================================================
    # The actual file includes a SampleApiLatencyHigh alert in the
    # same sampleapi-slo-alerts group. Shown separately here for clarity.
    #
    # NOTE: The actual file does NOT include a separate "high error rate"
    # threshold alert — it relies on the SLO burn-rate alerts above.
    # The latency alert below IS in the actual file:

        # --- High Latency (in the actual sampleapi-slo-alerts group) ---
        # Fires when p99 latency exceeds 1 second for 10 minutes.
        - alert: SampleApiLatencyHigh
          expr: |
            histogram_quantile(0.99,
              sum(rate(http_request_duration_seconds_bucket{job="sampleapi"}[5m])) by (le)
            ) > 1.0
          for: 10m
          labels:
            severity: warning
            service: sampleapi
            slo: latency
          annotations:
            summary: "SampleApi p99 latency exceeds 1 second"
            description: >-
              SampleApi p99 latency is {{ $value | humanizeDuration }} over the last 5 minutes.
              SLO target: p99 < 1 second.

# ---
# The infrastructure alerts below come from a SEPARATE PrometheusRule CRD:
#   infra/phase20/prometheus-rules-infra.yaml
#   metadata.name: sampleapi-infra-alerts
# ---

    # ===========================================================
    # Group 4: Pod Health Alerts
    # (from infra/phase20/prometheus-rules-infra.yaml)
    # ===========================================================
    - name: sampleapi-pod-health
      rules:
        # --- Pod Restarts (CrashLoopBackOff detection) ---
        # NOTE: The actual file uses namespace filter (not container filter)
        # to catch restarts across ALL pods in sampleapi-* namespaces.
        - alert: PodRestartingFrequently
          expr: |
            increase(kube_pod_container_status_restarts_total{namespace=~"sampleapi-.*"}[15m]) > 3
          for: 5m
          labels:
            severity: warning
            team: devsecops
          annotations:
            summary: "Pod {{ $labels.pod }} restarting frequently"
            description: >-
              Pod {{ $labels.pod }} in {{ $labels.namespace }} has restarted
              {{ $value | humanize }} times in the last 15 minutes.
              Check logs: oc logs {{ $labels.pod }} -n {{ $labels.namespace }} --previous

        # --- Pod Not Ready ---
        - alert: PodNotReady
          expr: |
            kube_pod_status_ready{namespace=~"sampleapi-.*", condition="true"} == 0
          for: 5m
          labels:
            severity: warning
            team: devsecops
          annotations:
            summary: "Pod {{ $labels.pod }} not ready"
            description: >-
              Pod {{ $labels.pod }} in {{ $labels.namespace }} has been not-ready
              for more than 5 minutes.

        # --- Container OOM Killed ---
        - alert: ContainerOOMKilled
          expr: |
            kube_pod_container_status_last_terminated_reason{namespace=~"sampleapi-.*", reason="OOMKilled"} == 1
          for: 0m
          labels:
            severity: critical
            team: devsecops
          annotations:
            summary: "Container {{ $labels.container }} OOM killed"
            description: >-
              Container {{ $labels.container }} in pod {{ $labels.pod }}
              ({{ $labels.namespace }}) was OOM killed. Increase memory limits.

        # --- Deployment Replicas Mismatch ---
        - alert: DeploymentReplicasMismatch
          expr: |
            kube_deployment_spec_replicas{namespace=~"sampleapi-.*"}
            !=
            kube_deployment_status_available_replicas{namespace=~"sampleapi-.*"}
          for: 10m
          labels:
            severity: warning
            team: devsecops
          annotations:
            summary: "Deployment {{ $labels.deployment }} has replica mismatch"
            description: >-
              Deployment {{ $labels.deployment }} in {{ $labels.namespace }}
              wants {{ $labels.spec_replicas }} replicas but only
              {{ $value }} are available. Check pod events and resources.

    # ===========================================================
    # Group 5: Resource Alerts
    # (from infra/phase20/prometheus-rules-infra.yaml)
    # ===========================================================
    - name: sampleapi-resource-alerts
      rules:
        # --- High CPU ---
        - alert: ContainerCPUThrottling
          expr: |
            (
              sum by (namespace, pod, container) (
                rate(container_cpu_usage_seconds_total{namespace=~"sampleapi-.*", container!=""}[5m])
              )
              /
              sum by (namespace, pod, container) (
                kube_pod_container_resource_limits{namespace=~"sampleapi-.*", resource="cpu", container!=""}
              )
            ) > 0.80
          for: 10m
          labels:
            severity: warning
            team: devsecops
          annotations:
            summary: "Container {{ $labels.container }} CPU near limit"
            description: >-
              Container {{ $labels.container }} in {{ $labels.pod }}
              is using {{ $value | humanizePercentage }} of its CPU limit.

        # --- High Memory ---
        - alert: ContainerMemoryHigh
          expr: |
            (
              sum by (namespace, pod, container) (
                container_memory_working_set_bytes{namespace=~"sampleapi-.*", container!=""}
              )
              /
              sum by (namespace, pod, container) (
                kube_pod_container_resource_limits{namespace=~"sampleapi-.*", resource="memory", container!=""}
              )
            ) > 0.85
          for: 10m
          labels:
            severity: warning
            team: devsecops
          annotations:
            summary: "Container {{ $labels.container }} memory high"
            description: >-
              Container {{ $labels.container }} in {{ $labels.pod }}
              is using {{ $value | humanizePercentage }} of its memory limit.
              Risk of OOM kill if usage continues to grow.

    # ===========================================================
    # Group 6: Service Health
    # (from infra/phase20/prometheus-rules-infra.yaml)
    # ===========================================================
    - name: sampleapi-service-health
      rules:
        # --- No Endpoints for a Service (all pods down) ---
        - alert: ServiceNoEndpoints
          expr: |
            kube_endpoint_address_available{namespace=~"sampleapi-.*"} == 0
          for: 5m
          labels:
            severity: critical
            team: devsecops
          annotations:
            summary: "Service {{ $labels.endpoint }} has no endpoints"
            description: >-
              Service {{ $labels.endpoint }} in {{ $labels.namespace }}
              has zero healthy endpoints. All pods may be down or not ready.

        # --- PostgreSQL is down ---
        - alert: PostgreSQLDown
          expr: |
            kube_statefulset_status_replicas_ready{namespace=~"sampleapi-.*", statefulset="postgresql"} == 0
          for: 2m
          labels:
            severity: critical
            team: devsecops
          annotations:
            summary: "PostgreSQL is down in {{ $labels.namespace }}"
            description: >-
              PostgreSQL StatefulSet in {{ $labels.namespace }} has 0 ready replicas.
              Check: oc logs statefulset/postgresql -n {{ $labels.namespace }}

        # --- Redis is down ---
        - alert: RedisDown
          expr: |
            kube_statefulset_status_replicas_ready{namespace=~"sampleapi-.*", statefulset="redis"} == 0
          for: 2m
          labels:
            severity: critical
            team: devsecops
          annotations:
            summary: "Redis is down in {{ $labels.namespace }}"
            description: >-
              Redis StatefulSet in {{ $labels.namespace }} has 0 ready replicas.
              Check: oc logs statefulset/redis -n {{ $labels.namespace }}
```

Apply it:

```bash
# The inline YAML above is a combined teaching example. In the actual deployment,
# the SLO rules and infrastructure rules are split across separate files:
#   - infra/phase20/prometheus-rules-slo.yaml   (recording rules + SLO burn-rate alerts)
#   - infra/phase20/prometheus-rules-infra.yaml  (pod health, resource, service alerts)
# Apply both:
$OC apply -f infra/phase20/prometheus-rules-slo.yaml -n $NS_DEV
$OC apply -f infra/phase20/prometheus-rules-infra.yaml -n $NS_DEV
```

#### Verify Step 5

```bash
# Confirm the PrometheusRules were accepted
$OC get prometheusrule -n $NS_DEV
# Expected output:
#   NAME                     AGE
#   sampleapi-infra-alerts   44h
#   sampleapi-slo-alerts     44h

# Confirm Thanos Ruler loaded the rules (check for syntax errors)
$OC logs -l app.kubernetes.io/name=thanos-ruler -n openshift-user-workload-monitoring \
  --tail=20 | grep -i "sampleapi"
# Look for "msg=reload" or "block loaded" messages.
# If you see "error loading" messages, there is a YAML or PromQL syntax error.

# Check alert status via the OCP Console:
# Navigate to: Observe -> Alerting -> Alerting Rules
# Filter by: source "User" (to see user-defined rules, not platform rules)
# Look for: SampleApiErrorBudgetBurnCritical, DeploymentReplicasMismatch, etc.
# All rules should show state: "inactive" (good -- nothing is broken)
```

If rules show as "inactive", that means the conditions are not currently met -- which is exactly what you want in a healthy system.

---

### Step 6: Test Alert Firing (~10 minutes)

**Why this step matters:** An alert that has never fired is an alert you do not trust. You need to verify that the alerting pipeline works end to end: rule evaluates to true, alert transitions to pending, then fires after the `for` duration, and appears in the Alertmanager UI.

We will intentionally trigger the `DeploymentReplicasMismatch` alert (from `prometheus-rules-infra.yaml`) by scaling the deployment to zero replicas. This alert fires when desired replicas do not equal available replicas for 10 minutes. The `ServiceNoEndpoints` alert (also from `prometheus-rules-infra.yaml`) will fire after 5 minutes since the Service will have zero healthy endpoints.

#### 6a. Trigger the alert

Scale the deployment to zero replicas:

```bash
# Save current replica count for rollback
REPLICAS=$($OC get deployment sampleapi -n $NS_DEV -o jsonpath='{.spec.replicas}')
echo "Current replicas: $REPLICAS"

# Scale to zero -- this will trigger:
#   ServiceNoEndpoints after 5 minutes (zero healthy endpoints)
#   DeploymentReplicasMismatch after 10 minutes (desired != available)
$OC scale deployment sampleapi -n $NS_DEV --replicas=0
```

#### 6b. Watch the alert lifecycle

```bash
# Wait 30 seconds, then check alert status
sleep 30

# Check via PromQL: is the alert condition true?
$OC exec -n openshift-user-workload-monitoring prometheus-user-workload-0 -c prometheus -- \
  curl -s 'http://localhost:9090/api/v1/query?query=kube_deployment_status_available_replicas{deployment="sampleapi",namespace="sampleapi-dev"}' \
  | python3 -c "import sys,json; r=json.load(sys.stdin); print(f'Available replicas: {r[\"data\"][\"result\"][0][\"value\"][1]}')" 2>/dev/null
# Expected: Available replicas: 0
```

Now watch the alert transition through its states:

1. **Inactive** -- condition is not met (before you scaled down)
2. **Pending** -- condition is met but the `for` duration has not elapsed (first 2 minutes)
3. **Firing** -- condition has been true for longer than the `for` duration

Check the alert state in the OpenShift Console:
- Navigate to **Observe** then **Alerting**.
- Filter by name: `DeploymentReplicasMismatch` or `ServiceNoEndpoints`.
- After ~5 minutes, `ServiceNoEndpoints` will transition from **Pending** to **Firing**.
- After ~10 minutes, `DeploymentReplicasMismatch` will also transition to **Firing**.

```bash
# Or check via CLI after ~5-10 minutes:
$OC exec -n openshift-user-workload-monitoring prometheus-user-workload-0 -c prometheus -- \
  curl -s 'http://localhost:9090/api/v1/alerts' \
  | python3 -c "
import sys, json
data = json.load(sys.stdin)
for alert in data.get('data', {}).get('alerts', []):
    name = alert.get('labels', {}).get('alertname', '')
    if 'Deployment' in name or 'Service' in name or 'Pod' in name:
        print(f\"{name}: {alert['state']}\")
" 2>/dev/null
# Expected: ServiceNoEndpoints: firing (after ~5 min)
# Expected: DeploymentReplicasMismatch: firing (after ~10 min)
```

#### 6c. Restore the deployment

```bash
# Scale back up
$OC scale deployment sampleapi -n $NS_DEV --replicas=$REPLICAS

# Verify pods are back
$OC rollout status deployment/sampleapi -n $NS_DEV --timeout=60s

# Alert should transition back to inactive within a few minutes
```

> **Callout -- Do not skip this step.**
> Untested alerts are worse than no alerts. They create a false sense of security. You trust them, they do not fire, and the outage is discovered by a customer. Always trigger each critical alert at least once in a non-production environment.

---

## Recap

Here is what you built in this module and why each piece matters:

| What | Why | Key File |
|---|---|---|
| User workload monitoring ConfigMap | Without it, Prometheus ignores your namespaces entirely | `infra/phase20/user-workload-monitoring.yaml` |
| prometheus-net in the .NET app | Exposes `/metrics` with `http_request_duration_seconds` (histogram with `_count` for request totals and `_bucket` for latency) -- your SLIs | `app-source/src/SampleApi/Program.cs` |
| ServiceMonitor | Tells Prometheus which Services to scrape, on which port, at which path | `infra/phase20/servicemonitor-sampleapi.yaml` |
| PrometheusRule with SLO burn-rate alerts | Catches both fast spikes and slow leaks using error-budget math | `infra/phase20/prometheus-rules-slo.yaml` |
| PrometheusRule with pod/resource alerts | Catches infrastructure problems before they become user-facing | `infra/phase20/prometheus-rules-infra.yaml` |

The data flow end to end:

```
.NET App           ServiceMonitor         Prometheus              Thanos Ruler
  /metrics    --->   "scrape this    --->   stores time     --->   evaluates
  (counters,         Service on port        series data            PrometheusRule
   histograms)       'http' every 30s"                             expressions
                                                                       |
                                                                       v
                                                                  Alertmanager
                                                                  (route, notify)
```

---

## Common Mistakes

These are the errors that trip up nearly everyone the first time. Save yourself the debugging.

### 1. ServiceMonitor in the wrong namespace

**Symptom:** Prometheus shows zero targets for your app.

**Cause:** You applied the ServiceMonitor to `openshift-monitoring` or `devsecops-tools` instead of the namespace where the Service lives (`sampleapi-dev`).

**Fix:** The ServiceMonitor must be in the same namespace as the target Service. There is no cross-namespace discovery for user workloads without additional RBAC configuration.

```bash
# Wrong:
$OC apply -f servicemonitor-app.yaml -n openshift-monitoring

# Right:
$OC apply -f servicemonitor-app.yaml -n $NS_DEV
```

### 2. Port name vs port number

**Symptom:** Prometheus target shows `DOWN` or the target never appears.

**Cause:** The ServiceMonitor `port` field expects the port **name** (a string like `http`), not the port **number** (like `8080`).

```yaml
# Wrong:
endpoints:
  - port: "8080"          # This will silently fail

# Right:
endpoints:
  - port: http            # Matches Service spec.ports[].name
```

### 3. Forgetting to enable user workload monitoring

**Symptom:** ServiceMonitor exists, app exposes `/metrics`, but no data appears in the Observe UI.

**Cause:** The `cluster-monitoring-config` ConfigMap with `enableUserWorkload: true` was never applied, so the `openshift-user-workload-monitoring` namespace does not exist and no user Prometheus is running.

**Check:**

```bash
$OC get pods -n openshift-user-workload-monitoring
# If this returns "No resources found", user workload monitoring is not enabled.
```

### 4. Missing labels on the Service

**Symptom:** ServiceMonitor exists in the right namespace, port name is correct, but still no targets.

**Cause:** The ServiceMonitor `selector.matchLabels` does not match the Service's actual labels.

```bash
# Verify what labels the Service has:
$OC get svc sampleapi -n $NS_DEV --show-labels

# Then verify what the ServiceMonitor is looking for:
$OC get servicemonitor sampleapi-monitor -n $NS_DEV \
  -o jsonpath='{.spec.selector.matchLabels}'
# These must match.
```

### 5. App does not expose /metrics

**Symptom:** Prometheus target shows `DOWN` with a 404 error.

**Cause:** The `prometheus-net` package is installed but `app.MapMetrics()` is missing from `Program.cs`, or `UseHttpMetrics()` was added after `MapControllers()`.

**Test directly:**

```bash
$OC exec deployment/sampleapi -n $NS_DEV -- curl -s http://localhost:8080/metrics | head -3
# Should return Prometheus-formatted text, not a 404 HTML page.
```

### 6. PromQL division by zero

**Symptom:** Alert rule shows `NaN` in the console and never fires.

**Cause:** Error rate calculations divide errors by total requests. If there are zero requests, you get `NaN`. This is mathematically correct but operationally useless -- a service with zero traffic is probably down.

**Fix:** Add a `> 0` filter on the denominator or add a separate alert that catches zero-traffic scenarios. In the actual deployment, `ServiceNoEndpoints` (from `prometheus-rules-infra.yaml`) fires when all pods are down.

---

## Challenge: Create a Custom Latency SLO

Now that you understand the pattern, create a latency SLO alert on your own:

**Objective:** 99th percentile latency must stay below 500ms for 99.9% of the time (30-day window).

**Constraints:**
- Burn rate 14.4x critical alert with 1h / 5m windows.
- Burn rate 6x warning alert with 6h / 30m windows.
- Apply to the `sampleapi-dev` namespace.

**Hints:**
- The permitted latency SLO violation rate is 0.001 (same as availability).
- To measure latency violations, count requests that exceed 500ms: use `histogram_quantile` or count buckets above the threshold.
- An alternative approach: use `http_request_duration_seconds_bucket{le="0.5"}` (requests at or below 500ms) vs total to compute the "fast enough" ratio, then apply burn-rate math to the complement.

**Verification:** Scale the app to minimum resources to increase latency, then confirm the alert transitions from inactive to pending to firing.

---

## Self-Assessment

Answer these questions to confirm you internalized the material. If you cannot answer one, re-read the relevant section.

1. **Why does OpenShift disable user workload monitoring by default?**
   To prevent user workloads from generating unbounded metric cardinality that could destabilize the platform monitoring stack.

2. **What is the difference between a ServiceMonitor `port` value of `http` and `8080`?**
   `http` refers to the port name in the Service spec and is what Prometheus expects. `8080` is a port number and will silently fail to match.

3. **What two lines of code are needed to add Prometheus metrics to an ASP.NET Core app using prometheus-net?**
   `app.UseHttpMetrics()` to instrument the request pipeline, and `app.MapMetrics()` to expose the `/metrics` endpoint.

4. **If your SLO is 99.9% availability over 30 days, how many minutes of error budget do you have?**
   43.2 minutes (30 days * 24 hours * 60 minutes * 0.001).

5. **Why do multi-window burn-rate alerts use two time windows instead of one?**
   The short window ensures quick detection. The long window ensures the problem is sustained and not a brief spike. Both must be true to fire, eliminating false alarms from transient issues.

6. **A ServiceMonitor is applied to `openshift-monitoring` but the target Service is in `sampleapi-dev`. Will it work?**
   No. For user workload monitoring, the ServiceMonitor must be in the same namespace as the target Service.

7. **What does a burn rate of 14.4x mean in practical terms?**
   You are consuming your monthly error budget 14.4 times faster than sustainable. A 30-day budget would be exhausted in approximately 2 days.

8. **An alert shows `NaN` for the error rate expression. Is the app healthy or broken?**
   It depends. `NaN` usually means zero requests (division by zero). The app might be healthy but receiving no traffic, or it might be completely unreachable. You need a separate alert (like `ServiceNoEndpoints` from `prometheus-rules-infra.yaml`) to distinguish these cases.

---

## Going Further: Full Multi-Service Monitoring (Phase 20)

The steps above applied Phase 20 files for SampleApi. To extend coverage to all services and add alert routing, apply the remaining Phase 20 resources:

```bash
# Apply NotificationApi, PostgreSQL, and Redis ServiceMonitors to each namespace
for NS in $NS_DEV $NS_SIT $NS_UAT $NS_PROD; do
  $OC apply -f infra/phase20/servicemonitor-notificationapi.yaml -n $NS
  $OC apply -f infra/phase20/servicemonitor-postgresql.yaml -n $NS
  $OC apply -f infra/phase20/servicemonitor-redis.yaml -n $NS
done

# Apply pipeline alert rules to the tools namespace
$OC apply -f infra/phase20/prometheus-rules-pipeline.yaml -n $NS_TOOLS

# Configure alert routing (requires Slack webhook or SMTP credentials)
$OC apply -f infra/phase20/alertmanager-secret.yaml -n $NS_DEV
$OC apply -f infra/phase20/alertmanager-config.yaml -n $NS_DEV
```

The full Phase 20 deployment provides:

| Aspect | Coverage |
|--------|----------|
| **ServiceMonitors** | 4 per namespace: sampleapi, notificationapi, postgresql (via pg_exporter sidecar), redis (via redis_exporter sidecar) |
| **PrometheusRules** | 3 files: SLO burn-rate (`prometheus-rules-slo.yaml`), infrastructure alerts (`prometheus-rules-infra.yaml` -- pod health, resource pressure, PVC, service endpoints, PostgreSQL/Redis down), pipeline alerts (`prometheus-rules-pipeline.yaml` -- Jenkins queue, build failure rate, ArgoCD sync, GitLab health) |
| **AlertmanagerConfig** | `alertmanager-config.yaml` with severity-based Slack routing (critical/warning/info channels) + `alertmanager-secret.yaml` for webhook URLs |

Verify the full monitoring stack:

```bash
$OC get servicemonitor -n $NS_DEV
# Expected output:
#   NAME                      AGE
#   notificationapi-monitor   44h
#   postgresql-monitor        44h
#   redis-monitor             44h
#   sampleapi-monitor         3d

$OC get prometheusrule -n $NS_DEV
# Expected output:
#   NAME                     AGE
#   sampleapi-infra-alerts   44h
#   sampleapi-slo-alerts     44h

$OC get alertmanagerconfig -n $NS_DEV
# Expected output:
#   NAME               AGE
#   devsecops-alerts   44h
```

The `AlertmanagerConfig` (`infra/phase20/alertmanager-config.yaml`) routes alerts by severity to different Slack channels:

| Severity | Slack Channel | Group Wait | Repeat Interval |
|----------|--------------|------------|-----------------|
| critical | `#devsecops-critical` | 10s | 1h |
| warning | `#devsecops-alerts` | 5m | 4h |
| info | `#devsecops-info` | 30m | 12h |

It references a Secret (`alertmanager-slack-webhook` from `alertmanager-secret.yaml`) containing the Slack incoming webhook URLs. Replace the `CHANGE_ME` placeholder values with real webhook URLs before applying.

> **Tip:** Phase 14 (`infra/phase14/`) contains simplified starter versions of these files (no PVC, single PrometheusRule, basic ServiceMonitor with PodMonitor). They are useful as a minimal reference but use older metric names (`http_requests_received_total` with `code` label instead of `http_request_duration_seconds_count` with `status_code`). The Phase 20 files applied in this module use the correct `prometheus-net` v8.x metric names throughout.

---

## What's Next

**Module 13: Grafana Dashboards and Distributed Tracing** -- You now have metrics flowing and alerts firing. But staring at PromQL queries is not how you debug a production incident. In the next module, you will build Grafana dashboards that visualize request rate, error rate, latency distributions, and resource consumption in a single pane of glass. You will also deploy distributed tracing with OpenTelemetry and Tempo to see exactly where time is spent when SampleApi calls NotificationApi, PostgreSQL, and Redis.
