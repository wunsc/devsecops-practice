# Module 11: Logging with LokiStack

**Track:** Observability (Modules 11-13)
**Duration:** ~60 minutes
**Difficulty:** Intermediate

---

## What You'll Learn

By the end of this module you will be able to:

- Explain why centralized logging matters in a DevSecOps workflow and how log data flows through the OpenShift Logging 6.x stack.
- Install the two operators that make up OpenShift Logging: the **Loki Operator** and the **Cluster Logging Operator**.
- Provision S3-compatible object storage for Loki and deploy a **LokiStack** custom resource.
- Configure a **ClusterLogForwarder** with the correct ServiceAccount and RBAC so that Vector collectors actually ship logs.
- Write **LogQL** queries to find, filter, and aggregate logs from your `sampleapi` application across all four environments.
- Build reusable log queries that answer real DevSecOps questions: "Why did the last deployment fail?", "Are any pods crash-looping?", "What did the pipeline do at 03:12 AM?"

---

## Prerequisites

> **Environment variables:** Before running any commands, source the environment file:
> ```bash
> source ./env.sh
> ```
> This sets `$OC`, `$APPS_DOMAIN`, `$NS_TOOLS`, and all other cluster-specific variables used throughout this module. See `env.sh` for the full variable list.

| Requirement | How to check |
|---|---|
| Cluster-admin access to your OCP 4.14+ cluster | `$OC auth can-i '*' '*' --all-namespaces` -- expect `yes` |
| Application namespaces exist (`sampleapi-dev`, `-sit`, `-uat`, `-prod`) | `$OC get ns -l team=devsecops` |
| Application pods running in at least `sampleapi-dev` | `$OC get pods -n $NS_DEV` |
| `oc` CLI available | `$OC version` |
| S3-compatible storage available (AWS S3, ODF with NooBaa, or other S3-compatible store) | See Step 2 if you need to create it |
| Module 10 (E2E walkthrough) or equivalent deployment experience completed | Recommended but not strictly required |

---

## Concepts

### Why Centralized Logging

In a single-namespace hobby project you can get away with `oc logs <pod>`. In production you cannot, for three reasons:

1. **Pods are ephemeral.** When a pod restarts or gets evicted, its logs vanish. If the crash happened at 03:00 AM, the evidence is gone by the time you investigate at 09:00 AM.
2. **Scale breaks `oc logs`.** Your `sampleapi` runs across four namespaces with up to eight replicas. Manually tailing each pod is not a debugging strategy; it is a career hazard.
3. **Compliance demands retention.** Audit and security logs must be retained for 30, 90, or 365 days depending on your regulatory regime. Pod-level logs have no retention guarantee at all.

Centralized logging solves all three by collecting logs at the node level, shipping them to durable storage, and exposing them through a query interface.

### The Log Pipeline: App to Query

Here is the data flow you are about to build:

```
Your .NET App         Vector (DaemonSet)         Loki (StatefulSet)       OCP Console / LogQL
  writes to             reads from                  indexes and               you query
  stdout/stderr  --->   /var/log/pods/...  --->     stores in S3    --->     via Observe > Logs
                        on every node               (object storage)
```

Each component has a specific job:

| Component | What it does | Deployed by |
|---|---|---|
| **Your app** | Writes structured JSON to stdout. That is its only logging responsibility. | You (Kustomize + ArgoCD) |
| **Vector** | DaemonSet that tails container log files from every node, parses them, adds Kubernetes metadata (namespace, pod, container), and forwards to Loki. Replaces Fluentd in Logging 6.x. | Cluster Logging Operator |
| **LokiStack** | Receives log streams from Vector, indexes them by labels (not full-text), compresses and stores chunks in S3. Horizontally scalable. | Loki Operator |
| **OCP Console** | Built-in log viewer under Observe > Logs. Sends LogQL queries to Loki behind the scenes. | OpenShift (built-in) |

> **Key difference from Elasticsearch:** Loki does NOT index the log message body. It indexes only the label set (namespace, pod name, container name, log level, etc.). This makes it dramatically cheaper to operate, but it means your queries filter by labels first, then grep through the matching streams. Design your labels accordingly.

### LogQL in 60 Seconds

LogQL has two query types:

**1. Log queries** -- return log lines:
```logql
{kubernetes_namespace_name="sampleapi-dev"} |= "error"
```
Read this as: "Give me all log lines from the `sampleapi-dev` namespace that contain the string `error`."

**2. Metric queries** -- return numbers (for dashboards):
```logql
rate({kubernetes_namespace_name="sampleapi-dev"} |= "error" [5m])
```
Read this as: "How many error log lines per second over the last 5 minutes?"

The curly braces `{}` are the **label selector** (fast -- uses the index). Everything after `|` is a **pipeline** (slower -- scans matching lines). Always narrow with labels first.

Common pipeline operators:

| Operator | Purpose | Example |
|---|---|---|
| `\|= "text"` | Line contains string | `\|= "NullReferenceException"` |
| `!= "text"` | Line does NOT contain string | `!= "healthz"` |
| `\| json` | Parse JSON fields from the line | `\| json \| level="Error"` |
| `\| line_format` | Reformat the output | `\| line_format "{{.message}}"` |
| `\| logfmt` | Parse logfmt key=value pairs | `\| logfmt \| status>=500` |

You will use all of these in Step 5 and Step 6.

---

## Steps

### Step 1: Install the Logging Operators

OpenShift Logging 6.x is split into two operators. You need both:

| Operator | Purpose | Why separate? |
|---|---|---|
| **Loki Operator** | Manages LokiStack CRs (the log *store*) | You might use Loki without OpenShift Logging, or use it for other tenants |
| **Cluster Logging Operator** | Manages ClusterLogForwarder CRs (the log *collector* and *routing*) | The collector is independent of the backend |

#### 1a. Install the Loki Operator

```yaml
# file: logging/loki-operator-subscription.yaml
# Installs the Loki Operator from the OperatorHub.
# The operator watches for LokiStack CRs in any namespace.
# The Namespace and OperatorGroup are included so the operator
# can manage LokiStack CRs across all namespaces (AllNamespaces mode).
apiVersion: v1
kind: Namespace
metadata:
  name: openshift-logging
  labels:
    openshift.io/cluster-monitoring: "true"      # <-- Enable monitoring for logging namespace
  annotations:
    openshift.io/description: "OpenShift Logging — LokiStack + Vector log collection"
---
apiVersion: operators.coreos.com/v1
kind: OperatorGroup
metadata:
  name: openshift-logging-operatorgroup
  namespace: openshift-logging
spec: {}                                         # <-- AllNamespaces mode, required by Loki Operator
---
apiVersion: operators.coreos.com/v1alpha1
kind: Subscription
metadata:
  name: loki-operator
  namespace: openshift-logging                   # <-- Same namespace as Cluster Logging Operator
spec:
  channel: stable-6.2                            # <-- Match your OCP version; 6.2 for OCP 4.20
  installPlanApproval: Automatic
  name: loki-operator
  source: redhat-operators
  sourceNamespace: openshift-marketplace
```

Apply it:

```bash
$OC apply -f infra/phase19/loki-operator-subscription.yaml
```

Wait for the operator to reach `Succeeded`:

```bash
# Poll until the CSV is ready (typically 60-90 seconds)
$OC get csv -n openshift-logging -w
```

Expected output (the version may differ):

```
NAME                     DISPLAY         VERSION   PHASE
loki-operator.v6.2.x     Loki Operator   6.2.x     Succeeded
```

> **Why `openshift-logging`?** Both the Loki Operator and the Cluster Logging Operator are installed in the `openshift-logging` namespace. The OperatorGroup uses `spec: {}` (AllNamespaces mode) so the Loki Operator can manage LokiStack CRs cluster-wide. Older documentation may reference `openshift-operators-redhat`, but the actual deployment uses `openshift-logging` for both operators.

#### 1b. Install the Cluster Logging Operator

```yaml
# file: logging/cluster-logging-operator-subscription.yaml
# Installs the Cluster Logging Operator.
# This operator manages Vector collectors (DaemonSet) and
# the ClusterLogForwarder CR that routes logs to Loki.
apiVersion: operators.coreos.com/v1alpha1
kind: Subscription
metadata:
  name: cluster-logging
  namespace: openshift-logging                   # <-- Same namespace as the Loki Operator
spec:
  channel: stable-6.2                            # <-- Match your OCP version; 6.2 for OCP 4.20
  installPlanApproval: Automatic
  name: cluster-logging
  source: redhat-operators
  sourceNamespace: openshift-marketplace
```

The `openshift-logging` namespace and its OperatorGroup were already created in Step 1a (by the Loki Operator subscription file). The Cluster Logging Operator installs into the same namespace and shares the same OperatorGroup -- you do not need to create another one.

> **Warning:** Do NOT create a second OperatorGroup in `openshift-logging`. Kubernetes only allows one OperatorGroup per namespace. Attempting to create a second one will cause operator installation failures.

Apply the subscription:

```bash
$OC apply -f infra/phase19/logging-operator-subscription.yaml
```

Wait for it:

```bash
$OC get csv -n openshift-logging -w
```

Expected:

```
NAME                            DISPLAY                  VERSION   PHASE
cluster-logging.v6.2.x          Red Hat OpenShift Logging 6.2.x    Succeeded
```

#### Verify: Both Operators Running

```bash
$OC get csv -n openshift-logging | grep loki
# Expected: loki-operator.v6.2.x   Succeeded

$OC get csv -n openshift-logging | grep logging
# Expected: cluster-logging.v6.2.x   Succeeded
```

> **Quick win checkpoint:** You now have both operators installed. No logs are flowing yet -- that requires a LokiStack (storage) and a ClusterLogForwarder (routing). But the machinery is in place.

---

### Step 2: Create S3 Storage for Loki

Loki stores log chunks and index data in S3-compatible object storage. It does NOT use PersistentVolumes for long-term data (only for short-lived WAL and caching). You have three options:

| Option | When to use |
|---|---|
| **ODF ObjectBucketClaim** | You have OpenShift Data Foundation installed (recommended for OCP clusters) |
| **AWS S3** | You are running on AWS and prefer native S3 |
| **NooBaa (ODF)** | On-prem or lab environment without native S3 (ODF provides S3-compatible storage via NooBaa) |

This tutorial uses the **ODF ObjectBucketClaim** approach. ODF's NooBaa component provides S3-compatible storage on-cluster. An ObjectBucketClaim (OBC) is like a PVC but for object storage -- you request a bucket, ODF creates it and provides credentials automatically. No manual IAM setup, no AWS CLI, no external dependencies.

> **Alternative:** If you do not have ODF and want to use AWS S3 directly, create an S3 bucket and an IAM user with `s3:ListBucket`, `s3:PutObject`, `s3:GetObject`, `s3:DeleteObject` permissions. Then create a Secret with keys `access_key_id`, `access_key_secret`, `bucketnames`, `endpoint`, `region` in the `openshift-logging` namespace. The LokiStack CR itself stays the same regardless of storage backend.

#### 2a. Verify ODF Is Available

```bash
# Check if ODF StorageCluster is ready
$OC get storagecluster -n openshift-storage
# Expected: ocs-storagecluster   Phase=Ready

# Check if NooBaa (S3 gateway) is available
$OC get noobaa -n openshift-storage
# Expected: noobaa   Phase=Ready

# Check if the NooBaa storage class exists
$OC get sc openshift-storage.noobaa.io
# Expected: storage class listed
```

If ODF is not installed, you need to install the ODF Operator first via OperatorHub and create a StorageCluster. That is outside the scope of this module. Alternatively, fall back to the AWS S3 approach described above.

#### 2b. Create an ObjectBucketClaim for Loki

```yaml
# file: logging/obc-loki.yaml
# ObjectBucketClaim for Loki log storage.
# ODF's NooBaa creates the S3 bucket and provides credentials automatically.
apiVersion: objectbucket.io/v1alpha1
kind: ObjectBucketClaim
metadata:
  name: loki-bucket
  namespace: openshift-logging
spec:
  generateBucketName: loki-logs          # <-- ODF generates: loki-logs-<random>
  storageClassName: openshift-storage.noobaa.io  # <-- NooBaa storage class from ODF
```

Apply it:

```bash
$OC apply -f infra/phase19/obc-loki.yaml
```

Wait for the OBC to become Bound:

```bash
$OC get obc loki-bucket -n openshift-logging -w
# Expected: STATUS changes from Pending to Bound (takes ~30s)
```

When this OBC is created, ODF automatically:
1. Creates the S3 bucket in NooBaa
2. Creates a **ConfigMap** `loki-bucket` with `BUCKET_NAME`, `BUCKET_HOST`, `BUCKET_PORT`
3. Creates a **Secret** `loki-bucket` with `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`

#### 2c. Create the Loki Secret from OBC Credentials

The Loki Operator expects a Secret with specific key names that differ from what the OBC generates. You need to transform the OBC-generated credentials into the format Loki expects:

```bash
# Extract OBC-generated values
BUCKET_NAME=$($OC get configmap loki-bucket -n openshift-logging -o jsonpath='{.data.BUCKET_NAME}')
BUCKET_HOST=$($OC get configmap loki-bucket -n openshift-logging -o jsonpath='{.data.BUCKET_HOST}')
BUCKET_PORT=$($OC get configmap loki-bucket -n openshift-logging -o jsonpath='{.data.BUCKET_PORT}')
ACCESS_KEY=$($OC get secret loki-bucket -n openshift-logging -o jsonpath='{.data.AWS_ACCESS_KEY_ID}' | base64 -d)
SECRET_KEY=$($OC get secret loki-bucket -n openshift-logging -o jsonpath='{.data.AWS_SECRET_ACCESS_KEY}' | base64 -d)

# Create the secret in Loki's expected format
$OC create secret generic logging-loki-s3 -n openshift-logging \
  --from-literal=access_key_id="${ACCESS_KEY}" \
  --from-literal=access_key_secret="${SECRET_KEY}" \
  --from-literal=bucketnames="${BUCKET_NAME}" \
  --from-literal=endpoint="https://${BUCKET_HOST}:${BUCKET_PORT}" \
  --from-literal=region=""
```

> **Key gotcha:** The OBC Secret uses `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY`, but the Loki Operator expects `access_key_id` and `access_key_secret`. These are NOT the same key names. Also note `bucketnames` (plural) -- a Loki-specific field name.

#### 2d. Trust NooBaa's TLS Certificate

NooBaa uses a self-signed certificate. Loki must trust it. Mount the OpenShift service-CA bundle:

```bash
# Create a ConfigMap that OpenShift will inject with the service CA
$OC create configmap loki-ca-bundle -n openshift-logging
$OC annotate configmap loki-ca-bundle -n openshift-logging \
  service.beta.openshift.io/inject-cabundle=true

# Verify it was populated
$OC get configmap loki-ca-bundle -n openshift-logging -o jsonpath='{.data}' | head -c 100
# Expected: starts with "service-ca.crt: -----BEGIN CERTIFICATE-----"
```

The LokiStack CR references this ConfigMap in its `storage.tls` section (shown in Step 3).

Verify:

```bash
$OC get secret logging-loki-s3 -n openshift-logging
# Expected: logging-loki-s3   Opaque   5   <age>

$OC get obc loki-bucket -n openshift-logging -o jsonpath='{.status.phase}'
# Expected: Bound
```

---

### Step 3: Deploy LokiStack

Now you deploy the actual Loki instance. The Loki Operator manages it through a **LokiStack** custom resource.

#### Choosing the Right Size

The Loki Operator offers T-shirt sizes:

| Size | Ingestion rate | Replicas | Use case |
|---|---|---|---|
| `1x.demo` | ~n/a | 1 of each | Demos only. Not HA. |
| `1x.extra-small` | up to 100 GB/day | 2 ingesters | Small clusters, labs |
| `1x.small` | up to 500 GB/day | 3 ingesters | Medium clusters |
| `1x.medium` | up to 2 TB/day | 3+ ingesters | Large clusters |

For our DevSecOps lab with a 6-node cluster, `1x.demo` is appropriate. Use `1x.small` or larger for production:

```yaml
# file: logging/lokistack.yaml
# Deploys a LokiStack instance that Vector will ship logs to.
# Size 1x.demo is appropriate for demos/labs. Use 1x.small+ for production.
# The LokiStack stores log chunks in S3 (via ODF NooBaa ObjectBucketClaim).
apiVersion: loki.grafana.com/v1
kind: LokiStack
metadata:
  name: logging-loki                              # <-- This name is referenced by ClusterLogForwarder
  namespace: openshift-logging
spec:
  size: 1x.demo                                  # <-- Demo size (single replicas); use 1x.small+ for HA

  storage:
    schemas:
      - version: v13                              # <-- Latest schema version for Loki 3.x
        effectiveDate: "2024-01-01"               # <-- Must be a past date
    secret:
      name: logging-loki-s3                       # <-- The secret created from OBC credentials in Step 2c
      type: s3                                    # <-- Storage type: s3, azure, gcs, swift
    tls:
      caName: loki-ca-bundle                      # <-- Trust NooBaa's self-signed cert (Step 2d)

  storageClassName: gp3-csi                       # <-- For WAL and caching PVCs; match your cluster

  # Tenant configuration for OpenShift integration
  # "openshift-logging" mode creates built-in tenants:
  #   application, infrastructure, audit
  # This is what the OCP Console expects.
  tenants:
    mode: openshift-logging                       # <-- Do NOT use "dynamic" or "static" with OCP

  # Retention: how long to keep logs before Loki deletes them from S3
  limits:
    global:
      retention:
        days: 7                                   # <-- Default: 7 days; adjust per compliance needs
    tenants:
      application:
        retention:
          days: 14                                # <-- App logs: 14 days (more useful for debugging)
      infrastructure:
        retention:
          days: 7                                 # <-- Infra logs: 7 days is sufficient
      audit:
        retention:
          days: 30                                # <-- Audit logs: 30 days (compliance)
```

Apply it:

```bash
$OC apply -f infra/phase19/lokistack.yaml
```

This creates several pods. Wait for them to become ready:

```bash
# Watch the pods come up (takes 2-4 minutes)
$OC get pods -n openshift-logging -w
```

Expected pods for `1x.demo`:

```
NAME                                           READY   STATUS    RESTARTS   AGE
logging-loki-compactor-0                       1/1     Running   0          2m
logging-loki-distributor-<hash>                1/1     Running   0          2m
logging-loki-gateway-<hash>                    2/2     Running   0          2m
logging-loki-index-gateway-0                   1/1     Running   0          2m
logging-loki-ingester-0                        1/1     Running   0          2m
logging-loki-querier-<hash>                    1/1     Running   0          2m
logging-loki-query-frontend-<hash>             1/1     Running   0          2m
```

#### Verify: LokiStack Is Healthy

```bash
# Quick check: is the LokiStack resource present?
$OC get lokistack -n openshift-logging
```

Expected output:

```
NAME           AGE
logging-loki   45h
```

```bash
# Detailed check: inspect the status conditions
$OC get lokistack logging-loki -n openshift-logging -o jsonpath='{.status.conditions[*].type}{"\n"}{.status.conditions[*].status}'
```

Expected:

```
Pending  Ready
False    True
```

The `Ready=True` condition means Loki is operational and connected to S3.

If `Ready` is `False`, check the pods:

```bash
# Look for failing pods
$OC get pods -n openshift-logging -l app.kubernetes.io/instance=logging-loki --field-selector=status.phase!=Running

# Check events for scheduling or S3 connection issues
$OC get events -n openshift-logging --sort-by='.lastTimestamp' | tail -20
```

Common failure: the S3 secret has wrong credentials or wrong bucket name. Double-check with:

```bash
$OC get secret logging-loki-s3 -n openshift-logging -o jsonpath='{.data.bucketnames}' | base64 -d
```

---

### Step 4: Configure ClusterLogForwarder

The LokiStack is now running, but nothing is sending it logs. You need a **ClusterLogForwarder** CR to tell the Cluster Logging Operator what to collect and where to send it.

#### Why This Is Not Automatic

This is the step that trips up most people. In OpenShift Logging 6.x, the ClusterLogForwarder:

1. Deploys Vector collector pods as a DaemonSet.
2. Requires a **ServiceAccount** with specific cluster roles.
3. Without the ServiceAccount, the collector pods start but silently skip log collection because they cannot read pod metadata.

Let us get the RBAC right from the start.

#### 4a. Create the ServiceAccount and Bind Roles

```yaml
# file: logging/collector-rbac.yaml
# ServiceAccount + ClusterRoleBindings for the Vector collector.
# Without these, collectors start but cannot read pod logs or Kubernetes metadata.
apiVersion: v1
kind: ServiceAccount
metadata:
  name: logcollector                              # <-- Name must match what ClusterLogForwarder references
  namespace: openshift-logging
  labels:
    app: logcollector
---
# Allow reading application container logs
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: logcollector-application-logs
  labels:
    app: logcollector
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: collect-application-logs                  # <-- Provided by Cluster Logging Operator
subjects:
  - kind: ServiceAccount
    name: logcollector
    namespace: openshift-logging
---
# Allow reading infrastructure logs (openshift-*, kube-* namespaces)
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: logcollector-infrastructure-logs
  labels:
    app: logcollector
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: collect-infrastructure-logs               # <-- Provided by Cluster Logging Operator
subjects:
  - kind: ServiceAccount
    name: logcollector
    namespace: openshift-logging
---
# Allow reading audit logs (API server, OAuth, node audit)
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: logcollector-audit-logs
  labels:
    app: logcollector
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: collect-audit-logs                        # <-- Provided by Cluster Logging Operator
subjects:
  - kind: ServiceAccount
    name: logcollector
    namespace: openshift-logging
---
# Allow writing logs to LokiStack endpoints
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: logcollector-logs-writer
  labels:
    app: logcollector
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: logging-collector-logs-writer             # <-- Provided by Loki Operator
subjects:
  - kind: ServiceAccount
    name: logcollector
    namespace: openshift-logging
```

Apply it:

```bash
$OC apply -f infra/phase19/cluster-log-forwarder-sa.yaml
```

Verify the ClusterRoles exist (the `collect-*` roles are created by the Cluster Logging Operator, and `logging-collector-logs-writer` is created by the Loki Operator):

```bash
$OC get clusterrole collect-application-logs collect-infrastructure-logs collect-audit-logs logging-collector-logs-writer
# Expected: All four exist. If the collect-* roles are missing, the Cluster Logging Operator
# is not installed correctly. If logging-collector-logs-writer is missing, the Loki Operator
# is not installed correctly.
```

#### 4b. Create the ClusterLogForwarder

```yaml
# file: logging/clusterlogforwarder.yaml
# Routes logs from your application namespaces (and infra/audit) to LokiStack.
#
# Key design decisions:
# - All application logs are collected (built-in "application" input covers
#   all non-infrastructure namespaces including sampleapi-* and devsecops-*).
# - JSON parsing is enabled so structured .NET log fields become queryable.
# - DEBUG-level logs from production namespaces are dropped to reduce volume.
# - Infrastructure and audit logs go to separate Loki tenants (built-in with
#   openshift-logging tenancy mode).
# - TLS is configured to trust the OpenShift service-CA for LokiStack gateway.
apiVersion: observability.openshift.io/v1         # <-- Logging 6.x API group
kind: ClusterLogForwarder
metadata:
  name: instance
  namespace: openshift-logging
spec:
  serviceAccount:
    name: logcollector                            # <-- The SA you created in 4a; this is NOT optional

  # --- Outputs: Where logs go ---
  outputs:
    - name: loki-output
      type: lokiStack                             # <-- Sends to the LokiStack CR in this namespace
      lokiStack:
        target:
          name: logging-loki                      # <-- Must match LokiStack metadata.name
          namespace: openshift-logging
        authentication:
          token:
            from: serviceAccount                  # <-- Uses the logcollector SA token for auth
      tls:
        ca:
          configMapName: openshift-service-ca.crt # <-- Trust service-serving-signer CA
          key: service-ca.crt                     # <-- for LokiStack gateway TLS

  # --- Pipelines: Route log types to outputs ---
  pipelines:
    # Application logs: container stdout/stderr from user namespaces
    # Includes sampleapi-*, devsecops-tools, devsecops-gitlab
    - name: app-logs
      inputRefs:
        - application                             # <-- Built-in: all non-infra namespace pods
      outputRefs:
        - loki-output
      filterRefs:
        - drop-debug-prod                         # <-- Drop DEBUG logs from production
        - parse-json                              # <-- Parse .NET structured JSON logs

    # Infrastructure logs (nodes, kubelets, CRI-O)
    - name: infra-logs
      inputRefs:
        - infrastructure                          # <-- Built-in: control plane + infra pods
      outputRefs:
        - loki-output

    # Audit logs (API server, OAuth, node-level)
    - name: audit-logs
      inputRefs:
        - audit                                   # <-- Built-in: /var/log/audit/*
      outputRefs:
        - loki-output

  # --- Filters: Transform and reduce log volume ---
  filters:
    # Drop DEBUG-level logs from production namespaces
    # Production should only log Info/Warning/Error (set in ConfigMap).
    # This filter catches any DEBUG logs that slip through.
    - name: drop-debug-prod
      type: drop
      drop:
        - test:
            - field: .kubernetes.namespace_name
              matches: "sampleapi-prod"
            - field: .level
              matches: "debug|Debug|DEBUG"

    # Parse JSON-structured log messages from .NET applications
    # .NET logs output structured JSON when configured with:
    #   builder.Logging.AddJsonConsole()
    # This filter extracts JSON fields (level, message, exception, etc.)
    # into top-level log fields for easier LogQL querying.
    - name: parse-json
      type: parse
```

Apply it:

```bash
$OC apply -f infra/phase19/cluster-log-forwarder.yaml
```

This triggers the Cluster Logging Operator to deploy a Vector DaemonSet. Wait for collector pods:

```bash
# Watch collector pods appear (one per node)
$OC get pods -n openshift-logging -l component=collector -w
```

Expected (one pod per node in your cluster):

```
NAME                READY   STATUS    RESTARTS   AGE
collector-2x7kf     1/1     Running   0          30s
collector-5m9tn     1/1     Running   0          30s
collector-8hj4p     1/1     Running   0          30s
collector-bw2rc     1/1     Running   0          30s
collector-g6ftd     1/1     Running   0          30s
collector-xnq9z     1/1     Running   0          30s
```

#### Verify: Logs Are Flowing

Give it 60 seconds for the first logs to be ingested, then check:

```bash
# Check the ClusterLogForwarder status
$OC get clusterlogforwarder instance -n openshift-logging -o jsonpath='{.status.conditions[*].type}{"\n"}{.status.conditions[*].status}{"\n"}'
# Expected: Ready  True (among others)

# Check collector pod logs for errors
$OC logs -l component=collector -n openshift-logging --tail=10
# Expected: Lines showing successful sends, no "error" or "failed" messages.
# Look for: "BytesSent" or "component_sent_bytes_total" -- means data is flowing.
```

> **Quick win checkpoint:** Open the OpenShift Console, navigate to **Observe > Logs**. Select the `sampleapi-dev` namespace from the dropdown. You should see live log lines from your application pods. If you see logs, the entire pipeline is working: App -> Vector -> Loki -> Console.

If the Observe > Logs page shows "No datapoints found", check these in order:

```bash
# 1. Are collector pods running?
$OC get pods -n openshift-logging -l component=collector
# If no pods: ClusterLogForwarder is missing or has errors

# 2. Are collectors sending data?
$OC logs -l component=collector -n openshift-logging --tail=30 | grep -i error
# If auth errors: the ServiceAccount is missing or not bound

# 3. Is LokiStack healthy?
$OC get lokistack logging-loki -n openshift-logging -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}'
# Must be "True"
```

---

### Step 5: Query Application Logs with LogQL

Now that logs are flowing, let us query them. You can run LogQL queries in two places:

- **OCP Console:** Observe > Logs (graphical, no setup needed)
- **API directly:** `curl` against the Loki gateway (for scripting and automation)

#### 5a. Basic Queries via OCP Console

Open **Observe > Logs** in the OpenShift Console. Switch the log type dropdown to **application** (not infrastructure or audit).

Try these queries in the query box. The Console uses LogQL syntax:

**All logs from sampleapi-dev:**

```logql
{kubernetes_namespace_name="sampleapi-dev"}
```

This is the simplest possible query. The label selector `kubernetes_namespace_name` is automatically added by Vector when it enriches the log with Kubernetes metadata.

**Logs from a specific pod:**

```logql
{kubernetes_namespace_name="sampleapi-dev", kubernetes_pod_name=~"sampleapi-.*"}
```

The `=~` operator is a regex match. Useful when pod names have random suffixes.

**Filter for errors only:**

```logql
{kubernetes_namespace_name="sampleapi-dev"} |= "error"
```

The `|=` operator is a case-sensitive substring match. It scans the log line body after Loki retrieves the matching streams.

**Case-insensitive error search:**

```logql
{kubernetes_namespace_name="sampleapi-dev"} |~ "(?i)error|exception|fail"
```

The `|~` operator is a regex filter. `(?i)` makes it case-insensitive. The `|` inside the regex is "OR".

#### 5b. Structured JSON Log Queries

If your .NET application uses structured logging (which ASP.NET Core does by default when you configure JSON logging), you can parse JSON fields:

```logql
{kubernetes_namespace_name="sampleapi-dev"} | json | level="Error"
```

This pipes each log line through the `json` parser, which extracts top-level JSON keys as labels. Then it filters where the `level` field equals `Error`.

> **Tip:** To see what fields are available, run a raw query first (`{kubernetes_namespace_name="sampleapi-dev"}`) and look at a log line. If it looks like `{"timestamp":"...","level":"Information","message":"Request starting..."}`, then you can filter on `level`, `message`, `timestamp`, etc. after `| json`.

**Filter by HTTP status code (if your app logs it):**

```logql
{kubernetes_namespace_name="sampleapi-dev"} | json | StatusCode >= 500
```

**Exclude health check noise:**

```logql
{kubernetes_namespace_name="sampleapi-dev"} != "/healthz" != "/readyz"
```

Health check probes fire every few seconds and flood your logs. Exclude them to focus on real requests.

#### 5c. LogQL via the API (for Scripting)

You can query Loki directly through the gateway service. This is useful for CI/CD pipelines, alerting scripts, or custom dashboards:

```bash
# Get the Loki gateway route (if exposed) or use the internal service
LOKI_GATEWAY="https://$($OC get route logging-loki-gateway -n openshift-logging -o jsonpath='{.spec.host}' 2>/dev/null)"

# If no route exists, use port-forwarding:
# $OC port-forward svc/logging-loki-gateway-http 3100:8080 -n openshift-logging &
# LOKI_GATEWAY="http://localhost:3100"

# Get a token for authentication
TOKEN=$($OC whoami -t)

# Query application logs from the last hour
curl -sk -H "Authorization: Bearer ${TOKEN}" \
  "${LOKI_GATEWAY}/api/logs/v1/application/loki/api/v1/query_range" \
  --data-urlencode 'query={kubernetes_namespace_name="sampleapi-dev"}' \
  --data-urlencode 'limit=10' | jq '.data.result[0].values[:3]'
```

> **Note the URL path:** For OpenShift Logging with `openshift-logging` tenancy mode, the API path includes the tenant name: `/api/logs/v1/application/...` for application logs, `/api/logs/v1/infrastructure/...` for infra logs, `/api/logs/v1/audit/...` for audit logs.

---

### Step 6: Create Useful Log Queries for DevSecOps

These are the queries your team will actually use day-to-day. Save them as bookmarks or dashboard panels.

#### Query 1: Recent Errors Across All Environments

```logql
{kubernetes_namespace_name=~"sampleapi-.*"} |~ "(?i)error|exception|critical"
  != "/healthz" != "/readyz"
```

WHY: When an alert fires, this is the first query you run. It shows errors across dev, sit, uat, and prod in one view, minus the health check noise.

#### Query 2: Errors by Namespace (Rate)

```logql
sum by (kubernetes_namespace_name) (
  rate({kubernetes_namespace_name=~"sampleapi-.*"} |~ "(?i)error|exception" [5m])
)
```

WHY: Shows error rate per environment. If `sampleapi-sit` suddenly has 10x more errors than `sampleapi-dev`, something went wrong during promotion.

#### Query 3: Pod Restart Evidence

```logql
{kubernetes_namespace_name=~"sampleapi-.*"} |~ "Started container|Back-off restarting"
```

Or check infrastructure logs for the kubelet perspective:

```logql
{kubernetes_namespace_name=~"sampleapi-.*", kubernetes_container_name="sampleapi"}
  |~ "(?i)unhandled exception|fatal|out of memory"
```

WHY: When `kube_pod_container_status_restarts_total` is climbing, these queries tell you what the pod logged right before it died.

#### Query 4: Pipeline-Related Logs (Jenkins)

```logql
{kubernetes_namespace_name="devsecops-tools", kubernetes_pod_name=~"jenkins-.*"}
  |~ "(?i)build|pipeline|checkout|sonarqube|acs|podman"
```

WHY: When a Jenkins pipeline fails and the Jenkins UI is slow or inaccessible, you can still read the build logs from Loki.

#### Query 5: Deployment Events (ArgoCD Syncs)

```logql
{kubernetes_namespace_name="openshift-gitops"} |= "sampleapi" |~ "(?i)sync|reconcil"
```

WHY: Shows when ArgoCD synced each environment and whether it succeeded. Correlate with application error spikes.

#### Query 6: Security-Relevant Audit Queries

Switch to the **audit** log type in the Console (or use `/api/logs/v1/audit/...` in the API):

```logql
{kubernetes_namespace_name=~"sampleapi-.*"}
  | json
  | verb="delete"
```

WHY: Who deleted resources in your application namespaces? Audit logs capture every API server request with the authenticated user, verb, and resource.

**Failed API calls (potential unauthorized access attempts):**

```logql
{} | json | responseStatus_code >= 403
  | line_format "{{.user_username}} tried {{.verb}} {{.objectRef_resource}}/{{.objectRef_name}} in {{.objectRef_namespace}} -> {{.responseStatus_code}}"
```

WHY: Security teams care about 403s and 401s. This query reformats the audit log into a human-readable sentence.

#### Query 7: Log Volume by Namespace (Capacity Planning)

```logql
sum by (kubernetes_namespace_name) (
  bytes_rate({kubernetes_namespace_name=~"sampleapi-.*|devsecops-.*"} [1h])
)
```

WHY: Tells you which namespace is producing the most log volume, so you can tune log levels or adjust retention policies before your S3 bill surprises you.

---

## Recap

Here is what you built in this module:

| What | How | Why |
|---|---|---|
| **Loki Operator** | OLM Subscription in `openshift-logging` | Manages LokiStack CRs |
| **Cluster Logging Operator** | OLM Subscription in `openshift-logging` | Manages Vector collectors and log routing |
| **ODF ObjectBucketClaim + Secret** | OBC via NooBaa (or AWS S3) + Kubernetes Secret with credentials | Durable S3-compatible storage for log chunks and index |
| **LokiStack** | `LokiStack` CR (`1x.demo`, S3 backend via ODF, `openshift-logging` tenancy) | Ingests, indexes, stores, and serves logs |
| **ClusterLogForwarder** | `ClusterLogForwarder` CR with ServiceAccount, inputs, outputs, pipelines | Routes app/infra/audit logs from Vector to Loki |
| **LogQL queries** | Seven production-ready queries | Troubleshoot errors, track deployments, audit access, plan capacity |

The data flow:

```
.NET App (stdout) -> Vector DaemonSet -> LokiStack -> OCP Console (Observe > Logs) / LogQL API
```

---

## Common Mistakes

> **Mistake 1: Missing ServiceAccount on the ClusterLogForwarder.**
> Symptom: Collector pods start, but no logs appear in Loki. No errors in collector logs either -- they just silently skip.
> Fix: Create the `logcollector` ServiceAccount in `openshift-logging` and bind it to `collect-application-logs`, `collect-infrastructure-logs`, `collect-audit-logs`, and `logging-collector-logs-writer` ClusterRoles. The `serviceAccount.name` field in the ClusterLogForwarder spec is mandatory in Logging 6.x. The `logging-collector-logs-writer` ClusterRole (provided by the Loki Operator) is required for the collector to write logs to the LokiStack gateway.

> **Mistake 2: Wrong S3 secret keys.**
> Symptom: LokiStack pods crash-loop with "access denied" or "no such bucket".
> Fix: The secret must have exactly these keys: `access_key_id`, `access_key_secret`, `bucketnames`, `endpoint`, `region`. Note `bucketnames` (plural) and `access_key_secret` (not `secret_access_key`). The key names are Loki-specific, not AWS-standard.

> **Mistake 3: Using `type: elasticsearch` or Fluentd references in Logging 6.x.**
> Symptom: Validation errors on the ClusterLogForwarder CR.
> Fix: Logging 6.x dropped Elasticsearch and Fluentd support. The only supported collector is Vector, and the recommended store is LokiStack. If you see references to `type: fluentd` or `type: elasticsearch` in older documentation, they are for Logging 5.x and will not work.

> **Mistake 4: Installing the Loki Operator without the correct OperatorGroup.**
> Symptom: The operator installs but LokiStack CR creation fails with RBAC errors.
> Fix: The Loki Operator must be installed in `openshift-logging` with an OperatorGroup using `spec: {}` (AllNamespaces mode). Without this, the operator cannot watch for LokiStack CRs. Older documentation may reference `openshift-operators-redhat`, but the actual deployment uses `openshift-logging`.

> **Mistake 5: Querying the wrong tenant path in the API.**
> Symptom: API queries return empty results even though the Console shows logs.
> Fix: Use `/api/logs/v1/application/loki/api/v1/query_range` for app logs, not `/loki/api/v1/query_range`. The OCP Loki gateway enforces multi-tenancy through URL path segments.

> **Mistake 6: Setting `tenants.mode` to `dynamic` or `static`.**
> Symptom: Logs flow to Loki but the OCP Console Observe > Logs page shows nothing.
> Fix: Use `tenants.mode: openshift-logging`. This creates the built-in `application`, `infrastructure`, and `audit` tenants that the Console expects. Custom tenant modes require custom UIs.

---

## Challenge

Create a LogQL query that answers this question:

*"For each environment (dev, sit, uat, prod), what was the error rate per minute over the last 6 hours, excluding health check endpoints?"*

Requirements:
- Use a metric query (not a log query).
- Group results by `kubernetes_namespace_name`.
- Exclude log lines containing `/healthz` or `/readyz`.
- Use a 1-minute rate window.

Bonus: Add this as a panel in the OCP Console by navigating to Observe > Dashboards and creating a custom dashboard (if available), or export it as a Grafana dashboard JSON.

<details>
<summary>Hint</summary>

Start with `sum by (kubernetes_namespace_name)` and use `rate(...)` with a `[1m]` window. Chain two `!=` filters before the closing `[1m]`.

</details>

<details>
<summary>Solution</summary>

```logql
sum by (kubernetes_namespace_name) (
  rate(
    {kubernetes_namespace_name=~"sampleapi-.*"} |~ "(?i)error|exception" != "/healthz" != "/readyz" [1m]
  )
)
```

Run this in the OCP Console under Observe > Logs, and switch to the "Graph" view (if available) to see the time series.

</details>

---

## Self-Assessment

Before moving on, confirm you can answer these questions:

1. What two operators must be installed for OpenShift Logging 6.x with LokiStack? What namespace does each go in?
2. Why does Loki need S3 (or S3-compatible) storage? What happens if you try to use PersistentVolumes only?
3. What happens if you create a ClusterLogForwarder without a ServiceAccount? How would you diagnose this?
4. Write a LogQL query that finds all log lines from `sampleapi-prod` containing "500" but not "/healthz", and parses them as JSON to extract the `message` field.
5. What is the difference between `|=` and `|~` in LogQL?
6. What URL path would you use to query audit logs via the Loki API on an OpenShift cluster with `openshift-logging` tenancy mode?

<details>
<summary>Answers</summary>

1. **Loki Operator** and **Cluster Logging Operator**, both in `openshift-logging`. The Loki Operator requires an OperatorGroup with `spec: {}` (AllNamespaces mode).
2. Loki is designed around object storage for cost-efficient, scalable log retention. PVCs are only used for short-lived WAL (Write-Ahead Log) and caching. Without S3, Loki has nowhere to store log chunks long-term and will reject writes once the WAL fills up.
3. Collector pods start but do not collect logs. No error is obvious. Diagnose by checking `oc get clusterlogforwarder instance -n openshift-logging -o yaml` for status conditions, and verify the ServiceAccount exists with `oc get sa logcollector -n openshift-logging`.
4. `{kubernetes_namespace_name="sampleapi-prod"} |= "500" != "/healthz" | json | line_format "{{.message}}"`
5. `|=` is a substring match (fast, literal string). `|~` is a regex match (slower, supports patterns like `(?i)` for case-insensitivity and `|` for alternation).
6. `/api/logs/v1/audit/loki/api/v1/query_range`

</details>

---

## Next Module

**Module 12: Monitoring and Alerting** -- You will configure ServiceMonitors for your application, write PrometheusRules that fire alerts when error rates spike or pods crash-loop, and verify that metrics flow end-to-end from your .NET app through Prometheus to the OpenShift Console. The metrics you collect in Module 12 pair naturally with the logs you just configured: an alert tells you *something is wrong*, and Loki tells you *why*.
