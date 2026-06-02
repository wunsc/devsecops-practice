# Module 17: SBOM Generation and Vulnerability Analysis

| | |
|---|---|
| **Track** | Supply Chain & Multi-Language |
| **Duration** | ~75 minutes |
| **Difficulty** | Intermediate |
| **Prerequisites** | Module 16 (Java pipelines working), Module 5 (SonarQube SAST concepts) |

---

## What You'll Learn

By the end of this module, you will be able to:

1. Explain what a Software Bill of Materials (SBOM) is, why governments now mandate it, and how it differs from SCA (Dependency-Check)
2. Deploy Red Hat Trusted Profile Analyzer (RHTPA / Trustify) on OpenShift with Keycloak authentication, importers, and a PostgreSQL backend
3. Generate CycloneDX SBOMs for both .NET and Java projects using the appropriate tooling
4. Upload SBOMs to Trustify via its REST API and interpret the vulnerability analysis results
5. Integrate SBOM generation and vulnerability gating into all three pipeline triggers (T1, T2, T3) with different strictness levels
6. Distinguish between the SBOM vulnerability gate and other security gates (SAST, SCA, ACS) -- what each catches that the others miss

---

## Prerequisites

Before starting this module, confirm:

- [ ] Module 16 complete -- Java pipelines (order-service) building and deploying successfully
- [ ] .NET pipelines (sampleapi) still working end-to-end (T1/T2/T3)
- [ ] Jenkins agent image built with `dotnet-CycloneDX` pre-installed
- [ ] `oc` CLI authenticated with cluster-admin access
- [ ] Familiarity with OWASP Dependency-Check from Module 5 (SCA concepts)

> **Environment variables:** Before running any commands, source the environment file:
> ```bash
> source ./env.sh
> ```
> This sets `$OC`, `$APPS_DOMAIN`, `$NS_RHTPA`, `$TRUSTIFY_URL`, and all other cluster-specific variables used throughout this module. See `env.sh` for the full variable list.

```bash
# Quick prerequisite check
$OC whoami                          # Expected: admin (or your cluster-admin user)
$OC get pods -n $NS_TOOLS -l app=jenkins  # Expected: jenkins pod Running
$OC get ns $NS_RHTPA 2>/dev/null || echo "Namespace will be created in this module"
```

---

## 1. Concepts: Software Supply Chain Security (15 min)

### The Supply Chain Problem

Your application is not just the code you write. It is the code you write *plus* every library you depend on, every base image layer beneath it, and every tool that touched it during the build. A modern .NET application might have 15 direct NuGet dependencies, but after transitive resolution, the final artifact contains 170+ packages. A Java Spring Boot application pulls in 90+ JARs from Maven Central.

You did not write any of that code. You did not review it. You probably have not even read the license. But when it runs in your production cluster, it runs with your security posture.

### Two Incidents That Changed Everything

**Log4Shell (CVE-2021-44228, December 2021):** A critical remote code execution vulnerability in Apache Log4j, a Java logging library so ubiquitous that most organizations did not even know they had it. The first question every CISO asked was: "Which of our applications use Log4j?" Most could not answer. They did not have an inventory of their software components.

**xz-utils backdoor (CVE-2024-3094, March 2024):** A sophisticated, multi-year social engineering attack that inserted a backdoor into the xz compression library. The backdoor targeted SSH authentication on Linux systems. It was caught by accident -- a developer noticed SSH was 500ms slower than expected and investigated. Without that lucky observation, the backdoor would have shipped in every major Linux distribution.

Both incidents share the same root cause: **organizations did not know what was inside their software.**

### SBOM: The Ingredient Label for Software

An SBOM is a structured, machine-readable list of every component in a software artifact. Think of it as the ingredient label on food packaging:

```
+----------------------------------+       +----------------------------------+
|   FOOD INGREDIENT LABEL          |       |   SOFTWARE BILL OF MATERIALS     |
+----------------------------------+       +----------------------------------+
| Product: Chocolate Chip Cookie   |       | Component: SampleApi v1.0.0      |
|                                  |       |                                  |
| Ingredients:                     |       | Components:                      |
|  - Flour (wheat)                 |       |  - Microsoft.AspNetCore.OpenApi   |
|  - Sugar                         |       |    8.0.0 (pkg:nuget/...)         |
|  - Butter (cream, salt)          |       |  - Swashbuckle.AspNetCore 7.2.0  |
|  - Chocolate chips               |       |    (pkg:nuget/...)               |
|    (cocoa, sugar, cocoa butter)  |       |  - Newtonsoft.Json 13.0.3        |
|  - Eggs                          |       |    (pkg:nuget/...)               |
|  - Vanilla extract               |       |  - System.Text.Json 8.0.0       |
|                                  |       |    (pkg:nuget/...)               |
| Contains: Wheat, Dairy, Eggs     |       | Known vulnerabilities: 2 Medium  |
| Allergen warning: Tree nuts      |       | License conflicts: 0             |
+----------------------------------+       +----------------------------------+
```

Just as food labels let you check for allergens, SBOMs let you check for vulnerable components. When the next Log4Shell drops, you run a query against your SBOM database: "Which of my artifacts contain this library?" -- and you get an answer in seconds, not weeks.

### Executive Order 14028

In May 2021, the U.S. Executive Order 14028 ("Improving the Nation's Cybersecurity") mandated that all software sold to the federal government must include an SBOM. This was not a suggestion. It was a procurement requirement.

The ripple effects are global:

- **EU Cyber Resilience Act (CRA):** Requires SBOMs for software products sold in the EU (effective 2027)
- **NIST SP 800-218 (SSDF):** References SBOMs as a core practice for secure software development
- **Banking regulators (OCC, FFIEC):** Increasingly requiring software composition analysis and inventory for third-party risk management

If you are building software for government, financial services, or healthcare, SBOM generation is not optional -- it is a compliance requirement.

### CycloneDX vs SPDX

Two SBOM formats dominate the industry:

| Aspect | CycloneDX | SPDX |
|--------|-----------|------|
| **Origin** | OWASP (security-focused) | Linux Foundation (legal-focused) |
| **Primary use case** | Vulnerability analysis, VEX | License compliance |
| **Spec version** | 1.6 (latest) | 2.3 / 3.0 |
| **Formats** | JSON, XML, Protobuf | JSON, RDF, YAML, Tag-Value |
| **VEX support** | Native (CycloneDX VEX) | Separate document (SPDX 3.0) |
| **Tooling** | Excellent (.NET, Java, Go, JS) | Good (broader language coverage) |
| **ISO standard** | ISO/IEC 27036 reference | ISO/IEC 5962:2021 |

We use **CycloneDX** because:
1. It was designed for security analysis from the start
2. Trustify/RHTPA natively ingests CycloneDX format
3. The tooling for .NET (`dotnet-CycloneDX`) and Java (`cyclonedx-maven-plugin`) is mature and well-maintained
4. OWASP Dependency-Check (which we already use) outputs CycloneDX-compatible results

### Where SBOM Fits in the Pipeline

SBOM generation happens after the build, alongside other security scans:

```
Checkout --> Build --> Unit Tests --> SAST --> SCA --> SBOM (here) --> Container Build --> ...
                                      |        |       |
                                      |        |       +-- "What components are inside,
                                      |        |           and do any have known CVEs?"
                                      |        |           (CycloneDX + Trustify)
                                      |        |
                                      |        +---------- "Do my declared dependencies
                                      |                    have known CVEs?"
                                      |                    (OWASP Dependency-Check)
                                      |
                                      +------------------- "Does my source code have
                                                           bugs or vulnerabilities?"
                                                           (SonarQube)
```

> **Why this matters:** SBOM and SCA (Dependency-Check) seem to do the same thing, but they are complementary. Dependency-Check scans the project *declaration files* (`.csproj`, `pom.xml`) against the NVD database. SBOM generation resolves the *actual dependency tree* (including transitive dependencies), exports it as a structured document, and uploads it to Trustify where it is cross-referenced against Red Hat CSAF advisories, CVE.org data, and CWE weakness catalogs. Dependency-Check might miss a transitive dependency that the SBOM captures. The SBOM also serves as a compliance artifact -- a deliverable you attach to your release.

### SBOM vs SCA: A Concrete Example

Consider a .NET project with this dependency chain:

```
Your csproj declares:
  Microsoft.AspNetCore.OpenApi 8.0.0
    └── Microsoft.OpenApi 1.6.11
        └── Microsoft.OpenApi.Readers 1.6.11
            └── SharpYaml 2.1.0        <-- transitive, 3 levels deep
```

- **SCA (Dependency-Check):** Scans your `.csproj` file. May or may not resolve transitive deps depending on configuration. Reports against NVD.
- **SBOM (CycloneDX + Trustify):** Resolves the full dependency tree *after* `dotnet restore`. Lists all 173 actual packages. Uploads to Trustify for matching against Red Hat security advisories (CSAF) which include patches and mitigations, not just CVE IDs.

The SBOM gives you the complete picture. SCA gives you a fast scan. Use both.

---

## 2. Deploy Trustify / RHTPA (15 min)

Red Hat Trusted Profile Analyzer (RHTPA) provides the Trustify server -- a vulnerability analysis platform that ingests SBOMs, cross-references them against advisory databases, and reports which components have known vulnerabilities.

### Architecture

```
  $NS_RHTPA namespace (test-app)
  +---------------------------------------------------+
  |                                                   |
  |  +------------------+    +---------------------+  |
  |  | Trustify Server  |    | Trustify Importer   |  |
  |  | (REST API +      |    | (Fetches advisories |  |
  |  |  vulnerability   |    |  from CVE.org,      |  |
  |  |  analysis engine)|    |  Red Hat CSAF,      |  |
  |  +--------+---------+    |  CWE database)      |  |
  |           |              +-----+---------------+  |
  |           v                    |                   |
  |  +--------+---------+         |                   |
  |  | PostgreSQL       |<--------+                   |
  |  | (10Gi PVC)       |  stores advisories,         |
  |  | 103K+ advisories |  SBOMs, analysis results    |
  |  | 969 CWE entries  |                              |
  |  +------------------+                              |
  +---------------------------------------------------+
          ^
          | HTTPS (in-cluster: server.test-app.svc)
          |
  +-------+----------+       +-----------------------+
  | Jenkins Pipeline  |       | Keycloak              |
  | (generateSBOM.   |       | trustify realm         |
  |  groovy)          |------>| walker client          |
  |                   | OAuth | (client_credentials)  |
  +-------------------+       +-----------------------+
```

### 2.1 Install the RHTPA Operator

The RHTPA operator manages the Trustify lifecycle -- deployment, upgrades, database migrations.

```yaml
# infra/phase26/rhtpa-operator-subscription.yaml
# ← THIS IS KEY: The operator watches for Trustify CRs in all namespaces
apiVersion: operators.coreos.com/v1alpha1
kind: Subscription
metadata:
  name: rhtpa-operator                    # ← THIS IS KEY: operator name
  namespace: openshift-operators          # ← THIS IS KEY: global operator namespace
spec:
  channel: stable                         # ← THIS IS KEY: use stable channel
  installPlanApproval: Automatic
  name: rhtpa-operator
  source: redhat-operators
  sourceNamespace: openshift-marketplace
```

Apply and wait for the operator to become ready:

```bash
# Create the namespace where Trustify will run
$OC create namespace $NS_RHTPA --dry-run=client -o yaml | $OC apply -f -

# Install the operator
$OC apply -f infra/phase26/rhtpa-operator-subscription.yaml

# Wait for the operator pod to be ready (takes 1-2 minutes)
$OC wait --for=condition=ready pod \
  -l app.kubernetes.io/name=rhtpa-operator \
  -n openshift-operators --timeout=180s
```

### Verify: Operator Installed

```bash
$OC get csv -n openshift-operators | grep rhtpa
# Expected output:
# rhtpa-operator.v1.x.x   Red Hat Trusted Profile Analyzer   Succeeded
```

### 2.2 Create the Trustify Instance

The Trustify Custom Resource defines the server, importer, and database:

```yaml
# infra/phase26/trustify-cr.yaml
# ← THIS IS KEY: This single CR deploys Server + Importer + PostgreSQL
apiVersion: trustification.redhat.com/v1alpha1
kind: Trustify
metadata:
  name: trustify                          # ← THIS IS KEY: instance name
  namespace: test-app                     # ← THIS IS KEY: must match $NS_RHTPA
spec:
  # --- Database ---
  database:
    storage:
      size: 10Gi                          # ← THIS IS KEY: PVC for advisory data
      storageClassName: gp3-csi           # ← THIS IS KEY: match your cluster's StorageClass

  # --- Importers ---
  # These fetch advisory data from external sources on a schedule
  importers:
    - name: cve-database                  # ← THIS IS KEY: CVE.org vulnerability database
      enabled: true
      schedule: "0 */6 * * *"             # Every 6 hours
    - name: redhat-csaf                   # ← THIS IS KEY: Red Hat security advisories (CSAF v2)
      enabled: true
      schedule: "0 */6 * * *"
    - name: cwe-database                  # ← THIS IS KEY: Common Weakness Enumeration
      enabled: true
      schedule: "0 0 * * 0"               # Weekly (CWE changes slowly)

  # --- Server ---
  server:
    replicas: 1
    resources:
      requests:
        cpu: 200m
        memory: 512Mi
      limits:
        cpu: "1"
        memory: 2Gi
```

Apply and wait:

```bash
$OC apply -f infra/phase26/trustify-cr.yaml

# Wait for all Trustify pods to be ready (first boot takes 3-5 minutes)
$OC wait --for=condition=ready pod \
  -l app.kubernetes.io/part-of=trustify \
  -n $NS_RHTPA --timeout=300s
```

### Verify: Trustify Pods Running

```bash
$OC get pods -n $NS_RHTPA -l app.kubernetes.io/part-of=trustify
# Expected output:
# NAME                                  READY   STATUS    RESTARTS   AGE
# trustify-server-5c8d7b6f4-x2k9p      1/1     Running   0          3m
# trustify-importer-7b9c4d8f2-m4n7q    1/1     Running   0          3m
# trustify-postgresql-0                 1/1     Running   0          4m
```

### 2.3 Set Up Keycloak Authentication

Trustify uses Keycloak for API authentication. The `walker` client authenticates Jenkins using the `client_credentials` OAuth2 grant -- no user interaction required.

The Keycloak instance is typically deployed alongside the cluster (or as part of RHTPA). You need to configure:

1. A `trustify` realm
2. A `walker` client with `client_credentials` grant type
3. Scopes: `create:document`, `read:document`, `update:document`, `delete:document`

```yaml
# infra/phase26/trustify-keycloak-client.yaml
# ← THIS IS KEY: Client secret for Jenkins → Trustify authentication
apiVersion: v1
kind: Secret
metadata:
  name: trustify-walker-secret            # ← THIS IS KEY: stores the client secret
  namespace: test-app
  labels:
    app: trustify
    component: keycloak
type: Opaque
stringData:
  # ← THIS IS KEY: This is the Keycloak client secret for the 'walker' client
  # Generate with: openssl rand -hex 32
  # Must match the client secret configured in Keycloak's trustify realm
  client-secret: "<YOUR-WALKER-CLIENT-SECRET>"
```

```bash
# Apply the secret
$OC apply -f infra/phase26/trustify-keycloak-client.yaml
```

### 2.4 Create the Jenkins Credential

Jenkins needs to access the walker client secret to obtain OAuth2 tokens from Keycloak:

```bash
# Store the walker client secret where Jenkins can use it via withCredentials()
# ← THIS IS KEY: credential ID must be 'trustify-walker-token' (hardcoded in generateSBOM.groovy)
$OC create secret generic trustify-walker-token \
  --from-literal=text=<YOUR-WALKER-CLIENT-SECRET> \
  -n $NS_TOOLS

# Label for discoverability
$OC label secret trustify-walker-token \
  team=devsecops component=jenkins credential-type=trustify -n $NS_TOOLS
```

> **Why this matters:** The credential ID `trustify-walker-token` is referenced directly in `generateSBOM.groovy` at line 291. If you name the secret differently, the `withCredentials` block will throw a `CredentialNotFoundException` and the function will skip vulnerability analysis entirely (it catches the exception and logs a warning, but no SBOM upload or gate check occurs).

### 2.5 Verify Trustify API Access

```bash
# Get the Keycloak URL (from env.sh or discover it)
KEYCLOAK_URL="https://keycloak.${APPS_DOMAIN}"

# Step 1: Get an access token from Keycloak
WALKER_SECRET=$($OC get secret trustify-walker-secret -n $NS_RHTPA \
  -o jsonpath='{.data.client-secret}' | base64 -d)

TOKEN=$(curl -sfk -X POST \
  "${KEYCLOAK_URL}/realms/trustify/protocol/openid-connect/token" \
  -d "client_id=walker" \
  -d "client_secret=${WALKER_SECRET}" \
  -d "grant_type=client_credentials" \
  -d "scope=create:document read:document" | \
  python3 -c "import json,sys; print(json.load(sys.stdin).get('access_token',''))")

echo "Token obtained: $(echo $TOKEN | cut -c1-20)..."
# Expected: Token obtained: eyJhbGciOiJSUzI1NiIs...

# Step 2: Hit the Trustify API
curl -sfk -H "Authorization: Bearer $TOKEN" \
  "$TRUSTIFY_URL/api/v2/sbom?limit=1" | python3 -m json.tool
# Expected (on first run, no SBOMs uploaded yet):
# {
#     "items": [],
#     "total": 0
# }
```

### 2.6 Verify Importers Running

The importers fetch advisory data from external sources. After initial setup, they populate the database with vulnerability information that Trustify uses to analyze your SBOMs.

```bash
# Check importer status
$OC get pods -n $NS_RHTPA -l app.kubernetes.io/component=importer
# Expected output:
# NAME                                      READY   STATUS    RESTARTS   AGE
# trustify-importer-7b9c4d8f2-m4n7q        1/1     Running   0          10m

# Check importer logs for data counts
$OC logs -n $NS_RHTPA -l app.kubernetes.io/component=importer --tail=20
# Look for lines like:
#   Imported 103472 advisories from cve-database
#   Imported 4521 advisories from redhat-csaf
#   Imported 969 weaknesses from cwe-database
```

> **Why this matters:** If the importers have not completed their initial run, Trustify has no advisory data to match against your SBOMs. The vulnerability analysis will return 0 findings -- not because your software is safe, but because the database is empty. Always verify importer completion before trusting gate results. The initial import takes 10-30 minutes depending on network speed.

### What We Just Deployed

| Component | Namespace | Purpose | Access |
|-----------|-----------|---------|--------|
| Trustify Server | `test-app` | SBOM ingestion + vulnerability analysis REST API | `https://server.test-app.svc` (in-cluster) |
| Trustify Importer | `test-app` | Fetches CVE, CSAF, CWE advisory data on schedule | Internal (no external access) |
| PostgreSQL | `test-app` | Stores advisories, SBOMs, analysis results (10Gi PVC) | Internal (no external access) |
| Keycloak `walker` client | Keycloak | OAuth2 `client_credentials` auth for Jenkins | Keycloak admin console |
| Jenkins credential | `devsecops-tools` | Stores walker client secret | Jenkins Credentials page |

---

## 3. Understand generateSBOM.groovy (10 min)

The `generateSBOM.groovy` function in `jenkins-shared-lib/vars/` implements the complete SBOM lifecycle in four steps. Let us walk through each one.

### 3.1 The Four-Step Flow

```
  +------------------+     +------------------+     +-------------------+     +------------------+
  |  Step 1: GENERATE |     |  Step 2: UPLOAD  |     |  Step 3: POLL     |     |  Step 4: GATE    |
  |  CycloneDX SBOM  |---->|  POST to         |---->|  Wait for vuln    |---->|  Check thresholds|
  |  (.NET or Java)  |     |  Trustify API    |     |  analysis results |     |  critical/high   |
  +------------------+     +------------------+     +-------------------+     +------------------+
        |                        |                        |                        |
  dotnet-CycloneDX         OAuth2 token from        GET /api/v2/sbom/         maxCritical=0
  or cyclonedx-maven       Keycloak, then           {id}/advisories           maxHigh=5
  plugin                   POST /api/v2/sbom        (10 attempts x 5s)        PASS or FAIL
```

### 3.2 Function Signature and Parameters

```groovy
// From jenkins-shared-lib/vars/generateSBOM.groovy
def call(Map config = [:]) {
    def project      = config.project      ?: '.'          // ← project root directory
    def language     = config.language     ?: 'dotnet'     // ← 'dotnet' or 'java'
    def serviceName  = config.serviceName  ?: 'unknown'    // ← used in SBOM filename
    def imageTag     = config.imageTag     ?: 'latest'     // ← used in SBOM filename
    def trustifyUrl  = config.trustifyUrl  ?: 'https://server.test-app.svc'
    def keycloakUrl  = config.keycloakUrl  ?: env.KEYCLOAK_URL ?: '...'
    def maxCritical  = config.maxCritical != null ? config.maxCritical : 0   // ← gate threshold
    def maxHigh      = config.maxHigh != null ? config.maxHigh : 5          // ← gate threshold
    // ...
}
```

Every parameter has a sensible default. The function is safe to call with just `generateSBOM(project: '.')`, but the orchestrators pass explicit values for clarity and traceability.

Note the `!= null` check for `maxCritical` and `maxHigh`. This is intentional. Without it, passing `maxCritical: 0` would be falsy in Groovy and would trigger the default value. The `!= null` check ensures that an explicit `0` is preserved.

### 3.3 Step 1: Generate CycloneDX SBOM

The function detects the language and runs the appropriate CycloneDX tool:

**.NET path:**

```groovy
// Verify CycloneDX CLI is pre-installed in agent image
def toolCheck = sh(script: 'command -v dotnet-CycloneDX 2>/dev/null || echo MISSING',
                   returnStdout: true).trim()
if (toolCheck == 'MISSING') {
    error "dotnet-CycloneDX not found in agent image."
}

// Find the solution or project file
// ← THIS IS KEY: Searches for .sln first, falls back to .csproj
sh """
    SLN=\$(find . -maxdepth 2 -name '*.sln' | head -1)
    [ -z "\$SLN" ] && SLN=\$(find . -maxdepth 3 -name '*.csproj' | head -1)
    dotnet-CycloneDX \$SLN -o \${WORKSPACE} -F Json -fn ${sbomFile} -spv 1.5
"""
```

**Java path:**

```groovy
// Uses the CycloneDX Maven plugin (no pre-install required)
// ← THIS IS KEY: Plugin version 2.8.0 is specified inline, not in pom.xml
sh """
    cd ${project}
    mvn org.cyclonedx:cyclonedx-maven-plugin:2.8.0:makeAggregateBom \
        -DoutputFormat=json -DoutputName=bom \
        -Dmaven.repo.local=\${WORKSPACE}/.m2 -B
"""
// Copy the generated SBOM to workspace root
sh "cp ${project}/target/bom.json \${WORKSPACE}/${sbomFile}"
```

After generation, the function verifies the SBOM exists and counts components:

```groovy
componentCount = sh(script: """
    python3 -c "import json; d=json.load(open('${sbomFile}')); \
    print(len(d.get('components',[])))"
""", returnStdout: true).trim() as int

echo "  SBOM generated: ${componentCount} components"

// Archive as Jenkins build artifact for later retrieval
archiveArtifacts artifacts: sbomFile, allowEmptyArchive: true
```

### 3.4 Step 2: Upload to Trustify

Authentication happens through a private helper function that obtains an OAuth2 token from Keycloak:

```groovy
private String getTrustifyToken(String keycloakUrl) {
    withCredentials([string(credentialsId: 'trustify-walker-token',
                            variable: 'WALKER_SECRET')]) {
        return sh(script: """
            curl -sfk -X POST \
              "${keycloakUrl}/realms/trustify/protocol/openid-connect/token" \
              -d "client_id=walker" \
              -d "client_secret=\${WALKER_SECRET}" \
              -d "grant_type=client_credentials" \
              -d "scope=create:document read:document" | \
              python3 -c "import json,sys; \
                print(json.load(sys.stdin).get('access_token',''))"
        """, returnStdout: true).trim()
    }
}
```

The upload uses `POST /api/v2/sbom` with the SBOM JSON as the request body. It retries up to 3 times with exponential backoff:

```groovy
// ← THIS IS KEY: X-Document-Id header provides a unique identifier for the SBOM
def docId = sh(script: "cat /proc/sys/kernel/random/uuid", returnStdout: true).trim()

def uploadHttp = sh(script: """
    curl -sk -X POST "${trustifyUrl}/api/v2/sbom" \
        -H "Authorization: Bearer ${trustifyToken}" \
        -H "Content-Type: application/json" \
        -H "X-Document-Id: urn:uuid:${docId}" \
        --data-binary @\${WORKSPACE}/${sbomFile} \
        -w '%{http_code}' -o /tmp/sbom-upload.json
""", returnStdout: true).trim()
```

On success (HTTP 201), the response contains the SBOM ID which is needed for polling:

```groovy
sbomId = sh(script: """
    python3 -c "import json; \
      print(json.load(open('/tmp/sbom-upload.json')).get('id',''))"
""", returnStdout: true).trim()
```

### 3.5 Step 3: Poll for Vulnerability Analysis

After upload, Trustify analyzes the SBOM asynchronously -- matching each component against its advisory database. The function polls for results:

```groovy
// ← THIS IS KEY: SBOM IDs contain colons, which must be URL-encoded
def encodedIdForPoll = sbomId.replace(':', '%3A')

for (int attempt = 1; attempt <= 10; attempt++) {        // 10 attempts
    def checkHttp = sh(script: """
        curl -sk -o /dev/null -w '%{http_code}' \
            -H "Authorization: Bearer ${trustifyToken}" \
            "${trustifyUrl}/api/v2/sbom/${encodedIdForPoll}/advisories?limit=1"
    """, returnStdout: true).trim()

    if (checkHttp == '200') {                             // Analysis ready
        analysisReady = true
        break
    }
    sleep 5                                               // 5 seconds between polls
}
```

Once ready, it fetches the full advisory data and parses severity counts using an inline Python script that handles the variable JSON structure of Trustify's response.

### 3.6 Step 4: Gate Check

The gate is simple -- compare counts against thresholds:

```groovy
if (criticalCount > maxCritical) {
    gateResult = 'FAILED'
    echo "  GATE FAILED: ${criticalCount} critical vulnerabilities (max: ${maxCritical})"
} else if (highCount > maxHigh) {
    gateResult = 'FAILED'
    echo "  GATE FAILED: ${highCount} high vulnerabilities (max: ${maxHigh})"
} else {
    echo "  GATE PASSED: within thresholds"
}
```

### 3.7 Return Map

The function returns a structured map with everything the orchestrator needs:

```groovy
return [
    status: status,              // 'SUCCESS' or 'FAILURE'
    duration: duration,          // seconds elapsed
    sbomFile: sbomFile,          // filename for artifact reference
    components: componentCount,  // number of components in SBOM
    uploadStatus: uploadStatus,  // 'SUCCESS', 'FAILED', or 'SKIPPED'
    sbomId: sbomId,              // Trustify SBOM identifier
    gateResult: gateResult,      // 'PASSED' or 'FAILED'
    critical: criticalCount,     // severity counts
    high: highCount,
    medium: mediumCount,
    low: lowCount,
    totalVulns: totalVulns,
    findings: vulnDetails.take(5).join('; '),  // top 5 findings summary
    trustifyUrl: sbomId ? "${trustifyUrl}/#/sboms/${sbomId}" : ''
]
```

This return map is consumed by `commentOnMR.groovy` to render the SBOM section in MR comments, and by `createPromotionMR.groovy` to include vulnerability data in promotion MR descriptions.

---

## 4. Generate an SBOM Manually (10 min)

Before running the pipeline, let us generate SBOMs by hand to understand what the tools produce. This builds intuition for interpreting pipeline results.

### 4.1 .NET SBOM Generation

Exec into a Jenkins agent pod (or any pod with the dotnet SDK and CycloneDX installed):

```bash
# Find a running Jenkins agent pod, or use the Jenkins controller
AGENT_POD=$($OC get pods -n $NS_TOOLS -l jenkins=agent -o name | head -1)

# If no agent pod is running, we can do this from a temporary pod:
$OC run sbom-test --rm -it --restart=Never -n $NS_TOOLS \
  --image=image-registry.openshift-image-registry.svc:5000/${NS_TOOLS}/jenkins-agent-devsecops:latest \
  -- bash
```

Inside the pod (or locally if you have the tools):

```bash
# Clone the sampleapi source
git clone https://gitlab-devsecops-gitlab.${APPS_DOMAIN}/devsecops/app-source.git
cd app-source

# Restore NuGet packages (required for SBOM -- it reads the resolved dependency tree)
dotnet restore

# Generate the CycloneDX SBOM
# ← THIS IS KEY: -F Json produces JSON format, -spv 1.5 sets spec version
dotnet-CycloneDX *.sln -o . -F Json -fn sbom-sampleapi.json -spv 1.5

# Check the output
ls -lh sbom-sampleapi.json
# Expected: -rw-r--r-- 1 user user 85K ... sbom-sampleapi.json
```

### 4.2 Inspect the SBOM Structure

```bash
# View the metadata (what this SBOM describes)
python3 -c "
import json
d = json.load(open('sbom-sampleapi.json'))
print(f'Format:    {d[\"bomFormat\"]}')
print(f'Spec:      {d[\"specVersion\"]}')
print(f'Component: {d[\"metadata\"][\"component\"][\"name\"]}')
print(f'Version:   {d[\"metadata\"][\"component\"][\"version\"]}')
print(f'Total:     {len(d[\"components\"])} components')
print(f'Deps:      {len(d.get(\"dependencies\",[]))} dependency entries')
"
# Expected output:
# Format:    CycloneDX
# Spec:      1.5
# Component: SampleApi
# Version:   1.0.0
# Total:     173 components
# Deps:      174 dependency entries
```

```bash
# List the first 10 components (name, version, type)
python3 -c "
import json
d = json.load(open('sbom-sampleapi.json'))
for c in d['components'][:10]:
    print(f\"  {c['type']:10} {c['name']:45} {c['version']}\")
"
# Expected output (abbreviated):
#   library    Microsoft.AspNetCore.OpenApi                   8.0.0
#   library    Microsoft.Extensions.ApiDescription.Server     8.0.0
#   library    Microsoft.Extensions.DependencyInjection       8.0.0
#   library    Microsoft.Extensions.Logging                   8.0.0
#   library    Newtonsoft.Json                                13.0.3
#   library    Swashbuckle.AspNetCore                         7.2.0
#   ...
```

### 4.3 Count Direct vs Transitive Dependencies

```bash
# Direct dependencies are declared in your .csproj file
# Let's count them
grep -c 'PackageReference' src/SampleApi/SampleApi.csproj
# Expected: ~5-8 direct dependencies

# The SBOM has 173 components -- the rest are transitive
echo "Direct:     $(grep -c 'PackageReference' src/SampleApi/SampleApi.csproj) packages"
echo "Total SBOM: $(python3 -c "import json; print(len(json.load(open('sbom-sampleapi.json'))['components']))")"
echo "Transitive: the difference"
# Expected:
# Direct:     5 packages
# Total SBOM: 173 components
# Transitive: the difference
```

> **Why this matters:** Your `.csproj` declares 5 packages. But you are actually shipping 173. The 168 transitive dependencies are where supply chain attacks hide -- in the packages that nobody explicitly chose and nobody reviews. The SBOM makes the invisible visible.

### 4.4 Java SBOM for Comparison

If you have the order-service Java project available:

```bash
# Clone the order-service source
git clone https://gitlab-devsecops-gitlab.${APPS_DOMAIN}/devsecops/order-service.git
cd order-service

# Generate CycloneDX SBOM via Maven
mvn org.cyclonedx:cyclonedx-maven-plugin:2.8.0:makeAggregateBom \
    -DoutputFormat=json -DoutputName=bom -B

# Count components
python3 -c "
import json
d = json.load(open('target/bom.json'))
print(f'Java SBOM: {len(d[\"components\"])} components')
"
# Expected output:
# Java SBOM: 95 components
```

Compare the two:

```
+---------------------+---------------------+
|  .NET (sampleapi)   |  Java (order-svc)   |
+---------------------+---------------------+
| 173 components      | 95 components       |
| CycloneDX 1.5       | CycloneDX 1.6       |
| dotnet-CycloneDX    | Maven plugin 2.8.0  |
| pkg:nuget/...       | pkg:maven/...       |
+---------------------+---------------------+
```

The .NET application has nearly twice the component count because the .NET ecosystem ships smaller, more granular packages. `Microsoft.Extensions.DependencyInjection` alone pulls in 8 transitive packages. This is not better or worse -- it is an ecosystem characteristic you should be aware of when setting SBOM thresholds.

---

## 5. Run the Pipeline with SBOM Stage (15 min)

Now let us see the SBOM stage running as part of the full pipeline.

### 5.1 How Each Trigger Calls generateSBOM()

The three pipeline orchestrators call `generateSBOM()` with different configurations:

**T1 -- Merge Request (lenient):**

```groovy
// From pipelineMR.groovy -- lenient thresholds for feature branch validation
results.sbom = generateSBOM(
    project: '.',
    language: pipelineConfig.activeLanguage,
    serviceName: serviceName,
    imageTag: "mr-${env.gitlabMergeRequestIid ?: 'unknown'}",
    maxCritical: 0,       // ← THIS IS KEY: zero critical tolerance even in MR
    maxHigh: 10           // ← THIS IS KEY: lenient high threshold (10 vs 5)
)
if (results.sbom.status == 'FAILURE') {
    unstable "SBOM vulnerability gate: ${results.sbom.gateResult} ..."
    // ← THIS IS KEY: 'unstable' not 'error' -- MR pipeline warns but does NOT fail
}
```

**T2 -- Merge to Main (default thresholds):**

```groovy
// From pipelineMerge.groovy -- default thresholds
results.sbom = generateSBOM(
    project: '.',
    language: pipelineConfig.activeLanguage,
    serviceName: serviceName,
    imageTag: pipelineConfig.imageTag       // ← main-<short-sha>
    // maxCritical and maxHigh use defaults: 0 and 5
)
if (results.sbom.status == 'FAILURE') {
    error "SBOM gate FAILED: ${results.sbom.gateResult} ..."
    // ← THIS IS KEY: 'error' -- merge pipeline FAILS and stops
}
```

**T3 -- Tag Release (strict):**

```groovy
// From pipelineTag.groovy -- strictest enforcement
results.sbom = generateSBOM(
    project: '.',
    language: pipelineConfig.activeLanguage,
    serviceName: serviceName,
    imageTag: pipelineConfig.imageTag       // ← v1.2.0 (semver tag)
    // maxCritical=0, maxHigh=5 (defaults)
)
if (results.sbom.status == 'FAILURE') {
    error "SBOM gate FAILED: ${results.sbom.gateResult} ..."
    // ← THIS IS KEY: T3 failure means image is NOT signed, NOT pushed, NO promotion MR
}
```

The enforcement escalation across triggers:

| Trigger | maxCritical | maxHigh | On Failure | Consequence |
|---------|-------------|---------|------------|-------------|
| **T1 (MR)** | 0 | 10 | `unstable` (warn) | MR shows yellow warning, developer informed |
| **T2 (Merge)** | 0 | 5 | `error` (fail) | Pipeline stops, image not built |
| **T3 (Tag)** | 0 | 5 | `error` (fail) | Image not signed, not pushed, no promotion MR |

### 5.2 Trigger a T2 Pipeline for sampleapi

Push a commit to the `main` branch of `app-source` (or merge an MR). The T2 pipeline triggers:

```bash
# If you need to trigger manually via GitLab API:
GITLAB_TOKEN=$($OC get secret gitlab-api-token -n $NS_TOOLS \
  -o jsonpath='{.data.token}' | base64 -d)

curl -sfk -X POST \
  -H "PRIVATE-TOKEN: ${GITLAB_TOKEN}" \
  "${GITLAB_URL}/api/v4/projects/${GITLAB_PROJECT_APP_SOURCE}/pipeline" \
  -d "ref=main" | python3 -m json.tool
```

### 5.3 Watch the SBOM Stage in Jenkins

Open the Jenkins console output for the running build:

1. Navigate to `$JENKINS_URL` in your browser
2. Find the `sampleapi-merge` job
3. Click on the latest build number
4. Click **Console Output**

Look for the SBOM stage output:

```
=== SBOM Analysis & Vulnerability Gate ===
  Service: sampleapi (dotnet)
  Thresholds: critical=0, high=5
  [1/4] Generating CycloneDX SBOM...
  Using: ./SampleApi.sln
  SBOM generated: 173 components
  [2/4] Uploading SBOM to Trustify...
  Uploaded: SBOM ID=urn:uuid:a1b2c3d4-e5f6-7890-abcd-ef1234567890
  [3/4] Waiting for Trustify vulnerability analysis...
  Attempt 1/10: HTTP 404 — waiting 5s...
  Attempt 2/10: HTTP 404 — waiting 5s...
  Analysis ready after 15s
  Vulnerabilities: Critical=0 High=2 Medium=8 Low=3
  [4/4] Checking thresholds...
  GATE PASSED: within thresholds
```

### 5.4 Check Trustify UI

If Trustify has an external route, open it in your browser:

```bash
# Check if Trustify has an external route
$OC get route -n $NS_RHTPA
# If no route exists, create one for viewing (optional):
# $OC create route edge trustify-ui --service=server --port=8080 -n $NS_RHTPA
```

In the Trustify UI, navigate to **SBOMs** to see the uploaded SBOM. Click on it to view:

- The component list (173 components for sampleapi)
- Matched advisories with severity ratings
- Package URLs (purls) for each component
- Dependency relationships

### 5.5 Trigger for order-service (Java)

Repeat the same process for the Java order-service:

```bash
# Trigger a pipeline for order-service
curl -sfk -X POST \
  -H "PRIVATE-TOKEN: ${GITLAB_TOKEN}" \
  "${GITLAB_URL}/api/v4/projects/${GITLAB_PROJECT_ORDER_SERVICE}/pipeline" \
  -d "ref=main" | python3 -m json.tool
```

Watch the console output for the SBOM stage:

```
=== SBOM Analysis & Vulnerability Gate ===
  Service: order-service (java)
  Thresholds: critical=0, high=5
  [1/4] Generating CycloneDX SBOM...
  SBOM generated: 95 components
  [2/4] Uploading SBOM to Trustify...
  Uploaded: SBOM ID=urn:uuid:b2c3d4e5-f6a7-8901-bcde-f23456789012
  [3/4] Waiting for Trustify vulnerability analysis...
  Analysis ready after 10s
  Vulnerabilities: Critical=0 High=1 Medium=5 Low=2
  [4/4] Checking thresholds...
  GATE PASSED: within thresholds
```

### 5.6 Compare Results

```
+------------------------------+------------------------------+
|  sampleapi (.NET)            |  order-service (Java)        |
+------------------------------+------------------------------+
| 173 components               | 95 components                |
| 0 critical, 2 high           | 0 critical, 1 high           |
| 8 medium, 3 low              | 5 medium, 2 low              |
| GATE PASSED                  | GATE PASSED                  |
| SBOM file: 85 KB             | SBOM file: 52 KB             |
+------------------------------+------------------------------+
```

> **Why this matters:** The .NET app has nearly twice the components but also more findings. This is expected -- more components means more surface area. The Java app has fewer components but Spring Boot pulls in several large JARs (spring-web, spring-data-jpa, jackson-databind) that are mature and well-patched. Component count alone does not predict vulnerability count.

### 5.7 Check the MR Comment

If the pipeline was triggered by a merge request, the `commentOnMR` function posts a summary that includes the SBOM results. Navigate to the MR in GitLab and look for a comment like:

```
| Stage | Status | Details |
|-------|--------|---------|
| ...   | ...    | ...     |
| SBOM (CycloneDX) | ✅ SUCCESS | **173** components, Upload: SUCCESS | C:**0** H:**2** M:**8** L:**3** | [Trustify](https://server.test-app.svc/#/sboms/urn:uuid:...) |
| ...   | ...    | ...     |
```

The comment includes:
- Component count from the SBOM
- Upload status (SUCCESS/FAILED/SKIPPED)
- Vulnerability severity breakdown
- Direct link to the SBOM in Trustify UI

---

## 6. Test the Vulnerability Gate (10 min)

The SBOM gate prevents software with too many known vulnerabilities from progressing through the pipeline. Let us understand how it works and what happens when it fails.

### 6.1 Default Thresholds

The gate has two thresholds:

| Threshold | Default Value | Meaning |
|-----------|---------------|---------|
| `maxCritical` | `0` | Any critical vulnerability fails the gate |
| `maxHigh` | `5` | More than 5 high-severity vulnerabilities fails the gate |

Medium and low severity vulnerabilities are reported but do not fail the gate. This is a deliberate trade-off -- medium/low findings are too numerous and too noisy to be blocking gates. They appear in reports for tracking but do not stop the pipeline.

### 6.2 What Happens When the Gate Fails

When the gate fails on T2 or T3, the pipeline stops immediately:

```
                                    T3 Pipeline Flow
                                         |
  Checkout → Build → Test → SAST → SCA → SBOM → Container Build → Push → Sign → ...
                                     |
                            Gate FAILED here
                                     |
                                     v
                         +-----------------------+
                         | Pipeline STOPS        |
                         | Image NOT built       |
                         | Image NOT signed      |
                         | Image NOT pushed       |
                         | No promotion MR       |
                         | Developer notified    |
                         +-----------------------+
```

The console output shows:

```
  [4/4] Checking thresholds...
  GATE FAILED: 3 critical vulnerabilities (max: 0)
  Top findings:
    CRITICAL: RHSA-2024:1234 — Spring Framework RCE (CVE-2024-XXXX)
    CRITICAL: CVE-2024-5678 — jackson-databind deserialization
    CRITICAL: CVE-2024-9012 — log4j-api information disclosure

ERROR: SBOM gate FAILED: FAILED (Critical: 3, High: 2)
```

On T1 (MR), the behavior is different -- the pipeline marks the build as `unstable` (yellow) but does **not** fail:

```groovy
// T1: Warn but continue
if (results.sbom.status == 'FAILURE') {
    unstable "SBOM vulnerability gate: ${results.sbom.gateResult} ..."
    // Pipeline continues -- developer sees yellow warning in GitLab MR
}
```

This is intentional. MR validation is a feedback mechanism, not a hard block. The developer should see the warning and address it, but blocking MR validation on vulnerability counts that may change (as Trustify imports new advisories) creates unnecessary friction.

### 6.3 Simulating a Gate Failure

To test the gate without introducing actual vulnerabilities, temporarily lower the thresholds:

```groovy
// In a test branch, modify the pipeline call:
results.sbom = generateSBOM(
    project: '.',
    language: 'dotnet',
    serviceName: 'sampleapi',
    imageTag: 'test',
    maxCritical: 0,
    maxHigh: 0    // ← THIS IS KEY: set to 0 to trigger failure on any high finding
)
```

With `maxHigh: 0`, even a single high-severity finding triggers the gate. Since most real-world applications have at least some high findings, this reliably produces a failure for testing purposes.

### 6.4 Gate Results in Pipeline Reports

The SBOM gate result flows into multiple downstream outputs:

**Jenkins Console Output:**
```
  GATE FAILED: 2 high vulnerabilities (max: 0)
```

**GitLab MR Comment (via commentOnMR):**
```
| SBOM (CycloneDX) | ❌ FAILURE | **173** components, Upload: SUCCESS |
| | | C:**0** H:**2** M:**8** L:**3** |
```

**Promotion MR Description (via createPromotionMR, T3 only):**
```
- SBOM vulnerabilities: **0** critical, **2** high — [View in Trustify](https://...)
```

**Jenkins Build Artifact:**

The SBOM JSON file itself is archived as a build artifact in every pipeline run. You can download it from the Jenkins build page:

```bash
# Download the archived SBOM from Jenkins
curl -sfk -u admin:$(cat ~/.jenkins-token) \
  "${JENKINS_URL}/job/sampleapi-merge/lastSuccessfulBuild/artifact/sbom-sampleapi-main-abc1234.json" \
  -o downloaded-sbom.json

# Verify
python3 -c "
import json
d = json.load(open('downloaded-sbom.json'))
print(f'Components: {len(d[\"components\"])}')
"
```

### 6.5 The CycloneDX JSON Structure

Understanding the SBOM format helps you troubleshoot gate results. Here are the key fields:

```json
{
  "bomFormat": "CycloneDX",
  "specVersion": "1.6",
  "serialNumber": "urn:uuid:a1b2c3d4-...",
  "version": 1,
  "metadata": {
    "timestamp": "2026-05-29T10:30:00Z",
    "component": {
      "type": "application",
      "name": "SampleApi",
      "version": "1.0.0"
    },
    "tools": [
      {
        "vendor": "CycloneDX",
        "name": "CycloneDX module for .NET",
        "version": "0.27.2"
      }
    ]
  },
  "components": [
    {
      "type": "library",
      "name": "Microsoft.AspNetCore.OpenApi",
      "version": "8.0.0",
      "purl": "pkg:nuget/Microsoft.AspNetCore.OpenApi@8.0.0",
      "hashes": [
        {
          "alg": "SHA-256",
          "content": "a1b2c3d4e5f6..."
        }
      ],
      "licenses": [
        {
          "license": {
            "id": "MIT"
          }
        }
      ]
    }
  ],
  "dependencies": [
    {
      "ref": "pkg:nuget/Microsoft.AspNetCore.OpenApi@8.0.0",
      "dependsOn": [
        "pkg:nuget/Microsoft.OpenApi@1.6.11",
        "pkg:nuget/Microsoft.AspNetCore.OpenApi.Extensions@8.0.0"
      ]
    }
  ]
}
```

Key fields for troubleshooting:

| Field | Purpose |
|-------|---------|
| `bomFormat` | Always `CycloneDX` -- Trustify validates this on upload |
| `specVersion` | `1.5` (.NET) or `1.6` (Java) -- determines available fields |
| `metadata.component` | What this SBOM describes (your application) |
| `components[].purl` | Package URL -- the universal identifier Trustify uses for matching |
| `components[].hashes` | Cryptographic hashes for integrity verification |
| `dependencies` | The dependency tree structure (which component depends on which) |

> **Why this matters:** The `purl` (Package URL) is the field Trustify uses to match components against advisory databases. If a component has no `purl`, Trustify cannot analyze it for vulnerabilities. The CycloneDX tools for .NET and Java always include purls, but custom or internal packages may not have them. Check the SBOM for missing purls if vulnerability counts seem suspiciously low.

---

## Recap: What Just Happened?

Let us trace the full SBOM lifecycle through the pipeline:

```
  Developer pushes code to main
         |
         v
  [T2 Pipeline starts]
         |
         v
  Checkout → Build → Unit Tests → SAST → SCA
         |
         v
  +------------------------------------------+
  |  SBOM STAGE (generateSBOM.groovy)        |
  |                                          |
  |  1. GENERATE                             |
  |     dotnet-CycloneDX *.sln               |
  |     → sbom-sampleapi-main-abc1234.json   |
  |     → 173 components identified          |
  |     → Archived as Jenkins artifact       |
  |                                          |
  |  2. UPLOAD                               |
  |     Keycloak: walker → access_token      |
  |     POST /api/v2/sbom (SBOM JSON body)   |
  |     → SBOM ID returned                   |
  |                                          |
  |  3. POLL                                 |
  |     GET /api/v2/sbom/{id}/advisories     |
  |     × 10 attempts, 5s interval           |
  |     → Advisory matches returned          |
  |     → Critical=0, High=2, Medium=8, L=3  |
  |                                          |
  |  4. GATE                                 |
  |     0 critical ≤ 0 max? YES              |
  |     2 high ≤ 5 max? YES                  |
  |     → GATE PASSED                        |
  +------------------------------------------+
         |
         v
  Container Build → Push → Sign → Verify → ACS → GitOps → Deploy DEV
         |
         v
  [commentOnMR posts SBOM results to GitLab MR]
```

What we built:
- **Trustify/RHTPA** deployed with PostgreSQL, 3 importers, Keycloak authentication
- **103K+ advisories** from CVE.org, Red Hat CSAF, and CWE databases
- **generateSBOM.groovy** -- 305-line function covering both .NET and Java
- **Three-tier gate enforcement** -- warn on MR (T1), fail on merge (T2), fail on tag (T3)
- **Full traceability** -- SBOM archived in Jenkins, uploaded to Trustify, linked in MR comments

The security coverage matrix is now:

| Layer | Tool | Catches | Module |
|-------|------|---------|--------|
| Source code | SonarQube (SAST) | Bugs, code vulnerabilities, hotspots | Module 5 |
| Dependencies (declared) | OWASP Dependency-Check (SCA) | Known CVEs in direct/transitive deps | Module 5 |
| Dependencies (resolved) | CycloneDX + Trustify (SBOM) | Full component inventory + advisory matching | **This module** |
| Container image | ACS / StackRox | OS-level CVEs, misconfigurations | Module 6 |
| Running application | OWASP ZAP (DAST) | Runtime vulnerabilities (XSS, injection) | Module 7 |

Each layer catches threats the others miss. Together they form a defense-in-depth strategy where a vulnerability must evade five independent checks to reach production.

---

## Common Mistakes

These are real issues encountered during implementation. Each one cost debugging time.

### Mistake 1: Missing dotnet-CycloneDX in Agent Image

```
ERROR: dotnet-CycloneDX not found in agent image. Rebuild agent with:
       RUN dotnet tool install --global CycloneDX --version 6.2.0
```

The `dotnet-CycloneDX` tool must be pre-installed in the Jenkins agent image. It is a `dotnet` global tool, not a system package. The `generateSBOM.groovy` function checks for it explicitly and fails fast with a clear error message if missing.

```dockerfile
# In your Jenkins agent Dockerfile:
# ← THIS IS KEY: Install as a global tool, then add to PATH
RUN dotnet tool install --global CycloneDX --version 6.2.0
ENV PATH="${PATH}:/root/.dotnet/tools"
```

### Mistake 2: Trustify Credential ID Mismatch

```groovy
// WRONG: Using a different credential ID
withCredentials([string(credentialsId: 'trustify-secret', variable: 'WALKER_SECRET')])
// Result: CredentialNotFoundException, vulnerability analysis silently skipped

// RIGHT: Must match the hardcoded ID in generateSBOM.groovy
withCredentials([string(credentialsId: 'trustify-walker-token', variable: 'WALKER_SECRET')])
```

The credential ID `trustify-walker-token` is hardcoded in the `getTrustifyToken()` helper function (line 291 of `generateSBOM.groovy`). If the Jenkins credential has a different ID, the `withCredentials` block throws an exception, which the function catches. It then logs `WARNING: Trustify token retrieval failed` and returns an empty token. The entire upload and analysis is skipped -- the function returns with `uploadStatus: 'SKIPPED'` and `gateResult: 'PASSED'` (no data = no failures = gate passes).

This is the most dangerous failure mode because it is silent. The pipeline appears healthy, but no vulnerability analysis is actually happening.

### Mistake 3: SBOM ID URL Encoding

```bash
# WRONG: SBOM IDs contain colons, which are invalid in URLs
curl "https://trustify/api/v2/sbom/urn:uuid:abc123/advisories"
# Result: HTTP 400 Bad Request

# RIGHT: URL-encode the colons
curl "https://trustify/api/v2/sbom/urn%3Auuid%3Aabc123/advisories"
```

Trustify SBOM IDs use URN format (`urn:uuid:...`), which contains colons. The function handles this with `.replace(':', '%3A')`, but if you are querying the API manually for debugging, you must encode them yourself.

### Mistake 4: Empty Importer Database

```
  Vulnerabilities: Critical=0 High=0 Medium=0 Low=0
  GATE PASSED: within thresholds
```

If the importers have not completed their initial run, Trustify has no advisory data. Every SBOM analysis returns 0 vulnerabilities. The gate always passes. This creates a false sense of security.

Always verify importer status before trusting gate results:

```bash
$OC logs -n $NS_RHTPA -l app.kubernetes.io/component=importer --tail=5
# Should show import counts, not "starting import..."
```

### Mistake 5: Using `maxHigh: 0` in Production Pipelines

```groovy
// FRAGILE: Zero tolerance for high findings
generateSBOM(maxCritical: 0, maxHigh: 0)
// Result: Pipeline fails on every run because most real apps have some high findings
```

Setting `maxHigh: 0` is useful for testing the gate mechanism, but it is impractical for production pipelines. The default of 5 is a reasonable starting point. Adjust based on your application's actual vulnerability profile.

A better approach: start with `maxHigh: 10`, reduce to 5, then to 3 as you remediate findings. Never set it to 0 unless you are confident that all high findings have been addressed.

### Mistake 6: Confusing SCA and SBOM Gate Failures

The pipeline has two separate vulnerability gates:

```
Dependency-Check (SCA) → stage 'Dependency Check'    → Module 5
CycloneDX + Trustify   → stage 'Generate SBOM'       → This module
```

When a vulnerability gate fails, check the console output carefully to determine which gate failed. The error messages are different:

```
# SCA gate failure:
ERROR: Dependency-Check found 3 vulnerabilities with CVSS >= 7.0

# SBOM gate failure:
ERROR: SBOM gate FAILED: FAILED (Critical: 1, High: 7)
```

The two gates use different data sources (NVD vs Trustify advisories) and may report different findings for the same component.

---

## Self-Assessment

Answer these without looking back at the module. If you cannot answer confidently, re-read the relevant section.

1. What is the difference between an SBOM and the output of OWASP Dependency-Check? Why do we run both?

2. Your pipeline logs show `WARNING: No Trustify token -- vulnerability analysis skipped`. The gate shows `PASSED`. Is this a real pass or a false positive? What is the most likely cause?

3. The sampleapi SBOM has 173 components but the `.csproj` only declares 5 packages. Explain the discrepancy.

4. Write the `curl` command to upload an SBOM file `sbom.json` to Trustify, authenticating with an OAuth2 bearer token stored in `$TOKEN`.

5. Why does T1 (MR) use `unstable` instead of `error` when the SBOM gate fails?

6. A developer says "The SBOM gate just started failing on a project that has not changed in 2 weeks." How is this possible?

7. Why does `generateSBOM.groovy` use `config.maxCritical != null` instead of just `config.maxCritical ?: 0`?

<details>
<summary>Answers</summary>

1. OWASP Dependency-Check scans project declaration files (`.csproj`, `pom.xml`) against the NVD database. It answers "do my declared dependencies have known CVEs?" An SBOM is a complete, structured inventory of *all* resolved components (including transitives) that is uploaded to Trustify for matching against Red Hat CSAF advisories, CVE.org, and CWE databases. The SBOM is also a compliance artifact. We run both because they use different data sources (NVD vs Red Hat advisories) and may catch different findings.

2. This is a **false positive** (false pass). The `No Trustify token` warning means the Keycloak `client_credentials` call failed, so no SBOM was uploaded, no analysis was performed, and no vulnerabilities were found -- not because the software is safe, but because the analysis was skipped entirely. The most likely cause is a missing or incorrectly named Jenkins credential (`trustify-walker-token`).

3. The 5 packages in `.csproj` are **direct** dependencies. After `dotnet restore` resolves the full dependency tree, each direct dependency pulls in its own dependencies (transitives). `Microsoft.AspNetCore.OpenApi` alone brings in 8+ packages. The CycloneDX tool walks the resolved tree and lists all 173 actual packages that will be included in the build output.

4. `curl -sk -X POST "https://server.test-app.svc/api/v2/sbom" -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -H "X-Document-Id: urn:uuid:$(cat /proc/sys/kernel/random/uuid)" --data-binary @sbom.json`

5. T1 is a feedback mechanism for the developer, not a deployment gate. MR validation should inform, not block. Vulnerability counts can change as Trustify imports new advisories -- a scan that passes today might fail tomorrow on the same code. Blocking MRs on volatile data creates unnecessary friction and erodes developer trust in the pipeline.

6. Trustify's importers run on a schedule (every 6 hours for CVE and CSAF). A newly published advisory may match a component that was previously clean. The code did not change, but the advisory database did. This is expected behavior -- it means the gate is working. The developer needs to update the affected dependency or document an exception.

7. In Groovy, `0` is falsy. The expression `config.maxCritical ?: 0` evaluates `maxCritical: 0` as false and replaces it with the default `0` -- which happens to be the same value in this case, but would silently break if the default were different. `config.maxCritical != null` correctly distinguishes between "parameter not provided" (null) and "parameter explicitly set to 0."

</details>

---

## Next Module Preview

**Module 18: Image Signing with RHTAS (Sigstore/Cosign)** covers the final supply chain security layer -- cryptographic image signing and verification:

- Deploy Red Hat Trusted Artifact Signer (RHTAS) on OpenShift with Rekor transparency log and Fulcio certificate authority
- Sign container images using keyless signing (workload identity, no private key management)
- Attach SBOM attestations to signed images (the SBOM you generated in this module becomes a signed artifact)
- Verify signatures at deployment time via ACS admission control policies
- Understand the Sigstore trust model: Fulcio issues short-lived certificates, Rekor provides an immutable audit log

Together with this module, Module 18 completes the supply chain security story: you know what is inside your software (SBOM), you know whether it has vulnerabilities (Trustify), and you can prove cryptographically that the artifact has not been tampered with (RHTAS).

---

*Module 17 of the DevSecOps Tutorial Series -- Supply Chain & Multi-Language Track*
*Files referenced in this module:*
- *`jenkins-shared-lib/vars/generateSBOM.groovy` -- SBOM generation, upload, and gate function*
- *`jenkins-shared-lib/vars/pipelineMR.groovy` -- T1 orchestrator (lenient SBOM gate)*
- *`jenkins-shared-lib/vars/pipelineMerge.groovy` -- T2 orchestrator (default SBOM gate)*
- *`jenkins-shared-lib/vars/pipelineTag.groovy` -- T3 orchestrator (strict SBOM gate)*
- *`jenkins-shared-lib/vars/commentOnMR.groovy` -- MR comment with SBOM results*
- *`infra/phase26/` -- RHTPA/Trustify deployment manifests*
- *`env.sh` -- Environment variables (`$NS_RHTPA`, `$TRUSTIFY_URL`)*
