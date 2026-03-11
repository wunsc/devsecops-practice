# Module 15: Production Hardening

**Track:** Advanced | **Duration:** ~75 minutes | **Prerequisites:** Modules 1--14 completed, working DevSecOps pipeline with DEV/SIT/UAT/PROD environments deployed via ArgoCD

---

## What You'll Learn

By the end of this module, you will be able to:

- Configure PodDisruptionBudgets (PDBs) that protect availability during voluntary disruptions
- Apply anti-affinity rules that spread pods across failure domains
- Layer network policies that enforce zero-trust in production
- Run compliance scans with Red Hat ACS to validate security posture
- Execute rolling updates with zero downtime and roll back when things go wrong
- Articulate why production is not "DEV with more replicas" -- it is a fundamentally different operational posture

---

## Prerequisites

Before starting this module, confirm:

> **Environment variables:** Before running any commands, source the environment file:
> ```bash
> source ./env.sh
> ```
> This sets `$OC`, `$APPS_DOMAIN`, `$NS_TOOLS`, and all other cluster-specific variables used throughout this module. See `env.sh` for the full variable list.

```bash
# You are logged in with cluster-admin
$OC whoami
# --> admin

# Log in to ArgoCD (all argocd commands in this module need this)
ARGOCD_PASS=$($OC get secret openshift-gitops-cluster -n $NS_GITOPS \
  -o jsonpath='{.data.admin\.password}' | base64 -d)
argocd login openshift-gitops-server.openshift-gitops.svc:443 \
  --username admin --password "$ARGOCD_PASS" --insecure --grpc-web
# --> 'admin:login' logged in successfully

# PROD namespace exists with running pods
$OC get pods -n $NS_PROD
# --> Expected:
# NAME                               READY   STATUS      RESTARTS   AGE
# notificationapi-849fbbfd-pkmcb     1/1     Running     1          26h
# notificationapi-849fbbfd-pww2l     1/1     Running     1          28h
# postgresql-0                       1/1     Running     2          47h
# postgresql-backup-29551800-sq2gz   0/1     Completed   0          3h
# redis-0                            1/1     Running     2          47h
# sampleapi-5c9cbb84d5-n945l         1/1     Running     1          28h
# sampleapi-5c9cbb84d5-sk824         1/1     Running     1          28h
# sampleapi-5c9cbb84d5-x5mhn         1/1     Running     1          26h

# ArgoCD manages PROD
argocd app get sampleapi-prod
# --> Status: Synced, Health: Healthy

# ACS Central is reachable (roxctl uses ROX_API_TOKEN env var, not a CLI flag)
export ROX_API_TOKEN=$($OC get secret acs-token -n $NS_TOOLS -o jsonpath='{.data.token}' | base64 -d)
roxctl -e "$ACS_URL:443" --insecure-skip-tls-verify central whoami
# --> Authenticated response
```

If any of these fail, go back to the relevant module and resolve the issue before continuing.

---

## Concepts: The Production Mindset

Before touching a single YAML file, you need to understand WHY production hardening matters. The rest of this module is mechanics. This section is the thinking.

### Production Is Not DEV With More Replicas

Compare what we currently have across environments:

| Dimension | DEV | PROD |
|-----------|-----|------|
| Replicas | 1 | 3 |
| CPU request / limit | 100m / 500m | 500m / 2000m |
| Memory request / limit | 128Mi / 512Mi | 512Mi / 2Gi |
| Logging level | Debug | Warning |
| Swagger UI | Enabled | Disabled |
| ArgoCD sync | Automated | Manual (MR approval) |
| PodDisruptionBudget | None | minAvailable: 1 |
| Anti-affinity | None | Spread across nodes |
| Namespace quota | 6 CPU / 12Gi | 12 CPU / 24Gi |

The numbers are different, yes. But the real difference is **operational posture**:

- **DEV tolerates downtime.** If a pod dies, nobody pages anyone. You redeploy and move on.
- **PROD tolerates nothing.** Every disruption is a potential incident. Every configuration exists to prevent, detect, or recover from failure.

This distinction drives every decision in this module.

### Defense in Depth for Availability

Production hardening is layered, just like security:

```
Layer 1: PodDisruptionBudget    --> "You cannot take down too many pods at once"
Layer 2: Anti-affinity           --> "My pods are spread across failure domains"
Layer 3: Rolling update strategy --> "New versions deploy without dropping connections"
Layer 4: Network policies        --> "Only authorized traffic reaches my pods"
Layer 5: Compliance scanning     --> "I can prove my posture is correct at any point"
Layer 6: Rollback capability     --> "If something goes wrong, I revert in seconds"
```

Each layer compensates for failures in the layers above and below it. No single layer is sufficient on its own. Together, they make production resilient.

---

## Step 1: Configure PodDisruptionBudgets

**WHY:** Kubernetes will happily evict every one of your pods simultaneously during a node drain. A PDB tells the cluster: "You may evict pods, but you must keep at least N running at all times." Without a PDB, a routine cluster upgrade can cause a full outage.

### 1.1 Tell -- What a PDB Does

A PDB constrains **voluntary disruptions** only:

| Disruption Type | PDB Protects? | Example |
|----------------|---------------|---------|
| Voluntary | Yes | `oc adm drain`, cluster upgrade, `oc delete pod` with eviction API |
| Involuntary | No | Node crash, OOM kill, kernel panic |

The key parameter is `minAvailable` (or its inverse, `maxUnavailable`). With 3 replicas and `minAvailable: 2`, the cluster can evict at most 1 pod at a time. It must wait for a replacement to become Ready before evicting the next.

### 1.2 Show -- The Production PDB

Here is the PDB already defined in your production overlay. Open it and study it:

```bash
cat app-gitops/services/sampleapi/overlays/production/pdb.yaml
```

```yaml
# app-gitops/services/sampleapi/overlays/production/pdb.yaml
# PDB ensures at least 1 pod is always available during voluntary disruptions
# (node drain, rolling updates, cluster upgrades)
---
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: sampleapi-pdb
  labels:
    app: sampleapi
spec:
  minAvailable: 1            # <-- At least 1 pod must be running at all times
  selector:
    matchLabels:
      app: sampleapi         # <-- Must match the pod labels in your Deployment
```

> **CALLOUT -- Why `minAvailable: 1` and not `minAvailable: 2`?**
>
> With 3 replicas and `minAvailable: 2`, only 1 pod can be disrupted at a time.
> That is safe but slow during rolling updates: each pod must fully start before
> the next one is evicted. With `minAvailable: 1`, up to 2 pods can be evicted
> simultaneously, making upgrades faster while still guaranteeing availability.
> Choose based on your SLA. For a stricter SLA, use `minAvailable: 2`.

The PDB is included in the production kustomization as a resource:

```yaml
# app-gitops/services/sampleapi/overlays/production/kustomization.yaml (excerpt)
resources:
  - ../../base
  - pdb.yaml                        # <-- PDB is a standalone resource, not a patch
  - configmap-env.yaml
  - secret-env.yaml
```

It is a **resource**, not a patch, because PDBs are their own API object -- they are not part of the Deployment spec.

### 1.3 Do -- Verify the PDB Is Active

```bash
# Check that the PDB exists in production
$OC get pdb -n $NS_PROD
```

Expected output:

```
NAME                  MIN AVAILABLE   MAX UNAVAILABLE   ALLOWED DISRUPTIONS   AGE
notificationapi-pdb   1               N/A               1                     2d
sampleapi-pdb         1               N/A               2                     2d
```

The `ALLOWED DISRUPTIONS` column shows how many pods can be safely evicted right now. With 3 running pods and `minAvailable: 1`, `ALLOWED DISRUPTIONS = 2` for sampleapi (3 replicas - 1 minAvailable = 2). NotificationApi has 2 replicas, so only 1 disruption is allowed.

### 1.4 Do -- Quick Win: Watch the PDB Protect Your Pods

This is the moment where the PDB proves its value. You will attempt to drain a node and watch the PDB constrain the eviction.

First, identify which nodes your production pods are running on:

```bash
$OC get pods -n $NS_PROD -o wide
# Note the NODE column -- your pods may be on 1, 2, or 3 different nodes
```

Now, simulate what happens during a cluster upgrade by deleting a pod and watching the PDB in real time. Open two terminals:

**Terminal 1 -- Watch the PDB:**
```bash
$OC get pdb sampleapi-pdb -n $NS_PROD -w
```

**Terminal 2 -- Delete a pod (simulating eviction):**
```bash
# Delete one pod -- Kubernetes will respect the PDB
$OC delete pod -n $NS_PROD -l app=sampleapi --field-selector=status.phase=Running \
  --wait=false | head -1

# Watch the replacement come up
$OC get pods -n $NS_PROD -w
```

In Terminal 1, you will see `ALLOWED DISRUPTIONS` drop from 2 to 1, then back to 2 once the replacement pod reaches Ready.

> **CALLOUT -- ArgoCD Auto-Heal**
>
> You may notice that ArgoCD does not intervene when you delete a pod. That is correct.
> The Deployment controller (not ArgoCD) manages pod count. ArgoCD manages the desired
> state in Git. Since you did not change Git, ArgoCD sees no drift. The Deployment
> controller sees "2 pods but spec says 3" and creates a replacement. This is
> Kubernetes self-healing, not GitOps.

### 1.5 Do -- Strengthen the PDB for Stricter SLAs

If your SLA requires that at least 2 pods are always serving traffic (for example, to survive a pod failure during a rolling update), tighten the PDB:

```yaml
# Stricter PDB -- use this for high-SLA services
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: sampleapi-pdb
  labels:
    app: sampleapi
spec:
  minAvailable: 2            # <-- At least 2 pods must be running
  selector:                  #     Only 1 can be disrupted at a time with 3 replicas
    matchLabels:
      app: sampleapi
```

For this tutorial, keep the existing `minAvailable: 1`. The point is that you understand the trade-off: stricter PDBs improve availability but slow down rolling updates and node drains.

### 1.6 Verify

```bash
# Confirm PDB is enforced
$OC get pdb -n $NS_PROD -o jsonpath='{.items[0].status.disruptionsAllowed}'
echo
# --> 2 (with minAvailable: 1 and 3 healthy pods)

# Confirm all pods recovered
$OC get pods -n $NS_PROD
# --> Expected:
# NAME                               READY   STATUS      RESTARTS   AGE
# notificationapi-849fbbfd-pkmcb     1/1     Running     1          26h
# notificationapi-849fbbfd-pww2l     1/1     Running     1          28h
# postgresql-0                       1/1     Running     2          47h
# postgresql-backup-29551800-sq2gz   0/1     Completed   0          3h
# redis-0                            1/1     Running     2          47h
# sampleapi-5c9cbb84d5-n945l         1/1     Running     1          28h
# sampleapi-5c9cbb84d5-sk824         1/1     Running     1          28h
# sampleapi-5c9cbb84d5-x5mhn         1/1     Running     1          26h
```

---

## Step 2: Add Anti-Affinity Rules

**WHY:** If all 3 production pods land on the same node and that node fails, you have a full outage -- despite having 3 replicas. Anti-affinity tells the scheduler: "Spread my pods across nodes so that a single node failure takes out at most one pod."

### 2.1 Tell -- Preferred vs Required Anti-Affinity

Kubernetes offers two strengths of anti-affinity:

| Type | Behavior | Risk |
|------|----------|------|
| `preferredDuringSchedulingIgnoredDuringExecution` | Scheduler tries to spread pods but will co-locate them if no other option exists | Pods may land on the same node if cluster is tight |
| `requiredDuringSchedulingIgnoredDuringExecution` | Scheduler refuses to schedule a pod if it would violate the rule | Pods may stay Pending forever if not enough nodes |

We use `preferred` because our cluster has 3 worker nodes and 3 replicas. With `required`, if one worker node goes down, the replacement pod cannot schedule because the remaining 2 nodes already have one pod each, and the rule forbids co-location. That turns a partial outage into a full one. Prefer availability over perfect distribution.

### 2.2 Show -- The Production Anti-Affinity Patch

This is already defined in your production deployment patch:

```bash
cat app-gitops/services/sampleapi/overlays/production/patch-deployment.yaml
```

```yaml
# app-gitops/services/sampleapi/overlays/production/patch-deployment.yaml
# PRODUCTION-specific: 3 replicas, higher resources, anti-affinity
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: sampleapi
spec:
  replicas: 3
  template:
    spec:
      # Anti-affinity -- spread pods across nodes for HA
      affinity:
        podAntiAffinity:
          preferredDuringSchedulingIgnoredDuringExecution:
            - weight: 100                    # <-- Max weight: strongest preference
              podAffinityTerm:
                labelSelector:
                  matchLabels:
                    app: sampleapi           # <-- "Avoid nodes that already run sampleapi"
                topologyKey: kubernetes.io/hostname  # <-- Spread by node hostname
      containers:
        - name: sampleapi
          resources:
            requests:
              cpu: 500m
              memory: 512Mi
            limits:
              cpu: "2"
              memory: 2Gi
```

Walk through the key fields:

- **`weight: 100`** -- On a scale of 1--100, how much the scheduler should try to honor this rule. 100 means "try very hard."
- **`labelSelector.matchLabels.app: sampleapi`** -- The scheduler looks for pods with this label. If a node already has one, it prefers a different node.
- **`topologyKey: kubernetes.io/hostname`** -- The failure domain. `hostname` means "spread across nodes." You could also use `topology.kubernetes.io/zone` to spread across availability zones.

Compare this to the DEV overlay, which has no affinity rules at all:

```yaml
# app-gitops/services/sampleapi/overlays/dev/patch-deployment.yaml (for contrast)
apiVersion: apps/v1
kind: Deployment
metadata:
  name: sampleapi
spec:
  replicas: 1                # <-- Single replica, no affinity needed
  template:
    spec:
      containers:
        - name: sampleapi
          resources:
            requests:
              cpu: 100m
              memory: 128Mi
            limits:
              cpu: 500m
              memory: 512Mi
```

DEV does not need anti-affinity because there is only 1 replica. Anti-affinity is a production concern.

### 2.3 Do -- Verify Pod Spread

```bash
# Check which nodes your production pods are on
$OC get pods -n $NS_PROD -o custom-columns=\
NAME:.metadata.name,\
NODE:.spec.nodeName,\
STATUS:.status.phase
```

You should see pods distributed across different nodes. If your cluster has 3 worker nodes, you should see one pod per node.

If two pods are on the same node, the scheduler could not find a better placement (maybe a node was temporarily unavailable). The `preferred` rule allows this -- it is not a failure.

### 2.4 Do -- Verify the Affinity Is Applied

```bash
# Inspect the deployment's pod template for the affinity block
$OC get deployment sampleapi -n $NS_PROD \
  -o jsonpath='{.spec.template.spec.affinity}' | jq .
```

Expected output:

```json
{
  "podAntiAffinity": {
    "preferredDuringSchedulingIgnoredDuringExecution": [
      {
        "weight": 100,
        "podAffinityTerm": {
          "labelSelector": {
            "matchLabels": {
              "app": "sampleapi"
            }
          },
          "topologyKey": "kubernetes.io/hostname"
        }
      }
    ]
  }
}
```

### 2.5 Verify

```bash
# Confirm pods are spread (at least 2 different nodes for 3 pods)
NODES=$($OC get pods -n $NS_PROD -o jsonpath='{.items[*].spec.nodeName}' | tr ' ' '\n' | sort -u | wc -l)
echo "Pods are spread across $NODES unique nodes"
# --> Should be 2 or 3

# Confirm all 3 pods are healthy
$OC get pods -n $NS_PROD -o wide
# --> 3 pods, all Running, Ready 1/1
```

---

## Step 3: Harden Network Policies for Production

**WHY:** In a Kubernetes cluster without network policies, any pod can talk to any other pod in any namespace. That means a compromised pod in the `sampleapi-dev` namespace could reach your production database. Network policies enforce zero-trust: deny everything, then explicitly allow only the traffic your application needs.

### 3.1 Tell -- The Layered Network Policy Model

Your project already has network policies applied in Phase 1. Here is the full policy stack for production:

```
+-------------------------------------------+
| default-deny-ingress                      |  <-- Blocks ALL inbound traffic
+-------------------------------------------+
          |
          v   (exceptions carved out below)
+-------------------------------------------+
| allow-from-openshift-ingress              |  <-- External HTTPS via Route
+-------------------------------------------+
| allow-monitoring                          |  <-- Prometheus scraping on port 8080
+-------------------------------------------+
| allow-same-namespace                      |  <-- Pod-to-pod within sampleapi-prod
+-------------------------------------------+
| allow-from-argocd                         |  <-- ArgoCD health checks + sync
+-------------------------------------------+
```

This is the zero-trust pattern: start with deny-all, then add explicit allow rules. Each allow rule is scoped to specific source namespaces and target pods.

### 3.2 Show -- The Default Deny Policy

```bash
cat infra/phase1/networkpolicies/default-deny.yaml
```

The production section:

```yaml
# Default deny-all ingress for sampleapi-prod
# Zero-trust baseline: all traffic is blocked unless explicitly allowed
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: default-deny-ingress
  namespace: sampleapi-prod
  labels:
    team: devsecops
    policy: default-deny
spec:
  podSelector: {}              # <-- Empty selector = applies to ALL pods
  policyTypes:
    - Ingress                  # <-- Block all inbound traffic
  # No ingress rules = deny all
```

The critical detail is the empty `ingress` array. In Kubernetes NetworkPolicy semantics, specifying `policyTypes: [Ingress]` with no `ingress` rules means "deny all inbound traffic to every pod in this namespace."

### 3.3 Show -- The Allow Rules

Each allow rule carves a specific hole in the deny-all policy:

**Allow external traffic via OpenShift Router:**

```yaml
# Only the OpenShift ingress controller (HAProxy) can reach our pods
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: allow-from-openshift-ingress
  namespace: sampleapi-prod
spec:
  podSelector:
    matchLabels:
      app: sampleapi                       # <-- Only allow traffic TO sampleapi pods
  ingress:
    - from:
        - namespaceSelector:
            matchLabels:
              policy-group.network.openshift.io/ingress: ""  # <-- FROM the router namespace
  policyTypes:
    - Ingress
```

**Allow Prometheus scraping:**

```yaml
# Prometheus must reach port 8080 to collect metrics
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: allow-monitoring
  namespace: sampleapi-prod
spec:
  podSelector:
    matchLabels:
      app: sampleapi
  ingress:
    - from:
        - namespaceSelector:
            matchLabels:
              network.openshift.io/policy-group: monitoring   # <-- Only monitoring namespace
      ports:
        - protocol: TCP
          port: 8080           # <-- Restricted to the metrics port only
  policyTypes:
    - Ingress
```

Notice that the monitoring policy restricts by both source namespace AND destination port. The ingress router policy does not restrict by port because the router already handles TLS termination and only forwards to the port defined in the Route.

### 3.4 Do -- Verify Network Policies in Production

```bash
# List all network policies in production
$OC get networkpolicy -n $NS_PROD
```

Expected output (Phase 1 baseline policies plus Phase 17 inter-service policies):

```
NAME                              POD-SELECTOR           AGE
default-deny-ingress              <none>                 ...
allow-from-openshift-ingress      app=sampleapi          ...
allow-monitoring                  app=sampleapi          ...
allow-same-namespace              <none>                 ...
allow-from-argocd                 <none>                 ...
allow-app-to-postgresql           app=sampleapi          ...
allow-app-to-redis                app in (sampleapi,...) ...
allow-app-to-notificationapi      app=sampleapi          ...
allow-monitoring-newservices       app=notificationapi    ...
allow-router-to-notificationapi   app=notificationapi    ...
deny-postgresql-egress            app=postgresql          ...
deny-redis-egress                 app=redis               ...
```

You should see 12 policies (5 from Phase 1 + 7 from Phase 17). If any are missing, re-apply:

```bash
$OC apply -f infra/phase1/networkpolicies/
$OC apply -f infra/phase17/networkpolicies/
```

### 3.5 Do -- Test That the Policies Actually Block Unauthorized Traffic

This is the step most people skip. A network policy that is not tested is a network policy that does not exist.

**Test 1: Verify external access works (should succeed):**

```bash
curl -sk $APP_PROD_URL/healthz
# --> {"status":"healthy","timestamp":"..."}
```

**Test 2: Verify cross-namespace traffic is blocked (should fail):**

```bash
# Try to reach the prod pod from the DEV namespace
# This should time out because default-deny blocks it
$OC run -n $NS_DEV test-netpol --rm -it --restart=Never \
  --image=registry.access.redhat.com/ubi9/ubi-minimal:latest \
  -- curl -s --connect-timeout 5 \
  http://sampleapi.${NS_PROD}.svc:8080/healthz 2>&1 || true
# --> Expected: connection timeout (blocked by network policy)
```

**Test 3: Verify same-namespace traffic works (should succeed):**

```bash
# Try to reach the prod pod from within the PROD namespace
$OC run -n $NS_PROD test-netpol --rm -it --restart=Never \
  --image=registry.access.redhat.com/ubi9/ubi-minimal:latest \
  -- curl -s --connect-timeout 5 \
  http://sampleapi.${NS_PROD}.svc:8080/healthz 2>&1 || true
# --> Expected: {"status":"healthy","timestamp":"..."} (allowed by allow-same-namespace)
```

### 3.6 Do -- Add a Production-Specific Egress Policy (Hardening Exercise)

The current policies only restrict **ingress** (inbound). Production should also restrict **egress** (outbound) to prevent a compromised pod from calling out to the internet.

Create a new file:

```yaml
# infra/phase1/networkpolicies/prod-egress.yaml
# Production egress restriction: only allow DNS + internal cluster traffic
# This prevents a compromised pod from exfiltrating data to the internet
---
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: restrict-egress
  namespace: sampleapi-prod
  labels:
    team: devsecops
    policy: prod-egress
spec:
  podSelector:
    matchLabels:
      app: sampleapi
  policyTypes:
    - Egress
  egress:
    # Allow DNS resolution (required for service discovery)
    - to: []
      ports:
        - protocol: UDP
          port: 53
        - protocol: TCP
          port: 53
    # Allow traffic to pods within the same namespace (e.g., database)
    - to:
        - podSelector: {}
    # Allow traffic to the Kubernetes API (for health reporting)
    - to:
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: default
      ports:
        - protocol: TCP
          port: 443
    # Allow traffic to monitoring namespace (push metrics if needed)
    - to:
        - namespaceSelector:
            matchLabels:
              network.openshift.io/policy-group: monitoring
```

Apply it:

```bash
$OC apply -f infra/phase1/networkpolicies/prod-egress.yaml
```

Verify the egress policy was created:

```bash
$OC get networkpolicy restrict-egress -n $NS_PROD
# --> restrict-egress   app=sampleapi   ...
```

> **CALLOUT -- Why Not Restrict Egress in DEV?**
>
> DEV pods need to reach external NuGet feeds, external APIs for testing, and
> other services that are impractical to enumerate. Restricting egress in DEV
> creates friction without proportional security benefit. Production is different:
> the application's dependencies are fixed and known, so you can enumerate exactly
> what outbound traffic is legitimate.

### 3.7 Verify

```bash
# Full policy count in production (should be 13 with the new egress policy)
# 5 (Phase 1) + 7 (Phase 17 inter-service) + 1 (new egress) = 13
$OC get networkpolicy -n $NS_PROD --no-headers | wc -l
# --> 13

# App still accessible externally
curl -sk $APP_PROD_URL/healthz
# --> {"status":"healthy","timestamp":"..."}
```

---

## Step 4: Configure Rolling Update Strategy

**WHY:** The default Kubernetes rolling update strategy allows `maxUnavailable: 25%`, which means with 3 replicas, 1 pod can be taken down immediately. Combined with our PDB and anti-affinity, this is already safe. But for production, we want tighter control: never take down a pod until a new one is ready.

### 4.1 Tell -- maxUnavailable vs maxSurge

| Parameter | What It Controls | Production Value | Why |
|-----------|-----------------|-----------------|-----|
| `maxUnavailable` | How many pods can be down during update | 0 | Never reduce capacity below current replica count |
| `maxSurge` | How many extra pods can exist during update | 1 | Create 1 new pod, wait for Ready, then terminate 1 old pod |

With `maxUnavailable: 0` and `maxSurge: 1`, the rollout follows this sequence:

```
Start:     [v1] [v1] [v1]           --> 3 pods serving traffic
Step 1:    [v1] [v1] [v1] [v2]      --> 4th pod (v2) created, waiting for Ready
Step 2:    [v1] [v1] [v2] [--]      --> v2 Ready, 1 v1 terminated
Step 3:    [v1] [v1] [v2] [v2]      --> 2nd v2 created, waiting
Step 4:    [v1] [v2] [v2] [--]      --> Ready, 1 v1 terminated
Step 5:    [v1] [v2] [v2] [v2]      --> 3rd v2 created, waiting
Step 6:    [v2] [v2] [v2] [--]      --> Done. Zero downtime throughout.
```

At no point during this sequence are fewer than 3 pods serving traffic. That is the guarantee.

### 4.2 Show -- Adding the Strategy to the Production Patch

The current production patch does not explicitly set a rolling update strategy. The Deployment inherits the Kubernetes default (`maxUnavailable: 25%`, `maxSurge: 25%`). For production hardening, make it explicit.

Add the `strategy` block to your production deployment patch:

```yaml
# app-gitops/services/sampleapi/overlays/production/patch-deployment.yaml
# PRODUCTION-specific: 3 replicas, higher resources, anti-affinity, strict rolling update
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: sampleapi
spec:
  replicas: 3
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxUnavailable: 0        # <-- Never reduce available pods during update
      maxSurge: 1              # <-- Create at most 1 extra pod during update
  template:
    spec:
      # Anti-affinity -- spread pods across nodes for HA
      affinity:
        podAntiAffinity:
          preferredDuringSchedulingIgnoredDuringExecution:
            - weight: 100
              podAffinityTerm:
                labelSelector:
                  matchLabels:
                    app: sampleapi
                topologyKey: kubernetes.io/hostname
      containers:
        - name: sampleapi
          resources:
            requests:
              cpu: 500m
              memory: 512Mi
            limits:
              cpu: "2"
              memory: 2Gi
```

> **CALLOUT -- Resource Quota Headroom**
>
> With `maxSurge: 1`, during a rollout you temporarily have 4 pods instead of 3.
> That 4th pod needs resources. Your production quota allows 12 CPU / 24Gi limits.
> Each pod requests 500m CPU and 512Mi memory, so 4 pods need 2 CPU and 2Gi --
> well within quota. Always check that your quota can accommodate the surge.
>
> ```bash
> $OC describe quota -n $NS_PROD | grep -E "cpu|memory"
> ```

### 4.3 Do -- Apply the Updated Patch via GitOps

In a real workflow, you would commit this change to the `app-gitops` repository and let ArgoCD sync it. For this exercise, you can apply it directly to see the effect, then sync afterward:

```bash
# Preview what Kustomize would render
cd app-gitops
kustomize build services/sampleapi/overlays/production | grep -A 10 "strategy:"

# If you updated the patch file, commit and push:
git add services/sampleapi/overlays/production/patch-deployment.yaml
git commit -m "harden: add explicit rolling update strategy for production"
git push origin main

# Then sync via ArgoCD (PROD is manual sync)
argocd app sync sampleapi-prod
argocd app wait sampleapi-prod --health --timeout 120
```

### 4.4 Verify

```bash
# Confirm the strategy is applied
$OC get deployment sampleapi -n $NS_PROD \
  -o jsonpath='{.spec.strategy}' | jq .
```

Expected:

```json
{
  "type": "RollingUpdate",
  "rollingUpdate": {
    "maxUnavailable": 0,
    "maxSurge": 1
  }
}
```

---

## Step 5: Test Rolling Update and Rollback

**WHY:** Configuring a rolling update strategy is meaningless if you have never actually watched one happen and practiced reverting it. This step builds muscle memory for production incidents.

### 5.1 Do -- Watch a Rolling Update in Real Time

Open two terminals. You will trigger a deployment change and watch it roll out pod by pod.

**Terminal 1 -- Watch pods:**

```bash
$OC get pods -n $NS_PROD -w
```

**Terminal 2 -- Trigger a rollout by updating an environment variable:**

```bash
# This simulates what happens when ArgoCD syncs a new image tag.
# We use a harmless annotation change to trigger a rollout without changing the image.
$OC patch deployment sampleapi -n $NS_PROD \
  -p '{"spec":{"template":{"metadata":{"annotations":{"rollout-trigger":"module-15-test"}}}}}'
```

In Terminal 1, observe the sequence:

1. A new pod is created (the surge pod)
2. It goes through `ContainerCreating` --> `Running` --> `Ready`
3. An old pod is terminated
4. Repeat until all pods are replaced

At no point should you see fewer than 3 pods in `Ready` state (assuming `maxUnavailable: 0`).

### 5.2 Do -- Verify Zero-Downtime During Rollout

While the rollout is in progress (or trigger another one), continuously hit the health endpoint:

```bash
# Run this in a separate terminal DURING the rollout
for i in $(seq 1 30); do
  HTTP_CODE=$(curl -sk -o /dev/null -w "%{http_code}" \
    $APP_PROD_URL/healthz)
  echo "$(date +%H:%M:%S) --> HTTP $HTTP_CODE"
  sleep 1
done
```

Every single request should return HTTP 200. If you see a 503 or connection refused, something is wrong with your readiness probe or your PDB configuration.

### 5.3 Do -- Practice a Rollback

Rollback is the most important production skill. You need to be able to do it under pressure, so practice it now when nothing is on fire.

**Method 1: ArgoCD rollback (preferred for GitOps)**

```bash
# View deployment history
argocd app history sampleapi-prod
# Expected output (example):
#   ID   DATE                           REVISION
#   12   2026-03-09 12:30:00 +0000 UTC  main (abc1234)
#   11   2026-03-08 10:15:00 +0000 UTC  main (def5678)  <-- previous

# Rollback to the previous revision (use the ID from history, e.g., 11)
# NOTE: ArgoCD rollback requires a valid positive revision ID -- 0 is not valid
PREV_REV=$(argocd app history sampleapi-prod -o json | jq '.[1].id')
argocd app rollback sampleapi-prod $PREV_REV

# Verify the rollback took effect
argocd app get sampleapi-prod
```

**Method 2: Git revert (the GitOps-native way)**

```bash
# In the app-gitops repo, revert the last commit
cd app-gitops
git log --oneline -5
# Identify the commit to revert
git revert HEAD --no-edit
git push origin main

# Then sync
argocd app sync sampleapi-prod
```

**Method 3: Kubernetes native rollback (emergency only)**

```bash
# View rollout history
$OC rollout history deployment/sampleapi -n $NS_PROD

# Roll back to previous revision
$OC rollout undo deployment/sampleapi -n $NS_PROD

# Watch it happen
$OC rollout status deployment/sampleapi -n $NS_PROD
```

> **CALLOUT -- Which Rollback Method to Use**
>
> In a GitOps workflow, Method 2 (Git revert) is the correct approach because it
> keeps Git as the single source of truth. Method 1 (ArgoCD rollback) is faster
> but creates drift between Git and the cluster. Method 3 (oc rollout undo) is
> for emergencies when you cannot access ArgoCD or Git. After using Method 1 or 3,
> always follow up by updating Git to match the rolled-back state.

### 5.4 Verify

```bash
# Confirm production is healthy after the rollback exercise
$OC get pods -n $NS_PROD
# --> Expected:
# NAME                               READY   STATUS      RESTARTS   AGE
# notificationapi-849fbbfd-pkmcb     1/1     Running     1          26h
# notificationapi-849fbbfd-pww2l     1/1     Running     1          28h
# postgresql-0                       1/1     Running     2          47h
# postgresql-backup-29551800-sq2gz   0/1     Completed   0          3h
# redis-0                            1/1     Running     2          47h
# sampleapi-5c9cbb84d5-n945l         1/1     Running     1          28h
# sampleapi-5c9cbb84d5-sk824         1/1     Running     1          28h
# sampleapi-5c9cbb84d5-x5mhn         1/1     Running     1          26h

$OC rollout status deployment/sampleapi -n $NS_PROD
# --> deployment "sampleapi" successfully rolled out

curl -sk $APP_PROD_URL/healthz
# --> {"status":"healthy","timestamp":"2026-03-10T05:25:02.3751347Z"}

argocd app get sampleapi-prod | grep -E "Status|Health"
# --> Status: Synced, Health: Healthy
```

---

## Step 6: Compliance Scanning with Red Hat ACS

**WHY:** Production hardening is not a one-time task. Configuration drifts. New CVEs are disclosed. Developers push workarounds. You need continuous compliance scanning to verify that your security posture remains correct over time.

### 6.1 Tell -- What ACS Compliance Scanning Does

Red Hat Advanced Cluster Security (ACS / StackRox) evaluates your running cluster against security policies at three lifecycle stages:

| Stage | When It Runs | What It Checks | Response |
|-------|-------------|----------------|----------|
| Build | CI pipeline (`roxctl image check`) | CVEs in image layers, Dockerfile best practices | Fail the pipeline |
| Deploy | Admission controller intercepts `kubectl apply` | Root user, untrusted registries, resource limits | Block the deployment |
| Runtime | Continuous monitoring of running pods | Anomalous processes, network connections, file access | Alert or kill the pod |

For production hardening, we focus on deploy-time and runtime policies.

### 6.2 Show -- The Deployed ACS Policies

Your project has these ACS policies already configured:

```
infra/phase5/acs-policies/
  block-critical-cves.yaml          --> Build-time: CVSS >= 9.0 fails the pipeline
  block-root-images.yaml            --> Deploy-time: containers running as UID 0 are blocked
  block-untrusted-registries.yaml   --> Deploy-time: only allow images from known registries
  detect-runtime-threats.yaml       --> Runtime: detect crypto miners, reverse shells
```

The deploy-time policy for blocking root containers is particularly relevant for production:

```json
{
  "name": "Block Root User Containers",
  "lifecycleStages": ["DEPLOY"],
  "severity": "HIGH_SEVERITY",
  "enforcementActions": ["SCALE_TO_ZERO_ENFORCEMENT"],
  "scope": [
    {"namespace": "sampleapi-dev"},
    {"namespace": "sampleapi-sit"},
    {"namespace": "sampleapi-uat"},
    {"namespace": "sampleapi-prod"}
  ]
}
```

`SCALE_TO_ZERO_ENFORCEMENT` means ACS will scale the deployment to 0 replicas if a container runs as root. In production, this is a hard stop.

### 6.3 Do -- Run a Compliance Check on Production

```bash
# Check the production image against ACS policies
# First, get the current image tag
PROD_IMAGE=$($OC get deployment sampleapi -n $NS_PROD \
  -o jsonpath='{.spec.template.spec.containers[0].image}')
echo "Production image: $PROD_IMAGE"

# Run roxctl image check (same command the CI pipeline uses)
export ROX_API_TOKEN=$($OC get secret acs-token -n $NS_TOOLS \
  -o jsonpath='{.data.token}' | base64 -d)

roxctl -e "$ACS_URL:443" --insecure-skip-tls-verify \
  image check --image "$PROD_IMAGE" --output table
```

Review the output. Each policy violation is listed with its severity, description, and remediation guidance.

### 6.4 Do -- Run a Full Image Scan

An image check evaluates policies. An image scan enumerates every CVE in the image:

```bash
roxctl -e "$ACS_URL:443" --insecure-skip-tls-verify \
  image scan --image "$PROD_IMAGE" --output table | head -40
```

This shows every CVE in the image with its CVSS score, affected package, and fixed version. For production, you should have zero CRITICAL and zero HIGH CVEs. If any appear, they must be tracked as remediation items.

### 6.5 Do -- Verify Deploy-Time Enforcement

Test that ACS actually blocks a non-compliant deployment in production:

```bash
# Attempt to deploy a known-insecure image (nginx 1.14 runs as root)
$OC set image deployment/sampleapi sampleapi=docker.io/library/nginx:1.14 \
  -n $NS_PROD 2>&1
```

If ACS admission control is enabled, one of two things happens:

1. The admission controller blocks the change immediately (you see an error)
2. The pods start but ACS scales the deployment to 0 (enforcement action)

Either way, production is protected. Revert to the correct state:

```bash
# Revert via ArgoCD (re-sync from Git, the source of truth)
argocd app sync sampleapi-prod
argocd app wait sampleapi-prod --health --timeout 120
```

### 6.6 Do -- Check Compliance in the ACS Dashboard

Open the ACS Central UI for a visual compliance report:

```
URL:  $ACS_URL
Menu: Compliance --> Scan Environment --> Review results
```

The compliance dashboard shows pass/fail against industry standards (CIS, NIST, PCI-DSS) broken down by cluster, namespace, and deployment. Export the report as evidence for audits.

### 6.7 Verify

```bash
# ACS sees the production image (re-run image check on the deployed image)
PROD_IMAGE=$($OC get deployment sampleapi -n $NS_PROD \
  -o jsonpath='{.spec.template.spec.containers[0].image}')
roxctl -e "$ACS_URL:443" --insecure-skip-tls-verify \
  image check --image "$PROD_IMAGE" --output table 2>&1 | head -10
# --> Should show policy evaluation results for the production image

# Production pods are still healthy after the enforcement test
$OC get pods -n $NS_PROD
# --> sampleapi (3 Running), notificationapi (2 Running), postgresql-0, redis-0
```

---

## Going Further: Automated Chaos Tests, Backups, and Compliance (Phase 24)

This module walked you through production hardening manually -- you verified PDBs, tested anti-affinity, ran network policy tests, and performed compliance checks by hand. Phase 24 automates all of this as repeatable Kubernetes Jobs and CronJobs so these checks run continuously without human intervention.

### Automated Chaos Tests

Three pre-built chaos test manifests live in `infra/phase24/`:

| Test | File | What It Does |
|------|------|-------------|
| **Pod Kill** | `chaos-test-pod-kill.yaml` | Deletes one sampleapi pod, waits 30s, verifies auto-heal (>= 2 pods running) |
| **Network Partition** | `chaos-test-network.yaml` | Blocks all ingress to NotificationApi, verifies SampleApi degrades gracefully |
| **Node Drain** | `chaos-test-node-drain.sh` | Drains a worker node, verifies zero-downtime migration via continuous curl |

Run the pod kill chaos test:

```bash
# Apply the chaos test Job in PROD
$OC apply -f infra/phase24/chaos-test-pod-kill.yaml -n $NS_PROD

# Watch the job output
$OC logs -f job/chaos-test-pod-kill -n $NS_PROD
# Expected: "PASS: Auto-heal successful — 3 pods running"

# Cleanup
$OC delete job chaos-test-pod-kill -n $NS_PROD
```

Run the network partition test (in DEV -- never in PROD without approval):

```bash
# Apply partition — blocks traffic to NotificationApi
$OC apply -f infra/phase24/chaos-test-network.yaml -n $NS_DEV

# SampleApi should still respond (graceful degradation)
curl -sk $APP_DEV_URL/api/WeatherForecast
# Expected: weather data returned (notification silently fails)

# Remove partition
$OC delete -f infra/phase24/chaos-test-network.yaml -n $NS_DEV
```

### Automated Backups

Two CronJobs run daily in production:

| CronJob | File | Schedule | Retention |
|---------|------|----------|-----------|
| **PostgreSQL backup** | `backup-postgresql-cronjob.yaml` | Daily 02:00 UTC | 7 days on PVC |
| **GitLab backup** | `backup-gitlab-cronjob.yaml` | Daily 03:00 UTC | On PVC |

Deploy the PostgreSQL backup:

```bash
$OC apply -f infra/phase24/backup-postgresql-cronjob.yaml -n $NS_PROD

# Verify it was created
$OC get cronjob postgresql-backup -n $NS_PROD
# Expected: SCHEDULE=0 2 * * *

# Trigger a manual run to test
$OC create job postgresql-backup-test --from=cronjob/postgresql-backup -n $NS_PROD
$OC logs -f job/postgresql-backup-test -n $NS_PROD
# Expected: "Backup completed" with non-zero file size
```

> **Gotcha:** The backup image must match the PostgreSQL server version. If your StatefulSet runs `postgresql-16`, the CronJob must also use `postgresql-16:latest` -- a version mismatch causes `pg_dump: server version mismatch` errors.

### Automated Compliance Scanning

A weekly CronJob runs `roxctl` compliance scans across all namespaces:

```bash
$OC apply -f infra/phase24/compliance-scan-job.yaml -n $NS_TOOLS

# Verify it was created
$OC get cronjob acs-compliance-scan -n $NS_TOOLS
# Expected: SCHEDULE=0 2 * * 0 (every Sunday)

# Trigger a manual run
$OC create job compliance-scan-test --from=cronjob/acs-compliance-scan -n $NS_TOOLS
$OC logs -f job/compliance-scan-test -n $NS_TOOLS
# Expected: Compliance scan triggered, results listed
```

Results are stored in ACS Central and visible in the Compliance dashboard (`$ACS_URL` → Compliance → Scan Environment).

### Inter-Service Network Policies (Phase 17)

Step 3 covered the basic namespace-level network policies from Phase 1. Phase 17 added fine-grained inter-service policies in `infra/phase17/networkpolicies/`:

| Policy | What It Allows |
|--------|---------------|
| `allow-app-to-postgresql.yaml` | SampleApi → PostgreSQL on port 5432 |
| `allow-app-to-redis.yaml` | SampleApi + NotificationApi → Redis on port 6379 |
| `allow-app-to-notification.yaml` | SampleApi → NotificationApi on port 8081 |
| `deny-db-egress.yaml` | PostgreSQL + Redis have NO egress (locked down) |

These should already be applied. Verify:

```bash
$OC get networkpolicy -n $NS_PROD | grep -E "postgresql|redis|notification"
# Expected: 7 policies matching the Phase 17 inter-service rules
# (allow-app-to-postgresql, allow-app-to-redis, allow-app-to-notificationapi,
#  allow-monitoring-newservices, allow-router-to-notificationapi,
#  deny-postgresql-egress, deny-redis-egress)
```

### Additional Production Documentation (Phase 24)

Phase 24 also produced documentation that complements this module:

- **`infra/phase24/capacity-planning.md`** -- Resource usage analysis and growth projections
- **`infra/phase24/disaster-recovery.md`** -- DR procedures for each component (PostgreSQL, GitLab, ArgoCD)
- **`infra/phase24/production-go-live-checklist.md`** -- Final sign-off checklist before going live

---

## Recap: What You Hardened

Here is a summary of every hardening measure now in place for production:

| Layer | What | File | Effect |
|-------|------|------|--------|
| Availability | PodDisruptionBudget | `overlays/production/pdb.yaml` | At least 1 pod always running during voluntary disruptions |
| Availability | Anti-affinity | `overlays/production/patch-deployment.yaml` | Pods spread across nodes; single node failure is survivable |
| Availability | Rolling update | `overlays/production/patch-deployment.yaml` | Zero-downtime deploys: maxUnavailable=0, maxSurge=1 |
| Network | Default deny | `networkpolicies/default-deny.yaml` | All inbound traffic blocked by default |
| Network | Allow rules | `networkpolicies/allow-*.yaml` | Only router, monitoring, same-ns, ArgoCD can reach pods |
| Network | Egress restriction | `networkpolicies/prod-egress.yaml` | Outbound limited to DNS + cluster-internal |
| Security | ACS build policy | `acs-policies/block-critical-cves.yaml` | Images with CVSS >= 9.0 never reach production |
| Security | ACS deploy policy | `acs-policies/block-root-images.yaml` | Root containers blocked at admission |
| Security | ACS runtime | `acs-policies/detect-runtime-threats.yaml` | Anomalous behavior detected in running pods |
| Resources | Quota | `resourcequotas.yaml` | PROD: 12 CPU / 24Gi limit; prevents resource starvation |
| Resources | LimitRange | `limitranges.yaml` | PROD: default 1 CPU / 1Gi per container; max 4 CPU / 8Gi |
| GitOps | Manual sync | `argocd/sampleapi-prod.yaml` | Changes require MR approval before sync |
| Config | Logging=Warning | `overlays/production/configmap-env.yaml` | No debug noise in production logs |
| Config | Swagger=false | `overlays/production/configmap-env.yaml` | API documentation not exposed in production |

---

## Common Mistakes

### Mistake 1: PDB Too Strict for the Replica Count

```yaml
# WRONG: minAvailable equals replica count
spec:
  replicas: 3
# ...
  minAvailable: 3       # <-- Deadlock! No pod can ever be evicted.
                         #     Node drains will hang forever.
                         #     Rolling updates will never complete.
```

**Rule of thumb:** `minAvailable` must be strictly less than `replicas`. For 3 replicas, use 1 or 2.

### Mistake 2: Required Anti-Affinity on a Small Cluster

```yaml
# DANGEROUS on a 3-node cluster with 3 replicas:
requiredDuringSchedulingIgnoredDuringExecution:
  - labelSelector:
      matchLabels:
        app: sampleapi
    topologyKey: kubernetes.io/hostname
```

If one node goes down, the replacement pod cannot schedule because both remaining nodes already have a pod. Use `preferred` instead -- it achieves the same distribution under normal conditions but allows co-location when the cluster is constrained.

### Mistake 3: Network Policies Without Default Deny

```yaml
# INEFFECTIVE: Adding allow rules without a default deny
# Kubernetes network policies are additive. Without a deny-all policy,
# all traffic is allowed by default. Your allow rules do nothing.
```

Always start with `default-deny-ingress`. Then add explicit allow rules. The deny policy creates the zero-trust baseline; the allow rules carve exceptions.

### Mistake 4: Forgetting DNS in Egress Policies

```yaml
# BROKEN: Restricting egress without allowing DNS
spec:
  policyTypes:
    - Egress
  egress:
    - to:
        - podSelector: {}    # <-- Allows pod-to-pod but NO DNS
                              #     Service discovery breaks completely
                              #     All requests to svc names fail
```

Always include UDP/TCP port 53 in your egress allow rules. Without DNS, your pods cannot resolve service names, ConfigMap-injected URLs, or anything else.

### Mistake 5: Not Testing Rollback Before You Need It

The worst time to learn how to roll back is during a production incident at 2 AM. Practice rollback regularly. Add it to your runbook. Make it a drill.

---

## Challenge: Simulate a Production Incident and Recover

**Scenario:** A bad image has been deployed to production. The application is returning HTTP 500 errors. You need to identify the problem, roll back, and verify recovery -- all within 5 minutes.

### Setup (run this to simulate the incident)

```bash
# Intentionally break the production deployment by setting a non-existent image
$OC set image deployment/sampleapi sampleapi=sampleapi:does-not-exist \
  -n $NS_PROD
```

### Your Task

1. **Detect** the problem (which commands do you run?)
2. **Assess** the blast radius (how many pods are affected?)
3. **Roll back** using the method of your choice
4. **Verify** the application is healthy
5. **Document** what happened (write a 3-line incident summary)

### Expected Recovery Steps

```bash
# 1. DETECT: Check pod status
$OC get pods -n $NS_PROD
# --> You should see pods in ImagePullBackOff or ErrImagePull

# 2. ASSESS: Check how many pods are still serving
$OC get pods -n $NS_PROD -o wide | grep Running
# --> With maxUnavailable=0, all 3 original pods should still be Running
#     (the bad pods are NEW pods that cannot start)
# --> If maxUnavailable was >0, you may have fewer Running pods

# 3. ROLLBACK: Use ArgoCD to restore the Git-defined state
argocd app sync sampleapi-prod --force
argocd app wait sampleapi-prod --health --timeout 120

# 4. VERIFY: Confirm recovery
curl -sk $APP_PROD_URL/healthz
# --> {"status":"healthy","timestamp":"..."}

$OC get pods -n $NS_PROD
# --> 3 pods Running, all Ready

# 5. DOCUMENT:
# Incident: Bad image tag deployed to production.
# Impact: Zero -- rolling update strategy (maxUnavailable=0) prevented old pods
#   from being terminated before new pods were Ready. New pods failed to pull image.
# Resolution: ArgoCD force-sync restored correct image from Git source of truth.
```

> **KEY INSIGHT:** Notice that `maxUnavailable: 0` saved you here. The old pods were
> never terminated because the new pods never became Ready. If you had used the
> default `maxUnavailable: 25%`, one old pod would have been killed immediately,
> reducing capacity by 33% during the incident.

---

## Self-Assessment

Answer these questions without looking at the tutorial. If you cannot answer confidently, re-read the relevant section.

1. What is the difference between a voluntary and involuntary disruption? Which does a PDB protect against?

2. You have 3 replicas and `minAvailable: 3` in your PDB. What happens when you try to drain a node? Why is this a problem?

3. Why do we use `preferred` anti-affinity instead of `required` in a 3-node cluster?

4. With `maxUnavailable: 0` and `maxSurge: 1`, how many pods exist at peak during a rolling update of a 3-replica deployment?

5. You have a default-deny network policy but forgot to add an allow rule for the OpenShift router. What symptom does the user see?

6. You need to roll back production immediately. ArgoCD is unreachable. Git is unreachable. What command do you run?

7. An ACS policy with `SCALE_TO_ZERO_ENFORCEMENT` triggers on your production deployment. What happens to your pods? How do you recover?

### Answers

1. Voluntary: admin-initiated (drain, upgrade, eviction API). Involuntary: unplanned (node crash, OOM). PDB protects only against voluntary disruptions.

2. The drain hangs forever. The PDB says "keep 3 pods running" but you only have 3 replicas. No pod can be evicted. This is a deadlock.

3. Because if a node fails, `required` anti-affinity prevents the replacement pod from scheduling on a node that already has a pod. With only 2 remaining nodes, each already running one pod, the replacement stays Pending indefinitely. `preferred` allows co-location as a fallback.

4. Four pods. `maxSurge: 1` creates one extra pod, and `maxUnavailable: 0` means no old pod is removed until the new one is Ready.

5. The application returns HTTP 503 or the connection is refused. External traffic from the router cannot reach the pods because the network policy blocks it.

6. `$OC rollout undo deployment/sampleapi -n $NS_PROD`. This uses the Kubernetes-native rollback mechanism and requires only cluster access.

7. ACS scales the deployment to 0 replicas. All pods are terminated. To recover: fix the policy violation (use a compliant image), then `argocd app sync sampleapi-prod` or `$OC scale deployment sampleapi --replicas=3 -n $NS_PROD` (emergency).

---

## Conclusion: What You Have Built

Over the course of 15 modules, you have assembled a production-grade DevSecOps workflow from the ground up. Step back and look at the full picture.

### The Pipeline: From Commit to Production

```
Developer pushes code
        |
        v
   [T1: Merge Request Pipeline]
   Unit tests --> SAST (SonarQube) --> SCA (Dependency-Check)
   --> Pass/Fail reported to GitLab MR
        |
        v  (MR approved and merged)
   [T2: Merge to Main Pipeline]
   Same gates + Build image (Podman) --> Image scan (ACS)
   --> Push to registry --> Update GitOps --> Deploy DEV (auto-sync)
        |
        v  (Version tag pushed)
   [T3: Tag Pipeline]
   Same gates + Strict ACS scan + DAST (OWASP ZAP) + Performance Test (k6)
   --> Push versioned image --> Create SIT promotion MR
        |
        v  (Cascading promotion via T4)
   [GitOps Promotion]
   SIT (Team Lead approval) --> UAT (QA Lead approval)
   --> PROD (CAB approval) --> Each via MR + ArgoCD manual sync
```

### The Security Layers: Defense in Depth

```
Layer 1: Pre-commit     gitleaks (secret detection)
Layer 2: Build-time     SonarQube (SAST) + Dependency-Check (SCA)
Layer 3: Image scan     ACS/StackRox (CVE + policy check)
Layer 4: Deploy-time    ACS admission controller (root user, untrusted registry)
Layer 5: Runtime        ACS continuous monitoring (crypto miners, reverse shells)
Layer 6: Network        NetworkPolicies (zero-trust, deny-all + explicit allow)
Layer 7: Platform       ResourceQuotas, LimitRanges, PDBs, anti-affinity
```

### The Operational Posture: Production Hardening

```
Availability:   3 replicas + PDB + anti-affinity + rolling updates
Network:        Default deny + explicit allow (ingress + egress)
Compliance:     ACS continuous scanning + deploy-time enforcement
GitOps:         Manual sync + MR approval for PROD
Rollback:       ArgoCD history, Git revert, or oc rollout undo
Observability:  Prometheus metrics + Grafana dashboards + LokiStack logging +
                Tempo distributed tracing + SLO burn-rate alerting
Performance:    k6 load tests as pipeline quality gates (T3)
```

### The Repository Architecture: Separation of Concerns

```
app-source               Application code only. Zero CI/CD artifacts.
notificationapi-source   Second microservice. Same clean-repo pattern.
build-config             Shared Dockerfile, sonar config, k6 test scripts. Separate from app.
jenkins-shared-lib       All pipeline logic. One function per file. Map config. Structured returns.
app-gitops               Per-service overlays + 12 ArgoCD apps. Per-env config + secrets.
```

Every piece fits together. The pipeline builds the image. The security gates validate it. GitOps deploys it. The production hardening protects it. And when something goes wrong -- because it will -- you have rollback procedures, compliance evidence, and operational runbooks ready.

This is not a demo. This is how production-grade DevSecOps works.

---

**End of Module 15 -- End of the DevSecOps Tutorial Series.**
