# Phase 19: Logging with LokiStack

## Overview

Production-grade centralized logging using OpenShift Logging 6.x:
- **Loki Operator** — manages LokiStack (log storage + query engine)
- **Cluster Logging Operator** — manages ClusterLogForwarder (Vector collector + routing)
- **ODF (NooBaa)** — S3-compatible object storage backend for Loki

## Architecture

```
┌─────────────┐   ┌─────────────┐   ┌─────────────┐   ┌─────────────┐
│ sampleapi   │   │ notification│   │  jenkins     │   │  gitLab     │
│ pod stdout  │   │ api stdout  │   │  pod stdout  │   │  pod stdout │
└──────┬──────┘   └──────┬──────┘   └──────┬──────┘   └──────┬──────┘
       │                 │                  │                  │
       └─────────────────┴──────────────────┴──────────────────┘
                                   │
                    ┌──────────────▼──────────────┐
                    │   Vector DaemonSet           │
                    │   (1 pod per node)            │
                    │   Reads /var/log/pods/*       │
                    │   ClusterLogForwarder routes  │
                    └──────────────┬──────────────┘
                                   │
                    ┌──────────────▼──────────────┐
                    │   LokiStack (logging-loki)   │
                    │   Distributor → Ingester      │
                    │   Querier → Query Frontend    │
                    │   Compactor → Index Gateway   │
                    └──────────────┬──────────────┘
                                   │
                    ┌──────────────▼──────────────┐
                    │   NooBaa S3 Bucket            │
                    │   (via ObjectBucketClaim)     │
                    └──────────────────────────────┘
                                   │
              ┌────────────────────┴────────────────────┐
              │                                         │
    ┌─────────▼──────────┐              ┌──────────────▼───────┐
    │   OCP Console       │              │   Grafana (Phase 21) │
    │   Observe → Logs    │              │   Explore → LogQL    │
    └────────────────────┘              └──────────────────────┘
```

## Prerequisites

- OCP 4.17+ (Logging 6.x requires OCP 4.14+)
- At least 3 worker nodes (ODF requirement for Ceph replication)
- `gp3-csi` StorageClass available (AWS default)
- Cluster-admin access

## Execution Order

```bash
# 1. Install ODF Operator (skip if already installed)
oc apply -f odf-operator-subscription.yaml
# Wait for CSV: oc get csv -n openshift-storage | grep odf
# Takes 2-3 minutes

# 2. Deploy StorageCluster (skip if already exists)
oc apply -f odf-storagecluster.yaml
# Wait for Ready: oc get storagecluster -n openshift-storage
# Takes 5-10 minutes — Ceph needs to initialize across nodes

# 3. Install Loki Operator + Cluster Logging Operator
oc apply -f loki-operator-subscription.yaml
oc apply -f logging-operator-subscription.yaml
# Wait for both CSVs: oc get csv -n openshift-logging
# Takes 2-3 minutes

# 4. Create ObjectBucketClaims
oc apply -f obc-loki.yaml
oc apply -f obc-tempo.yaml
# Wait for Bound: oc get obc -n openshift-logging
# Takes 30-60 seconds

# 5. Deploy LokiStack
oc apply -f lokistack.yaml
# Wait for Ready: oc get lokistack logging-loki -n openshift-logging
# Takes 2-5 minutes

# 6. Create collector ServiceAccount + RBAC
oc apply -f cluster-log-forwarder-sa.yaml

# 7. Deploy ClusterLogForwarder
oc apply -f cluster-log-forwarder.yaml
# Wait for collector pods: oc get pods -n openshift-logging -l component=collector
# Takes 1-2 minutes (one pod per node)
```

## Verification

```bash
# ODF running
oc get storagecluster -n openshift-storage
# Expected: ocs-storagecluster Phase=Ready

# OBC bound with auto-generated credentials
oc get obc -n openshift-logging
# Expected: loki-bucket STATUS=Bound

# Both operators installed
oc get csv -n openshift-logging | grep -E "loki|logging"
# Expected: 2 operators, both Succeeded

# LokiStack ready
oc get lokistack logging-loki -n openshift-logging
# Expected: STATUS=Ready (or READY=True)

# Collector pods running (one per node)
oc get pods -n openshift-logging -l component=collector
# Expected: 6 pods Running

# Query logs from OCP Console:
#   Observe → Logs → Select "application" tenant
#   Query: {kubernetes_namespace_name="sampleapi-dev"}
#   Should show recent logs from sampleapi pods
```

## Log Retention

| Tenant | Retention | Rationale |
|--------|-----------|-----------|
| application | 14 days | Enough for debugging recent issues |
| infrastructure | 7 days | High volume, less frequently queried |
| audit | 30 days | Compliance requirement |

## File Inventory

| File | Purpose |
|------|---------|
| `odf-operator-subscription.yaml` | ODF Operator for S3 object storage |
| `odf-storagecluster.yaml` | Ceph + NooBaa deployment |
| `loki-operator-subscription.yaml` | Loki Operator for log storage |
| `logging-operator-subscription.yaml` | Cluster Logging Operator for Vector collector |
| `obc-loki.yaml` | S3 bucket for Loki log storage |
| `obc-tempo.yaml` | S3 bucket for Tempo traces (Phase 22 pre-provision) |
| `lokistack.yaml` | LokiStack CR (log storage + query engine) |
| `cluster-log-forwarder-sa.yaml` | ServiceAccount + RBAC for Vector collector |
| `cluster-log-forwarder.yaml` | ClusterLogForwarder (log routing + filtering) |
| `log-query-examples.md` | Useful LogQL queries for DevSecOps workflows |

## Troubleshooting

### OBC stuck in Pending
```bash
oc get obc loki-bucket -n openshift-logging -o yaml | grep -A5 status
# Check NooBaa is healthy:
oc get noobaa -n openshift-storage
# If NooBaa not ready, wait for ODF StorageCluster to finish initializing
```

### LokiStack not Ready
```bash
oc describe lokistack logging-loki -n openshift-logging
oc get pods -n openshift-logging -l app.kubernetes.io/instance=logging-loki
# Check for pending pods (PVC issues, resource limits)
oc get events -n openshift-logging --sort-by='.lastTimestamp' | tail -20
```

### No collector pods
```bash
oc get clusterlogforwarder instance -n openshift-logging -o yaml | grep -A10 conditions
# Common: SA missing ClusterRoleBindings
# Fix: re-apply cluster-log-forwarder-sa.yaml
```

### No logs in OCP Console
```bash
# Check collector is sending to Loki
oc logs -l component=collector -n openshift-logging --tail=20
# Look for: "sending batch" or connection errors to Loki

# Check LokiStack ingester is receiving
oc logs -l app.kubernetes.io/component=ingester -n openshift-logging --tail=10
```

### ODF requires too many resources
For lab environments with limited nodes, consider using MinIO instead of ODF:
1. Skip `odf-operator-subscription.yaml` and `odf-storagecluster.yaml`
2. Deploy a standalone MinIO instance in `openshift-logging`
3. Create a Secret manually with S3 credentials
4. Update `lokistack.yaml` to reference the MinIO secret
See Phase 22 troubleshooting for MinIO alternative deployment.
