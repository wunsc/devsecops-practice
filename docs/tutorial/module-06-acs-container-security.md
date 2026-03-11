# Module 6: Container Security with ACS (StackRox)

| Detail          | Value                                      |
|-----------------|--------------------------------------------|
| **Duration**    | ~75 minutes                                |
| **Track**       | Security                                   |
| **Difficulty**  | Intermediate                               |
| **Namespace**   | `stackrox`, `rhacs-operator`               |

---

## What You'll Learn

By the end of this module you will be able to:

- Explain why container security requires three enforcement layers (build, deploy, runtime) and what each one catches that the others miss.
- Install the Red Hat ACS (StackRox) operator on OpenShift and deploy Central, Scanner, and SecuredCluster components.
- Generate and apply an init bundle -- the TLS trust anchor that lets SecuredCluster talk to Central.
- Scan container images from the command line with `roxctl` and interpret the results.
- Write ACS policies that block root-running containers at deploy time via the admission controller.
- Integrate `roxctl image check` and `roxctl image scan` into a Jenkins pipeline using the shared library.
- Distinguish between strict mode (T3 release pipeline) and normal mode (T2 merge pipeline) enforcement.

---

## Prerequisites

Before starting this module you need:

- An OpenShift 4.x cluster with `cluster-admin` access.
- Phase 1 namespaces applied (`stackrox` namespace exists; `rhacs-operator` will be created by the operator subscription in this module).
- The `roxctl` CLI installed and on your `PATH` (check with `roxctl version`).
- The `oc` CLI authenticated to your cluster (`oc whoami` returns your user).
- Familiarity with Kubernetes Deployments, Services, and CRDs (covered in Modules 1-2).

> **Environment variables:** Before running any commands, source the environment file:
> ```bash
> source ./env.sh
> ```
> This sets `$OC`, `$APPS_DOMAIN`, `$NS_TOOLS`, and all other cluster-specific variables used throughout this module. See `env.sh` for the full variable list.

```bash
# Quick prerequisite check
$OC whoami                  # Expected: admin (or your cluster-admin user)
roxctl version              # Expected: 4.x.x
$OC get ns $NS_ACS          # Expected: namespace exists (from Phase 1)
```

---

## Concepts: The Three Layers of Container Security

Before touching any YAML, you need to understand WHY ACS exists and what problem it solves that SonarQube and OWASP Dependency-Check cannot.

SonarQube finds bugs in your source code. Dependency-Check finds CVEs in your libraries. But neither of them can answer these questions:

- Does the final container image contain a vulnerable OS package that was never in your `csproj`?
- Is a deployment about to run as root, even though your Dockerfile says `USER 1001`?
- Did someone just spawn a reverse shell inside a running production container?

ACS answers all three by operating at three distinct lifecycle stages:

```
                    YOUR CODE
                       |
                       v
  +-----------------------------------------+
  |        BUILD TIME (CI Pipeline)         |  <-- Layer 1
  |  roxctl image check / image scan        |
  |  "Does this IMAGE have critical CVEs?"  |
  |  "Does it violate build policies?"      |
  +-----------------------------------------+
                       |
                    PASS/FAIL
                       |
                       v
  +-----------------------------------------+
  |        DEPLOY TIME (Admission Ctrl)     |  <-- Layer 2
  |  Kubernetes Admission Webhook           |
  |  "Is this container running as root?"   |
  |  "Is it from a trusted registry?"       |
  |  "Is the image signed?"                 |
  +-----------------------------------------+
                       |
                   ALLOW/DENY
                       |
                       v
  +-----------------------------------------+
  |        RUNTIME (Collector / eBPF)       |  <-- Layer 3
  |  Per-node DaemonSet monitors processes  |
  |  "Did someone just run xmrig?"          |
  |  "Is there a reverse shell?"            |
  |  "Unexpected network connections?"      |
  +-----------------------------------------+
```

Each layer catches threats the previous one cannot:

| Layer      | Catches                                        | Misses                                    |
|------------|------------------------------------------------|-------------------------------------------|
| **Build**  | Known CVEs, policy violations in the image     | Runtime exploits, zero-days               |
| **Deploy** | Bad configs (root user, untrusted registry)    | Vulnerabilities inside allowed images     |
| **Runtime**| Active exploitation, crypto miners, shells     | Nothing -- but only detects, rarely prevents|

> **Key insight:** Build-time scanning answers "is this image safe to deploy?" Deploy-time
> enforcement answers "is this deployment configured safely?" Runtime monitoring answers
> "is something bad happening right now?" You need all three.

### ACS Architecture on OpenShift

```
  stackrox namespace                              every node
  +----------------------------+                  +------------------+
  |  Central                   |                  |  Collector       |
  |  (UI, API, policy engine,  |  <-- gRPC -->    |  (eBPF probes,   |
  |   vulnerability database)  |                  |   process/net    |
  |                            |                  |   monitoring)    |
  +----------------------------+                  +------------------+
  |  Scanner / Scanner V4      |                       ^ DaemonSet
  |  (image layer analysis)    |                       | one per node
  +----------------------------+
  |  Central DB (PostgreSQL)   |
  +----------------------------+
         ^           ^
         |           |
    +--------+  +-----------+
    | Sensor |  | Admission |      Sensor watches K8s API for
    | (K8s   |  | Controller|  <-- deployments, policies, network.
    |  watcher)|            |      Admission Controller is a
    +--------+  +-----------+      validating webhook that can
                                   block creates/updates.

  CI Pipeline (Jenkins)
  +----------------------------+
  |  roxctl image check        |  <-- REST API --> Central
  |  roxctl image scan         |
  |  (build-time enforcement)  |
  +----------------------------+
```

**Central** is the brain -- it stores all vulnerability data, policies, and scan results. Everything else reports to Central.

**Sensor** watches the Kubernetes API server for new deployments, pods, and network flows. It sends this data to Central for policy evaluation.

**Collector** runs on every node as a DaemonSet. It uses eBPF probes to monitor process execution, network connections, and file access inside containers. This is how ACS detects runtime threats without modifying your application.

**Admission Controller** is a Kubernetes validating webhook. When someone creates or updates a Deployment, the API server asks the admission controller "should I allow this?" The controller evaluates deploy-time policies and returns allow or deny.

**roxctl** is the CLI that your Jenkins pipeline uses. It talks to Central's REST API to run image checks (policy evaluation) and image scans (vulnerability detection) at build time.

---

## Step 1: Install the ACS Operator

**WHY:** ACS is not a single binary -- it has seven components (Central, Central DB, Scanner, Scanner V4, Sensor, Collector, Admission Controller) with complex interdependencies. The operator manages the full lifecycle: installation, upgrades, and configuration reconciliation. Installing manually would be fragile and hard to upgrade.

The operator watches for two Custom Resources: `Central` (deploys the management plane) and `SecuredCluster` (deploys the data plane). You create the CRDs; the operator does the rest.

Look at the subscription manifest:

```yaml
# infra/phase5/acs-operator-subscription.yaml (relevant excerpt)
#
# The operator installs into rhacs-operator namespace.
# It watches ALL namespaces for Central and SecuredCluster CRs.
# Channel "stable" tracks the latest production-ready release.

apiVersion: operators.coreos.com/v1alpha1
kind: Subscription
metadata:
  name: rhacs-operator
  namespace: rhacs-operator
spec:
  channel: stable                          # <-- production channel
  installPlanApproval: Automatic           # <-- auto-approve upgrades
  name: rhacs-operator
  source: redhat-operators                 # <-- Red Hat's operator catalog
  sourceNamespace: openshift-marketplace
```

The `OperatorGroup` in the same file has an empty `spec: {}`, which means the operator has cluster-wide scope -- it can manage CRs in any namespace. This is required because Central lives in `stackrox` but the operator lives in `rhacs-operator`.

### Do: Install the Operator

```bash
$OC apply -f infra/phase5/acs-operator-subscription.yaml
```

### Verify: Wait for the Operator

The operator takes 2-3 minutes to install. OLM downloads the operator image, creates the CSV (ClusterServiceVersion), and starts the operator pod.

```bash
# Poll until the CSV reaches Succeeded phase
echo "Waiting for ACS operator..."
while ! $OC get csv -n rhacs-operator 2>/dev/null | grep -q Succeeded; do
    sleep 10
    echo "  still waiting..."
done

$OC get csv -n rhacs-operator
```

Expected output:

```
NAME                      DISPLAY                          VERSION   PHASE
rhacs-operator.v4.5.4     Advanced Cluster Security        4.5.4     Succeeded
```

> **If the operator stays in Pending or InstallReady:** Check the install plan
> with `$OC get installplan -n rhacs-operator -o yaml`. Common cause: the cluster
> cannot reach the Red Hat operator catalog. Verify with
> `$OC get catalogsource -n openshift-marketplace`.

You now have the operator running but no ACS components yet. The operator is waiting for you to create the `Central` and `SecuredCluster` Custom Resources.

---

## Step 2: Deploy ACS Central

**WHY:** Central is the management plane. Without it, there is nothing to store policies, scan results, or serve the UI. Every other ACS component depends on Central. Deploy it first.

Look at the Central CR:

```yaml
# infra/phase5/acs-central.yaml (relevant excerpt)
#
# This CR tells the operator to deploy Central, its embedded PostgreSQL DB,
# the legacy Scanner, and Scanner V4 (the next-gen scanner).

apiVersion: platform.stackrox.io/v1alpha1
kind: Central
metadata:
  name: stackrox-central-services
  namespace: stackrox                       # <-- must be stackrox
spec:
  central:
    exposure:
      route:
        enabled: true                       # <-- creates an OpenShift Route for the UI
    persistence:
      persistentVolumeClaim:
        claimName: stackrox-db
        storageClassName: gp3-csi           # <-- must match your cluster's StorageClass
    resources:
      requests:
        cpu: 500m
        memory: 2Gi
      limits:
        cpu: "2"
        memory: 4Gi
    db:
      persistence:
        persistentVolumeClaim:
          claimName: central-db
          storageClassName: gp3-csi
  scanner:
    analyzer:
      scaling:
        autoScaling: Enabled                # <-- scales 1-3 replicas based on load
        minReplicas: 1
        maxReplicas: 3
  scannerV4:                                # <-- next-gen scanner (ACS 4.x)
    indexer:
      scaling:
        autoScaling: Enabled
    matcher:
      scaling:
        autoScaling: Enabled
    db:
      persistence:
        persistentVolumeClaim:
          claimName: scanner-v4-db
          storageClassName: gp3-csi
```

A few things to note:

- **Three PVCs** are created: `stackrox-db` (Central's data), `central-db` (PostgreSQL), and `scanner-v4-db` (Scanner V4's vulnerability database). Make sure your `storageClassName` is correct -- get yours with `$OC get sc`.
- **Scanner V4** is the newer scanner engine that replaces the legacy scanner. Both run simultaneously during the transition period.
- **Auto-scaling** for scanners means that if you push many images at once (like during a batch scan), ACS will spin up additional scanner pods to handle the load.

### Do: Apply the Central CR

```bash
$OC apply -f infra/phase5/acs-central.yaml
```

### Verify: Wait for Central

Central takes 3-5 minutes to start. The operator creates the PostgreSQL database first, then Central itself, then the scanners.

```bash
echo "Waiting for Central..."
$OC wait --for=condition=ready pod -l app=central -n $NS_ACS --timeout=600s
```

Once Central is ready, verify all the management-plane pods:

```bash
$OC get pods -n $NS_ACS
```

Expected output (management plane only -- no sensor/collector yet):

```
NAME                                  READY   STATUS    RESTARTS   AGE
central-5b7f9c4d8f-x2k7p             1/1     Running   0          3m
central-db-7d8f6c5b9a-m4n2p          1/1     Running   0          4m
scanner-6c9d7e8f1b-j3k5l             1/1     Running   0          2m
scanner-db-8e7f6d5c4b-n9m1p          1/1     Running   0          3m
scanner-v4-indexer-5d6e7f8g-h2j4k    1/1     Running   0          2m
scanner-v4-matcher-9a8b7c6d-p5q3r    1/1     Running   0          2m
scanner-v4-db-4f5g6h7i-s8t1u         1/1     Running   0          3m
```

Get the admin password and verify the UI:

```bash
# The operator auto-generates an admin password
ACS_PASS=$($OC get secret central-htpasswd -n $NS_ACS \
    -o jsonpath='{.data.password}' | base64 -d)
echo "ACS admin password: ${ACS_PASS}"

# Get the route URL (or use $ACS_URL from env.sh)
$OC get route central -n $NS_ACS
# Expected output:
# NAME      HOST/PORT                                                                    PATH   SERVICES   PORT    TERMINATION            WILDCARD
# central   central-stackrox.apps.cluster-pmqwq.pmqwq.sandbox270.opentlc.com                   central    https   passthrough/Redirect   None

ACS_URL="https://$($OC get route central -n $NS_ACS -o jsonpath='{.spec.host}')"
echo "ACS Central URL: ${ACS_URL}"

# Health check
curl -sk -u "admin:${ACS_PASS}" "${ACS_URL}/v1/ping"
```

Expected output:

```
{"status":"ok"}
```

> **Quick win:** Open `${ACS_URL}` in your browser and log in with `admin` and the
> password you just retrieved. You should see the ACS dashboard -- but it will be
> mostly empty because no secured cluster is connected yet. That changes in Step 4.

---

## Step 3: Generate the Init Bundle

**WHY this is the step everyone forgets.** Central and SecuredCluster are separate components that need to trust each other over mTLS. The init bundle is a set of TLS certificates that establishes this trust. Without it, Sensor cannot authenticate to Central, and you get cryptic "connection refused" or "certificate unknown" errors.

The init bundle generates three Kubernetes Secrets:

| Secret Name              | Used By              | Contains                              |
|--------------------------|----------------------|---------------------------------------|
| `collector-tls`          | Collector DaemonSet  | TLS cert/key for collector-to-sensor  |
| `sensor-tls`             | Sensor Deployment    | TLS cert/key for sensor-to-central    |
| `admission-control-tls`  | Admission Controller | TLS cert/key for webhook-to-central   |

These secrets MUST exist in the `stackrox` namespace BEFORE you create the SecuredCluster CR. If you apply SecuredCluster first, the pods will crash-loop because they cannot find their TLS certificates.

Look at the generation script:

```bash
# infra/phase5/acs-init-bundle-generate.sh (key section)
#
# The script auto-detects the Central route and admin password,
# then calls roxctl to generate the bundle.

roxctl -e "${CENTRAL_URL}:443" \
    --password "${ADMIN_PASSWORD}" \
    --insecure-skip-tls-verify \
    central init-bundles generate "${BUNDLE_NAME}" \
    --output-secrets "${BUNDLE_OUTPUT}"

# The output is a YAML file containing 3 Kubernetes Secrets.
# Apply it to the stackrox namespace.
$OC apply -f "${BUNDLE_OUTPUT}" -n $NS_ACS
```

### Do: Generate and Apply the Init Bundle

```bash
bash infra/phase5/acs-init-bundle-generate.sh
```

### Verify: Confirm the TLS Secrets Exist

```bash
$OC get secrets -n $NS_ACS | grep -E 'collector-tls|sensor-tls|admission-control-tls'
```

Expected output:

```
admission-control-tls   kubernetes.io/tls   3      15s
collector-tls           kubernetes.io/tls   3      15s
sensor-tls              kubernetes.io/tls   3      15s
```

All three secrets must exist. If any are missing, the corresponding component will fail to start in the next step.

> **Alternative: Generate via the UI.** If `roxctl` is not installed, you can
> generate the init bundle through Central's web UI:
> Platform Configuration --> Integrations --> Cluster Init Bundle --> Generate Bundle.
> Download the Kubernetes secrets YAML and apply it with `oc apply -f`.

---

## Step 4: Deploy SecuredCluster

**WHY:** Central is the brain, but it has no eyes or hands. SecuredCluster deploys the components that actually watch your cluster: Sensor (monitors the K8s API), Collector (monitors runtime processes on every node), and the Admission Controller (blocks bad deployments).

Look at the SecuredCluster CR:

```yaml
# infra/phase5/acs-secured-cluster.yaml

apiVersion: platform.stackrox.io/v1alpha1
kind: SecuredCluster
metadata:
  name: stackrox-secured-cluster-services
  namespace: stackrox
spec:
  clusterName: local-cluster
  centralEndpoint: "central.stackrox.svc:443"    # <-- SEE WARNING BELOW
  sensor:
    resources:
      requests: { cpu: 250m, memory: 512Mi }
      limits:   { cpu: "1",  memory: 2Gi   }
  admissionControl:
    listenOnCreates: true                          # <-- intercept new deployments
    listenOnUpdates: true                          # <-- intercept deployment updates
    listenOnEvents: true                           # <-- monitor K8s events
    contactImageScanners: DoNotScanInline          # <-- don't add scan latency to deploys
    timeoutSeconds: 20
  perNode:
    collector:
      collection: EBPF                             # <-- eBPF mode (no kernel module needed)
    taintToleration: TolerateTaints                 # <-- run on tainted nodes too
```

> **WARNING: The `centralEndpoint` gotcha.**
>
> The default service name that ACS Central creates is `central.stackrox.svc:443`,
> NOT `central-stackrox.stackrox.svc:443`. This is one of the most common
> misconfigurations. If you get it wrong, Sensor will fail to connect with
> TLS handshake errors. Verify the actual service name:
>
> ```bash
> $OC get svc -n $NS_ACS | grep central
> # Look for the service named "central" (port 443)
> ```
>
> If your manifest says `central-stackrox.stackrox.svc:443`, fix it:
>
> ```bash
> # After applying, patch if needed:
> $OC patch securedcluster stackrox-secured-cluster-services -n $NS_ACS \
>     --type merge -p '{"spec":{"centralEndpoint":"central.stackrox.svc:443"}}'
> ```

Key configuration choices:

- **`collection: EBPF`** -- Collector uses eBPF probes instead of a kernel module. eBPF is safer (no kernel changes), works on more OpenShift versions, and requires no special node configuration. This is the recommended mode.
- **`contactImageScanners: DoNotScanInline`** -- When the admission controller intercepts a deployment, it does NOT trigger a full image scan on the spot. That would add 30-60 seconds of latency to every `oc apply`. Instead, it checks against cached scan results. Images are scanned asynchronously when they first appear.
- **`listenOnCreates/Updates`** -- The admission controller is active. It WILL block deployments that violate enforced policies. This is the deploy-time enforcement layer.

### Do: Apply the SecuredCluster CR

```bash
$OC apply -f infra/phase5/acs-secured-cluster.yaml
```

### Verify: Wait for All Components

```bash
# Wait for Sensor
echo "Waiting for Sensor..."
$OC wait --for=condition=ready pod -l app=sensor -n $NS_ACS --timeout=300s

# Wait for Collector DaemonSet (one pod per node)
echo "Waiting for Collector..."
$OC rollout status daemonset/collector -n $NS_ACS --timeout=300s

# Wait for Admission Controller
echo "Waiting for Admission Controller..."
$OC wait --for=condition=ready pod -l app=admission-control -n $NS_ACS --timeout=120s
```

Now verify the full picture:

```bash
$OC get pods -n $NS_ACS
```

Expected output (6-node cluster: 3 masters + 3 workers):

```
NAME                                  READY   STATUS    RESTARTS   AGE
admission-control-6d7e8f9g-a1b2c     1/1     Running   0          60s
admission-control-6d7e8f9g-d3e4f     1/1     Running   0          60s
admission-control-6d7e8f9g-g5h6i     1/1     Running   0          60s
central-5b7f9c4d8f-x2k7p             1/1     Running   0          10m
central-db-7d8f6c5b9a-m4n2p          1/1     Running   0          11m
collector-j7k8l                       3/3     Running   0          45s
collector-m9n0p                       3/3     Running   0          45s
collector-q1r2s                       3/3     Running   0          45s
collector-t3u4v                       3/3     Running   0          45s
collector-w5x6y                       3/3     Running   0          45s
collector-z7a8b                       3/3     Running   0          45s
scanner-6c9d7e8f1b-j3k5l             1/1     Running   0          9m
scanner-db-8e7f6d5c4b-n9m1p          1/1     Running   0          10m
scanner-v4-indexer-5d6e7f8g-h2j4k    1/1     Running   0          9m
scanner-v4-matcher-9a8b7c6d-p5q3r    1/1     Running   0          9m
scanner-v4-db-4f5g6h7i-s8t1u         1/1     Running   0          10m
sensor-3c4d5e6f-y9z0a                1/1     Running   0          50s
```

Key things to check:

- **Collector pods** = number of nodes in your cluster (one per node via DaemonSet).
- **Admission controller** = 3 replicas (HA deployment).
- **No CrashLoopBackOff** -- if Sensor is crash-looping, check `centralEndpoint` and init bundle secrets.

Verify Sensor is connected to Central:

```bash
ACS_PASS=$($OC get secret central-htpasswd -n $NS_ACS -o jsonpath='{.data.password}' | base64 -d)
ACS_URL="https://$($OC get route central -n $NS_ACS -o jsonpath='{.spec.host}')"

# Check cluster health via API
curl -sk -u "admin:${ACS_PASS}" "${ACS_URL}/v1/clusters" | \
    python3 -c "import json,sys; clusters=json.load(sys.stdin)['clusters']; print(f'{len(clusters)} cluster(s) connected'); [print(f'  {c[\"name\"]}: healthStatus={c.get(\"healthStatus\",{}).get(\"overallHealthStatus\",\"UNKNOWN\")}') for c in clusters]"
```

Expected output:

```
1 cluster(s) connected
  local-cluster: healthStatus=HEALTHY
```

> **If Sensor cannot connect to Central:** The two most common causes are:
>
> 1. Wrong `centralEndpoint` -- verify with `$OC get svc central -n $NS_ACS`.
> 2. Missing init bundle secrets -- verify with `$OC get secret sensor-tls -n $NS_ACS`.
>
> Check Sensor logs: `$OC logs deployment/sensor -n $NS_ACS | grep -i error`

---

## Step 5: Scan an Image with roxctl

**WHY:** Before integrating ACS into the pipeline, you should understand what `roxctl` does at the command line. The Jenkins shared library wraps these exact commands, so understanding the raw output helps you debug pipeline failures.

`roxctl` performs two distinct operations:

| Command              | What It Does                                     | When It Fails                          |
|----------------------|--------------------------------------------------|----------------------------------------|
| `roxctl image check` | Evaluates the image against ACS policies         | Image violates an enforced policy      |
| `roxctl image scan`  | Lists all CVEs in the image (vulnerability scan) | N/A -- always succeeds (returns data)  |

The difference matters: `image check` is a pass/fail gate based on your policies. `image scan` is informational -- it dumps every CVE regardless of policy.

### Do: Generate an API Token and Scan

First, create an API token for CLI access (you will also use this token for Jenkins):

```bash
ACS_PASS=$($OC get secret central-htpasswd -n $NS_ACS -o jsonpath='{.data.password}' | base64 -d)
ACS_URL="https://$($OC get route central -n $NS_ACS -o jsonpath='{.spec.host}')"

# Generate a Continuous Integration token
ACS_TOKEN=$(curl -sk -u "admin:${ACS_PASS}" \
    -X POST "${ACS_URL}/v1/apitokens/generate" \
    -H "Content-Type: application/json" \
    -d '{"name":"jenkins-ci","roles":["Continuous Integration"]}' \
    | python3 -c "import sys,json; print(json.load(sys.stdin)['token'])")

echo "ACS API Token generated (first 20 chars): ${ACS_TOKEN:0:20}..."

# Store for Jenkins to use later
$OC create secret generic acs-token \
    --from-literal=token="${ACS_TOKEN}" \
    -n $NS_TOOLS
$OC label secret acs-token team=devsecops component=jenkins -n $NS_TOOLS
```

Now scan a known-good image:

```bash
# IMPORTANT: roxctl reads the token from the ROX_API_TOKEN environment variable.
# It does NOT have a --token flag. This is a common mistake.
export ROX_API_TOKEN="${ACS_TOKEN}"

# Image check -- evaluates against policies
roxctl -e "${ACS_URL}:443" \
    --insecure-skip-tls-verify \
    image check \
    --image "registry.access.redhat.com/ubi9/ubi-minimal:latest"
```

Expected output (abbreviated):

```
Policy check results for image: registry.access.redhat.com/ubi9/ubi-minimal:latest
(TOTAL: X alerts (X low, X medium, X high, X critical))

+----------+----------+----------------+----------+
|  POLICY  | SEVERITY |  DESCRIPTION   | VIOLATED |
+----------+----------+----------------+----------+
| ...      | ...      | ...            | ...      |
+----------+----------+----------------+----------+
```

The exit code tells you the outcome:
- Exit 0 = all policies passed.
- Exit 1 or higher = at least one enforced policy was violated.

Now try `image scan` for the vulnerability list:

```bash
roxctl -e "${ACS_URL}:443" \
    --insecure-skip-tls-verify \
    image scan \
    --image "registry.access.redhat.com/ubi9/ubi-minimal:latest" \
    --output json | python3 -c "
import json, sys
data = json.load(sys.stdin)
components = data.get('result', {}).get('scan', {}).get('components', [])
total_vulns = sum(len(c.get('vulns', [])) for c in components)
print(f'Components scanned: {len(components)}')
print(f'Total vulnerabilities: {total_vulns}')
for sev in ['CRITICAL', 'IMPORTANT', 'MODERATE', 'LOW']:
    count = sum(1 for c in components for v in c.get('vulns', []) if v.get('severity') == sev + '_VULNERABILITY')
    if count > 0:
        print(f'  {sev}: {count}')
"
```

Expected output (varies by image version):

```
Components scanned: 42
Total vulnerabilities: 7
  MODERATE: 5
  LOW: 2
```

### How This Looks in the Jenkins Pipeline

The `scanACSImage.groovy` shared library function wraps these exact commands. Here is the core logic from our actual pipeline integration:

```groovy
// jenkins-shared-lib/vars/scanACSImage.groovy (key excerpt)
//
// roxctl reads ROX_API_TOKEN from env (set by withCredentials above).
// Do NOT use --token flag (doesn't exist) or || true (swallows errors).
// returnStatus:true captures exit code without failing the step.

withCredentials([string(credentialsId: tokenCredId, variable: 'ROX_API_TOKEN')]) {
    // Image check -- evaluates against ACS policies
    def checkExitCode = sh(
        script: """
            roxctl -e "${acsUrl}:443" \\
                --insecure-skip-tls-verify \\
                image check \\
                --image "${imageRef}" \\
                --output json > ${reportDir}/image-check.json
        """,
        returnStatus: true       // <-- captures exit code, doesn't fail the step
    )

    // Image scan -- vulnerability scanning
    def scanExitCode = sh(
        script: """
            roxctl -e "${acsUrl}:443" \\
                --insecure-skip-tls-verify \\
                image scan \\
                --image "${imageRef}" \\
                --output json > ${reportDir}/image-scan.json
        """,
        returnStatus: true
    )
}
```

Notice the pattern: `returnStatus: true` captures the exit code as a variable instead of failing the pipeline step. The function then decides whether to fail based on strict mode vs normal mode:

```groovy
// Strict mode (T3 tag pipeline): fail on ANY critical vuln or policy violation
if (strict && (critical > 0 || checkExitCode != 0)) {
    failed = true
}
// Normal mode (T2 merge pipeline): warn but don't block
else if (checkExitCode != 0) {
    echo "WARNING: ACS policy check failed -- review image-check.json"
}
```

This two-tier enforcement is intentional. The T2 pipeline (merge to main) deploys to DEV -- you want visibility into issues but not a hard block on every policy violation. The T3 pipeline (release tag) produces the image that will eventually reach production -- there, you enforce strictly.

---

## Step 6: Create Security Policies

**WHY:** ACS ships with 70+ built-in policies, but they are mostly set to "inform" mode. For a DevSecOps workflow, you need custom policies that match your organization's security posture and are set to "enforce." You also want policies scoped to your application namespaces, not cluster-wide.

Our project defines six custom policies across the three lifecycle stages:

| Policy Name                               | Stage   | Action                | What It Catches                       |
|-------------------------------------------|---------|-----------------------|---------------------------------------|
| Block Critical CVEs (Build)               | BUILD   | `FAIL_BUILD`          | CVSS >= 9.0 in the image             |
| Block Root User Containers (Deploy)       | DEPLOY  | `SCALE_TO_ZERO`       | Containers running as UID 0          |
| Block Untrusted Registries (Deploy)       | DEPLOY  | `SCALE_TO_ZERO`       | Images from non-approved registries   |
| Require Image Signature (Deploy)          | DEPLOY  | `SCALE_TO_ZERO`       | Unsigned images in production         |
| Detect Cryptocurrency Mining (Runtime)    | RUNTIME | `KILL_POD`            | xmrig, minerd, cpuminer processes     |
| Detect Reverse Shell (Runtime)            | RUNTIME | `KILL_POD`            | bash -i, /dev/tcp, nc -e patterns     |

The policies are stored as JSON files in `infra/phase13/acs-policies/` and imported via the ACS REST API.

Look at the deploy-time "block root" policy -- this is the one you will test in Step 7:

```json
// infra/phase13/acs-policies/deploy-block-root-user.json (key excerpt)
{
  "name": "DevSecOps - Block Root User Containers (Deploy)",
  "lifecycleStages": ["DEPLOY"],
  "severity": "HIGH_SEVERITY",
  "enforcementActions": ["SCALE_TO_ZERO_ENFORCEMENT"],
  "policySections": [
    {
      "sectionName": "Container runs as root",
      "policyGroups": [
        {
          "fieldName": "Container User",
          "values": [{ "value": "0" }]       // <-- matches UID 0 (root)
        }
      ]
    }
  ],
  "scope": [
    { "namespace": "sampleapi-dev" },         // <-- scoped to app namespaces only
    { "namespace": "sampleapi-sit" },
    { "namespace": "sampleapi-uat" },
    { "namespace": "sampleapi-prod" }
  ]
}
```

The enforcement action `SCALE_TO_ZERO_ENFORCEMENT` means: if someone deploys a root-running container, ACS will scale the deployment to zero replicas. The deployment object still exists (for debugging), but no pods run.

### Do: Import the Policies

```bash
ACS_PASS=$($OC get secret central-htpasswd -n $NS_ACS -o jsonpath='{.data.password}' | base64 -d)
export ACS_ADMIN_PASSWORD="${ACS_PASS}"
export ACS_URL="https://$($OC get route central -n $NS_ACS -o jsonpath='{.spec.host}')"
export ACS_CENTRAL_URL="${ACS_URL}"     # apply-acs-policies.sh reads this variable name

bash infra/phase13/apply-acs-policies.sh
```

### Verify: Confirm Policies Are Loaded

```bash
export ROX_API_TOKEN="${ACS_TOKEN}"

curl -sk -H "Authorization: Bearer ${ROX_API_TOKEN}" \
    "${ACS_URL}/v1/policies?query=" | \
    python3 -c "
import json, sys
data = json.load(sys.stdin)
custom = [p for p in data.get('policies', []) if not p.get('isDefault', True)]
print(f'Custom policies: {len(custom)}')
for p in custom:
    lifecycle = ', '.join(p.get('lifecycleStages', []))
    severity = p.get('severity', 'UNKNOWN').replace('_SEVERITY', '')
    enforced = 'ENFORCED' if p.get('enforcementActions') else 'INFORM'
    disabled = ' (DISABLED)' if p.get('disabled', False) else ''
    print(f'  [{lifecycle:7s}] [{severity:8s}] [{enforced:8s}] {p[\"name\"]}{disabled}')
"
```

Expected output:

```
Custom policies: 6
  [BUILD  ] [CRITICAL] [ENFORCED] DevSecOps - Block Critical CVEs (Build)
  [DEPLOY ] [HIGH    ] [ENFORCED] DevSecOps - Block Root User Containers (Deploy)
  [DEPLOY ] [HIGH    ] [ENFORCED] DevSecOps - Block Untrusted Registries (Deploy)
  [DEPLOY ] [MEDIUM  ] [ENFORCED] DevSecOps - Require Image Signature (Deploy) (DISABLED)
  [RUNTIME] [CRITICAL] [ENFORCED] DevSecOps - Detect Cryptocurrency Mining (Runtime)
  [RUNTIME] [CRITICAL] [ENFORCED] DevSecOps - Detect Reverse Shell (Runtime)
```

The signature policy is disabled by default -- it requires a Cosign signing key to be configured first. The other five are active and enforcing.

---

## Step 7: Test the Admission Controller

**WHY:** A security policy is worthless if it does not actually block anything. This step proves that the deploy-time enforcement layer works end-to-end: you will try to deploy a container that runs as root, and ACS will block it.

This is the most satisfying step in the entire module. You get to watch a deployment get rejected in real time.

### Do: Deploy a Root-Running Container

```bash
# Create a test deployment that runs nginx as root (UID 0)
# This SHOULD be blocked by the "Block Root User Containers" policy

cat <<EOF | $OC apply -n $NS_DEV -f -
apiVersion: apps/v1
kind: Deployment
metadata:
  name: test-root-container
  namespace: sampleapi-dev
  labels:
    app: test-root
spec:
  replicas: 1
  selector:
    matchLabels:
      app: test-root
  template:
    metadata:
      labels:
        app: test-root
    spec:
      containers:
      - name: nginx
        image: docker.io/library/nginx:1.25
        ports:
        - containerPort: 80
EOF
```

### Verify: Check That ACS Blocked It

There are two possible outcomes depending on your admission controller configuration:

**Outcome A -- Admission controller rejects the create** (if `contactImageScanners: ScanIfMissing`):

```
Error from server: admission webhook "admission-control.stackrox.io" denied the request:
violated policy "DevSecOps - Block Root User Containers (Deploy)"
```

**Outcome B -- Deployment is created but scaled to zero** (if `contactImageScanners: DoNotScanInline`, which is our configuration):

```bash
# The deployment exists...
$OC get deployment test-root-container -n $NS_DEV

# ...but the pods are scaled to zero or restarting
$OC get pods -l app=test-root -n $NS_DEV
# Expected: 0/1 pods running, or pods in CrashLoopBackOff
```

Check the ACS violations:

```bash
curl -sk -H "Authorization: Bearer ${ROX_API_TOKEN}" \
    "${ACS_URL}/v1/alerts?query=Deployment:test-root-container" | \
    python3 -c "
import json, sys
data = json.load(sys.stdin)
alerts = data.get('alerts', [])
print(f'Violations found: {len(alerts)}')
for a in alerts:
    policy = a.get('policy', {}).get('name', 'unknown')
    state = a.get('state', 'unknown')
    print(f'  Policy: {policy}')
    print(f'  State:  {state}')
    print(f'  Action: {a.get(\"enforcementAction\", \"none\")}')
"
```

Expected output:

```
Violations found: 1
  Policy: DevSecOps - Block Root User Containers (Deploy)
  State:  ACTIVE
  Action: SCALE_TO_ZERO_ENFORCEMENT
```

You can also see the violation in the ACS Central UI under Violations in the left navigation.

### Clean Up the Test

```bash
$OC delete deployment test-root-container -n $NS_DEV
```

> **What about the "Block Untrusted Registries" policy?** The nginx image came from
> `docker.io`, which is not in the trusted registry allow-list. So this test
> deployment would also be caught by that policy. Two violations for the price of one.

---

## Bonus: Configure Internal Registry Integration

**WHY:** When your Jenkins pipeline pushes an image to the OpenShift internal registry and then runs `roxctl image scan`, ACS Central needs to pull that image to scan its layers. Without a registry integration, Central cannot authenticate to the internal registry and you get `401 Unauthorized` errors. The scan produces empty results, which `scanACSImage.groovy` correctly warns about but does not treat as a pass.

```bash
ACS_PASS=$($OC get secret central-htpasswd -n $NS_ACS -o jsonpath='{.data.password}' | base64 -d)
ACS_URL="https://$($OC get route central -n $NS_ACS -o jsonpath='{.spec.host}')"

# Get a token that Central can use to pull images
REGISTRY_TOKEN=$($OC create token jenkins-sa -n $NS_TOOLS --duration=8760h)

# Create the image integration via API
curl -sk -u "admin:${ACS_PASS}" \
    -X POST "${ACS_URL}/v1/imageintegrations" \
    -H "Content-Type: application/json" \
    -d '{
        "name": "OCP Internal Registry",
        "type": "docker",
        "categories": ["REGISTRY"],
        "docker": {
            "endpoint": "image-registry.openshift-image-registry.svc:5000",
            "username": "jenkins-sa",
            "password": "'"${REGISTRY_TOKEN}"'",
            "insecure": true
        },
        "skipTestIntegration": false
    }' | python3 -c "import json,sys; r=json.load(sys.stdin); print(f'Integration ID: {r.get(\"id\",\"FAILED\")}')"
```

Expected output:

```
Integration ID: a1b2c3d4-e5f6-7890-abcd-ef1234567890
```

---

## Recap

Here is what you built in this module and why each piece matters:

| Component              | Purpose                                          | Namespace       |
|------------------------|--------------------------------------------------|-----------------|
| ACS Operator           | Manages Central and SecuredCluster lifecycle     | `rhacs-operator`|
| Central + DB           | Policy engine, vulnerability DB, UI, API         | `stackrox`      |
| Scanner / Scanner V4   | Image layer analysis and CVE detection           | `stackrox`      |
| Init Bundle            | mTLS trust between Central and SecuredCluster    | `stackrox`      |
| Sensor                 | Watches K8s API, reports to Central              | `stackrox`      |
| Collector (DaemonSet)  | eBPF runtime monitoring on every node            | `stackrox`      |
| Admission Controller   | Deploy-time policy enforcement webhook           | `stackrox`      |
| 6 Custom Policies      | Build/deploy/runtime security gates              | `stackrox`      |
| Registry Integration   | Allows Central to pull internal registry images  | `stackrox`      |

The three enforcement layers work together:

1. **Build time** -- Jenkins runs `roxctl image check` and `roxctl image scan`. If a critical CVE is found and the pipeline is in strict mode (T3), the build fails.
2. **Deploy time** -- The admission controller evaluates every deployment against deploy-time policies. Root containers and untrusted registries are blocked.
3. **Runtime** -- Collector watches for process execution anomalies. Crypto miners and reverse shells trigger pod termination.

---

## Common Mistakes

These are real issues encountered during implementation. Each one cost debugging time that you can skip by reading this section.

### 1. Wrong `centralEndpoint` in SecuredCluster

The most common cause of Sensor crash-loops. The service is called `central` (not `central-stackrox`):

```
WRONG:  centralEndpoint: "central-stackrox.stackrox.svc:443"
RIGHT:  centralEndpoint: "central.stackrox.svc:443"
```

Verify with: `$OC get svc -n $NS_ACS | grep central`

### 2. Forgetting the Init Bundle

If you apply SecuredCluster before the init bundle, Sensor, Collector, and Admission Controller will all crash-loop with TLS errors. The fix is simple:

```bash
# Generate and apply the init bundle
bash infra/phase5/acs-init-bundle-generate.sh

# Then delete the crashing pods so they restart with the new secrets
$OC delete pods -n $NS_ACS -l app=sensor
$OC delete pods -n $NS_ACS -l app=collector
$OC delete pods -n $NS_ACS -l app=admission-control
```

### 3. Using `--token` Flag with roxctl

`roxctl` does NOT have a `--token` command-line flag. It reads the API token from the `ROX_API_TOKEN` environment variable. This is handled automatically by Jenkins' `withCredentials` block:

```groovy
// CORRECT -- withCredentials sets ROX_API_TOKEN as an env var
withCredentials([string(credentialsId: 'acs-token', variable: 'ROX_API_TOKEN')]) {
    sh "roxctl -e '${acsUrl}:443' --insecure-skip-tls-verify image check --image '${imageRef}'"
}

// WRONG -- there is no --token flag
sh "roxctl --token '${token}' image check --image '${imageRef}'"
```

### 4. Using `|| true` After roxctl Commands

Never append `|| true` to `roxctl` commands in Jenkins pipelines. It swallows the exit code, causing the pipeline to report SUCCESS even when ACS found critical violations:

```groovy
// WRONG -- || true hides a non-zero exit code
sh "roxctl image check --image '${imageRef}' || true"

// CORRECT -- returnStatus captures the exit code as a variable
def exitCode = sh(
    script: "roxctl image check --image '${imageRef}'",
    returnStatus: true
)
// Now you can decide what to do with exitCode
```

When `|| true` swallows the error, `roxctl` may produce an empty JSON file. The pipeline then parses 0 critical vulnerabilities from the empty file and reports "scan passed" -- a dangerous false positive.

### 5. Missing Registry Integration

Without a registry integration, `roxctl image scan` returns `401 Unauthorized` when scanning images in the OCP internal registry. The scan appears to succeed (exit code 0) but the JSON output is empty or contains no vulnerability data. The `scanACSImage.groovy` function detects this:

```groovy
// Check if scan actually produced valid results
def scanFileSize = sh(
    script: "wc -c < ${reportDir}/image-scan.json 2>/dev/null || echo 0",
    returnStdout: true
).trim()
if (scanFileSize.toInteger() < 10 && scanExitCode != 0) {
    echo "WARNING: ACS image scan failed and produced no results"
}
```

Fix it by adding a registry integration as shown in the Bonus section above.

### 6. roxctl Missing Flags

Some documentation and tutorials reference `--force-print-all-violations`. This flag may not exist in your version of roxctl. Do not add it to your pipeline commands without verifying with `roxctl image check --help` -- an unrecognized flag will cause a hard failure.

---

## Challenge: Create a Custom "No Latest Tag" Policy

Now that you understand how ACS policies work, create one yourself. This policy should:

- **Name:** "DevSecOps - Block Latest Tag (Deploy)"
- **Stage:** DEPLOY
- **Action:** SCALE_TO_ZERO_ENFORCEMENT
- **Rule:** Block any deployment that uses the `:latest` tag (or no tag, which defaults to latest)
- **Scope:** All four `sampleapi-*` namespaces
- **Rationale:** The `:latest` tag is mutable -- it can point to a different image at any time. Deployments should use immutable tags (SHA or semver) for reproducibility.

Hints:

- The ACS policy field for image tags is `"fieldName": "Image Tag"`.
- Use `"value": "latest"` to match the tag.
- Save your policy JSON to `infra/phase13/acs-policies/deploy-block-latest-tag.json`.
- Import it with the same REST API call used in the `apply-acs-policies.sh` script.
- Test it by deploying an image with the `:latest` tag to `sampleapi-dev`.

To verify your solution:

```bash
# This deployment should be blocked by your new policy
cat <<EOF | $OC apply -n $NS_DEV -f -
apiVersion: apps/v1
kind: Deployment
metadata:
  name: test-latest-tag
spec:
  replicas: 1
  selector:
    matchLabels:
      app: test-latest
  template:
    metadata:
      labels:
        app: test-latest
    spec:
      containers:
      - name: test
        image: registry.access.redhat.com/ubi9/ubi-minimal:latest
EOF

# Check for violations
curl -sk -H "Authorization: Bearer ${ROX_API_TOKEN}" \
    "${ACS_URL}/v1/alerts?query=Deployment:test-latest-tag" | python3 -c "
import json, sys
alerts = json.load(sys.stdin).get('alerts', [])
if alerts:
    print('SUCCESS: Your policy caught the :latest tag deployment')
else:
    print('FAIL: No violations found -- check your policy definition')
"

# Clean up
$OC delete deployment test-latest-tag -n $NS_DEV 2>/dev/null
```

---

## Self-Assessment

Answer these questions without scrolling back up. If you cannot answer confidently, re-read the relevant section.

1. **Why does ACS need three enforcement layers?** What does each layer catch that the others cannot?

2. **What happens if you apply the SecuredCluster CR before generating the init bundle?** What are the three secrets the init bundle creates?

3. **What is the difference between `roxctl image check` and `roxctl image scan`?** Which one is the pass/fail gate in the pipeline?

4. **How does `roxctl` authenticate to ACS Central?** (Hint: it is NOT a command-line flag.)

5. **Why is `|| true` dangerous after a `roxctl` command?** What is the correct Jenkins pattern for capturing the exit code?

6. **What does `SCALE_TO_ZERO_ENFORCEMENT` do?** Why is it used instead of rejecting the deployment outright?

7. **What is the correct service name for `centralEndpoint` in the SecuredCluster CR?** Why do people get this wrong?

8. **In our pipeline, what is the difference between strict mode and normal mode for ACS scanning?** Which pipeline trigger uses strict mode and why?

<details>
<summary>Answers</summary>

1. Each layer catches threats the previous one cannot. **Build time** catches known CVEs and policy violations in the image, but cannot detect runtime exploits or zero-days. **Deploy time** catches bad configurations (root user, untrusted registry) that build-time scanning does not evaluate. **Runtime** detects active exploitation (crypto miners, reverse shells) that only manifests when code is actually executing. No single layer covers everything.

2. Sensor, Collector, and Admission Controller will all crash-loop with TLS handshake errors because they cannot authenticate to Central. The three secrets are: `collector-tls` (Collector-to-Sensor TLS), `sensor-tls` (Sensor-to-Central TLS), and `admission-control-tls` (Admission Controller-to-Central TLS).

3. `roxctl image check` evaluates the image against your ACS policies and returns a pass/fail exit code -- it is the gate in the pipeline. `roxctl image scan` lists all CVEs in the image regardless of policy -- it is informational and always succeeds (exit code 0).

4. `roxctl` reads the API token from the `ROX_API_TOKEN` environment variable. There is no `--token` command-line flag. In Jenkins, `withCredentials([string(credentialsId: 'acs-token', variable: 'ROX_API_TOKEN')])` sets this automatically.

5. `|| true` forces the exit code to 0 regardless of what `roxctl` returned. This means the pipeline reports SUCCESS even when ACS found critical violations. Worse, `roxctl` may produce an empty or partial JSON file, which the pipeline parses as "0 critical vulnerabilities" -- a dangerous false positive. The correct pattern is `returnStatus: true`, which captures the exit code as a variable for the function to evaluate.

6. `SCALE_TO_ZERO_ENFORCEMENT` scales the deployment's replica count to zero. The deployment object remains (so you can inspect its spec for debugging), but no pods run. It is used instead of outright rejection because our admission controller uses `DoNotScanInline` mode -- it does not block at admission time but enforces asynchronously after the deployment is created.

7. The correct endpoint is `central.stackrox.svc:443`. People get this wrong because they assume the service name matches the route hostname pattern (`central-stackrox`), but the ACS operator creates the service with just `central` as the name. Verify with `oc get svc -n stackrox | grep central`.

8. **Normal mode** (T2 merge pipeline) warns about policy violations and critical CVEs but does not fail the build -- it deploys to DEV for fast developer feedback. **Strict mode** (T3 tag pipeline) fails the build if any critical CVE or policy violation is found, because T3 produces the release-ready image that will eventually reach production. Strict mode is the hard gate before an image can be promoted beyond DEV.

</details>

---

## What's Next

**Module 7: DAST with OWASP ZAP** covers the final security layer -- testing the running application for vulnerabilities that only appear at runtime (XSS, SQL injection, insecure headers). You will deploy ZAP as a sidecar container in the T3 pipeline agent pod and scan your application's HTTP endpoints. While ACS catches known CVEs in your image layers, ZAP catches vulnerabilities in your application's behavior that no static analysis can find.

---

## File Reference

All files referenced in this module:

| File | Purpose |
|------|---------|
| `infra/phase5/acs-operator-subscription.yaml` | OLM Subscription + namespaces for ACS operator |
| `infra/phase5/acs-central.yaml` | Central CR (management plane) |
| `infra/phase5/acs-secured-cluster.yaml` | SecuredCluster CR (data plane) |
| `infra/phase5/acs-init-bundle-generate.sh` | Script to generate mTLS init bundle |
| `infra/phase5/acs-policies/block-critical-cves.yaml` | Build-time: block CVSS >= 9.0 |
| `infra/phase5/acs-policies/block-root-images.yaml` | Deploy-time: block root containers |
| `infra/phase5/acs-policies/block-untrusted-registries.yaml` | Deploy-time: registry allow-list |
| `infra/phase5/acs-policies/detect-runtime-threats.yaml` | Runtime: shell + crypto miner detection |
| `infra/phase13/acs-policies/build-block-critical-cves.json` | Policy JSON for API import |
| `infra/phase13/acs-policies/deploy-block-root-user.json` | Policy JSON for API import |
| `infra/phase13/acs-policies/deploy-block-untrusted-reg.json` | Policy JSON for API import |
| `infra/phase13/acs-policies/deploy-require-signature.json` | Policy JSON for API import (disabled) |
| `infra/phase13/acs-policies/runtime-detect-cryptominer.json` | Policy JSON for API import |
| `infra/phase13/acs-policies/runtime-detect-reverse-shell.json` | Policy JSON for API import |
| `infra/phase13/apply-acs-policies.sh` | Script to import all policies via REST API |
| `jenkins-shared-lib/vars/scanACSImage.groovy` | Jenkins shared library ACS integration |
