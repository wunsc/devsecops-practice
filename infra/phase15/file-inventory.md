# DevSecOps Project — Complete File Inventory

## Summary

| Repository / Directory | File Count | Purpose |
|----------------------|-----------|---------|
| `infra/phase1/` | 11 | Namespaces, RBAC, NetworkPolicies, Quotas |
| `infra/phase2/` | 8 | GitLab CE deployment |
| `infra/phase3/` | 7 | SonarQube CE deployment |
| `infra/phase4/` | 3 | Registry configuration |
| `infra/phase5/` | 9 | Red Hat ACS (StackRox) |
| `infra/phase6/` | 8 | OpenShift GitOps (ArgoCD) |
| `infra/phase7/` | 8 | Jenkins + custom agent |
| `infra/phase8/` | — | (Files in jenkins-shared-lib/) |
| `infra/phase12/` | 3 | Jenkins jobs + webhooks |
| `infra/phase13/` | 12 | Security policies + quality gates |
| `infra/phase14/` | 8 | Monitoring, logging, alerting |
| `infra/phase15/` | 3 | Execution runbook + validation |
| `jenkins-shared-lib/` | 24 | Pipeline logic (shared library) |
| `build-config/` | 5 | Dockerfile, scan configs |
| `app-source/` | 15 | .NET 8.0 Web API |
| `app-gitops/` | 29 | Kustomize overlays + ArgoCD apps |
| **TOTAL** | **~153** | |

## Infrastructure Files (infra/)

### Phase 1: Infrastructure Foundation
```
infra/phase1/
├── namespaces.yaml
├── serviceaccounts.yaml
├── clusterroles.yaml
├── rolebindings.yaml
├── networkpolicies/
│   ├── default-deny.yaml
│   ├── allow-ingress-router.yaml
│   ├── allow-monitoring.yaml
│   ├── tools-egress.yaml
│   └── app-namespace.yaml
├── resourcequotas.yaml
└── limitranges.yaml
```

### Phase 2: GitLab CE
```
infra/phase2/
├── gitlab-secrets.yaml
├── gitlab-postgresql-statefulset.yaml
├── gitlab-redis-statefulset.yaml
├── gitlab-configmap.yaml
├── gitlab-deployment.yaml
├── gitlab-service.yaml
├── gitlab-route.yaml
└── README-gitlab-setup.md
```

### Phase 3: SonarQube CE
```
infra/phase3/
├── sonarqube-secrets.yaml
├── sonarqube-postgresql-statefulset.yaml
├── sonarqube-configmap.yaml
├── sonarqube-deployment.yaml
├── sonarqube-service.yaml
├── sonarqube-route.yaml
└── README-sonarqube-setup.md
```

### Phase 4: Registry
```
infra/phase4/
├── registry-pull-secrets.yaml
├── registry-sa-rolebinding.yaml
└── README-registry-setup.md
```

### Phase 5: ACS (StackRox)
```
infra/phase5/
├── acs-operator-subscription.yaml
├── acs-central.yaml
├── acs-secured-cluster.yaml
├── acs-init-bundle-generate.sh
├── acs-policies/
│   ├── block-critical-cves.yaml
│   ├── block-root-images.yaml
│   ├── block-untrusted-registries.yaml
│   └── detect-runtime-threats.yaml
└── README-acs-setup.md
```

### Phase 6: ArgoCD
```
infra/phase6/
├── gitops-operator-subscription.yaml
├── argocd-repo-secret.yaml
├── argocd-appproject.yaml
├── argocd-app-dev.yaml
├── argocd-app-sit.yaml
├── argocd-app-uat.yaml
├── argocd-app-prod.yaml
└── README-argocd-setup.md
```

### Phase 7: Jenkins
```
infra/phase7/
├── Dockerfile.agent
├── agent-buildconfig.yaml
├── jenkins-deployment.yaml
├── jenkins-sa-clusterrolebinding.yaml
├── jenkins-casc-configmap.yaml
├── jenkins-credentials-secrets.yaml
├── jenkins-agent-pod-template.yaml
└── README-jenkins-setup.md
```

### Phase 12: Webhooks
```
infra/phase12/
├── jenkins-casc-jobs.yaml
├── setup-webhooks.sh
└── README-webhook-verification.md
```

### Phase 13: Security Policies
```
infra/phase13/
├── sonarqube-quality-gate.json
├── acs-policies/
│   ├── build-block-critical-cves.json
│   ├── deploy-block-root-user.json
│   ├── deploy-block-untrusted-reg.json
│   ├── deploy-require-signature.json
│   ├── runtime-detect-cryptominer.json
│   └── runtime-detect-reverse-shell.json
├── gitleaks-config.toml
├── pre-commit-config.yaml
├── apply-sonarqube-gate.sh
├── apply-acs-policies.sh
└── README-security-gates.md
```

### Phase 14: Monitoring
```
infra/phase14/
├── user-workload-monitoring.yaml
├── servicemonitor-app.yaml
├── prometheus-rules.yaml
├── grafana-dashboard-app.json
├── grafana-dashboard-pipeline.json
├── cluster-log-forwarder.yaml
├── acs-notifier-config.yaml
└── README-monitoring-setup.md
```

### Phase 15: Execution Runbook
```
infra/phase15/
├── EXECUTION-RUNBOOK.md
├── validate-all.sh
└── file-inventory.md
```

## Application Repositories

### jenkins-shared-lib/ (Phase 8)
```
jenkins-shared-lib/
├── vars/
│   ├── checkoutSource.groovy
│   ├── checkoutBuildConfig.groovy
│   ├── buildDotnet.groovy
│   ├── runUnitTests.groovy
│   ├── scanSonarQube.groovy
│   ├── scanDependencyCheck.groovy
│   ├── buildContainerImage.groovy
│   ├── scanACSImage.groovy
│   ├── scanOWASPZAP.groovy
│   ├── pushToRegistry.groovy
│   ├── signImage.groovy
│   ├── updateGitOps.groovy
│   ├── deployToEnvironment.groovy
│   ├── reportToGitLab.groovy
│   ├── notifyTeam.groovy
│   ├── pipelineMR.groovy
│   ├── pipelineMerge.groovy
│   └── pipelineTag.groovy
├── src/com/devsecops/
│   ├── PipelineConfig.groovy
│   ├── SecurityGate.groovy
│   └── ImageTagger.groovy
├── resources/com/devsecops/
│   └── notification-template.html
├── README.md
└── CHANGELOG.md
```

### build-config/ (Phase 9)
```
build-config/
├── Dockerfile
├── sonar-project.properties
├── dependency-check-suppression.xml
├── zap-scan-config.yaml
└── README.md
```

### app-source/ (Phase 10)
```
app-source/
├── SampleApi.sln
├── src/SampleApi/
│   ├── SampleApi.csproj
│   ├── Program.cs
│   ├── Controllers/
│   │   ├── WeatherForecastController.cs
│   │   └── HealthController.cs
│   ├── Models/
│   │   └── WeatherForecastOptions.cs
│   ├── appsettings.json
│   ├── appsettings.Development.json
│   └── appsettings.Production.json
├── tests/SampleApi.Tests/
│   ├── SampleApi.Tests.csproj
│   └── WeatherForecastControllerTests.cs
├── .dockerignore
├── .gitleaks.toml
├── .pre-commit-config.yaml
└── README.md
```

### app-gitops/ (Phase 11)
```
app-gitops/
├── base/
│   ├── kustomization.yaml
│   ├── deployment.yaml
│   ├── service.yaml
│   ├── route.yaml
│   ├── configmap-app.yaml
│   └── serviceaccount.yaml
├── overlays/
│   ├── dev/
│   │   ├── kustomization.yaml
│   │   ├── configmap-env.yaml
│   │   ├── secret-env.yaml
│   │   └── patch-deployment.yaml
│   ├── sit/
│   │   ├── kustomization.yaml
│   │   ├── configmap-env.yaml
│   │   ├── secret-env.yaml
│   │   └── patch-deployment.yaml
│   ├── uat/
│   │   ├── kustomization.yaml
│   │   ├── configmap-env.yaml
│   │   ├── secret-env.yaml
│   │   └── patch-deployment.yaml
│   └── production/
│       ├── kustomization.yaml
│       ├── configmap-env.yaml
│       ├── secret-env.yaml
│       ├── patch-deployment.yaml
│       └── poddisruptionbudget.yaml
├── argocd/
│   ├── project.yaml
│   ├── app-dev.yaml
│   ├── app-sit.yaml
│   ├── app-uat.yaml
│   └── app-prod.yaml
└── README.md
```
