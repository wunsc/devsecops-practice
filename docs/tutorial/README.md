# DevSecOps on OpenShift — Tutorial Curriculum

> **Format:** Self-paced, hands-on tutorial with real cluster exercises
> **Duration:** ~30 hours across 23 modules / 6 tracks
> **Platform:** Red Hat OpenShift 4.x with Jenkins, ArgoCD, ACS, SonarQube, OWASP ZAP, RHTAS, RHTPA
> **Application:** .NET 8 + Java 21 microservices (5 services) with PostgreSQL and Redis, deployed across 4 environments

---

## How to Use This Tutorial

Each module follows the **Tell - Show - Do - Verify** pattern:

1. **Tell** — We explain WHAT and WHY before touching any YAML
2. **Show** — Complete, annotated code you can read and understand
3. **Do** — Exact commands to copy-paste and execute
4. **Verify** — Check commands with expected output so you know it worked

Every module builds on the previous ones. Complete them in order within each track.
Tracks can be taken independently after the Foundation Track.

---

## Track Overview

```
+---------------------------------------------------------------------+
|                     DevSecOps Tutorial Curriculum                    |
|                    23 Modules / 6 Tracks / ~30h                     |
+---------------------------------------------------------------------+
|                                                                     |
|  FOUNDATION TRACK (Modules 1-4) --- ~6h                             |
|  Build the platform base skills                                     |
|  M1 --> M2 --> M2B --> M3 --> M4                                    |
|                                                                     |
|  SECURITY TRACK (Modules 5-7) --- ~4h                               |
|  Add security scanning at every layer                               |
|  M5 --> M6 --> M7                                                   |
|                                                                     |
|  INTEGRATION TRACK (Modules 8-10) --- ~5h15m                        |
|  Wire everything into a complete pipeline                           |
|  M8 --> M9 --> M9B --> M10                                          |
|                                                                     |
|  OBSERVABILITY TRACK (Modules 11-13) --- ~4h15m                     |
|  See what's happening in your platform                              |
|  M11 --> M12 --> M13                                                |
|                                                                     |
|  ADVANCED TRACK (Modules 14-15) --- ~3h                             |
|  Performance gates and production hardening                         |
|  M14 --> M15                                                        |
|                                                                     |
|  SUPPLY CHAIN & MULTI-LANGUAGE TRACK (Modules 16-20) --- ~8h        |
|  Multi-language pipelines and supply chain security                 |
|  M16 --> M16B --> M17 --> M18 --> M19 --> M20                       |
|                                                                     |
+---------------------------------------------------------------------+
```

---

## Foundation Track (~6 hours)

Build the platform that everything else runs on.

| Module | Title | Duration | Quick Win |
|--------|-------|----------|-----------|
| [Module 1](module-01-openshift-fundamentals.md) | OpenShift Fundamentals for DevSecOps | ~75 min | See your namespaces in the OCP console within 5 minutes |
| [Module 2](module-02-container-builds.md) | Container Builds on OpenShift | ~60 min | Your first container running in OpenShift |
| [Module 2B](module-02b-gitlab-registry.md) | GitLab & Registry Setup | ~60 min | Push code to your own GitLab instance within 10 minutes |
| [Module 3](module-03-jenkins.md) | Jenkins on OpenShift | ~75 min | "Hello World" pipeline running on a custom agent pod |
| [Module 4](module-04-gitops-argocd.md) | GitOps with ArgoCD | ~60 min | Change a value in Git, watch ArgoCD sync it automatically |

---

## Security Track (~4 hours)

Add security scanning at every layer — source code, dependencies, containers, and running apps.

| Module | Title | Duration | Quick Win |
|--------|-------|----------|-----------|
| [Module 5](module-05-sast-sonarqube.md) | SAST with SonarQube | ~60 min | See your code's quality profile with bugs/vulns categorized |
| [Module 6](module-06-acs-container-security.md) | Container Security with ACS | ~75 min | Deploy a root-running container and watch ACS block it |
| [Module 7](module-07-dast-owasp-zap.md) | DAST with OWASP ZAP | ~45 min | See real OWASP Top 10 findings against your app |

---

## Integration Track (~5 hours 15 minutes)

Wire every tool into a seamless, automated pipeline.

| Module | Title | Duration | Quick Win |
|--------|-------|----------|-----------|
| [Module 8](module-08-three-trigger-pipeline.md) | The 3-Trigger Pipeline | ~75 min | Open an MR, watch Jenkins scan, see pass/fail on the MR page |
| [Module 9](module-09-per-env-config.md) | Per-Environment Configuration | ~60 min | Same app binary, different behavior in DEV vs PROD |
| [Module 9B](module-09b-multi-service-architecture.md) | Multi-Service Architecture | ~75 min | Hit SampleApi and see it call NotificationApi, query PostgreSQL, and check Redis |
| [Module 10](module-10-e2e-walkthrough.md) | End-to-End DevSecOps Walkthrough | ~90 min | See your v1.0.0 release promoted to production |

---

## Observability Track (~4 hours 15 minutes)

See what is happening inside your platform and applications.

| Module | Title | Duration | Quick Win |
|--------|-------|----------|-----------|
| [Module 11](module-11-logging-lokistack.md) | Logging with LokiStack | ~60 min | Query your app's logs from the OCP console |
| [Module 12](module-12-monitoring-alerting.md) | Monitoring and Alerting | ~75 min | See request rate, error rate, and latency in real-time |
| [Module 13](module-13-grafana-dashboards.md) | Grafana Dashboards and Distributed Tracing | ~90 min | Single pane of glass: app health + pipeline status + traces across services |

---

## Advanced Track (~3 hours)

Performance gates and production-grade hardening.

| Module | Title | Duration | Quick Win |
|--------|-------|----------|-----------|
| [Module 14](module-14-performance-testing.md) | Performance Testing as Quality Gate | ~60 min | Pipeline fails because your app is too slow — fix it and pass |
| [Module 15](module-15-production-hardening.md) | Production Hardening | ~60 min | Kill a pod, watch PDB prevent disruption, ArgoCD auto-heals |

---

## Supply Chain & Multi-Language Track (~8 hours)

Extend the platform with Java microservices, SBOM generation, image signing, and cascading promotion across 5 services and 4 environments.

| Module | Title | Duration | Quick Win |
|--------|-------|----------|-----------|
| [Module 16](module-16-java-microservices.md) | Java Microservices on OpenShift | ~90 min | Java Spring Boot service running alongside .NET services with shared pipeline logic |
| [Module 16B](module-16b-distributed-tracing.md) | Distributed Tracing Deep Dive | ~75 min | Trace a request across 5 services in the Grafana waterfall view |
| [Module 17](module-17-sbom-trustify.md) | SBOM Generation & Vulnerability Analysis | ~75 min | See every dependency of your app cataloged in Trustify with CVE matching |
| [Module 18](module-18-image-signing-rhtas.md) | Image Signing & Verification with RHTAS | ~75 min | Cryptographically verify your image came from your pipeline — not tampered |
| [Module 19](module-19-promotion-pipeline.md) | The T4 Promotion Pipeline | ~75 min | Watch a release cascade from SIT to PROD automatically via MR approvals |
| [Module 20](module-20-e2e-five-services.md) | Full 5-Service E2E Walkthrough | ~90 min | All 5 services promoted to production with signed images and SBOMs |

---

## Delivery Formats

| Format | Duration | Best For |
|--------|----------|----------|
| Full workshop (instructor-led) | 4 days (8h/day) | Team enablement |
| Self-paced course | 4-5 weeks (1h/day) | Individual learning |
| Quick-start lab | 4h (M1+M3+M8+M10) | Fast hands-on intro |
| Security deep-dive | 1 day (M5+M6+M7+M14) | Security team focus |
| Supply chain security | 1 day (M17+M18+M19) | Supply chain & compliance |
| Observability bootcamp | 1 day (M11+M12+M13+M16B) | SRE/ops team focus |
| Multi-language + E2E | 1 day (M16+M19+M20) | Platform architects |

---

## Prerequisites

Before starting Module 1, ensure you have:

- [ ] Access to an OpenShift 4.x cluster (admin privileges)
- [ ] `oc` CLI installed (can be at a custom path like `~/Downloads/oc`)
- [ ] `helm`, `kustomize`, `jq`, `yq`, `roxctl`, `argocd`, `cosign` CLI tools (recommended: install to `~/bin/`)
- [ ] A terminal with `bash` or `zsh`
- [ ] Basic familiarity with Kubernetes concepts (pods, services, deployments)
- [ ] Basic familiarity with Git (clone, commit, push, branches)

### Environment Setup (Do This First)

Every module uses environment variables for cluster-specific values (URLs, namespaces, CLI paths). This makes the tutorial repeatable on any cluster.

1. Copy `env.sh` from the `docs/` directory to your project root (or use it in-place)
2. Edit the variables to match your cluster (API URL, apps domain, storage class)
3. Source it before running any commands:

```bash
source ./env.sh

# Verify it works
$OC whoami                    # Expected: admin (or your cluster-admin user)
echo $APPS_DOMAIN             # Expected: your cluster's apps domain
echo $NS_DEV                  # Expected: sampleapi-dev
```

> **Why `$OC` instead of `oc`?** The `oc` binary may be at a non-standard path (e.g., `~/Downloads/oc`). Using `$OC` ensures commands work regardless of where the binary is installed. All tutorial commands use `$OC` for consistency.

---

## Architecture Overview

```
Developer --> GitLab (MR) --> Jenkins (T1: scan) --> GitLab (status)
                |
         GitLab (merge) --> Jenkins (T2: build+deploy) --> Registry --> ArgoCD --> DEV
                |                                                        |
         GitLab (tag) --> Jenkins (T3: DAST+perf) --> Registry    (per-service apps)
                                                           |
                                    SIT <-- UAT <-- PROD (promotion via MR per service)
```

### Toolchain

| Tool | Purpose | Module |
|------|---------|--------|
| OpenShift 4.x | Container platform | M1 |
| Podman | Rootless container builds | M2 |
| Jenkins | CI pipeline orchestration | M3 |
| ArgoCD | GitOps deployment | M4 |
| SonarQube CE | SAST, code quality | M5 |
| ACS (StackRox) | Container security | M6 |
| OWASP ZAP | DAST scanning | M7 |
| Kustomize | Per-env configuration | M9 |
| LokiStack | Log aggregation | M11 |
| Prometheus | Metrics and alerting | M12 |
| Grafana | Dashboards | M13 |
| OpenTelemetry | Distributed tracing auto-instrumentation | M13, M16B |
| Tempo | Trace storage and query | M13, M16B |
| k6 | Performance testing | M14 |
| Spring Boot / Maven | Java microservice framework | M16 |
| CycloneDX | SBOM generation (.NET + Java) | M17 |
| Trustify (RHTPA) | SBOM management + vulnerability analysis | M17 |
| RHTAS (Cosign/Sigstore) | Image signing + verification | M18 |
