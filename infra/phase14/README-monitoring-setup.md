# Phase 14: Monitoring, Logging & Alerting

## Overview

This phase configures the observability stack for the DevSecOps workflow:
- **Monitoring**: Prometheus (user workload monitoring) + Grafana dashboards
- **Alerting**: PrometheusRule with alert conditions for application and pods
- **Logging**: OpenShift Logging (Vector + Loki/Elasticsearch) with log forwarding
- **ACS Notifications**: StackRox alert channels (email, Slack, webhook)

## Architecture

```
┌──────────────────────────────────────────────────────────┐
│                    Observability Stack                     │
├───────────────┬──────────────────┬────────────────────────┤
│  Monitoring   │    Logging       │    Security Alerts     │
│               │                  │                        │
│  Prometheus   │  Vector          │  ACS Central           │
│  (user wkld)  │  (collector)     │  (StackRox)            │
│       │       │       │          │       │                │
│       ▼       │       ▼          │       ▼                │
│  Thanos       │  Loki/           │  Email / Slack /       │
│  (query)      │  Elasticsearch   │  Webhook               │
│       │       │       │          │                        │
│       ▼       │       ▼          │                        │
│  Grafana      │  OCP Console     │                        │
│  Dashboards   │  Observe > Logs  │                        │
└───────────────┴──────────────────┴────────────────────────┘
```

## Files

| File | Purpose |
|------|---------|
| `user-workload-monitoring.yaml` | Enable Prometheus for user namespaces |
| `servicemonitor-app.yaml` | ServiceMonitor + PodMonitor for app pods |
| `prometheus-rules.yaml` | Alert rules (error rate, latency, restarts, resources) |
| `grafana-dashboard-app.json` | Application performance dashboard |
| `grafana-dashboard-pipeline.json` | CI/CD pipeline execution dashboard |
| `cluster-log-forwarder.yaml` | OpenShift Logging configuration |
| `acs-notifier-config.yaml` | ACS notification channels (email, Slack, webhook) |

## Execution Order

```bash
# Step 1: Enable user workload monitoring (cluster-admin required)
oc apply -f infra/phase14/user-workload-monitoring.yaml

# Step 2: Wait for user workload monitoring pods
oc get pods -n openshift-user-workload-monitoring -w
# Expected: prometheus-user-workload-0, thanos-ruler-user-workload-0

# Step 3: Apply ServiceMonitor to each app namespace
for ns in sampleapi-dev sampleapi-sit sampleapi-uat sampleapi-prod; do
  oc apply -f infra/phase14/servicemonitor-app.yaml -n $ns
done

# Step 4: Apply PrometheusRules to each app namespace
for ns in sampleapi-dev sampleapi-sit sampleapi-uat sampleapi-prod; do
  oc apply -f infra/phase14/prometheus-rules.yaml -n $ns
done

# Step 5: Import Grafana dashboards
# Via OCP Console: Observe > Dashboards > Import
# Or via Grafana API if standalone Grafana is deployed

# Step 6: Configure logging (requires Logging operator)
oc apply -f infra/phase14/cluster-log-forwarder.yaml -n openshift-logging

# Step 7: Configure ACS notifiers (via UI or API)
# See acs-notifier-config.yaml for instructions
```

## Verification

### Monitoring

```bash
# Check user workload monitoring is running
oc get pods -n openshift-user-workload-monitoring

# Check ServiceMonitors are created
oc get servicemonitor -n sampleapi-dev

# Check PrometheusRules are loaded
oc get prometheusrule -n sampleapi-dev

# Check targets in Prometheus
# OCP Console > Observe > Targets > filter by namespace

# Check alerts
# OCP Console > Observe > Alerting > filter by "sampleapi"
```

### Logging

```bash
# Check logging stack
oc get pods -n openshift-logging

# Check ClusterLogForwarder
oc get clusterlogforwarder instance -n openshift-logging -o yaml

# View application logs
# OCP Console > Observe > Logs > filter by namespace
```

### Grafana Dashboards

```bash
# Access Grafana (if standalone)
oc get route grafana -n openshift-monitoring

# Or use OCP Console built-in dashboards
# OCP Console > Observe > Dashboards
```

## Alert Runbooks

### SampleAPIHighErrorRate (Critical)
- **Threshold**: >5% of requests returning 5xx for 5 minutes
- **Action**: Check application logs, database connectivity, dependent services
- **Command**: `oc logs -l app=sampleapi -n <namespace> --tail=100`

### SampleAPIPodRestarting (Critical)
- **Threshold**: >3 restarts in 15 minutes
- **Action**: Check previous pod logs, describe pod for OOMKill or probe failures
- **Command**: `oc logs <pod> --previous && oc describe pod <pod>`

### SampleAPIDown (Critical)
- **Threshold**: 0 available replicas for 2 minutes
- **Action**: Immediate investigation — check events, deployment status, ArgoCD sync
- **Command**: `oc get events --sort-by='.lastTimestamp' -n <namespace>`

### SampleAPIHighCPU / SampleAPIHighMemory (Warning)
- **Threshold**: >80% CPU or >85% memory of limits for 10 minutes
- **Action**: Consider scaling or increasing resource limits in Kustomize overlay
- **Command**: `oc top pods -n <namespace>`

## Prometheus Metrics Reference

### Application Metrics (requires prometheus-net in app)
| Metric | Type | Description |
|--------|------|-------------|
| `http_requests_received_total` | Counter | Total HTTP requests by method, status code |
| `http_request_duration_seconds` | Histogram | Request latency distribution |

### Container Metrics (automatic via cAdvisor)
| Metric | Type | Description |
|--------|------|-------------|
| `container_cpu_usage_seconds_total` | Counter | CPU usage |
| `container_memory_working_set_bytes` | Gauge | Memory usage |
| `container_network_receive_bytes_total` | Counter | Network RX |
| `container_network_transmit_bytes_total` | Counter | Network TX |

### Kubernetes Metrics (automatic via kube-state-metrics)
| Metric | Type | Description |
|--------|------|-------------|
| `kube_deployment_status_replicas_available` | Gauge | Available replicas |
| `kube_pod_container_status_restarts_total` | Counter | Pod restart count |
| `kube_pod_status_ready` | Gauge | Pod readiness |

## Troubleshooting

### ServiceMonitor not showing in targets
1. Check that user workload monitoring is enabled
2. Verify ServiceMonitor labels match the Service
3. Check RBAC: `oc auth can-i get pods --as=system:serviceaccount:openshift-user-workload-monitoring:prometheus-user-workload -n sampleapi-dev`

### Alerts not firing
1. Check PrometheusRule is loaded: `oc get prometheusrule -n <namespace>`
2. Check alert expression in OCP Console > Observe > Alerting
3. Verify Thanos Ruler is running in openshift-user-workload-monitoring

### Logs not appearing
1. Check collector pods: `oc get pods -l component=collector -n openshift-logging`
2. Check ClusterLogForwarder status: `oc get clf instance -n openshift-logging -o yaml`
3. Check collector logs: `oc logs -l component=collector -n openshift-logging --tail=50`
