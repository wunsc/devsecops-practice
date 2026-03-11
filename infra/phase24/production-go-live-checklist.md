# Production Go-Live Checklist

## Pre-Go-Live Validation

### Security

- [x] NetworkPolicies: default-deny + explicit allow in all namespaces
- [x] RBAC: least-privilege ServiceAccounts (jenkins-sa, sampleapi-sa)
- [x] ACS admission controller deployed (3 replicas)
- [x] ACS security policies: block critical CVEs, block root user, block untrusted registries
- [x] SonarQube quality gate enforced (0 bugs, 0 vulns, ≥80% coverage)
- [x] OWASP Dependency-Check SCA in all pipelines
- [x] OWASP ZAP DAST in T3 (tag) pipeline
- [x] Image signing with Cosign (optional — graceful skip)
- [x] Secrets stored as Kubernetes Secrets (Phase 18 External Secrets not deployed — lab env)
- [ ] CIS Benchmark compliance scan — run and document findings
- [ ] NIST 800-53 / SOC2 control mapping — document which controls are satisfied

### Reliability

- [x] PodDisruptionBudgets on all PROD services (sampleapi: minAvail=1, notificationapi: minAvail=1)
- [x] PROD sampleapi: 3 replicas spread across 3 worker nodes
- [x] PROD notificationapi: 2 replicas spread across 2 worker nodes
- [x] Rolling update strategy: maxUnavailable=0, maxSurge=1
- [x] Health checks: /healthz (liveness), /readyz (readiness) — checks PostgreSQL + Redis
- [x] Resource requests AND limits set for all containers
- [x] PostgreSQL + Redis on StatefulSets with PVCs (data persists across restarts)
- [ ] Chaos test: pod kill — verify PDB + auto-heal
- [ ] Chaos test: network partition — verify graceful degradation
- [ ] Chaos test: node drain — verify zero-downtime migration

### Observability

- [x] LokiStack logging (Loki Operator v6.2 + Vector collectors)
- [x] 16 ServiceMonitors across all namespaces
- [x] 13 PrometheusRules (SLO burn-rate + infra alerts)
- [x] 2 AlertmanagerConfigs (dev + prod routing)
- [x] 6 Grafana dashboards (App Health, Pipeline, Infrastructure, SLO, Logs, Traces)
- [x] Distributed tracing (OTel auto-instrumentation → Tempo → Console UI + Grafana)
- [x] OpenShift Console distributed tracing UI (Cluster Observability Operator)
- [ ] Alert routing tested end-to-end (verify alert → notification delivery)
- [ ] On-call runbook entry for each PrometheusRule alert

### CI/CD

- [x] T1 (MR) pipeline: unit test + SAST + SCA → status on GitLab MR
- [x] T2 (merge) pipeline: build + scan + deploy DEV → auto-sync
- [x] T3 (tag) pipeline: full scan + DAST + perf test → release image
- [x] T4 (promote) pipeline: detect service+env change → ArgoCD sync → create next MR
- [x] 7 Jenkins jobs (3 sampleapi + 3 notificationapi + 1 promote)
- [x] 7 GitLab webhooks wired correctly
- [x] Cascading promotion chain: T3→SIT→T4→UAT→T4→PROD→T4→done
- [x] Concurrent T2 safe (git pull --rebase retry in updateGitOps)
- [ ] Pipeline SLA verified: T1 < 5 min, T2 < 10 min, T3 < 20 min
- [ ] Rollback tested: ArgoCD rollback + Git revert

### Documentation

- [x] PROGRESS.md — complete deployment log (Phases 1-22)
- [x] INFRASTRUCTURE.md — all components documented
- [x] SECRETS.md — all credentials mapped (location only, never values)
- [x] INTEGRATIONS.md — all integration points documented
- [x] DECISIONS.md — architectural decisions with rationale
- [x] RUNBOOK.md — Day-2 operations procedures
- [x] Disaster Recovery plan (infra/phase24/disaster-recovery.md)
- [x] Capacity Planning (infra/phase24/capacity-planning.md)

### Backups

- [ ] PostgreSQL backup CronJob running and tested
- [ ] GitLab backup CronJob running and tested
- [ ] Backup restore procedure documented and tested

## Sign-Off

| Role | Name | Date | Status |
|------|------|------|--------|
| Platform Engineer | | | |
| Security Lead | | | |
| QA Lead | | | |
| Operations Lead | | | |
| Project Manager | | | |
