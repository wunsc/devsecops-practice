# Phase 20: Monitoring & Alerting

## Overview

Production-grade monitoring and alerting using OpenShift User Workload Monitoring:
- **Prometheus** scrapes application metrics via ServiceMonitor CRDs
- **PrometheusRule** defines SLO burn-rate and infrastructure alerts
- **AlertmanagerConfig** routes alerts to Slack channels by severity

## Architecture

```
┌───────────────────────────────────────────────────────────────────┐
│ sampleapi-{env} namespace                                          │
│                                                                    │
│  ┌──────────┐   ┌──────────────────┐   ┌────────────────────┐    │
│  │SampleApi │◄──│ ServiceMonitor   │   │ PrometheusRule     │    │
│  │ /metrics │   │ sampleapi-monitor│   │ slo + infra alerts │    │
│  └──────────┘   └──────────────────┘   └────────┬───────────┘    │
│  ┌──────────┐   ┌──────────────────┐            │ fire           │
│  │Notific.  │◄──│ ServiceMonitor   │   ┌────────▼───────────┐    │
│  │Api       │   │ notificationapi- │   │ AlertmanagerConfig │    │
│  │ /metrics │   │ monitor          │   │ → Slack channels   │    │
│  └──────────┘   └──────────────────┘   └────────────────────┘    │
└───────────────────────────────────────────────────────────────────┘
                         │ scrape
              ┌──────────▼─────────────┐
              │ Prometheus (UWM)        │
              │ openshift-user-workload │
              │ -monitoring             │
              └────────────────────────┘
```

## Prerequisites

- OCP 4.17+ with cluster-admin access
- SampleApi and NotificationApi deployed (Phase 17)
- prometheus-net NuGet package added to both services (included in this phase)

## Execution Order

```bash
# 1. Enable user workload monitoring
oc apply -f user-workload-monitoring.yaml
# Wait: oc get pods -n openshift-user-workload-monitoring
# Expected: prometheus-user-workload-0, thanos-ruler-user-workload-0 Running

# 2. Push app changes (prometheus-net + AppMetrics.cs + Program.cs updates)
# This triggers T2 pipeline → builds new image → deploys to DEV
cd services/sampleapi && git add -A && git commit -m "feat: add Prometheus metrics (Phase 20)"
cd services/notificationapi && git add -A && git commit -m "feat: add Prometheus metrics (Phase 20)"

# 3. Apply ServiceMonitors to all app namespaces
for NS in sampleapi-dev sampleapi-sit sampleapi-uat sampleapi-prod; do
  oc apply -f servicemonitor-sampleapi.yaml -n $NS
  oc apply -f servicemonitor-notificationapi.yaml -n $NS
  oc apply -f servicemonitor-postgresql.yaml -n $NS
  oc apply -f servicemonitor-redis.yaml -n $NS
done

# 4. Apply PrometheusRules
for NS in sampleapi-dev sampleapi-sit sampleapi-uat sampleapi-prod; do
  oc apply -f prometheus-rules-slo.yaml -n $NS
  oc apply -f prometheus-rules-infra.yaml -n $NS
done
oc apply -f prometheus-rules-pipeline.yaml -n devsecops-tools

# 5. Apply AlertmanagerConfig (update secret with real Slack webhook URLs first!)
for NS in sampleapi-dev sampleapi-sit sampleapi-uat sampleapi-prod; do
  oc apply -f alertmanager-secret.yaml -n $NS
  oc apply -f alertmanager-config.yaml -n $NS
done
```

## Verification

```bash
# Metrics endpoint works
curl -sk https://sampleapi-dev.${APPS_DOMAIN}/metrics | head -20
# Expected: prometheus-net metrics (http_request_duration_seconds_*, process_*, etc.)

# ServiceMonitors active
oc get servicemonitor -n sampleapi-dev
# Expected: sampleapi-monitor, notificationapi-monitor, postgresql-monitor, redis-monitor

# PrometheusRules loaded
oc get prometheusrule -n sampleapi-dev
# Expected: sampleapi-slo-alerts, sampleapi-infra-alerts

# Query in OCP Console → Observe → Metrics:
#   rate(http_request_duration_seconds_count{namespace="sampleapi-dev"}[5m])
# Expected: non-zero data points

# Custom metrics working:
#   sampleapi_forecasts_total
#   sampleapi_cache_operations_total
# Expected: counter values after hitting /api/WeatherForecast

# Test alert firing:
oc scale deployment sampleapi -n sampleapi-dev --replicas=0
# Wait 5 minutes → OCP Console → Observe → Alerting
# Expected: ServiceNoEndpoints alert fires
oc scale deployment sampleapi -n sampleapi-dev --replicas=1
# Alert should resolve
```

## SLO Alert Design

| Severity | Burn Rate | Windows | Budget Consumed | Action |
|----------|-----------|---------|-----------------|--------|
| critical | 14.4x | 1h AND 5m | 2% in 1 hour | Page on-call immediately |
| warning | 6x | 6h AND 30m | 5% in 6 hours | Investigate within 1 hour |
| info | 1x | 3d AND 6h | 10% in 3 days | Review during business hours |

**SLO Target:** 99.9% availability = 43.2 minutes error budget per month.

## File Inventory

### Infrastructure (infra/phase20/)

| File | Purpose |
|------|---------|
| `user-workload-monitoring.yaml` | Enable Prometheus for user namespaces |
| `servicemonitor-sampleapi.yaml` | Scrape SampleApi /metrics |
| `servicemonitor-notificationapi.yaml` | Scrape NotificationApi /metrics |
| `servicemonitor-postgresql.yaml` | Scrape PostgreSQL metrics (pg_exporter sidecar) |
| `servicemonitor-redis.yaml` | Scrape Redis metrics (redis_exporter sidecar) |
| `prometheus-rules-slo.yaml` | SLO burn-rate alerts + recording rules |
| `prometheus-rules-infra.yaml` | Pod restarts, OOM, quota, PVC, service health |
| `prometheus-rules-pipeline.yaml` | Jenkins queue, failure rate, ArgoCD sync |
| `alertmanager-config.yaml` | Route alerts to Slack by severity |
| `alertmanager-secret.yaml` | Slack webhook URLs (replace placeholders!) |

### Application Changes

| File | Change |
|------|--------|
| `SampleApi.csproj` | Added `prometheus-net.AspNetCore` v8.2.1 |
| `SampleApi/Program.cs` | Added `UseHttpMetrics()` + `MapMetrics()` |
| `SampleApi/Metrics/AppMetrics.cs` | Custom business metrics (forecasts, cache, latency) |
| `NotificationApi.csproj` | Added `prometheus-net.AspNetCore` v8.2.1 |
| `NotificationApi/Program.cs` | Added `UseHttpMetrics()` + `MapMetrics()` |

## Troubleshooting

### No metrics at /metrics
- Verify `prometheus-net.AspNetCore` is in the csproj
- Verify `app.MapMetrics()` is called in Program.cs
- Verify the new image was built and deployed (check image tag in ArgoCD)

### ServiceMonitor has no targets
- Check Service labels match ServiceMonitor selector: `oc get svc -n sampleapi-dev --show-labels`
- The Service must have a port named `http` (matching the ServiceMonitor `port: http`)
- ServiceMonitor must be in the same namespace as the Service

### Alerts not firing
- Check PrometheusRule is loaded: `oc get prometheusrule -n sampleapi-dev -o yaml`
- Check Thanos Ruler logs: `oc logs -n openshift-user-workload-monitoring deploy/thanos-ruler-user-workload`
- Verify the metric exists in Prometheus: query it in OCP Console → Observe → Metrics

### PostgreSQL/Redis metrics missing
- pg_exporter and redis_exporter sidecars need to be added to the StatefulSets
- See comments in servicemonitor-postgresql.yaml and servicemonitor-redis.yaml for sidecar specs
