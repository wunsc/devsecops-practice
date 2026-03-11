# SonarQube CE — Post-Deployment Setup Guide

## Prerequisites
- Phase 1 (namespaces, RBAC) applied
- `devsecops-tools` namespace exists
- Privileged SCC granted for the init container (vm.max_map_count)

## Pre-Deployment: SCC Configuration

SonarQube's embedded Elasticsearch requires `vm.max_map_count >= 262144`. The init container
sets this via sysctl, which requires privileged access.

```bash
# Grant privileged SCC to the default SA in devsecops-tools
# (the init container runs as root to set sysctl values)
oc adm policy add-scc-to-user privileged -z default -n devsecops-tools
```

## Execution Order

```bash
# 1. Apply secrets first
oc apply -f infra/phase3/sonarqube-secrets.yaml

# 2. Deploy PostgreSQL and wait
oc apply -f infra/phase3/sonarqube-postgresql-statefulset.yaml
oc wait --for=condition=ready pod -l app=sonarqube-postgresql -n devsecops-tools --timeout=120s

# 3. Apply SonarQube config and deployment
oc apply -f infra/phase3/sonarqube-configmap.yaml
oc apply -f infra/phase3/sonarqube-deployment.yaml
oc apply -f infra/phase3/sonarqube-service.yaml
oc apply -f infra/phase3/sonarqube-route.yaml

# 4. Wait for SonarQube to become ready (can take 3-5 minutes)
oc wait --for=condition=ready pod -l app=sonarqube -n devsecops-tools --timeout=360s
```

## Post-Deploy Manual Steps

### 1. Access SonarQube
```bash
# Get the route URL
oc get route sonarqube -n devsecops-tools -o jsonpath='{.spec.host}'
# Open in browser: https://<route-host>
# Default login: admin / admin
```

### 2. Change Default Admin Password
- Login with `admin` / `admin`
- You will be prompted to change the password immediately
- Set a strong password and record it securely

### 3. Create Quality Gate
Navigate to: Quality Gates → Create

**Quality Gate: "DevSecOps Standard"**

| Metric | Operator | Value |
|--------|----------|-------|
| Bugs | is greater than | 0 |
| Vulnerabilities | is greater than | 0 |
| Code Coverage | is less than | 80.0% |
| Duplicated Lines (%) | is greater than | 3.0% |
| Security Hotspots Reviewed | is less than | 100% |
| Maintainability Rating | is worse than | A |
| Reliability Rating | is worse than | A |
| Security Rating | is worse than | A |

Set this gate as the **default** quality gate.

### 4. Create Projects
Create a project for each microservice:

**Project 1:**
- Navigate to: Projects → Create Project → Manually
- Project Key: `sampleapi`
- Display Name: `SampleApi`
- Main branch: `main`
- Select the "DevSecOps Standard" quality gate for this project

**Project 2:**
- Navigate to: Projects → Create Project → Manually
- Project Key: `notificationapi`
- Display Name: `NotificationApi`
- Main branch: `main`
- Select the "DevSecOps Standard" quality gate for this project

### 5. Generate Token for Jenkins
- Navigate to: My Account → Security → Generate Tokens
- Token name: `jenkins-integration`
- Type: `Global Analysis Token`
- Expires in: 365 days (or per policy)
- Click "Generate" and **copy the token immediately**

### 6. Store Token as OCP Secret
```bash
# Store the SonarQube token in devsecops-tools namespace for Jenkins
oc create secret generic sonarqube-token \
  --from-literal=token=<PASTE_TOKEN_HERE> \
  -n devsecops-tools

# Label it
oc label secret sonarqube-token team=devsecops component=jenkins -n devsecops-tools
```

## Verification

```bash
# Check pods are running
oc get pods -n devsecops-tools -l app=sonarqube
oc get pods -n devsecops-tools -l app=sonarqube-postgresql

# Check SonarQube system status via API
curl -s https://$(oc get route sonarqube -n devsecops-tools -o jsonpath='{.spec.host}')/api/system/status

# Expected response: {"id":"...","version":"26.3.0.120487","status":"UP"}

# Verify quality gate exists (after manual creation)
curl -s -u admin:<password> \
  https://$(oc get route sonarqube -n devsecops-tools -o jsonpath='{.spec.host}')/api/qualitygates/list
```

## Troubleshooting

### SonarQube pod stuck — Elasticsearch error
```bash
# Check if vm.max_map_count was set
oc logs deployment/sonarqube -n devsecops-tools -c sysctl-init

# If init container failed, verify SCC:
oc get scc privileged -o jsonpath='{.users}'
# Should contain: system:serviceaccount:devsecops-tools:default
```

### SonarQube CrashLoopBackOff — Database connection
```bash
# Verify PostgreSQL is ready
oc get pods -l app=sonarqube-postgresql -n devsecops-tools

# Check SonarQube logs for JDBC errors
oc logs deployment/sonarqube -n devsecops-tools -c sonarqube | grep -i jdbc

# Test PostgreSQL connectivity
oc rsh sonarqube-postgresql-0 -n devsecops-tools -- pg_isready -U sonarqube -d sonarqube
```

### Slow startup
SonarQube with Elasticsearch can take 3-5 minutes on first boot (index creation).
The startup probe allows up to 360 seconds. Check logs for progress:
```bash
oc logs -f deployment/sonarqube -n devsecops-tools -c sonarqube
```

## Architecture Notes
- SonarQube listens on port 9000
- TLS terminated at the OpenShift Route (edge termination)
- Embedded Elasticsearch stores search indices in `/opt/sonarqube/data`
- PostgreSQL stores all project analysis data, quality profiles, and quality gates
- The `sonar.properties` ConfigMap provides tuned JVM settings for constrained environments
- Jenkins pipelines use the `dotnet-sonarscanner` CLI (installed in the custom agent image) to submit analysis
- Quality gate results are polled via the SonarQube API in `scanSonarQube.groovy`
