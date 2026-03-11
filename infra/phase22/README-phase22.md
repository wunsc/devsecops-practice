# Phase 22: OpenTelemetry & Distributed Tracing

## Overview

Phase 22 instruments SampleApi and NotificationApi with OpenTelemetry, deploys an OTel
Collector and Tempo backend, and visualizes distributed traces in Grafana. This enables
end-to-end request tracing across services, showing exactly where latency occurs.

## Architecture

```
┌──────────────┐     ┌──────────────────┐     ┌──────────────┐
│  SampleApi   │────→│  NotificationApi │     │  PostgreSQL  │
│  (auto-instr)│     │  (auto-instr)    │     │              │
│  traces →    │     │  traces →        │     │              │
└──────┬───────┘     └──────┬───────────┘     └──────────────┘
       │                    │
       └────────┬───────────┘
                ▼
       ┌────────────────────┐
       │  OTel Collector    │  ← receives traces via OTLP (gRPC :4317)
       │  (Deployment)      │    routes per-tenant via k8s.namespace.name
       └────────┬───────────┘
                │ OTLP HTTP per-tenant
                ▼
       ┌────────────────────┐
       │  Tempo Gateway     │  ← HTTPS :8080, Bearer token auth
       │  (openshift mode)  │    4 tenants (dev/sit/uat/prod)
       └────────┬───────────┘
                ▼
       ┌────────────────────┐
       │  TempoStack        │  ← S3 backend (ODF NooBaa OBC)
       │                    │    48h retention
       └────────┬───────────┘
                ▼
       ┌────────────────────┐     ┌────────────────────┐
       │  Grafana           │     │  OCP Console        │
       │  Tempo datasource  │     │  UIPlugin:          │
       │  (gateway URL)     │     │  DistributedTracing │
       └────────────────────┘     └────────────────────┘
```

**Key Design Decisions:**
- **Auto-instrumentation** (not manual SDK): OTel Operator injects .NET agent via Deployment
  annotation `instrumentation.opentelemetry.io/inject-dotnet: "true"`. No app code changes.
  Uses community image `autoinstrumentation-dotnet:1.9.0` (Red Hat has no .NET image).
- **Multi-tenant TempoStack** with `openshift` tenancy mode and gateway. Each environment
  maps to a Tempo tenant. Console tracing UI requires multi-tenancy.
- **Routing connector** in OTel Collector maps `k8s.namespace.name` to per-tenant pipelines.
- **Cluster Observability Operator** provides UIPlugin CR for OpenShift Console tracing UI.

## Prerequisites

- Phase 19: OBC `tempo-bucket` in Bound state (`oc get obc tempo-bucket -n openshift-tempo`)
- Phase 21: Grafana Operator v5 running with existing Prometheus + Loki datasources
- ODF StorageCluster in Ready state

## Execution Order

### Step 1: Install Operators

```bash
oc apply -f infra/phase22/tempo-operator-subscription.yaml
oc apply -f infra/phase22/otel-operator-subscription.yaml

# Wait for operators
echo "Waiting for Tempo Operator..."
while ! oc get csv -n openshift-tempo-operator 2>/dev/null | grep -q Succeeded; do
  sleep 10; echo "  waiting..."
done

echo "Waiting for OTel Operator..."
while ! oc get csv -n openshift-opentelemetry-operator 2>/dev/null | grep -q Succeeded; do
  sleep 10; echo "  waiting..."
done
echo "Both operators installed"
```

### Step 2: Create Tempo S3 Secret

The OBC-generated secret uses AWS key names. Tempo expects different field names.
Reformat the secret:

```bash
# Extract OBC credentials
BUCKET_NAME=$(oc get configmap tempo-bucket -n openshift-tempo -o jsonpath='{.data.BUCKET_NAME}')
BUCKET_HOST=$(oc get configmap tempo-bucket -n openshift-tempo -o jsonpath='{.data.BUCKET_HOST}')
BUCKET_PORT=$(oc get configmap tempo-bucket -n openshift-tempo -o jsonpath='{.data.BUCKET_PORT}')
ACCESS_KEY=$(oc get secret tempo-bucket -n openshift-tempo -o jsonpath='{.data.AWS_ACCESS_KEY_ID}' | base64 -d)
SECRET_KEY=$(oc get secret tempo-bucket -n openshift-tempo -o jsonpath='{.data.AWS_SECRET_ACCESS_KEY}' | base64 -d)

# Create reformatted secret for TempoStack
oc create secret generic tempo-s3 -n openshift-tempo \
  --from-literal=access_key_id="$ACCESS_KEY" \
  --from-literal=access_key_secret="$SECRET_KEY" \
  --from-literal=bucket="$BUCKET_NAME" \
  --from-literal=endpoint="https://${BUCKET_HOST}:${BUCKET_PORT}"
```

### Step 3: Deploy TempoStack

```bash
oc apply -f infra/phase22/tempostack.yaml

echo "Waiting for TempoStack pods..."
sleep 30
oc get pods -n openshift-tempo -l app.kubernetes.io/managed-by=tempo-operator
# Expected: distributor, ingester, compactor, querier, query-frontend pods
```

### Step 4: Deploy OTel Collector

```bash
oc apply -f infra/phase22/otel-collector.yaml

echo "Waiting for OTel Collector..."
oc wait --for=condition=ready pod -l app.kubernetes.io/name=otel-collector-collector \
  -n openshift-tempo --timeout=120s
```

### Step 5: Install Cluster Observability Operator

Required for the OpenShift Console tracing UI (UIPlugin):

```bash
oc apply -f infra/phase22/cluster-observability-operator.yaml
# Wait: oc get csv -n openshift-observability-operator | grep observability
# Expected: Succeeded
```

### Step 6: Create RBAC for Multi-Tenant Gateway Access

```bash
oc apply -f infra/phase22/tempo-tenant-rbac.yaml
# Creates: tempostack-traces-write (for OTel Collector SA)
#          tempostack-traces-read (for Grafana SA + authenticated users)
```

### Step 7: Deploy Auto-Instrumentation

Create Instrumentation CRs in all app namespaces. This injects the .NET agent
automatically via Deployment annotation — no app code changes needed:

```bash
# Apply Instrumentation CR to each app namespace
for NS in sampleapi-dev sampleapi-sit sampleapi-uat sampleapi-prod; do
  oc apply -f infra/phase22/otel-instrumentation.yaml -n $NS
done
```

### Step 8: Update GitOps Overlays

Add OTEL_* environment variables to per-service ConfigMaps and auto-inject
annotation to Deployment patches in app-gitops:

```bash
# In each services/{svc}/overlays/{env}/configmap-env.yaml, add:
#   OTEL_EXPORTER_OTLP_ENDPOINT, OTEL_SERVICE_NAME,
#   OTEL_RESOURCE_ATTRIBUTES, OTEL_EXPORTER_OTLP_PROTOCOL=grpc

# In each services/{svc}/overlays/{env}/patch-deployment.yaml, add annotation:
#   instrumentation.opentelemetry.io/inject-dotnet: "true"

# Push app-gitops changes → ArgoCD syncs → pods restart with auto-instrumentation

# Generate some traces
for i in $(seq 1 20); do
  curl -sk https://sampleapi-dev.${APPS_DOMAIN}/api/WeatherForecast
  sleep 1
done
```

### Step 9: Add Grafana Tempo Datasource

```bash
TOKEN=$(oc create token grafana-sa -n devsecops-tools --duration=8760h)
cat infra/phase22/grafana-datasource-tempo.yaml | \
  sed "s|REPLACE_WITH_TOKEN|Bearer $TOKEN|" | \
  oc apply -f - -n devsecops-tools
```

### Step 10: Create Trace Dashboard + Console UIPlugin

```bash
oc apply -f infra/phase22/grafana-dashboard-traces.yaml -n devsecops-tools
oc apply -f infra/phase22/uiplugin-distributed-tracing.yaml
# Console plugin adds Observe → Traces in OpenShift Console
```

## Verification

```bash
# Tempo running
oc get tempostack tempo -n openshift-tempo
# Expected: Ready

# OTel Collector running
oc get pods -n openshift-tempo -l app.kubernetes.io/name=otel-collector-collector
# Expected: 1 pod Running

# Generate traces
for i in $(seq 1 10); do
  curl -sk https://sampleapi-dev.apps.cluster-pmqwq.pmqwq.sandbox270.opentlc.com/api/WeatherForecast
done

# Auto-instrumentation init container injected
oc get pod -n sampleapi-dev -l app=sampleapi -o jsonpath='{.items[0].spec.initContainers[*].name}'
# Expected: includes "opentelemetry-auto-instrumentation-dotnet"

# UIPlugin registered
oc get uiplugin distributed-tracing -o jsonpath='{.status.conditions[0].type}'
# Expected: Available

# Verify traces in Tempo via gateway
GATEWAY_HOST=$(oc get route tempo-tempo-gateway -n openshift-tempo -o jsonpath='{.spec.host}')
TOKEN=$(oc create token grafana-sa -n devsecops-tools --duration=1h)
curl -sk -H "Authorization: Bearer $TOKEN" \
  "https://${GATEWAY_HOST}/api/traces/v1/dev/tempo/api/search?limit=5"
# Expected: JSON with trace data

# OpenShift Console → Observe → Traces:
#   Select tenant (dev/sit/uat/prod) → see traces with span waterfall

# In Grafana: Explore → Tempo datasource → search for service=sampleapi
# Expected: traces showing SampleApi → NotificationApi spans

# Dashboard
# In Grafana: Dashboards → Distributed Tracing Explorer
# Expected: Trace search, latency breakdown
```

## Troubleshooting

### TempoStack not Ready
```bash
oc get events -n openshift-tempo --sort-by='.lastTimestamp' | tail -20
# Common: S3 credentials wrong → check tempo-s3 secret field names
# Common: OBC not Bound → check obc-tempo.yaml was applied in Phase 19
```

### No traces appearing
```bash
# Check auto-instrumentation init container was injected
oc get pod -n sampleapi-dev -l app=sampleapi -o jsonpath='{.items[0].spec.initContainers[*].name}'
# Expected: includes "opentelemetry-auto-instrumentation-dotnet"
# If missing: check Instrumentation CR exists and Deployment has annotation

# Check OTel Collector logs
oc logs deploy/otel-collector-collector -n openshift-tempo | tail -30

# Check OTEL env vars are set (injected by auto-instrumentation)
oc exec deploy/sampleapi -n sampleapi-dev -- env | grep OTEL
# Expected: OTEL_EXPORTER_OTLP_ENDPOINT, OTEL_SERVICE_NAME, OTEL_EXPORTER_OTLP_PROTOCOL=grpc
```

### Key Gotchas
- **.NET auto-instrumentation protocol**: .NET SDK defaults to `http/protobuf`. Must set
  `OTEL_EXPORTER_OTLP_PROTOCOL=grpc` in Instrumentation CR env.
- **Red Hat OTel Operator has no .NET image**: Use community image
  `ghcr.io/open-telemetry/opentelemetry-operator/autoinstrumentation-dotnet:1.9.0`.
- **Gateway + Jaeger ingress conflict**: Cannot enable both. Set `jaegerQuery.ingress.type: ""`
  when using gateway.
- **gRPC + Bearer token**: Using `bearertokenauth` with gRPC causes TLS errors. Use OTLP HTTP
  exporters on gateway port 8080 instead.
- **Console tracing UI**: Requires TempoStack with `tenants.mode: openshift` + gateway enabled.
  Without multi-tenancy, Console shows "Instances without multi-tenancy are not supported".

### Grafana Tempo datasource not working
```bash
# Check datasource exists
oc get grafanadatasource tempo-datasource -n devsecops-tools

# Re-inject token if needed
TOKEN=$(oc create token grafana-sa -n devsecops-tools --duration=8760h)
cat infra/phase22/grafana-datasource-tempo.yaml | \
  sed "s|REPLACE_WITH_TOKEN|Bearer $TOKEN|" | \
  oc apply -f - -n devsecops-tools
```
