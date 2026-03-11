# OpenShift GitOps (ArgoCD) — Post-Deployment Setup Guide

## Prerequisites
- Phase 1 (namespaces, RBAC with ArgoCD RoleBindings) applied
- Cluster has access to Red Hat operator catalog

## Execution Order

```bash
# 1. Install the OpenShift GitOps Operator
oc apply -f infra/phase6/gitops-operator-subscription.yaml

# 2. Wait for operator to be ready
echo "Waiting for OpenShift GitOps operator..."
while ! oc get csv -n openshift-gitops-operator 2>/dev/null | grep -q openshift-gitops; do
    sleep 10
    echo "  Still waiting..."
done
oc get csv -n openshift-gitops-operator | grep openshift-gitops
# Should show: openshift-gitops-operator.v1.x.x   Succeeded

# 3. Wait for default ArgoCD instance to be ready
oc wait --for=condition=ready pod -l app.kubernetes.io/name=openshift-gitops-server \
    -n openshift-gitops --timeout=300s

# 4. Label app namespaces so ArgoCD can manage them
# ArgoCD needs the argocd.argoproj.io/managed-by label to manage namespaces
oc label namespace sampleapi-dev argocd.argoproj.io/managed-by=openshift-gitops --overwrite
oc label namespace sampleapi-sit argocd.argoproj.io/managed-by=openshift-gitops --overwrite
oc label namespace sampleapi-uat argocd.argoproj.io/managed-by=openshift-gitops --overwrite
oc label namespace sampleapi-prod argocd.argoproj.io/managed-by=openshift-gitops --overwrite

# 5. Apply repo credentials (GitLab access for ArgoCD)
oc apply -f infra/phase6/argocd-repo-secret.yaml

# 6. Apply AppProject
oc apply -f infra/phase6/argocd-appproject.yaml

# 7. Apply Applications (do this AFTER the app-gitops repo is pushed to GitLab)
# There are 12 ArgoCD Application CRDs (4 per service + 4 infra):
#   sampleapi-{dev,sit,uat,prod}, notificationapi-{dev,sit,uat,prod}, infra-{dev,sit,uat,prod}
oc apply -f app-gitops/argocd/project.yaml
oc apply -f app-gitops/argocd/
```

## Post-Deploy Manual Steps

### 1. Get ArgoCD Admin Password
```bash
# Extract the auto-generated admin password
oc get secret openshift-gitops-cluster -n openshift-gitops \
    -o jsonpath='{.data.admin\.password}' | base64 -d
echo ""
```

### 2. Access ArgoCD UI
```bash
# Get the route URL
oc get route openshift-gitops-server -n openshift-gitops -o jsonpath='{.spec.host}'
# Open: https://<route-host>
# Login: admin / <password from step 1>
```

### 3. Store ArgoCD Password for Jenkins
```bash
# Jenkins needs ArgoCD credentials to trigger syncs in deployToEnvironment.groovy
# Use the admin password (NOT a JWT — JWT tokens expire after 24h)
ARGOCD_PASS=$(oc get secret openshift-gitops-cluster -n openshift-gitops \
    -o jsonpath='{.data.admin\.password}' | base64 -d)

oc create secret generic argocd-token \
    --from-literal=token="${ARGOCD_PASS}" \
    -n devsecops-tools
oc label secret argocd-token team=devsecops component=jenkins -n devsecops-tools
```

### 4. Verify Repo Connectivity
In ArgoCD UI:
- Navigate to: Settings → Repositories
- The `app-gitops` repo should show **Connected** status
- If not, check the credentials in `argocd-repo-secret.yaml`

### 5. Verify Applications
After applying the Application CRDs and pushing the app-gitops repo:
- DEV: Should auto-sync and show **Synced / Healthy**
- SIT/UAT/PROD: Should show **OutOfSync / Missing** (manual sync pending)

## Verification

```bash
# Check operator is installed
oc get csv -n openshift-gitops-operator | grep gitops

# Check ArgoCD pods are running
oc get pods -n openshift-gitops

# Check ArgoCD Applications status (12 total)
oc get applications -n openshift-gitops

# Expected (12 apps — 4 per service + 4 infra):
# NAME                    SYNC STATUS   HEALTH STATUS
# sampleapi-dev           Synced        Healthy
# sampleapi-sit           OutOfSync     Missing
# sampleapi-uat           OutOfSync     Missing
# sampleapi-prod          OutOfSync     Missing
# notificationapi-dev     Synced        Healthy
# notificationapi-sit     OutOfSync     Missing
# notificationapi-uat     OutOfSync     Missing
# notificationapi-prod    OutOfSync     Missing
# infra-dev               Synced        Healthy
# infra-sit               OutOfSync     Missing
# infra-uat               OutOfSync     Missing
# infra-prod              OutOfSync     Missing

# Check AppProject
oc get appproject devsecops -n openshift-gitops

# Verify namespace labels
oc get ns sampleapi-dev --show-labels | grep argocd

# Verify repo connection via argocd CLI (if installed)
argocd repo list --server $(oc get route openshift-gitops-server -n openshift-gitops -o jsonpath='{.spec.host}')
```

## Environment Promotion Flow

```
DEV (auto-sync)
  ↓ T2 pipeline updates services/{svc}/overlays/dev/kustomization.yaml image tag
  ↓ ArgoCD auto-syncs → app deployed to DEV

SIT (manual sync)
  ↓ T3 pipeline creates promotion MR: update services/{svc}/overlays/sit/kustomization.yaml
  ↓ Team Lead reviews and approves MR
  ↓ T4 pipeline auto-syncs in ArgoCD after merge:
    argocd app sync {svc}-sit

UAT (manual sync)
  ↓ T4 creates next promotion MR: update services/{svc}/overlays/uat/kustomization.yaml
  ↓ QA Lead approves + DAST re-run
  ↓ T4 auto-syncs after merge

PROD (manual sync)
  ↓ T4 creates next promotion MR: update services/{svc}/overlays/production/kustomization.yaml
  ↓ Change Advisory Board approves
  ↓ T4 auto-syncs after merge
```

## Troubleshooting

### Application stuck on "Unknown"
```bash
# Check ArgoCD application controller logs
oc logs deployment/openshift-gitops-application-controller -n openshift-gitops | tail -20

# Common cause: repo credentials incorrect or expired
oc get secret gitlab-repo-credentials -n openshift-gitops -o yaml
```

### "namespace not permitted" error
```bash
# Ensure the namespace has the managed-by label
oc get ns sampleapi-dev --show-labels | grep argocd

# If missing:
oc label namespace sampleapi-dev argocd.argoproj.io/managed-by=openshift-gitops
```

### Sync fails with permission denied
```bash
# Check ArgoCD SA has the right RoleBindings (Phase 1)
oc auth can-i create deployments \
    --as=system:serviceaccount:openshift-gitops:openshift-gitops-argocd-application-controller \
    -n sampleapi-dev
# Expected: yes
```

### ArgoCD JWT tokens expire after 24h
Never store ArgoCD JWT tokens in Jenkins credentials. Use the admin password
with `argocd login` instead. The Jenkins credential `argocd-token` should
contain the admin password, not a JWT.

### ArgoCD CLI connection from Jenkins agent
The external route with `--grpc-web` may time out. Use the internal service
endpoint instead:
```bash
argocd login openshift-gitops-server.openshift-gitops.svc:443 \
  --username admin --password "$ARGOCD_PASS" --insecure --grpc-web
```

## Architecture Notes
- OpenShift GitOps operator creates a default ArgoCD instance in `openshift-gitops`
- The AppProject `devsecops` restricts sources and destinations for security
- DEV is the only auto-sync environment; SIT/UAT/PROD require manual sync
- ArgoCD SA needs RoleBindings in each target namespace (created in Phase 1)
- Namespace label `argocd.argoproj.io/managed-by=openshift-gitops` is required
