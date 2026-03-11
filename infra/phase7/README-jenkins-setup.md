# Jenkins Deployment — Post-Deployment Setup Guide

## Prerequisites
- Phase 1 (namespaces, RBAC, ServiceAccounts) applied
- Phase 2 (GitLab) deployed and accessible
- Phase 3 (SonarQube) deployed and accessible
- Phase 5 (ACS) deployed with API token generated
- Phase 6 (ArgoCD) deployed with admin password available

## Execution Order

### Step 1: Build the Custom Agent Image
```bash
# Option A: Build directly with oc (recommended)
oc new-build --strategy=docker \
    --dockerfile="$(cat infra/phase7/Dockerfile.agent)" \
    --name=jenkins-agent-devsecops \
    -n devsecops-tools

# Wait for build to complete
oc logs -f bc/jenkins-agent-devsecops -n devsecops-tools

# Option B: Apply the BuildConfig and start manually
oc apply -f infra/phase7/agent-buildconfig.yaml
oc start-build jenkins-agent-devsecops \
    --from-file=infra/phase7/Dockerfile.agent \
    -n devsecops-tools --follow

# Verify image is in the registry
oc get istag jenkins-agent-devsecops:latest -n devsecops-tools
```

### Step 2: Apply Jenkins SA Permissions
```bash
oc apply -f infra/phase7/jenkins-sa-clusterrolebinding.yaml
```

### Step 3: Apply Credential Secrets
```bash
# IMPORTANT: Edit the file first — replace all CHANGE_ME values
oc apply -f infra/phase7/jenkins-credentials-secrets.yaml
```

### Step 4: Apply Jenkins Deployment
```bash
# Apply JCasC ConfigMap first (referenced by deployment)
oc apply -f infra/phase7/jenkins-casc-configmap.yaml

# Apply agent pod template ConfigMap
oc apply -f infra/phase7/jenkins-agent-pod-template.yaml

# Deploy Jenkins
oc apply -f infra/phase7/jenkins-deployment.yaml

# Wait for Jenkins to be ready
oc wait --for=condition=ready pod -l app=jenkins -n devsecops-tools --timeout=600s
```

### Step 5: Configure Credentials via Script Console
JCasC credential definitions cause circular resolution issues when credential IDs
match `casc_secrets/` filenames. Configure credentials via the Script Console instead.

```bash
# Access Jenkins
JENKINS_URL="https://$(oc get route jenkins -n devsecops-tools -o jsonpath='{.spec.host}')"
echo "Jenkins URL: ${JENKINS_URL}"
```

Open `${JENKINS_URL}/script` and run each of these:

```groovy
// --- GitLab credentials (usernamePassword) ---
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl
import com.cloudbees.plugins.credentials.CredentialsScope
import com.cloudbees.plugins.credentials.SystemCredentialsProvider

def store = SystemCredentialsProvider.instance.store
def cred = new UsernamePasswordCredentialsImpl(
    CredentialsScope.GLOBAL, "gitlab-token", "GitLab credentials",
    "root", "<PASTE_GITLAB_TOKEN>"
)
store.addCredentials(com.cloudbees.plugins.credentials.domains.Domain.global(), cred)
println "Created: gitlab-token"
```

```groovy
// --- GitLab API token (string) ---
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl
import hudson.util.Secret
import com.cloudbees.plugins.credentials.CredentialsScope
import com.cloudbees.plugins.credentials.SystemCredentialsProvider

def store = SystemCredentialsProvider.instance.store
def cred = new StringCredentialsImpl(
    CredentialsScope.GLOBAL, "gitlab-api-token", "GitLab API token for MR reporting",
    Secret.fromString("<PASTE_GITLAB_API_TOKEN>")
)
store.addCredentials(com.cloudbees.plugins.credentials.domains.Domain.global(), cred)
println "Created: gitlab-api-token"
```

```groovy
// --- SonarQube token (string) ---
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl
import hudson.util.Secret
import com.cloudbees.plugins.credentials.CredentialsScope
import com.cloudbees.plugins.credentials.SystemCredentialsProvider

def store = SystemCredentialsProvider.instance.store
def cred = new StringCredentialsImpl(
    CredentialsScope.GLOBAL, "sonarqube-token", "SonarQube analysis token",
    Secret.fromString("<PASTE_SONARQUBE_TOKEN>")
)
store.addCredentials(com.cloudbees.plugins.credentials.domains.Domain.global(), cred)
println "Created: sonarqube-token"
```

```groovy
// --- ACS token (string) ---
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl
import hudson.util.Secret
import com.cloudbees.plugins.credentials.CredentialsScope
import com.cloudbees.plugins.credentials.SystemCredentialsProvider

def store = SystemCredentialsProvider.instance.store
def cred = new StringCredentialsImpl(
    CredentialsScope.GLOBAL, "acs-token", "ACS API token for roxctl",
    Secret.fromString("<PASTE_ACS_TOKEN>")
)
store.addCredentials(com.cloudbees.plugins.credentials.domains.Domain.global(), cred)
println "Created: acs-token"
```

```groovy
// --- ArgoCD password (string — NOT JWT, JWTs expire after 24h) ---
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl
import hudson.util.Secret
import com.cloudbees.plugins.credentials.CredentialsScope
import com.cloudbees.plugins.credentials.SystemCredentialsProvider

def store = SystemCredentialsProvider.instance.store
def cred = new StringCredentialsImpl(
    CredentialsScope.GLOBAL, "argocd-token", "ArgoCD admin password",
    Secret.fromString("<PASTE_ARGOCD_ADMIN_PASSWORD>")
)
store.addCredentials(com.cloudbees.plugins.credentials.domains.Domain.global(), cred)
println "Created: argocd-token"
```

## Verification

```bash
# Check Jenkins pod is running
oc get pods -l app=jenkins -n devsecops-tools

# Check route
oc get route jenkins -n devsecops-tools

# Check JCasC was applied (7 jobs should exist)
curl -sk -u admin:<password> \
    "https://$(oc get route jenkins -n devsecops-tools -o jsonpath='{.spec.host}')/api/json?tree=jobs[name]"
# Expected: sampleapi-mr, sampleapi-merge, sampleapi-tag,
#           notificationapi-mr, notificationapi-merge, notificationapi-tag,
#           sampleapi-promote

# Test agent pod spin-up (run a simple build)
# In Jenkins UI: sampleapi-merge → Build Now
# Check that an agent pod is created:
oc get pods -l app=jenkins-agent -n devsecops-tools --watch

# Verify credentials exist
curl -sk -u admin:<password> \
    "https://$(oc get route jenkins -n devsecops-tools -o jsonpath='{.spec.host}')/credentials/store/system/domain/_/api/json"
```

## Troubleshooting

### Agent pod fails to start
```bash
# Check pod events
oc get events -n devsecops-tools --sort-by='.lastTimestamp' | grep agent

# Common issues:
# 1. Image pull error — verify agent image exists
oc get istag jenkins-agent-devsecops:latest -n devsecops-tools

# 2. SCC denied — verify privileged binding
oc auth can-i use scc/privileged --as=system:serviceaccount:devsecops-tools:jenkins-sa

# 3. Resource quota exceeded — check quota
oc describe quota -n devsecops-tools
```

### JCasC not applied
```bash
# Check if ConfigMap is mounted
oc exec deployment/jenkins -n devsecops-tools -- ls /var/jenkins_home/casc_configs/

# Reload JCasC manually via API
curl -sk -u admin:<password> -X POST \
    "https://$(oc get route jenkins -n devsecops-tools -o jsonpath='{.spec.host}')/configuration-as-code/reload"
```

### GitLab webhooks return 403
Anonymous users need `Overall/Read`, `Job/Read`, and `Job/Build` permissions.
These are configured in JCasC authorizationStrategy. If still failing:
```bash
# Check CSRF crumb issuer is configured to exclude session ID
oc get deployment jenkins -n devsecops-tools -o jsonpath='{.spec.template.spec.containers[0].env}' | grep csrf
```

### Podman build fails in agent
```bash
# Verify storage driver is vfs
oc exec <agent-pod> -n devsecops-tools -- podman info | grep graphDriverName
# Should output: vfs

# Verify privileged mode
oc exec <agent-pod> -n devsecops-tools -- cat /proc/self/status | grep CapEff
```

## Architecture Notes
- Jenkins controller runs on `jenkins/jenkins:lts-jdk21` (UID 1000, home `/var/jenkins_home`)
- Zero executors on controller — all builds run on ephemeral agent pods
- Agent image contains all tools: .NET SDK 8.0, Podman, roxctl, dotnet-sonarscanner, dependency-check v12.1.0, gitleaks, cosign, kustomize, oc, argocd, k6 v0.54.0
- Podman requires `--storage-driver=vfs` and `--isolation=chroot` on OpenShift
- Plugins incompatible with 2.541.x: `publishHTML`, `generic-webhook-trigger`
- 7 jobs defined via JCasC Job DSL: sampleapi-mr, sampleapi-merge, sampleapi-tag, notificationapi-mr, notificationapi-merge, notificationapi-tag, sampleapi-promote
- Credentials managed via Script Console (not JCasC) to avoid circular resolution bugs
