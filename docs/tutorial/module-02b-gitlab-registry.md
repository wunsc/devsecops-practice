# Module 2B: GitLab & Registry Setup

| | |
|---|---|
| **Track** | Foundation |
| **Duration** | ~60 minutes |
| **Difficulty** | Beginner-Intermediate |
| **Prerequisites** | Module 1 complete (namespaces exist), Module 2 complete (understand container builds) |

---

## What You'll Learn

By the end of this module you will be able to:

1. Deploy GitLab CE on OpenShift with external PostgreSQL and Redis (stateful, persistent, production-like).
2. Explain why GitLab's bundled services are disabled and external ones used instead.
3. Create the five repositories that follow the separation-of-concerns architecture (one per service + shared build/GitOps/pipeline repos).
4. Generate a GitLab Personal Access Token and store it as an OpenShift Secret for Jenkins.
5. Configure the OCP internal registry so Jenkins can push images and all environments can pull them.
6. Verify the full GitLab + Registry setup end-to-end.

---

## Prerequisites

Before starting, confirm Module 1 is complete:

> **Environment variables:** Source the environment file before running any commands:
> ```bash
> source ./env.sh
> ```

```bash
# Namespaces exist
$OC get ns $NS_GITLAB
# Expected: Active

$OC get ns $NS_TOOLS
# Expected: Active

$OC get ns $NS_DEV
# Expected: Active
```

---

## Part 1: Concepts — Why We Need GitLab (10 min)

### The Problem

You need a place to store code, review changes via Merge Requests, trigger webhooks to Jenkins, and track pipeline status. GitHub/GitLab SaaS works for public projects, but in a DevSecOps lab:

- Your cluster is behind a firewall — SaaS webhooks cannot reach Jenkins
- You want full control over tokens, permissions, and audit logs
- You want to demonstrate the entire workflow self-contained on one cluster

So we deploy GitLab CE (Community Edition) directly on OpenShift.

### Why External PostgreSQL and Redis?

GitLab Omnibus bundles PostgreSQL and Redis inside the same container. That works on a VM, but on Kubernetes:

- **Pod restarts lose data** unless every internal service writes to a persistent volume. Bundled services use GitLab's internal paths, which are hard to map cleanly to PVCs.
- **Resource isolation** — if Redis eats too much memory inside the GitLab pod, the entire container gets OOM-killed, taking PostgreSQL and the web UI with it. Separate StatefulSets have separate resource limits.
- **Independent lifecycle** — you can restart GitLab without losing database connections, and you can back up PostgreSQL independently.

### Architecture

```
┌─────────────────────────────────────────────────────────┐
│                 devsecops-gitlab namespace                │
│                                                           │
│  ┌──────────────────────┐                                │
│  │  GitLab CE            │ port 8080 (Nginx)             │
│  │  (Deployment, 1 pod)  │ port 8181 (Puma, internal)    │
│  │                       │                                │
│  │  PVCs:                │                                │
│  │  - gitlab-data (50Gi) │ ← repos, uploads, artifacts  │
│  │  - gitlab-logs (5Gi)  │ ← log rotation               │
│  └───────┬───────┬───────┘                                │
│          │       │                                        │
│    ┌─────▼────┐  │  ┌──────────┐                         │
│    │PostgreSQL│  └──│  Redis   │                         │
│    │  15      │     │  7       │                         │
│    │(SSet)    │     │(SSet)    │                         │
│    │10Gi PVC  │     │5Gi PVC   │                         │
│    └──────────┘     └──────────┘                         │
│                                                           │
│  ┌──────────────────────────────┐                        │
│  │  Route (TLS edge)            │                        │
│  │  gitlab-devsecops-gitlab     │                        │
│  │  .apps.<cluster-domain>      │                        │
│  └──────────────────────────────┘                        │
└─────────────────────────────────────────────────────────┘
```

### The 5 Repositories

Our DevSecOps workflow separates concerns into five Git repositories:

| Repository | Contains | Does NOT Contain |
|------------|----------|------------------|
| `app-source` | SampleApi .NET source code, unit tests, `.dockerignore`, `.gitleaks.toml` | No Jenkinsfile, no Dockerfile |
| `notificationapi-source` | NotificationApi .NET source code, unit tests | No Jenkinsfile, no Dockerfile |
| `build-config` | Shared Dockerfile, `sonar-project.properties`, OWASP configs, k6 perf tests | No application code |
| `jenkins-shared-lib` | All pipeline logic in `vars/`, `src/`, `resources/` | No Jenkinsfiles (jobs use inline CPS) |
| `app-gitops` | Kustomize base + per-env overlays, ArgoCD Application CRDs | No application code, no CI scripts |

> **Why is the Dockerfile not in the app repo?** Because one Dockerfile serves multiple services. It accepts `--build-arg PROJECT_NAME` to build any .NET service. Keeping it separate means a Dockerfile fix does not require changes to every app repo.

> **Why are there no Jenkinsfiles?** Pipeline logic lives entirely in the shared library. Jenkins jobs use inline CPS scripts that call shared library orchestrators. This keeps application repos clean and pipeline logic in one versioned place.

---

## Part 2: Deploy GitLab CE (20 min)

### Step 1: Examine the Manifests

Before applying anything, let's understand what we're deploying. The manifests are in `infra/phase2/`:

```bash
ls infra/phase2/
```

Expected:
```
gitlab-configmap.yaml
gitlab-deployment.yaml
gitlab-postgresql-statefulset.yaml
gitlab-redis-statefulset.yaml
gitlab-route.yaml
gitlab-secrets.yaml
gitlab-service.yaml
```

Let's look at the key design decisions in each file:

**Secrets** (`gitlab-secrets.yaml`):
- Three separate secrets: `gitlab-db-credentials`, `gitlab-redis-credentials`, `gitlab-rails-secrets`
- The `gitlab-rails-secrets` includes three encryption keys (`DB_KEY_BASE`, `SECRET_KEY_BASE`, `OTP_KEY_BASE`) — these **must** remain constant across pod restarts. If they change, all encrypted data in the database (tokens, webhooks, 2FA secrets) becomes unreadable.

**ConfigMap** (`gitlab-configmap.yaml`):
- Contains `gitlab.rb` — the GitLab Omnibus configuration file
- Disables all bundled services (PostgreSQL, Redis, Prometheus, Registry)
- Sets Puma to port 8181 (avoids conflict with Nginx on 8080)
- Points `external_url` to the OpenShift Route hostname
- Reads credentials from environment variables (injected from Secrets)

**Deployment** (`gitlab-deployment.yaml`):
- `strategy: Recreate` — GitLab CE doesn't support rolling updates as a single instance
- Memory limits: 3Gi request, 8Gi limit (GitLab CE is memory-hungry)
- Startup probe with 180s initial delay and 60 retries — GitLab takes 3-5 minutes to start
- Probes use `curl localhost:8181` (not pod IP) because Nginx returns 404 on pod IP due to `server_name` mismatch

### Step 2: Apply the Secrets

Secrets must be applied first — the PostgreSQL and Redis StatefulSets reference them:

```bash
$OC apply -f infra/phase2/gitlab-secrets.yaml
```

Expected:
```
secret/gitlab-db-credentials created
secret/gitlab-redis-credentials created
secret/gitlab-rails-secrets created
```

### Verify

```bash
$OC get secrets -n $NS_GITLAB -l team=devsecops
```

Expected: three secrets listed.

### Step 3: Deploy PostgreSQL

```bash
$OC apply -f infra/phase2/gitlab-postgresql-statefulset.yaml
```

Wait for it to be ready:

```bash
$OC wait --for=condition=ready pod -l app=gitlab-postgresql -n $NS_GITLAB --timeout=180s
```

Expected:
```
pod/gitlab-postgresql-0 condition met
```

### Verify

```bash
$OC get pods -n $NS_GITLAB -l app=gitlab-postgresql
```

Expected:
```
NAME                    READY   STATUS    RESTARTS   AGE
gitlab-postgresql-0     1/1     Running   0          30s
```

### Step 4: Deploy Redis

```bash
$OC apply -f infra/phase2/gitlab-redis-statefulset.yaml
```

Wait:

```bash
$OC wait --for=condition=ready pod -l app=gitlab-redis -n $NS_GITLAB --timeout=120s
```

Expected:
```
pod/gitlab-redis-0 condition met
```

### Step 5: Deploy GitLab CE

Now deploy the ConfigMap, Deployment, Service, and Route:

```bash
$OC apply -f infra/phase2/gitlab-configmap.yaml
$OC apply -f infra/phase2/gitlab-deployment.yaml
$OC apply -f infra/phase2/gitlab-service.yaml
$OC apply -f infra/phase2/gitlab-route.yaml
```

GitLab takes 3-5 minutes to start. Watch the pod logs:

```bash
echo "Waiting for GitLab CE pod to be ready (this takes 3-5 minutes)..."
$OC wait --for=condition=ready pod -l app=gitlab-ce -n $NS_GITLAB --timeout=600s
```

> **If it takes longer than 5 minutes**, check the pod events:
> ```bash
> $OC describe pod -l app=gitlab-ce -n $NS_GITLAB | tail -20
> ```
> Common issues:
> - **OOM killed** (exit 137): The namespace quota may be too small. GitLab CE needs at least 8Gi memory limit.
> - **CrashLoopBackOff**: Check `$OC logs -l app=gitlab-ce -n $NS_GITLAB` for configuration errors in `gitlab.rb`.

### Verify GitLab Is Running

```bash
# All 3 pods should be Running
$OC get pods -n $NS_GITLAB
```

Expected (example from live cluster):
```
NAME                         READY   STATUS    RESTARTS   AGE
gitlab-ce-5c47cc87d9-78c8c   1/1     Running   3          3d
gitlab-postgresql-0          1/1     Running   4          4d
gitlab-redis-0               1/1     Running   4          4d
```

> **Note:** The `RESTARTS` and `AGE` columns will vary. What matters is that all three pods show `1/1 Running`. A few restarts over days of uptime is normal — GitLab CE is memory-hungry and may restart during resource pressure.

```bash
# Check the readiness endpoint
curl -sk ${GITLAB_URL}/-/readiness
```

Expected:
```json
{"status":"ok"}
```

```bash
# Check the route returns HTTP 200
echo "GitLab URL: ${GITLAB_URL}"
curl -sk -o /dev/null -w "HTTP %{http_code}\n" ${GITLAB_URL}/users/sign_in
```

Expected:
```
HTTP 200
```

Open `${GITLAB_URL}` in your browser. You should see the GitLab login page.

---

## Part 3: Create Repositories and API Token (15 min)

### Step 1: Get the Root Password

```bash
$OC get secret gitlab-rails-secrets -n $NS_GITLAB \
  -o jsonpath='{.data.GITLAB_ROOT_PASSWORD}' | base64 -d && echo
```

This prints the initial root password. Copy it.

### Step 2: Log In to GitLab

1. Open `${GITLAB_URL}` in your browser
2. Log in as `root` with the password from Step 1

### Step 3: Create the Group

1. Click **Groups** (left sidebar) > **New group**
2. Group name: `devsecops`
3. Visibility: **Internal** (visible to logged-in users)
4. Click **Create group**

### Step 4: Create the 5 Repositories

For each repository, from the `devsecops` group page:

1. Click **New project** > **Create blank project**
2. Enter the name, set visibility to **Internal**, uncheck "Initialize repository with a README"
3. Click **Create project**

Create these five projects in order (the project ID auto-increments):

| # | Project Name | Expected Project ID |
|---|-------------|-------------------|
| 1 | `app-source` | 1 |
| 2 | `build-config` | 2 |
| 3 | `jenkins-shared-lib` | 3 |
| 4 | `app-gitops` | 4 |
| 5 | `notificationapi-source` | 5 |

> **Why does the project ID matter?** The Jenkins webhook configuration and the `pipelinePromote.groovy` orchestrator reference projects by ID. If your IDs differ (e.g., you created a test project first), update the `GITLAB_PROJECT_*` variables in `env.sh`.

### Verify

```bash
GITLAB_TOKEN_TMP="<paste-your-root-password-or-PAT-here>"
curl -sk -H "PRIVATE-TOKEN: $GITLAB_TOKEN_TMP" \
  "${GITLAB_URL}/api/v4/groups/devsecops/projects" | jq '.[].path_with_namespace'
```

Expected:
```
"devsecops/notificationapi-source"
"devsecops/app-gitops"
"devsecops/jenkins-shared-lib"
"devsecops/build-config"
"devsecops/app-source"
```

Here is what the `devsecops` group looks like in GitLab with all five repositories created:

![GitLab devsecops group showing all 5 repositories](../screenshot/gitlab-devsecops-group-5-repos.png)

### Step 5: Create a Personal Access Token

Jenkins needs an API token to clone repos, post pipeline status on MRs, and create promotion MRs. Create one:

1. In GitLab, click your avatar (top-right) > **Edit profile**
2. Left sidebar > **Access Tokens**
3. Fill in:
   - **Token name:** `jenkins-ci`
   - **Expiration date:** Set to 1+ year from now
   - **Scopes:** Check **api**, **read_repository**, **write_repository**
4. Click **Create personal access token**
5. **Copy the token immediately** — you cannot see it again

### Step 6: Store the Token in OpenShift

Jenkins will read this token from an OpenShift Secret:

```bash
$OC create secret generic gitlab-token -n $NS_TOOLS \
  --from-literal=username=root \
  --from-literal=password=<PASTE-TOKEN-HERE> \
  --from-literal=token=<PASTE-TOKEN-HERE>
```

> **Why three keys?** Different Jenkins credential types need different keys. The `username`/`password` pair is used for Git clone (HTTP basic auth). The `token` key is used for GitLab API calls (HTTP header).

### Verify

```bash
# Token secret exists
$OC get secret gitlab-token -n $NS_TOOLS
```

Expected:
```
NAME           TYPE     DATA   AGE
gitlab-token   Opaque   3      10s
```

```bash
# Test the token against GitLab API
GITLAB_TOKEN=$($OC get secret gitlab-token -n $NS_TOOLS -o jsonpath='{.data.token}' | base64 -d)
curl -sk -H "PRIVATE-TOKEN: $GITLAB_TOKEN" "${GITLAB_URL}/api/v4/version" | jq .
```

Expected (version will match your deployed GitLab CE):
```json
{
  "version": "17.0.0",
  "revision": "abc1234f567"
}
```

---

## Part 4: Configure the OCP Internal Registry (10 min)

### Why the Internal Registry?

OpenShift ships with a built-in container image registry at `image-registry.openshift-image-registry.svc:5000`. Using it means:

- **No external registry account needed** — no Quay.io or Docker Hub credentials
- **Cross-namespace image pulling** — any namespace with the right RBAC can pull images
- **Integrated with OpenShift RBAC** — push/pull permissions use ServiceAccount tokens
- **ImageStreams** — OpenShift tracks image metadata (tags, SHA, creation time) in ImageStream objects

### How Image Flow Works

```
Jenkins agent pod (devsecops-tools)
  │
  │ podman push → image-registry.openshift-image-registry.svc:5000/sampleapi-dev/sampleapi:main-abc1234
  │
  ▼
┌─────────────────────────────────────────────┐
│  OCP Internal Registry                       │
│  sampleapi-dev/sampleapi:main-abc1234       │  ← Images stored in DEV namespace
│  sampleapi-dev/sampleapi:v1.2.0             │
│  sampleapi-dev/notificationapi:main-def5678 │
└──────────────┬──────────────────────────────┘
               │
     ┌─────────┼─────────┬─────────┐
     ▼         ▼         ▼         ▼
  DEV pod   SIT pod   UAT pod   PROD pod
  (same ns) (pulls    (pulls    (pulls
             cross-ns) cross-ns) cross-ns)
```

All images are pushed to the `sampleapi-dev` namespace. SIT, UAT, and PROD namespaces pull from DEV using cross-namespace RBAC.

### Step 1: Grant Jenkins Image Push Permission

Jenkins needs `system:image-pusher` role in the DEV namespace to push built images:

```bash
$OC apply -f infra/phase4/registry-sa-rolebinding.yaml
```

This creates three RoleBindings:
- `jenkins-sa` can push images to `sampleapi-dev` (built app images)
- `jenkins-sa` can pull images from `sampleapi-dev` (verify pushes)
- `jenkins-sa` can push images to `devsecops-tools` (build agent images)

### Step 2: Grant Cross-Namespace Pull Access

SIT, UAT, and PROD need to pull images from DEV:

```bash
$OC apply -f infra/phase4/registry-pull-secrets.yaml

# Also grant image-puller to each environment namespace
for NS in $NS_SIT $NS_UAT $NS_PROD; do
  $OC policy add-role-to-group system:image-puller \
    system:serviceaccounts:${NS} -n $NS_DEV
  echo "Granted image-puller to ${NS}"
done
```

### Step 3: Create ImageStreams

ImageStreams are OpenShift's way of tracking container images. Create one for each service:

```bash
$OC create imagestream sampleapi -n $NS_DEV 2>/dev/null || echo "ImageStream sampleapi already exists"
$OC create imagestream notificationapi -n $NS_DEV 2>/dev/null || echo "ImageStream notificationapi already exists"
```

### Verify

```bash
# Jenkins can push images
$OC auth can-i update imagestreams/layers -n $NS_DEV \
  --as=system:serviceaccount:${NS_TOOLS}:jenkins-sa
# Expected: yes

# SIT can pull images from DEV
$OC auth can-i get imagestreams/layers -n $NS_DEV \
  --as=system:serviceaccount:${NS_SIT}:default
# Expected: yes

# ImageStreams exist
$OC get is -n $NS_DEV
```

Expected:
```
NAME              IMAGE REPOSITORY                                                        TAGS   UPDATED
notificationapi   image-registry.openshift-image-registry.svc:5000/sampleapi-dev/...
sampleapi         image-registry.openshift-image-registry.svc:5000/sampleapi-dev/...
```

---

## Part 5: Push Code to GitLab (Optional — Done Later in Module 8)

At this point, the repositories are empty. You will push code to them after Module 3 (Jenkins), when all five repos have been generated and reviewed. The push commands are in the [E2E Walkthrough (Module 10)](module-10-e2e-walkthrough.md).

For now, just verify everything is ready:

```bash
echo "=== GitLab Health ==="
curl -sk ${GITLAB_URL}/-/readiness
# Expected: {"status":"ok"}

echo ""
echo "=== GitLab Pods ==="
$OC get pods -n $NS_GITLAB
# Expected (example from live cluster):
#   NAME                         READY   STATUS    RESTARTS   AGE
#   gitlab-ce-5c47cc87d9-78c8c   1/1     Running   3          3d
#   gitlab-postgresql-0          1/1     Running   4          4d
#   gitlab-redis-0               1/1     Running   4          4d

echo "=== Registry Permissions ==="
$OC auth can-i update imagestreams/layers -n $NS_DEV \
  --as=system:serviceaccount:${NS_TOOLS}:jenkins-sa
# Expected: yes

echo "=== GitLab Token ==="
$OC get secret gitlab-token -n $NS_TOOLS -o jsonpath='{.data.token}' | base64 -d | wc -c
# Expected: a positive number (length of the token)
```

All checks should pass. If any fail, review the steps above before proceeding.

---

## What Just Happened?

Let's connect what we built to the bigger picture:

1. **GitLab CE** is running with external PostgreSQL and Redis — each a separate StatefulSet with its own PVC, resource limits, and health checks. If GitLab needs a restart, the database stays up.

2. **Five empty repositories** exist in the `devsecops` group. These will hold:
   - Application code (2 repos: SampleApi, NotificationApi)
   - Build configuration (1 repo: shared Dockerfile + scan configs)
   - Pipeline logic (1 repo: Jenkins shared library)
   - Deployment manifests (1 repo: Kustomize overlays + ArgoCD apps)

3. **A Personal Access Token** is stored as an OpenShift Secret. Jenkins will use this to clone repos, report pipeline status on MRs, and create promotion MRs — all via the GitLab API.

4. **The OCP internal registry** is configured with RBAC: Jenkins can push images to the DEV namespace, and all environment namespaces can pull from DEV. No external registry credentials needed.

---

## Common Mistakes

| Mistake | Symptom | Fix |
|---------|---------|-----|
| GitLab pod OOM-killed | Pod shows `Reason: OOMKilled` or exit code 137 | Increase memory limit to at least 8Gi, increase namespace quota if needed |
| Encryption keys regenerated on restart | `OpenSSL::Cipher::CipherError` in GitLab logs, all tokens/webhooks broken | Ensure `DB_KEY_BASE`, `SECRET_KEY_BASE`, `OTP_KEY_BASE` are mounted from a persistent Secret, never auto-generated |
| Puma and Nginx port conflict | `Address already in use - bind(2)` in logs | Set `puma['port'] = 8181` in `gitlab.rb` (Nginx uses 8080) |
| Probes fail via pod IP | Pod shows `Readiness probe failed: HTTP 404` | Use `exec` probes with `curl localhost:8181` instead of `httpGet` on pod IP (Nginx/Puma check Host header against `external_url`) |
| Wrong project IDs | Jenkins webhooks trigger wrong jobs | Verify IDs with `curl -H "PRIVATE-TOKEN: ..." .../api/v4/projects | jq '.[].id'` and update `env.sh` |
| Token expired or wrong scopes | Jenkins clone fails with `401 Unauthorized` | Create new token with `api`, `read_repository`, `write_repository` scopes |

---

## Self-Assessment

- [ ] I can explain why GitLab's bundled PostgreSQL and Redis are disabled on Kubernetes
- [ ] I can list the five repositories and explain what each one contains
- [ ] I know where the GitLab root password is stored and how to retrieve it
- [ ] I can verify that Jenkins has image-push permission to the internal registry
- [ ] I understand why encryption keys must be persistent across pod restarts
- [ ] I know the image reference format: `image-registry...svc:5000/{namespace}/{image}:{tag}`

---

## Next Module Preview

**Module 3: Jenkins on OpenShift** — Now that GitLab is running and the registry is configured, you need a pipeline engine. Module 3 covers deploying Jenkins with a custom agent image packed with DevSecOps tools (dotnet SDK, Podman, roxctl, dotnet-sonarscanner, and more), configuring it entirely through code with JCasC, and wiring your first inline CPS job that calls a shared library — no Jenkinsfile in the app repo.

---

*Module 2B complete. Estimated time: 60 minutes.*
