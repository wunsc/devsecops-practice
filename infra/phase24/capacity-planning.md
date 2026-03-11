# Capacity Planning — DevSecOps Platform

## Current Resource Usage (2026-03-09)

### PROD Environment (sampleapi-prod)

| Pod | CPU (actual) | Memory (actual) | CPU Limit | Memory Limit |
|-----|-------------|-----------------|-----------|-------------|
| sampleapi (×3) | 3m each | 188-215Mi each | 1500m each | 1Gi each |
| notificationapi (×2) | 3m each | 113-114Mi each | 500m each | 512Mi each |
| postgresql-0 | 6m | 40Mi | 1000m | 512Mi |
| redis-0 | 5m | 8Mi | 500m | 256Mi |
| **Total actual** | **~27m** | **~986Mi** | | |
| **Total limits** | | | **8250m** | **9472Mi** |

### Quota vs Usage

| Namespace | Quota CPU | Used CPU | Quota Memory | Used Memory | Headroom |
|-----------|-----------|----------|--------------|-------------|----------|
| sampleapi-dev | 6 CPU | 300m (5%) | 12Gi | 640Mi (5%) | ~95% |
| sampleapi-sit | 8 CPU | ~600m (7.5%) | 16Gi | ~1.3Gi (8%) | ~92% |
| sampleapi-uat | 8 CPU | ~600m (7.5%) | 16Gi | ~1.3Gi (8%) | ~92% |
| sampleapi-prod | 12 CPU | ~2250m (19%) | 24Gi | ~9.5Gi (40%) | ~60% |
| devsecops-tools | 16 CPU | ~4 CPU (25%) | 32Gi | ~12Gi (37%) | ~63% |
| devsecops-gitlab | 12 CPU | ~2 CPU (17%) | 20Gi | ~14Gi (70%) | ~30% |

### Cluster Resources

| Resource | Total | Used | Available |
|----------|-------|------|-----------|
| Worker Nodes | 3 | 3 | 0 spare |
| vCPU (workers) | ~48 | ~20 | ~28 |
| Memory (workers) | ~192Gi | ~80Gi | ~112Gi |
| Storage (gp3-csi) | Dynamic | ~100Gi PVCs | Auto-provision |

## Growth Projections

### Scenario 1: Add 2 More Microservices

Each new .NET service adds approximately:
- DEV: +100m CPU, +200Mi memory
- PROD: +300m CPU (×3 replicas), +600Mi memory

**Impact**: Minimal — well within current quotas.

### Scenario 2: Scale PROD to 5 Replicas Per Service

SampleApi: 5 × (750m CPU, 1Gi mem) = 3750m CPU, 5Gi memory
NotificationApi: 5 × (250m CPU, 512Mi mem) = 1250m CPU, 2.5Gi memory

**Impact**: Need to increase PROD quota from 12 CPU to 16 CPU, 24Gi to 32Gi memory.

### Scenario 3: Add Load Testing (k6) in Pipeline

k6 runs in Jenkins agent pod with ZAP sidecar during T3.
Adds ~500m CPU, 1Gi memory during test execution (temporary).

**Impact**: Already accounted for in devsecops-tools quota (16 CPU / 32Gi).

## Recommendations

1. **No immediate action needed** — all environments operate at <50% capacity
2. **Monitor GitLab memory** — at 70% quota usage, closest to limit
3. **Add a 4th worker node** before adding more services to PROD (for anti-affinity)
4. **Set up Prometheus alerts** for quota approaching 80% (already done in Phase 20)
5. **Review after Phase 23** — k6 load tests will show actual capacity under stress

## Monitoring Queries

```promql
# Namespace CPU usage vs quota
sum(rate(container_cpu_usage_seconds_total{namespace="sampleapi-prod"}[5m]))
/
kube_resourcequota{namespace="sampleapi-prod",resource="limits.cpu",type="hard"}

# Memory usage trend (7 days)
sum(container_memory_working_set_bytes{namespace="sampleapi-prod"})
```
