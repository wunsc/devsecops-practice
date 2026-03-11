# GitLab CE — Post-Deployment Setup Guide

## Prerequisites
- Phase 1 (namespaces, RBAC) applied
- All Phase 2 manifests applied in order (see Execution Order below)
- GitLab pod is running and ready

## Execution Order

```bash
# 1. Apply secrets first (referenced by other resources)
oc apply -f infra/phase2/gitlab-secrets.yaml

# 2. Deploy PostgreSQL and wait for it to be ready
oc apply -f infra/phase2/gitlab-postgresql-statefulset.yaml
oc wait --for=condition=ready pod -l app=gitlab-postgresql -n devsecops-gitlab --timeout=120s

# 3. Deploy Redis and wait for it to be ready
oc apply -f infra/phase2/gitlab-redis-statefulset.yaml
oc wait --for=condition=ready pod -l app=gitlab-redis -n devsecops-gitlab --timeout=120s

# 4. Apply GitLab config and deployment
oc apply -f infra/phase2/gitlab-configmap.yaml
oc apply -f infra/phase2/gitlab-deployment.yaml
oc apply -f infra/phase2/gitlab-service.yaml
oc apply -f infra/phase2/gitlab-route.yaml

# 5. Wait for GitLab to become ready (can take 5-10 minutes)
oc wait --for=condition=ready pod -l app=gitlab-ce -n devsecops-gitlab --timeout=600s
```

## Post-Deploy Manual Steps

### 1. Access GitLab
```bash
# Get the route URL
oc get route gitlab -n devsecops-gitlab -o jsonpath='{.spec.host}'
# Open in browser: https://<route-host>
# Login: root / <GITLAB_ROOT_PASSWORD from gitlab-rails-secrets>
```

### 2. Create Group
- Navigate to: Menu → Groups → New group
- Group name: `devsecops`
- Visibility: Internal
- Click "Create group"

### 3. Create 5 Repositories
Create the following projects under the `devsecops` group:

| Repository | Project ID | Description | Visibility |
|------------|------------|-------------|------------|
| `app-source` | 1 | SampleApi application source code (.NET Core Web API) | Internal |
| `build-config` | 2 | Build configurations (Dockerfile, sonar config, scan configs) | Internal |
| `jenkins-shared-lib` | 3 | Jenkins shared library (all pipeline logic) | Internal |
| `app-gitops` | 4 | GitOps deployment manifests (Kustomize overlays, ArgoCD apps) | Internal |
| `notificationapi-source` | 5 | NotificationApi microservice source code (.NET 8.0) | Internal |

### 4. Create Personal Access Token for Jenkins
- Navigate to: root user icon → Preferences → Access Tokens
- Token name: `jenkins-integration`
- Expiration: Set appropriately (e.g., 1 year)
- Scopes: `api`, `read_repository`, `write_repository`
- Click "Create personal access token"
- **Copy the token immediately** — it won't be shown again

### 5. Store Token as OCP Secret
```bash
# Store the GitLab token in devsecops-tools namespace for Jenkins
oc create secret generic gitlab-token \
  --from-literal=token=<PASTE_TOKEN_HERE> \
  -n devsecops-tools

# Label it for identification
oc label secret gitlab-token team=devsecops component=jenkins -n devsecops-tools
```

## Verification

```bash
# Check all pods are running
oc get pods -n devsecops-gitlab

# Expected output:
# NAME                         READY   STATUS    RESTARTS   AGE
# gitlab-ce-xxxxx              1/1     Running   0          5m
# gitlab-postgresql-0          1/1     Running   0          10m
# gitlab-redis-0               1/1     Running   0          8m

# Check route is accessible
curl -sI https://$(oc get route gitlab -n devsecops-gitlab -o jsonpath='{.spec.host}')/-/readiness

# Check PostgreSQL connectivity from GitLab
oc rsh -n devsecops-gitlab deployment/gitlab-ce gitlab-rake gitlab:check

# Check GitLab version
oc rsh -n devsecops-gitlab deployment/gitlab-ce cat /opt/gitlab/version-manifest.txt | head -1
```

## Troubleshooting

### GitLab pod stuck in CrashLoopBackOff
```bash
# Check logs for errors
oc logs -f deployment/gitlab-ce -n devsecops-gitlab

# Common issues:
# 1. PostgreSQL not ready — check pg pod status
# 2. Redis not reachable — check redis pod status
# 3. Puma port conflict — verify puma['port'] = 8181 in gitlab.rb
# 4. Memory OOM — check resource limits
```

### OpenSSL::Cipher::CipherError after pod restart
This means the encryption keys changed. The `db_key_base`, `secret_key_base`, and
`otp_key_base` values in `gitlab-rails-secrets` MUST remain constant. If they were
regenerated, all encrypted data (tokens, webhooks, 2FA) is permanently lost and
must be re-created.

### Database connection refused
```bash
# Verify PostgreSQL is accepting connections
oc rsh gitlab-postgresql-0 -n devsecops-gitlab -- pg_isready -U gitlab -d gitlabhq_production

# Check PostgreSQL logs
oc logs gitlab-postgresql-0 -n devsecops-gitlab
```

### Redis authentication failed
```bash
# Verify Redis password matches between secret and gitlab.rb
oc get secret gitlab-redis-credentials -n devsecops-gitlab -o jsonpath='{.data.REDIS_PASSWORD}' | base64 -d

# Test Redis connectivity
oc rsh gitlab-redis-0 -n devsecops-gitlab -- redis-cli -a <password> ping
```

## Architecture Notes
- GitLab Nginx listens on port 8080 (Route → Service → 8080)
- Puma (app server) listens on port 8181 (Nginx reverse-proxies to it)
- TLS terminated at the OpenShift Route (edge termination)
- PostgreSQL and Redis are separate StatefulSets with their own PVCs
- All encryption keys are stored in Kubernetes Secrets and injected via env vars
