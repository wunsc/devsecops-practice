# Module 1: OpenShift Fundamentals for DevSecOps

**Duration:** ~75 minutes
**Track:** Foundation (Modules 1-4)
**Difficulty:** Beginner-Intermediate
**Cluster:** OCP 4.x on AWS (set your cluster domain in `env.sh` → `$APPS_DOMAIN`)

---

## What You'll Learn

By the end of this module, you will be able to:

- Create a multi-namespace layout that separates CI/CD tools from application environments
- Configure ServiceAccounts with least-privilege RBAC so Jenkins and ArgoCD can only do what they need
- Implement zero-trust NetworkPolicies that block all traffic by default, then open only what is required
- Set ResourceQuotas and LimitRanges to prevent a single runaway pod from starving the cluster
- Explain **why** each of these matters in a DevSecOps context -- not just how to apply the YAML

---

## Prerequisites

Before starting, confirm you have:

- [ ] `oc` CLI installed and in your PATH
- [ ] Cluster-admin access to an OpenShift 4.x cluster
- [ ] A terminal open with `oc login` completed

> **Environment variables:** Before running any commands, source the environment file:
> ```bash
> source ./env.sh
> ```
> This sets `$OC`, `$APPS_DOMAIN`, `$NS_TOOLS`, and all other cluster-specific variables used throughout this module. See `env.sh` for the full variable list.

```bash
# Verify you're logged in with cluster-admin
$OC whoami
# Expected: admin (or your cluster-admin username)

$OC auth can-i '*' '*' --all-namespaces
# Expected: yes
```

If `oc auth can-i` returns `no`, stop here. You need cluster-admin for this module because we are creating ClusterRoles and multiple namespaces -- operations that regular users cannot perform.

---

## Concepts: Why This Matters (10 min)

### The Problem

Imagine you deploy Jenkins, SonarQube, GitLab, ArgoCD, and your application all into the `default` namespace. Everything works on day one. Then:

- A Jenkins agent pod goes haywire and consumes 32 GB of RAM. SonarQube gets OOM-killed.
- A developer accidentally `oc delete deployment --all` in the wrong namespace. Jenkins is gone.
- An attacker compromises your application pod and can now talk to Jenkins, read SonarQube tokens, and exfiltrate data from every service on the flat network.

None of these are hypothetical. They happen in production. The fix is not "be more careful" -- it is **structural isolation** built into the platform.

### The Four Layers of Isolation

OpenShift gives you four mechanisms that work together. Think of them as concentric walls around your workloads:

```
┌─────────────────────────────────────────────────────────────────────┐
│  Layer 1: NAMESPACES                                                │
│  "Which rooms exist in the building?"                               │
│                                                                     │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐              │
│  │ devsecops-   │  │ devsecops-   │  │ sampleapi-   │              │
│  │ tools        │  │ gitlab       │  │ dev          │   ...        │
│  │ (Jenkins,    │  │ (GitLab CE,  │  │ (App DEV     │              │
│  │  SonarQube)  │  │  PostgreSQL) │  │  environment)│              │
│  └──────────────┘  └──────────────┘  └──────────────┘              │
│                                                                     │
├─────────────────────────────────────────────────────────────────────┤
│  Layer 2: RBAC (ServiceAccounts + Roles + RoleBindings)             │
│  "Who is allowed to do what, in which room?"                        │
│                                                                     │
│  jenkins-sa ──can──▸ create pods in devsecops-tools                 │
│  jenkins-sa ──can──▸ push images to sampleapi-dev                   │
│  jenkins-sa ──CANNOT──▸ delete deployments in sampleapi-prod        │
│                                                                     │
│  argocd-sa ──can──▸ deploy to sampleapi-dev/sit/uat/prod            │
│  argocd-sa ──CANNOT──▸ create pods in devsecops-tools               │
│                                                                     │
├─────────────────────────────────────────────────────────────────────┤
│  Layer 3: NETWORK POLICIES                                          │
│  "Who is allowed to talk to whom?"                                  │
│                                                                     │
│  Default: DENY ALL inbound traffic                                  │
│  Then: allow OpenShift Router → app pods (so Routes work)           │
│  Then: allow Prometheus → app pods (so monitoring works)            │
│  Then: allow same-namespace pods → each other (so app → DB works)   │
│  Then: allow ArgoCD → app pods (so health checks work)              │
│                                                                     │
├─────────────────────────────────────────────────────────────────────┤
│  Layer 4: RESOURCE QUOTAS + LIMIT RANGES                            │
│  "How much of the building's resources can each room use?"          │
│                                                                     │
│  sampleapi-dev: max 6 CPU, 12Gi RAM, 15 pods                       │
│  sampleapi-prod: max 12 CPU, 24Gi RAM, 25 pods                     │
│  Every container gets a default limit even if dev forgets to set one│
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

### Our Namespace Architecture

Here is the full layout we are building. Every namespace has a specific purpose, and nothing shares a namespace unless it has to:

```
                    ┌──────────────────────────────────┐
                    │         OpenShift Cluster         │
                    └──────────────────────────────────┘
                                    │
          ┌─────────────────────────┼─────────────────────────┐
          │                         │                         │
   ┌──────▼──────┐          ┌──────▼──────┐          ┌───────▼───────┐
   │ devsecops-  │          │ devsecops-  │          │   stackrox    │
   │ tools       │          │ gitlab      │          │ (ACS operator │
   │             │          │             │          │  manages this)│
   │ - Jenkins   │◀─webhook─│ - GitLab CE │          │ - Central     │
   │ - SonarQube │          │ - PostgreSQL│          │ - Scanner     │
   │             │          │ - Redis     │          │ - Sensor      │
   └──────┬──────┘          └─────────────┘          └───────────────┘
          │
          │  pushes images / syncs deployments
          │
   ┌──────▼──────────────────────────────────────────────────────────┐
   │                    Application Environments                      │
   │                                                                  │
   │  ┌────────────┐  ┌────────────┐  ┌────────────┐  ┌────────────┐│
   │  │sampleapi-  │  │sampleapi-  │  │sampleapi-  │  │sampleapi-  ││
   │  │dev         │  │sit         │  │uat         │  │prod        ││
   │  │            │  │            │  │            │  │            ││
   │  │ 6CPU /12Gi │  │ 8CPU /16Gi │  │ 8CPU /16Gi │  │12CPU /24Gi ││
   │  │ auto-sync  │  │ manual     │  │ manual     │  │ manual     ││
   │  └────────────┘  └────────────┘  └────────────┘  └────────────┘│
   └──────────────────────────────────────────────────────────────────┘
```

Why separate `devsecops-tools` from `devsecops-gitlab`? Because GitLab CE is a large, stateful application that consumes significant resources. If it shares a namespace (and quota) with Jenkins, a GitLab memory spike could prevent Jenkins agent pods from scheduling. Separate namespaces mean separate quotas, separate network policies, and separate blast radii.

Why four application namespaces instead of one? Because each environment has different resource limits, different RBAC (who can approve a deployment), different NetworkPolicies, and a different ArgoCD sync policy. The namespace IS the security boundary.

> **Note:** The `stackrox` namespace is managed by the ACS operator. We do not create it manually -- the operator handles its lifecycle. We include it in our architecture diagrams for completeness, but you will not find it in our `namespaces.yaml`.

---

## Step 1: Create the Namespace Layout (10 min)

**Goal:** Get all six namespaces created so you can see them in the OpenShift console within five minutes.

### Why Namespaces First?

Every other resource in this module -- ServiceAccounts, RoleBindings, NetworkPolicies, Quotas -- lives inside a namespace. If the namespaces do not exist, nothing else can be applied. This is always step one.

### The YAML

Create a file called `namespaces.yaml`. Notice the labels -- they are not decorative. We will use them later in NetworkPolicies to select entire namespaces by purpose:

```yaml
# infra/phase1/namespaces.yaml
# All namespaces for the DevSecOps workflow with team labels
# Apply first: oc apply -f namespaces.yaml
---
apiVersion: v1
kind: Namespace
metadata:
  name: devsecops-tools
  labels:
    team: devsecops                    # ← Used by: oc get ns -l team=devsecops
    purpose: ci-cd-tools               # ← Used by: NetworkPolicy selectors
    # Jenkins and SonarQube run here
  annotations:
    openshift.io/description: "CI/CD tools — Jenkins, SonarQube"
---
apiVersion: v1
kind: Namespace
metadata:
  name: devsecops-gitlab
  labels:
    team: devsecops
    purpose: source-control            # ← NetworkPolicy: tools-egress allows traffic FROM this label
    # GitLab CE, PostgreSQL, Redis
  annotations:
    openshift.io/description: "GitLab CE source control"
---
# stackrox namespace is managed by the ACS operator — created in Phase 5
# Listed here for documentation only; do NOT apply if operator manages it
# apiVersion: v1
# kind: Namespace
# metadata:
#   name: stackrox
---
apiVersion: v1
kind: Namespace
metadata:
  name: sampleapi-dev
  labels:
    team: devsecops
    purpose: application
    environment: dev                   # ← Distinguishes DEV from SIT/UAT/PROD
    app: sampleapi                     # ← Links namespace to the application it serves
  annotations:
    openshift.io/description: "SampleApi DEV environment"
---
apiVersion: v1
kind: Namespace
metadata:
  name: sampleapi-sit
  labels:
    team: devsecops
    purpose: application
    environment: sit
    app: sampleapi
  annotations:
    openshift.io/description: "SampleApi SIT environment"
---
apiVersion: v1
kind: Namespace
metadata:
  name: sampleapi-uat
  labels:
    team: devsecops
    purpose: application
    environment: uat
    app: sampleapi
  annotations:
    openshift.io/description: "SampleApi UAT environment"
---
apiVersion: v1
kind: Namespace
metadata:
  name: sampleapi-prod
  labels:
    team: devsecops
    purpose: application
    environment: production            # ← "production" not "prod" — be explicit in labels
    app: sampleapi
  annotations:
    openshift.io/description: "SampleApi PRODUCTION environment"
```

### Apply It

```bash
$OC apply -f infra/phase1/namespaces.yaml
```

Expected output:

```
namespace/devsecops-tools created
namespace/devsecops-gitlab created
namespace/sampleapi-dev created
namespace/sampleapi-sit created
namespace/sampleapi-uat created
namespace/sampleapi-prod created
```

### Verify

```bash
# List only OUR namespaces (the team label is the filter)
$OC get ns -l team=devsecops
```

Expected output:

```
NAME               STATUS   AGE
devsecops-gitlab   Active   4d
devsecops-tools    Active   4d
sampleapi-dev      Active   4d
sampleapi-prod     Active   4d
sampleapi-sit      Active   4d
sampleapi-uat      Active   4d
```

> **Note:** You will NOT see `rhacs-operator` or `stackrox` namespaces in this output. Those are created by the ACS operator (Phase 5) and do not carry the `team=devsecops` label since they are operator-managed, not part of our `namespaces.yaml`.

```bash
# Verify labels are present (we'll need these for NetworkPolicies later)
$OC get ns sampleapi-dev --show-labels
```

Expected: you should see `app=sampleapi,environment=dev,purpose=application,team=devsecops` in the labels.

> **Tip:** Open the OpenShift web console and navigate to **Administration > Namespaces**. Filter by label `team=devsecops`. You should see all six namespaces. This is a good sanity check that your cluster access is working correctly.

---

## Step 2: Create ServiceAccounts with Least-Privilege (10 min)

### Why Separate ServiceAccounts?

Every pod in OpenShift runs as a ServiceAccount. If you do not specify one, the pod uses `default` -- which is the cluster equivalent of sharing one password for the entire team. The problem:

- Jenkins needs to create pods (agent containers), push images, and trigger builds.
- Your application just needs to run and maybe pull images.
- ArgoCD needs to create/update deployments in multiple namespaces.

If they all use `default`, they all get the same permissions. An attacker who compromises your application pod could then create new pods, push images, or modify deployments. Separate ServiceAccounts mean separate permission sets.

### The YAML

```yaml
# infra/phase1/serviceaccounts.yaml
# Service accounts for Jenkins (CI/CD) and application workloads (per env)
# Follows least-privilege: separate SAs for different responsibilities
---
# Jenkins service account in the tools namespace
# Used by Jenkins controller and agent pods for pipeline execution
apiVersion: v1
kind: ServiceAccount
metadata:
  name: jenkins-sa
  namespace: devsecops-tools            # ← Lives where Jenkins runs
  labels:
    team: devsecops
    component: jenkins
---
# Application service account for DEV environment
apiVersion: v1
kind: ServiceAccount
metadata:
  name: sampleapi-sa
  namespace: sampleapi-dev              # ← Same SA name, different namespace = different identity
  labels:
    team: devsecops
    app: sampleapi
    environment: dev
---
# Application service account for SIT environment
apiVersion: v1
kind: ServiceAccount
metadata:
  name: sampleapi-sa
  namespace: sampleapi-sit
  labels:
    team: devsecops
    app: sampleapi
    environment: sit
---
# Application service account for UAT environment
apiVersion: v1
kind: ServiceAccount
metadata:
  name: sampleapi-sa
  namespace: sampleapi-uat
  labels:
    team: devsecops
    app: sampleapi
    environment: uat
---
# Application service account for PROD environment
apiVersion: v1
kind: ServiceAccount
metadata:
  name: sampleapi-sa
  namespace: sampleapi-prod
  labels:
    team: devsecops
    app: sampleapi
    environment: production
```

Notice that `sampleapi-sa` appears four times with the same `name` but in four different `namespace` values. In Kubernetes, a ServiceAccount's identity is `namespace + name`. So `sampleapi-sa` in `sampleapi-dev` is a completely different identity from `sampleapi-sa` in `sampleapi-prod`. This lets you grant different permissions per environment -- for example, the prod SA might have stricter image pull policies.

> **Warning:** We do NOT create a ServiceAccount for ArgoCD here. The OpenShift GitOps Operator creates its own SA (`openshift-gitops-argocd-application-controller`) when you install it. We will reference that SA in our RoleBindings in Step 4. Creating a duplicate would cause confusion about which SA has which permissions.

### Apply It

```bash
$OC apply -f infra/phase1/serviceaccounts.yaml
```

### Verify

```bash
# Jenkins SA exists in tools namespace
$OC get sa jenkins-sa -n $NS_TOOLS
# Expected output:
#   NAME         SECRETS   AGE
#   jenkins-sa   0         4d

# App SAs exist in each environment namespace
for NS in $NS_DEV $NS_SIT $NS_UAT $NS_PROD; do
  echo -n "$NS: "
  $OC get sa sampleapi-sa -n $NS -o name 2>/dev/null || echo "MISSING!"
done
```

Expected output:

```
sampleapi-dev: serviceaccount/sampleapi-sa
sampleapi-sit: serviceaccount/sampleapi-sa
sampleapi-uat: serviceaccount/sampleapi-sa
sampleapi-prod: serviceaccount/sampleapi-sa
```

---

## Step 3: Define ClusterRoles for Cross-Namespace Access (10 min)

### Why ClusterRoles Instead of Roles?

A `Role` is scoped to a single namespace. A `ClusterRole` is a template that can be reused across multiple namespaces via `RoleBinding`. Here is the key distinction:

- **ClusterRole** = "what actions are allowed" (the permission template)
- **RoleBinding** = "who gets those actions, in which namespace" (the assignment)

We define two ClusterRoles:

1. **`argocd-deployer`** -- ArgoCD needs to create/update Deployments, Services, Routes, ConfigMaps, Secrets, and other resources in each application namespace.
2. **`jenkins-deployer`** -- Jenkins needs to create agent pods, manage builds, push images, and check deployment status.

The reason these are ClusterRoles (not per-namespace Roles) is that we want to reuse the same permission set in four different namespaces (dev/sit/uat/prod) without duplicating the definition. The ClusterRole says "these actions are possible." The RoleBinding (Step 4) says "for this SA, in this namespace."

### The YAML

```yaml
# infra/phase1/clusterroles.yaml
# ClusterRoles for ArgoCD deployer and Jenkins deployer
# These define WHAT actions are allowed; RoleBindings define WHERE
---
# ArgoCD deployer role — used by ArgoCD to manage application resources
# in target namespaces (sampleapi-dev, sampleapi-sit, etc.)
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: argocd-deployer
  labels:
    team: devsecops
rules:
  # Core resources for application deployment
  - apiGroups: [""]
    resources:
      - pods
      - services
      - endpoints
      - persistentvolumeclaims
      - configmaps
      - secrets
      - serviceaccounts
    verbs: ["get", "list", "watch", "create", "update", "patch", "delete"]
  # Deployment, ReplicaSet, StatefulSet management
  - apiGroups: ["apps"]
    resources:
      - deployments
      - replicasets
      - statefulsets
    verbs: ["get", "list", "watch", "create", "update", "patch", "delete"]
  # OpenShift Routes
  - apiGroups: ["route.openshift.io"]
    resources:
      - routes
    verbs: ["get", "list", "watch", "create", "update", "patch", "delete"]
  # Network policies
  - apiGroups: ["networking.k8s.io"]
    resources:
      - networkpolicies
    verbs: ["get", "list", "watch", "create", "update", "patch", "delete"]
  # Autoscaling
  - apiGroups: ["autoscaling"]
    resources:
      - horizontalpodautoscalers
    verbs: ["get", "list", "watch", "create", "update", "patch", "delete"]
  # Pod disruption budgets
  - apiGroups: ["policy"]
    resources:
      - poddisruptionbudgets
    verbs: ["get", "list", "watch", "create", "update", "patch", "delete"]
  # Events (read-only for status)
  - apiGroups: [""]
    resources:
      - events
    verbs: ["get", "list", "watch"]          # ← Read-only: ArgoCD monitors events, never creates them
---
# Jenkins deployer role — used by Jenkins SA to interact with
# OpenShift resources during pipeline execution
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: jenkins-deployer
  labels:
    team: devsecops
rules:
  # Pod management for ephemeral agent pods
  - apiGroups: [""]
    resources:
      - pods
      - pods/exec
      - pods/log
    verbs: ["get", "list", "watch", "create", "update", "patch", "delete"]
  # ConfigMaps and Secrets (read for pipeline config)
  - apiGroups: [""]
    resources:
      - configmaps
      - secrets
    verbs: ["get", "list", "watch"]          # ← Read-only: Jenkins reads config, doesn't write it
  # Events for debugging
  - apiGroups: [""]
    resources:
      - events
    verbs: ["get", "list", "watch"]
  # BuildConfigs for building agent images
  - apiGroups: ["build.openshift.io"]
    resources:
      - buildconfigs
      - builds
    verbs: ["get", "list", "watch", "create", "update", "patch"]
  # ImageStreams for pushing/pulling images
  - apiGroups: ["image.openshift.io"]
    resources:
      - imagestreams
      - imagestreamtags
    verbs: ["get", "list", "watch", "create", "update", "patch"]
  # Deployments (to check status)
  - apiGroups: ["apps"]
    resources:
      - deployments
      - replicasets
    verbs: ["get", "list", "watch"]          # ← Read-only: Jenkins checks status, ArgoCD does deploys
  # Routes (for post-deploy health verification)
  - apiGroups: ["route.openshift.io"]
    resources:
      - routes
    verbs: ["get", "list", "watch"]
  # Services (for post-deploy verification)
  - apiGroups: [""]
    resources:
      - services
    verbs: ["get", "list", "watch"]
```

Take a moment to compare the two roles. Notice the difference in verbs:

| Resource | ArgoCD | Jenkins |
|----------|--------|---------|
| Deployments | create, update, patch, delete | get, list, watch (read-only) |
| Secrets | create, update, patch, delete | get, list, watch (read-only) |
| Pods | full CRUD | full CRUD (needs to create agent pods) |
| BuildConfigs | none | create, update, patch |
| Routes | full CRUD | read-only |

This is least-privilege in action. Jenkins does not need to create Deployments because ArgoCD handles all deployments via GitOps. ArgoCD does not need to manage BuildConfigs because it never builds images. Each tool gets exactly the permissions it needs and nothing more.

### Apply It

```bash
$OC apply -f infra/phase1/clusterroles.yaml
```

### Verify

```bash
# Verify both ClusterRoles exist
$OC get clusterrole argocd-deployer jenkins-deployer
```

Expected output:

```
NAME               CREATED AT
argocd-deployer    2026-03-08T...
jenkins-deployer   2026-03-08T...
```

```bash
# Inspect what ArgoCD can do (human-readable)
$OC describe clusterrole argocd-deployer | head -30
```

You should see the list of resources and verbs matching what we defined above.

---

## Step 4: Bind Roles to ServiceAccounts (10 min)

### Why RoleBindings Are the Glue

You now have two things that are not yet connected:

1. **ServiceAccounts** (who) -- `jenkins-sa`, `sampleapi-sa`, and the ArgoCD SA (created by operator)
2. **ClusterRoles** (what) -- `argocd-deployer`, `jenkins-deployer`

A `RoleBinding` connects a specific subject (SA) to a specific role, scoped to a specific namespace. Without the binding, the SA has no permissions at all.

Here is the mental model:

```
    WHO                    WHAT                   WHERE
    ─────                  ─────                  ─────
    jenkins-sa        ──▸  jenkins-deployer  ──▸  devsecops-tools
    jenkins-sa        ──▸  jenkins-deployer  ──▸  sampleapi-dev
    jenkins-sa        ──▸  jenkins-deployer  ──▸  sampleapi-sit
    jenkins-sa        ──▸  jenkins-deployer  ──▸  sampleapi-uat
    jenkins-sa        ──▸  jenkins-deployer  ──▸  sampleapi-prod

    argocd-app-ctrl   ──▸  argocd-deployer   ──▸  sampleapi-dev
    argocd-app-ctrl   ──▸  argocd-deployer   ──▸  sampleapi-sit
    argocd-app-ctrl   ──▸  argocd-deployer   ──▸  sampleapi-uat
    argocd-app-ctrl   ──▸  argocd-deployer   ──▸  sampleapi-prod

    sampleapi-sa(sit) ──▸  system:image-puller ──▸ sampleapi-dev  (pull images from DEV registry)
    sampleapi-sa(uat) ──▸  system:image-puller ──▸ sampleapi-dev
    sampleapi-sa(prod)──▸  system:image-puller ──▸ sampleapi-dev
```

Notice the image-puller bindings at the bottom. Images are built once and stored in `sampleapi-dev`. For SIT/UAT/PROD to pull those images, their ServiceAccounts need `system:image-puller` in the `sampleapi-dev` namespace. Without this, you get `ImagePullBackOff` errors during promotion.

### The YAML

This is the longest file in Phase 1 because we need one binding per SA-per-namespace combination. There is no shortcut -- RBAC is explicit by design:

```yaml
# infra/phase1/rolebindings.yaml
# Per-namespace RoleBindings that bind ClusterRoles to ServiceAccounts
# ArgoCD SA gets deployer rights in each app namespace
# Jenkins SA gets deployer rights in tools namespace + image push in app namespaces
---
# --- ArgoCD deployer bindings (one per app namespace) ---

# ArgoCD → sampleapi-dev (auto-sync)
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: argocd-deployer-binding
  namespace: sampleapi-dev               # ← WHERE this binding takes effect
  labels:
    team: devsecops
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: argocd-deployer                  # ← WHAT permissions are granted
subjects:
  # OpenShift GitOps operator SA in openshift-gitops namespace
  - kind: ServiceAccount
    name: openshift-gitops-argocd-application-controller  # ← WHO gets the permissions
    namespace: openshift-gitops          # ← WHERE the SA lives (different from binding namespace!)
---
# ArgoCD → sampleapi-sit (manual sync)
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: argocd-deployer-binding
  namespace: sampleapi-sit
  labels:
    team: devsecops
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: argocd-deployer
subjects:
  - kind: ServiceAccount
    name: openshift-gitops-argocd-application-controller
    namespace: openshift-gitops
---
# ArgoCD → sampleapi-uat (manual sync)
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: argocd-deployer-binding
  namespace: sampleapi-uat
  labels:
    team: devsecops
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: argocd-deployer
subjects:
  - kind: ServiceAccount
    name: openshift-gitops-argocd-application-controller
    namespace: openshift-gitops
---
# ArgoCD → sampleapi-prod (manual sync)
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: argocd-deployer-binding
  namespace: sampleapi-prod
  labels:
    team: devsecops
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: argocd-deployer
subjects:
  - kind: ServiceAccount
    name: openshift-gitops-argocd-application-controller
    namespace: openshift-gitops
---
# --- Jenkins deployer bindings ---

# Jenkins SA → devsecops-tools (manage agent pods, builds)
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: jenkins-deployer-binding
  namespace: devsecops-tools             # ← Jenkins needs to create agent pods HERE
  labels:
    team: devsecops
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: jenkins-deployer
subjects:
  - kind: ServiceAccount
    name: jenkins-sa
    namespace: devsecops-tools
---
# Jenkins SA → sampleapi-dev (push images, check deployments)
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: jenkins-deployer-binding
  namespace: sampleapi-dev
  labels:
    team: devsecops
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: jenkins-deployer
subjects:
  - kind: ServiceAccount
    name: jenkins-sa
    namespace: devsecops-tools           # ← SA lives in tools, but binding grants access in dev
---
# Jenkins SA → sampleapi-sit
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: jenkins-deployer-binding
  namespace: sampleapi-sit
  labels:
    team: devsecops
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: jenkins-deployer
subjects:
  - kind: ServiceAccount
    name: jenkins-sa
    namespace: devsecops-tools
---
# Jenkins SA → sampleapi-uat
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: jenkins-deployer-binding
  namespace: sampleapi-uat
  labels:
    team: devsecops
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: jenkins-deployer
subjects:
  - kind: ServiceAccount
    name: jenkins-sa
    namespace: devsecops-tools
---
# Jenkins SA → sampleapi-prod
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: jenkins-deployer-binding
  namespace: sampleapi-prod
  labels:
    team: devsecops
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: jenkins-deployer
subjects:
  - kind: ServiceAccount
    name: jenkins-sa
    namespace: devsecops-tools
---
# --- Image puller bindings ---
# Allow app namespaces to pull images from the internal registry
# (needed when images are stored in sampleapi-dev and promoted)

# system:image-puller for sampleapi-sit → sampleapi-dev images
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: image-puller-sit
  namespace: sampleapi-dev               # ← Binding is in DEV (where images live)
  labels:
    team: devsecops
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: system:image-puller              # ← Built-in OCP role for pulling images
subjects:
  - kind: ServiceAccount
    name: sampleapi-sa
    namespace: sampleapi-sit             # ← SIT SA gets pull access to DEV images
  - kind: ServiceAccount
    name: default
    namespace: sampleapi-sit             # ← default SA too (fallback if pod doesn't specify SA)
---
# system:image-puller for sampleapi-uat → sampleapi-dev images
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: image-puller-uat
  namespace: sampleapi-dev
  labels:
    team: devsecops
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: system:image-puller
subjects:
  - kind: ServiceAccount
    name: sampleapi-sa
    namespace: sampleapi-uat
  - kind: ServiceAccount
    name: default
    namespace: sampleapi-uat
---
# system:image-puller for sampleapi-prod → sampleapi-dev images
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: image-puller-prod
  namespace: sampleapi-dev
  labels:
    team: devsecops
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: system:image-puller
subjects:
  - kind: ServiceAccount
    name: sampleapi-sa
    namespace: sampleapi-prod
  - kind: ServiceAccount
    name: default
    namespace: sampleapi-prod
```

### Apply It

```bash
$OC apply -f infra/phase1/rolebindings.yaml
```

### Verify

This is the most important verification in the module. We use `oc auth can-i` to test whether a specific SA actually has the permissions we expect:

```bash
# ── Test: Can Jenkins create pods in devsecops-tools? ──
$OC auth can-i create pods -n $NS_TOOLS \
  --as=system:serviceaccount:${NS_TOOLS}:jenkins-sa
# Expected: yes

# ── Test: Can Jenkins create pods in sampleapi-dev? ──
$OC auth can-i create pods -n $NS_DEV \
  --as=system:serviceaccount:${NS_TOOLS}:jenkins-sa
# Expected: yes

# ── Test: Can Jenkins DELETE deployments in sampleapi-prod? ──
$OC auth can-i delete deployments -n $NS_PROD \
  --as=system:serviceaccount:${NS_TOOLS}:jenkins-sa
# Expected: no  ← This is correct! Jenkins should NOT delete deployments.

# ── Test: Can the app SA in SIT pull images from DEV? ──
$OC auth can-i get imagestreams/layers -n $NS_DEV \
  --as=system:serviceaccount:${NS_SIT}:sampleapi-sa
# Expected: yes
```

> **Tip:** The `--as=system:serviceaccount:<namespace>:<name>` flag is your best friend when debugging RBAC. Use it before deploying anything to confirm permissions are wired correctly. It is much faster than deploying a pod and watching it fail.

---

## Step 5: Lock Down with NetworkPolicies (15 min)

### Why Zero-Trust Networking?

By default, every pod in an OpenShift cluster can talk to every other pod. This is convenient for development but dangerous in production. If an attacker compromises your application pod, they can:

- Connect to Jenkins and trigger builds
- Connect to SonarQube and exfiltrate scan results
- Connect to GitLab and steal source code
- Connect to the internal registry and push malicious images

NetworkPolicies implement the principle of zero-trust: **deny everything by default, then explicitly allow only the traffic flows that are required.**

### The Strategy

We apply policies in this order. The order matters conceptually (deny first, then allow), though Kubernetes applies them all simultaneously:

```
Step 5a: Default deny ALL ingress to app namespaces
Step 5b: Allow OpenShift Router → app pods (so Routes work)
Step 5c: Allow Prometheus → app pods (so monitoring works)
Step 5d: Allow same-namespace traffic (so app → database works)
Step 5e: Allow ArgoCD → app pods (so health checks work)
Step 5f: Allow all egress from tools namespace (Jenkins needs to reach everything)
```

### Step 5a: Default Deny All Ingress

This is the foundation of zero-trust. Once applied, no traffic can reach any pod in the namespace unless another NetworkPolicy explicitly allows it:

```yaml
# infra/phase1/networkpolicies/default-deny.yaml
# Default deny-all ingress for application namespaces
# Zero-trust baseline: all traffic is blocked unless explicitly allowed
# Applied to: sampleapi-dev, sampleapi-sit, sampleapi-uat, sampleapi-prod
---
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: default-deny-ingress
  namespace: sampleapi-dev
  labels:
    team: devsecops
    policy: default-deny
spec:
  podSelector: {}          # ← Empty selector = applies to ALL pods in namespace
  policyTypes:
    - Ingress
  # No ingress rules = deny all ingress traffic
---
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: default-deny-ingress
  namespace: sampleapi-sit
  labels:
    team: devsecops
    policy: default-deny
spec:
  podSelector: {}
  policyTypes:
    - Ingress
---
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: default-deny-ingress
  namespace: sampleapi-uat
  labels:
    team: devsecops
    policy: default-deny
spec:
  podSelector: {}
  policyTypes:
    - Ingress
---
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: default-deny-ingress
  namespace: sampleapi-prod
  labels:
    team: devsecops
    policy: default-deny
spec:
  podSelector: {}
  policyTypes:
    - Ingress
```

The critical line is `podSelector: {}`. An empty selector matches every pod. Combined with `policyTypes: [Ingress]` and zero ingress rules, this means: "select all pods, apply an ingress policy, allow nothing."

> **Warning:** After applying `default-deny`, your application Routes will stop working. That is expected and correct. We will fix it in Step 5b by allowing traffic from the OpenShift Router. Do not panic when your application becomes unreachable -- that is the policy doing its job.

### Step 5b: Allow OpenShift Router

OpenShift Routes work by having the HAProxy router (in `openshift-ingress` namespace) forward traffic to your pods. We need to allow that specific traffic flow:

```yaml
# infra/phase1/networkpolicies/allow-ingress-router.yaml
# Allow ingress from OpenShift Router (HAProxy) to application pods
# Required for external HTTPS traffic via OpenShift Routes
# The router runs in openshift-ingress namespace with label
#   policy-group.network.openshift.io/ingress=""
---
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: allow-from-openshift-ingress
  namespace: sampleapi-dev
  labels:
    team: devsecops
    policy: allow-ingress-router
spec:
  podSelector:
    matchLabels:
      app: sampleapi       # ← Only allow traffic to pods with this label
  ingress:
    - from:
        - namespaceSelector:
            matchLabels:
              # OCP router namespace label (set by default on openshift-ingress)
              policy-group.network.openshift.io/ingress: ""   # ← THIS IS THE KEY LINE
  policyTypes:
    - Ingress
---
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: allow-from-openshift-ingress
  namespace: sampleapi-sit
  labels:
    team: devsecops
    policy: allow-ingress-router
spec:
  podSelector:
    matchLabels:
      app: sampleapi
  ingress:
    - from:
        - namespaceSelector:
            matchLabels:
              policy-group.network.openshift.io/ingress: ""
  policyTypes:
    - Ingress
---
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: allow-from-openshift-ingress
  namespace: sampleapi-uat
  labels:
    team: devsecops
    policy: allow-ingress-router
spec:
  podSelector:
    matchLabels:
      app: sampleapi
  ingress:
    - from:
        - namespaceSelector:
            matchLabels:
              policy-group.network.openshift.io/ingress: ""
  policyTypes:
    - Ingress
---
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: allow-from-openshift-ingress
  namespace: sampleapi-prod
  labels:
    team: devsecops
    policy: allow-ingress-router
spec:
  podSelector:
    matchLabels:
      app: sampleapi
  ingress:
    - from:
        - namespaceSelector:
            matchLabels:
              policy-group.network.openshift.io/ingress: ""
  policyTypes:
    - Ingress
```

The label `policy-group.network.openshift.io/ingress: ""` is a well-known label that OpenShift automatically sets on the `openshift-ingress` namespace. You do not need to add it yourself. This is the standard way to allow router traffic in NetworkPolicies on OpenShift.

### Step 5c: Allow Prometheus Monitoring

Without this, Prometheus cannot scrape your application metrics, and your dashboards will be empty:

```yaml
# infra/phase1/networkpolicies/allow-monitoring.yaml
# Allow Prometheus (user workload monitoring) to scrape app pods
# OpenShift user workload monitoring runs in openshift-user-workload-monitoring namespace
# Also allow openshift-monitoring for platform-level scraping
---
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: allow-monitoring
  namespace: sampleapi-dev
  labels:
    team: devsecops
    policy: allow-monitoring
spec:
  podSelector:
    matchLabels:
      app: sampleapi
  ingress:
    - from:
        # User workload monitoring namespace
        - namespaceSelector:
            matchLabels:
              network.openshift.io/policy-group: monitoring  # ← OCP sets this on monitoring namespaces
      ports:
        - protocol: TCP
          port: 8080             # ← Only allow scraping on the app's metrics port
  policyTypes:
    - Ingress
---
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: allow-monitoring
  namespace: sampleapi-sit
  labels:
    team: devsecops
    policy: allow-monitoring
spec:
  podSelector:
    matchLabels:
      app: sampleapi
  ingress:
    - from:
        - namespaceSelector:
            matchLabels:
              network.openshift.io/policy-group: monitoring
      ports:
        - protocol: TCP
          port: 8080
  policyTypes:
    - Ingress
---
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: allow-monitoring
  namespace: sampleapi-uat
  labels:
    team: devsecops
    policy: allow-monitoring
spec:
  podSelector:
    matchLabels:
      app: sampleapi
  ingress:
    - from:
        - namespaceSelector:
            matchLabels:
              network.openshift.io/policy-group: monitoring
      ports:
        - protocol: TCP
          port: 8080
  policyTypes:
    - Ingress
---
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: allow-monitoring
  namespace: sampleapi-prod
  labels:
    team: devsecops
    policy: allow-monitoring
spec:
  podSelector:
    matchLabels:
      app: sampleapi
  ingress:
    - from:
        - namespaceSelector:
            matchLabels:
              network.openshift.io/policy-group: monitoring
      ports:
        - protocol: TCP
          port: 8080
  policyTypes:
    - Ingress
```

Notice we restrict monitoring to port 8080 specifically. Even Prometheus should not have access to every port on the pod.

### Step 5d: Allow Same-Namespace + ArgoCD Traffic

Pods within the same namespace need to communicate (for example, your app pod talking to a database pod in the same namespace). ArgoCD also needs to reach pods for health checks:

```yaml
# infra/phase1/networkpolicies/app-namespace.yaml
# Per-app-env network policies: allow inter-pod communication within namespace
# and allow ArgoCD to manage resources
---
# Allow pods within the same namespace to communicate (e.g., app → database)
# Applied to all 4 app namespaces
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: allow-same-namespace
  namespace: sampleapi-dev
  labels:
    team: devsecops
    policy: app-namespace
spec:
  podSelector: {}
  ingress:
    - from:
        - podSelector: {}    # ← Empty podSelector = any pod IN THE SAME NAMESPACE
  policyTypes:
    - Ingress
---
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: allow-same-namespace
  namespace: sampleapi-sit
  labels:
    team: devsecops
    policy: app-namespace
spec:
  podSelector: {}
  ingress:
    - from:
        - podSelector: {}
  policyTypes:
    - Ingress
---
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: allow-same-namespace
  namespace: sampleapi-uat
  labels:
    team: devsecops
    policy: app-namespace
spec:
  podSelector: {}
  ingress:
    - from:
        - podSelector: {}
  policyTypes:
    - Ingress
---
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: allow-same-namespace
  namespace: sampleapi-prod
  labels:
    team: devsecops
    policy: app-namespace
spec:
  podSelector: {}
  ingress:
    - from:
        - podSelector: {}
  policyTypes:
    - Ingress
---
# Allow ArgoCD (openshift-gitops namespace) to reach app namespaces
# for health checks and resource management
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: allow-from-argocd
  namespace: sampleapi-dev
  labels:
    team: devsecops
    policy: app-namespace
spec:
  podSelector: {}
  ingress:
    - from:
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: openshift-gitops  # ← Select by namespace name directly
  policyTypes:
    - Ingress
---
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: allow-from-argocd
  namespace: sampleapi-sit
  labels:
    team: devsecops
    policy: app-namespace
spec:
  podSelector: {}
  ingress:
    - from:
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: openshift-gitops
  policyTypes:
    - Ingress
---
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: allow-from-argocd
  namespace: sampleapi-uat
  labels:
    team: devsecops
    policy: app-namespace
spec:
  podSelector: {}
  ingress:
    - from:
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: openshift-gitops
  policyTypes:
    - Ingress
---
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: allow-from-argocd
  namespace: sampleapi-prod
  labels:
    team: devsecops
    policy: app-namespace
spec:
  podSelector: {}
  ingress:
    - from:
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: openshift-gitops
  policyTypes:
    - Ingress
```

The label `kubernetes.io/metadata.name` is automatically set by Kubernetes on every namespace to match its name. It is the most reliable way to select a namespace by name in a NetworkPolicy.

### Step 5e: Tools Namespace Egress + Ingress

Jenkins needs outbound connectivity to many destinations: GitLab (clone repos), SonarQube (submit scans), ACS (image checks), external package registries (NuGet, npm), and the internal image registry. Rather than listing every destination, we allow all egress from the tools namespace. We also need to allow inbound traffic from the OpenShift router (for the Jenkins/SonarQube UI) and from GitLab (for webhooks):

```yaml
# infra/phase1/networkpolicies/tools-egress.yaml
# Egress policy for Jenkins in devsecops-tools namespace
# Jenkins needs to reach: GitLab, SonarQube, ACS, ArgoCD, image registry,
# external package repos (NuGet, npm), and app namespaces
# Allow all egress from tools namespace since Jenkins connects to many destinations
---
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: tools-allow-all-egress
  namespace: devsecops-tools
  labels:
    team: devsecops
    policy: tools-egress
spec:
  podSelector: {}            # All pods in tools namespace (Jenkins controller + agents)
  policyTypes:
    - Egress
  egress:
    - {}                     # ← Allow all egress — Jenkins must reach external registries, APIs, and internal services
---
# Also allow ingress to tools namespace from OpenShift router (for Jenkins/SonarQube UI)
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: allow-ingress-to-tools
  namespace: devsecops-tools
  labels:
    team: devsecops
    policy: tools-ingress
spec:
  podSelector: {}
  ingress:
    # From OpenShift router (external access to Jenkins/SonarQube UI)
    - from:
        - namespaceSelector:
            matchLabels:
              policy-group.network.openshift.io/ingress: ""
    # From GitLab (webhooks to Jenkins)
    - from:
        - namespaceSelector:
            matchLabels:
              purpose: source-control    # ← This matches the label we set on devsecops-gitlab namespace
  policyTypes:
    - Ingress
```

> **Note:** We allow all egress from the tools namespace rather than enumerating every destination. This is a pragmatic decision. Jenkins connects to too many external endpoints (NuGet, npm registries, GitHub, container registries) to maintain an exhaustive allow-list. In a high-security environment, you might restrict this further using egress rules with CIDR blocks and port numbers.

### Apply All NetworkPolicies

```bash
$OC apply -f infra/phase1/networkpolicies/
```

Expected output:

```
networkpolicy.networking.k8s.io/default-deny-ingress created     (x4 namespaces)
networkpolicy.networking.k8s.io/allow-from-openshift-ingress created (x4)
networkpolicy.networking.k8s.io/allow-monitoring created          (x4)
networkpolicy.networking.k8s.io/allow-same-namespace created      (x4)
networkpolicy.networking.k8s.io/allow-from-argocd created         (x4)
networkpolicy.networking.k8s.io/tools-allow-all-egress created
networkpolicy.networking.k8s.io/allow-ingress-to-tools created
```

### Verify

```bash
# List policies in DEV — starts with 5 (deny + router + monitoring + same-ns + argocd),
# grows as you add services in later phases (Phase 17 adds inter-service policies)
$OC get netpol -n $NS_DEV
```

Expected output (after Phase 1 only — 5 policies):

```
NAME                           POD-SELECTOR    AGE
allow-from-argocd              <none>          10s
allow-from-openshift-ingress   app=sampleapi   10s
allow-monitoring               app=sampleapi   10s
allow-same-namespace           <none>          10s
default-deny-ingress           <none>          10s
```

After all phases are deployed (Phase 17+), the real cluster shows 12 policies reflecting
the full multi-service architecture:

```
NAME                              POD-SELECTOR                                AGE
allow-app-to-notificationapi      app=notificationapi                         2d
allow-app-to-postgresql           app=postgresql                              2d
allow-app-to-redis                app=redis                                   2d
allow-from-argocd                 <none>                                      4d
allow-from-openshift-ingress      app=sampleapi                               4d
allow-monitoring                  app=sampleapi                               4d
allow-monitoring-newservices      app in (notificationapi,postgresql,redis)   2d
allow-router-to-notificationapi   app=notificationapi                         2d
allow-same-namespace              <none>                                      4d
default-deny-ingress              <none>                                      4d
deny-postgresql-egress            app=postgresql                              2d
deny-redis-egress                 app=redis                                   2d
```

```bash
# Verify tools namespace has its own policies
$OC get netpol -n $NS_TOOLS
```

Expected output:

```
NAME                      POD-SELECTOR   AGE
allow-ingress-to-tools    <none>         10s
tools-allow-all-egress    <none>         10s
```

```bash
# Check all 4 app namespaces have policies (5 after Phase 1, 12 after Phase 17)
for NS in $NS_DEV $NS_SIT $NS_UAT $NS_PROD; do
  echo -n "$NS: "
  $OC get netpol -n $NS --no-headers | wc -l
done
```

Expected (after Phase 1 — before additional services are added):

```
sampleapi-dev: 5
sampleapi-sit: 5
sampleapi-uat: 5
sampleapi-prod: 5
```

On the live cluster after all phases, each namespace has 12 policies (the additional 7
cover inter-service communication for PostgreSQL, Redis, and NotificationApi).

---

## Step 6: Set Resource Quotas and LimitRanges (10 min)

### Why Quotas and Limits?

Without quotas, a single namespace can consume the entire cluster's resources. A misconfigured Jenkins agent requesting 64 GB of RAM would prevent application pods from scheduling. Without limit ranges, a developer who forgets to set resource limits on their pod gets unlimited resources -- and the first time that pod has a memory leak, it takes down everything else on the node.

Quotas and LimitRanges work together:

- **ResourceQuota** = the total budget for the entire namespace (all pods combined)
- **LimitRange** = the default and maximum for any single container

Think of it like a corporate expense policy: the quota is the department's annual budget, and the limit range is the maximum any single employee can spend on one purchase.

### ResourceQuotas

```yaml
# infra/phase1/resourcequotas.yaml
# Per-namespace resource quotas to prevent resource exhaustion
# Quota limits: tools = 16 CPU/32Gi (increased for ZAP sidecar + concurrent builds),
# gitlab = 8 CPU/16Gi, dev = 6 CPU/12Gi, sit/uat = 8 CPU/16Gi, prod = 12 CPU/24Gi
# Multi-service: sampleapi + notificationapi + postgresql + redis per namespace
---
apiVersion: v1
kind: ResourceQuota
metadata:
  name: devsecops-tools-quota
  namespace: devsecops-tools
  labels:
    team: devsecops
spec:
  hard:
    requests.cpu: "8"              # ← Total CPU requests across ALL pods in namespace
    requests.memory: 16Gi
    limits.cpu: "16"               # ← Total CPU limits (can be higher than requests for bursting)
    limits.memory: 32Gi            # ← 32Gi because Jenkins + SonarQube + agent pods + ZAP sidecar
    pods: "20"                     # ← Max number of pods (Jenkins controller + agents + SonarQube)
    persistentvolumeclaims: "10"
---
apiVersion: v1
kind: ResourceQuota
metadata:
  name: devsecops-gitlab-quota
  namespace: devsecops-gitlab
  labels:
    team: devsecops
spec:
  hard:
    requests.cpu: "4"
    requests.memory: 8Gi
    limits.cpu: "8"
    limits.memory: 16Gi            # ← GitLab CE needs 12Gi+ to avoid OOM (exit code 137)
    pods: "10"
    persistentvolumeclaims: "5"
---
apiVersion: v1
kind: ResourceQuota
metadata:
  name: sampleapi-dev-quota
  namespace: sampleapi-dev
  labels:
    team: devsecops
spec:
  hard:
    requests.cpu: "3"
    requests.memory: 6Gi
    limits.cpu: "6"
    limits.memory: 12Gi            # ← 2 services (SampleApi + NotificationApi) + PG + Redis
    pods: "15"
    persistentvolumeclaims: "5"
---
apiVersion: v1
kind: ResourceQuota
metadata:
  name: sampleapi-sit-quota
  namespace: sampleapi-sit
  labels:
    team: devsecops
spec:
  hard:
    requests.cpu: "4"
    requests.memory: 8Gi
    limits.cpu: "8"
    limits.memory: 16Gi            # ← 2 services x 2 replicas + PG + Redis
    pods: "15"
    persistentvolumeclaims: "5"
---
apiVersion: v1
kind: ResourceQuota
metadata:
  name: sampleapi-uat-quota
  namespace: sampleapi-uat
  labels:
    team: devsecops
spec:
  hard:
    requests.cpu: "4"
    requests.memory: 8Gi
    limits.cpu: "8"
    limits.memory: 16Gi            # ← 2 services x 2 replicas + PG + Redis
    pods: "15"
    persistentvolumeclaims: "5"
---
apiVersion: v1
kind: ResourceQuota
metadata:
  name: sampleapi-prod-quota
  namespace: sampleapi-prod
  labels:
    team: devsecops
spec:
  hard:
    requests.cpu: "6"
    requests.memory: 12Gi
    limits.cpu: "12"
    limits.memory: 24Gi            # ← 2 services x 3 replicas + PG + Redis + rolling update headroom
    pods: "25"                     # ← More pods for higher replica count (3 replicas in prod)
    persistentvolumeclaims: "10"
```

Notice the asymmetry: `devsecops-tools` gets 16 CPU / 32Gi because it runs Jenkins (controller + multiple agent pods simultaneously), SonarQube, and SonarQube's PostgreSQL. We learned the hard way that the original 8 CPU / 16Gi was too small when the T3 (tag) pipeline spins up an agent pod with a ZAP DAST sidecar container alongside the running Jenkins controller and SonarQube.

### LimitRanges

```yaml
# infra/phase1/limitranges.yaml
# Default container resource limits and requests per namespace
# Ensures pods without explicit resource specs get sensible defaults
# and prevents any single container from consuming excessive resources
---
apiVersion: v1
kind: LimitRange
metadata:
  name: default-limits
  namespace: devsecops-tools
  labels:
    team: devsecops
spec:
  limits:
    - type: Container
      default:                         # ← Applied when pod spec has NO limits
        cpu: "1"
        memory: 2Gi
      defaultRequest:                  # ← Applied when pod spec has NO requests
        cpu: 100m
        memory: 256Mi
      max:                             # ← No single container can exceed this
        cpu: "4"
        memory: 8Gi
      min:                             # ← No container can request less than this
        cpu: 50m
        memory: 64Mi
---
apiVersion: v1
kind: LimitRange
metadata:
  name: default-limits
  namespace: devsecops-gitlab
  labels:
    team: devsecops
spec:
  limits:
    - type: Container
      default:
        cpu: "1"
        memory: 2Gi
      defaultRequest:
        cpu: 100m
        memory: 256Mi
      max:
        cpu: "4"
        memory: 8Gi
      min:
        cpu: 50m
        memory: 64Mi
---
apiVersion: v1
kind: LimitRange
metadata:
  name: default-limits
  namespace: sampleapi-dev
  labels:
    team: devsecops
spec:
  limits:
    - type: Container
      default:
        cpu: 500m                      # ← DEV gets smaller defaults than tools namespace
        memory: 512Mi
      defaultRequest:
        cpu: 100m
        memory: 128Mi
      max:
        cpu: "2"                       # ← Single container max is half the namespace quota
        memory: 4Gi
      min:
        cpu: 50m
        memory: 64Mi
---
apiVersion: v1
kind: LimitRange
metadata:
  name: default-limits
  namespace: sampleapi-sit
  labels:
    team: devsecops
spec:
  limits:
    - type: Container
      default:
        cpu: 500m
        memory: 512Mi
      defaultRequest:
        cpu: 100m
        memory: 128Mi
      max:
        cpu: "2"
        memory: 4Gi
      min:
        cpu: 50m
        memory: 64Mi
---
apiVersion: v1
kind: LimitRange
metadata:
  name: default-limits
  namespace: sampleapi-uat
  labels:
    team: devsecops
spec:
  limits:
    - type: Container
      default:
        cpu: 500m
        memory: 512Mi
      defaultRequest:
        cpu: 100m
        memory: 128Mi
      max:
        cpu: "2"
        memory: 4Gi
      min:
        cpu: 50m
        memory: 64Mi
---
apiVersion: v1
kind: LimitRange
metadata:
  name: default-limits
  namespace: sampleapi-prod
  labels:
    team: devsecops
spec:
  limits:
    - type: Container
      default:
        cpu: "1"                       # ← PROD gets higher defaults — production workloads need headroom
        memory: 1Gi
      defaultRequest:
        cpu: 200m
        memory: 256Mi
      max:
        cpu: "4"
        memory: 8Gi
      min:
        cpu: 50m
        memory: 64Mi
```

The relationship between `default`, `defaultRequest`, `max`, and `min`:

```
     min ──────── defaultRequest ──────── default ──────── max
     50m CPU      100m CPU               500m CPU          2 CPU
     64Mi RAM     128Mi RAM              512Mi RAM         4Gi RAM

     "Can't go     "What scheduler       "Enforced         "Hard ceiling,
      below this"   reserves for you"     ceiling if you    even if you
                                          don't set one"    specify more"
```

### Apply Both

```bash
$OC apply -f infra/phase1/resourcequotas.yaml
$OC apply -f infra/phase1/limitranges.yaml
```

### Verify

```bash
# Check DEV quota
$OC describe quota -n $NS_DEV | grep -E "Resource|cpu|memory|pods|persistent"
```

Expected output (immediately after Phase 1 -- no workloads yet):

```
Resource                Used  Hard
--------                ----  ----
limits.cpu              0     6
limits.memory           0     12Gi
persistentvolumeclaims  0     5
pods                    0     15
requests.cpu            0     3
requests.memory         0     6Gi
```

After all services are deployed (Phase 17+), the live cluster shows actual resource consumption:

```
Name:                   sampleapi-dev-quota
Namespace:              sampleapi-dev
Resource                Used    Hard
--------                ----    ----
limits.cpu              1750m   6
limits.memory           1536Mi  12Gi
persistentvolumeclaims  2       5
pods                    4       15
requests.cpu            300m    3
requests.memory         640Mi   6Gi
```

The `Used` column starts at 0 because no pods exist yet. As pods are created, these numbers increase. If a pod creation would exceed the quota, it is rejected.

```bash
# Check PROD quota (should be higher)
$OC describe quota -n $NS_PROD | grep -E "limits.cpu|limits.memory|pods"
```

Expected (PROD gets higher limits to accommodate 3 replicas per service):

```
limits.cpu        0     12
limits.memory     0     24Gi
pods              0     25
```

```bash
# Check LimitRange defaults for DEV
$OC describe limitrange default-limits -n $NS_DEV
```

Expected: you should see the default/defaultRequest/max/min values matching what we defined above.

```bash
# Verify all 6 namespaces have quotas
for NS in $NS_TOOLS $NS_GITLAB $NS_DEV $NS_SIT $NS_UAT $NS_PROD; do
  echo -n "$NS: "
  $OC get quota -n $NS -o name 2>/dev/null || echo "MISSING!"
done
```

Expected: each namespace shows `resourcequota/<name>-quota`.

---

## What Just Happened? (5 min)

You built the security foundation for a production DevSecOps platform. Here is what is now in place:

```
┌─────────────────────────────────────────────────────────────────────┐
│                        What We Built                                │
├──────────────────────┬──────────────────────────────────────────────┤
│ 6 Namespaces         │ Logical isolation for tools, source control, │
│                      │ and 4 application environments               │
├──────────────────────┼──────────────────────────────────────────────┤
│ 5 ServiceAccounts    │ jenkins-sa (1) + sampleapi-sa (4 envs)      │
│                      │ Each with a distinct identity and purpose    │
├──────────────────────┼──────────────────────────────────────────────┤
│ 2 ClusterRoles       │ argocd-deployer + jenkins-deployer          │
│                      │ Reusable permission templates                │
├──────────────────────┼──────────────────────────────────────────────┤
│ 12 RoleBindings      │ 4 ArgoCD bindings + 5 Jenkins bindings      │
│                      │ + 3 image-puller bindings                    │
│                      │ Each scoped to a specific namespace          │
├──────────────────────┼──────────────────────────────────────────────┤
│ 22 NetworkPolicies   │ Default deny (4) + Router allow (4)         │
│                      │ + Monitoring (4) + Same-NS (4) + ArgoCD (4) │
│                      │ + Tools egress (1) + Tools ingress (1)      │
├──────────────────────┼──────────────────────────────────────────────┤
│ 6 ResourceQuotas     │ Per-namespace CPU/memory/pod limits          │
├──────────────────────┼──────────────────────────────────────────────┤
│ 6 LimitRanges        │ Per-container defaults and maximums          │
└──────────────────────┴──────────────────────────────────────────────┘
```

Nothing is deployed yet. No Jenkins, no GitLab, no application. But the security boundary is in place BEFORE any workload arrives. This is the right order: security first, then services.

---

## Common Mistakes

### Mistake 1: Forgetting the image-puller binding

**What you do wrong:**

You build an image in `sampleapi-dev` and try to promote it to `sampleapi-sit` by updating the Kustomize overlay. ArgoCD syncs, but the pod shows `ImagePullBackOff`.

**The error:**

```
Events:
  Type     Reason     Age   From               Message
  ----     ------     ----  ----               -------
  Warning  Failed     10s   kubelet            Failed to pull image
    "image-registry.openshift-image-registry.svc:5000/sampleapi-dev/sampleapi:main-abc123":
    unauthorized: authentication required
```

**The fix:**

The SIT ServiceAccount needs `system:image-puller` in the DEV namespace. This is what the image-puller RoleBindings in our `rolebindings.yaml` do. Without them, cross-namespace image pulls fail silently with an auth error.

### Mistake 2: Putting the RoleBinding in the wrong namespace

**What you do wrong:**

You want to let ArgoCD deploy to `sampleapi-dev`, so you create the RoleBinding in `openshift-gitops` (where ArgoCD lives):

```yaml
# WRONG — binding is in the wrong namespace
metadata:
  name: argocd-deployer-binding
  namespace: openshift-gitops       # ← This grants access to openshift-gitops, NOT sampleapi-dev!
```

**The fix:**

The RoleBinding must be in the **target** namespace (where you want the permissions to apply), not the source namespace (where the SA lives):

```yaml
# CORRECT — binding is in the target namespace
metadata:
  name: argocd-deployer-binding
  namespace: sampleapi-dev          # ← This grants access to sampleapi-dev
subjects:
  - kind: ServiceAccount
    name: openshift-gitops-argocd-application-controller
    namespace: openshift-gitops     # ← SA lives here, but access is granted in sampleapi-dev
```

### Mistake 3: NetworkPolicy denies traffic you expected to work

**What you do wrong:**

You apply `default-deny-ingress` but forget to apply the router allow policy. Your Route returns 503.

**The error:**

```bash
curl -sk $APP_DEV_URL/healthz
# Returns: 503 Service Unavailable
```

**The fix:**

Apply `allow-ingress-router.yaml` which allows traffic from the `openshift-ingress` namespace. Always apply deny and allow policies together. The order of `oc apply` does not matter (Kubernetes applies them simultaneously), but having the allow policy missing is the problem.

### Mistake 4: Quota too small for Jenkins agents

**What you do wrong:**

You set the tools namespace quota to 4 CPU / 8Gi. Jenkins controller takes 2 CPU / 4Gi. SonarQube takes 1 CPU / 2Gi. When Jenkins tries to spin up an agent pod requesting 1 CPU / 2Gi, it fails.

**The error:**

```
Events:
  Type     Reason            Age  From               Message
  ----     ------            ---  ----               -------
  Warning  FailedCreate      5s   replication-controller
    Error creating: pods "jenkins-agent-xyz" is forbidden:
    exceeded quota: devsecops-tools-quota,
    requested: limits.memory=2Gi, used: limits.memory=6Gi, limited: limits.memory=8Gi
```

**The fix:**

Size your quotas for peak usage, not average usage. The tools namespace needs headroom for concurrent agent pods. We set it to 16 CPU / 32Gi after hitting this exact problem in production with the T3 pipeline (which runs a ZAP DAST sidecar alongside the main agent container).

---

## Optional Challenge

If you want to go deeper, try these exercises:

1. **Verify deny-all is working.** Deploy a test pod in `sampleapi-dev` and try to `curl` a pod in `devsecops-tools` from inside it:

   ```bash
   $OC run test-pod --image=registry.access.redhat.com/ubi9/ubi-minimal:latest \
     -n $NS_DEV --rm -it --restart=Never \
     -- curl -s --connect-timeout 5 http://jenkins.${NS_TOOLS}.svc:8080/login
   ```

   This should time out (connection refused or timeout), proving that the default-deny policy is blocking cross-namespace traffic.

2. **Create a NetworkPolicy that blocks egress from PROD** to everything except the OpenShift DNS (port 53) and external HTTPS (port 443). This would prevent a compromised PROD pod from accessing internal services while still allowing it to resolve DNS names and make outbound API calls.

3. **Try to exceed a quota.** In `sampleapi-dev`, create a pod requesting more CPU than the LimitRange max per container allows. Observe the error message and understand how LimitRange and ResourceQuota interact — LimitRange caps individual containers, while ResourceQuota caps the namespace total.

---

## Self-Assessment

Before moving to Module 2, confirm you can check off each item:

- [ ] I can explain why tools and application workloads should run in separate namespaces
- [ ] I can explain the difference between a ClusterRole and a RoleBinding
- [ ] I can use `oc auth can-i` to verify whether a ServiceAccount has a specific permission
- [ ] I can explain why the image-puller RoleBinding lives in the source namespace (DEV), not the target namespace (SIT)
- [ ] I can explain what `podSelector: {}` means in a NetworkPolicy
- [ ] I can explain the difference between a ResourceQuota and a LimitRange
- [ ] All 6 namespaces show `Active` status
- [ ] All 5 ServiceAccounts exist in their respective namespaces
- [ ] Jenkins SA can create pods in `devsecops-tools` but cannot delete deployments in `sampleapi-prod`
- [ ] Each app namespace has exactly 5 NetworkPolicies
- [ ] Each namespace has a ResourceQuota and LimitRange

---

## Next Module: Container Builds for DevSecOps

In **Module 2: Container Builds on OpenShift**, you will:

- Write a multi-stage Dockerfile that produces a minimal, non-root .NET runtime image
- Build images with Podman (rootless, no Docker daemon required) on OpenShift
- Understand why the Dockerfile lives in a separate `build-config` repository, not alongside the application source
- Push images to the OCP internal registry and deploy them as pods

You will use the namespaces and ServiceAccounts you created in this module. The application image will be pushed to `sampleapi-dev` using the image-pusher permissions we configured.

---

*Module 1 complete. Source files: `infra/phase1/` in the [devsecops-project](https://github.com/your-org/devsecops-project) repository.*
