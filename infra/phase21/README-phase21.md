# Phase 21: Grafana Dashboards

## Overview

Deploy Grafana Operator v5 with 5 dashboards-as-code via GrafanaDashboard CRDs:
- **App Health (RED)** — Request rate, Error rate, Duration percentiles
- **Pipeline Execution** — Jenkins build success/failure, duration, queue
- **Infrastructure** — CPU, memory, quota, PVC usage per namespace
- **SLO Burn Rate** — Error budget remaining, burn rate windows, compliance history
- **Log Analytics** — Log volume, error rate, live log stream (via Loki)

## Architecture

```
┌─────────────────────────────────────────────────┐
│ Grafana (devsecops-tools namespace)              │
│                                                  │
│  GrafanaDatasource:                              │
│    ├── Prometheus (Thanos Querier)               │
│    └── Loki (LokiStack gateway)                  │
│                                                  │
│  GrafanaDashboard CRDs:                          │
│    ├── App Health (RED)                           │
│    ├── Pipeline Execution                        │
│    ├── Infrastructure Resources                  │
│    ├── SLO Burn Rate & Error Budget              │
│    └── Log Analytics                             │
│                                                  │
│  Authentication:                                 │
│    grafana-sa → cluster-monitoring-view           │
│             → cluster-logging-application-view    │
└─────────────────────────────────────────────────┘
```

## Prerequisites

- Phase 19: LokiStack deployed and receiving logs
- Phase 20: User workload monitoring enabled, ServiceMonitors + PrometheusRules applied
- `devsecops-tools` namespace with sufficient quota for Grafana pod

## Execution Order

```bash
# 1. Install Grafana Operator v5
oc apply -f grafana-operator-subscription.yaml
# Wait: oc get csv -n devsecops-tools | grep grafana
# Expected: grafana-operator.v5.x.y Succeeded (takes 1-2 min)

# 2. Create ServiceAccount + RBAC
oc apply -f grafana-sa.yaml
oc apply -f grafana-sa-clusterrolebinding.yaml

# 3. Generate SA token and store as Secret
TOKEN=$(oc create token grafana-sa -n devsecops-tools --duration=8760h)
oc create secret generic grafana-sa-token -n devsecops-tools \
  --from-literal=token="$TOKEN"

# 4. Deploy Grafana instance
oc apply -f grafana-instance.yaml
# Wait: oc get pods -n devsecops-tools -l app=grafana
# Expected: grafana-deployment-xxxx Running (takes 1-2 min)

# 5. Create datasources
oc apply -f grafana-datasource-prometheus.yaml
oc apply -f grafana-datasource-loki.yaml

# 6. Create dashboards
oc apply -f grafana-dashboard-app.yaml
oc apply -f grafana-dashboard-pipeline.yaml
oc apply -f grafana-dashboard-infrastructure.yaml
oc apply -f grafana-dashboard-slo.yaml
oc apply -f grafana-dashboard-logs.yaml
```

## Verification

```bash
# Grafana pod running
oc get pods -n devsecops-tools -l app=grafana
# Expected: grafana-deployment-xxxx Running

# Route accessible
GRAFANA_URL="https://grafana-devsecops-tools.apps.cluster-pmqwq.pmqwq.sandbox270.opentlc.com"
curl -sk -o /dev/null -w "%{http_code}" $GRAFANA_URL
# Expected: 200 or 302

# Datasources configured
oc get grafanadatasource -n devsecops-tools
# Expected: prometheus-datasource, loki-datasource

# Dashboards loaded
oc get grafanadashboard -n devsecops-tools
# Expected: app-health-dashboard, pipeline-dashboard, infrastructure-dashboard,
#           slo-dashboard, logs-dashboard

# Login to Grafana:
#   URL: $GRAFANA_URL
#   User: admin
#   Password: DevSec0ps-Grafana-2024
# Navigate to Dashboards → verify all 5 show data
```

## Dashboard Details

### App Health (RED Method)
- **Variables:** `$namespace` (sampleapi-dev/sit/uat/prod), `$service` (sampleapi/notificationapi)
- **Key panels:** Request rate, Error rate %, p50/p95/p99 latency, pod count, restarts, CPU/memory
- **Use case:** Daily monitoring — "is the app healthy right now?"

### Pipeline Execution
- **Key panels:** Total builds (24h), success rate gauge, failed builds, queue size, build duration trend
- **Use case:** CI/CD health — "are pipelines running smoothly?"
- **Note:** Requires Jenkins Prometheus Metrics plugin

### Infrastructure Resources
- **Variables:** `$namespace` (multi-select all namespaces)
- **Key panels:** CPU/memory usage by namespace, quota usage %, PVC usage %, pod status pie chart
- **Use case:** Capacity planning — "are we running out of resources?"

### SLO Burn Rate & Error Budget
- **Key panels:** Error budget remaining gauge, current error rate, burn rate 1h/6h/3d, error ratio history with SLO threshold line
- **Use case:** SLO compliance — "are we meeting our 99.9% availability target?"
- **Note:** Uses recording rules from Phase 20 prometheus-rules-slo.yaml

### Log Analytics
- **Variables:** `$namespace`, `$container` (multi-select)
- **Key panels:** Log volume by namespace, error log rate, error vs non-error ratio, live log stream
- **Use case:** Debugging — "what's in the logs?"
- **Note:** Requires Loki datasource (Phase 19)

## File Inventory

| File | Purpose |
|------|---------|
| `grafana-operator-subscription.yaml` | Install Grafana Operator v5 from community-operators |
| `grafana-instance.yaml` | Grafana CR with route, persistence, admin credentials |
| `grafana-sa.yaml` | ServiceAccount for Thanos/Loki access |
| `grafana-sa-clusterrolebinding.yaml` | ClusterRoleBindings for monitoring + logging view |
| `grafana-datasource-prometheus.yaml` | GrafanaDatasource: Thanos Querier (default) |
| `grafana-datasource-loki.yaml` | GrafanaDatasource: LokiStack application tenant |
| `grafana-dashboard-app.yaml` | Dashboard: App Health (RED method) |
| `grafana-dashboard-pipeline.yaml` | Dashboard: Pipeline execution + Jenkins health |
| `grafana-dashboard-infrastructure.yaml` | Dashboard: Namespace resources + quotas |
| `grafana-dashboard-slo.yaml` | Dashboard: SLO burn rate + error budget |
| `grafana-dashboard-logs.yaml` | Dashboard: Log volume + error patterns + live tail |
| `README-phase21.md` | This file |

## GitOps Integration (Future)

The dashboard CRDs can be moved into `app-gitops/monitoring/grafana/` and synced by
ArgoCD. This enables dashboard versioning, review via MR, and rollback — same workflow
as application manifests.

## Troubleshooting

### Grafana shows "No data" on Prometheus panels
- Check SA token: `oc create token grafana-sa -n devsecops-tools` (should work)
- Check ClusterRoleBinding: `oc get clusterrolebinding grafana-cluster-monitoring-view`
- Test Thanos Querier directly: `oc exec deploy/grafana -n devsecops-tools -- curl -sk -H "Authorization: Bearer $TOKEN" https://thanos-querier.openshift-monitoring.svc:9091/api/v1/query?query=up`

### Loki datasource "Bad Gateway"
- Check LokiStack is healthy: `oc get lokistack -n openshift-logging`
- Check logging-application-view ClusterRole exists (created by Loki Operator)
- Check the Loki gateway URL: `logging-loki-gateway-http.openshift-logging.svc:8080`

### Grafana pod CrashLoopBackOff
- Check events: `oc get events -n devsecops-tools --sort-by='.lastTimestamp'`
- Check PVC: `oc get pvc -n devsecops-tools` (storage may not be provisioned)
- Check resource quota: Grafana needs ~256Mi memory
