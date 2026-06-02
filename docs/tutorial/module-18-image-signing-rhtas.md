# Module 18: Image Signing and Verification with RHTAS

| | |
|---|---|
| **Track** | Supply Chain & Multi-Language |
| **Duration** | ~75 minutes |
| **Difficulty** | Advanced |
| **Prerequisites** | Module 17 (SBOM generation working), Module 6 (ACS container security concepts) |

---

## What You'll Learn

By the end of this module you will be able to:

- Explain why image signing matters and what supply chain attacks it prevents.
- Distinguish between key-based signing and keyless signing, and explain why keyless is superior for CI/CD.
- Describe the role of each RHTAS component (Fulcio, Rekor, CTLog, TSA, TUF, Trillian, Keycloak) in the signing trust chain.
- Read through `signImage.groovy` and explain every step: OIDC token acquisition, TLS trust extraction, keyless signing, key-based fallback, and SBOM attestation.
- Read through `verifyImage.groovy` and explain trust material extraction, signature verification, and attestation verification.
- Trigger a T2 pipeline and observe the Sign Image and Verify Image stages in action.
- Verify an image signature manually using the `cosign` CLI outside of Jenkins.
- Create an ACS policy that blocks unsigned images from deploying to production namespaces.
- Close the trust loop: every image that reaches production is signed, attested, and verified.

---

## Prerequisites

Before starting this module, confirm:

> **Environment variables:** Before running any commands, source the environment file:
> ```bash
> source ./env.sh
> ```
> This sets `$OC`, `$APPS_DOMAIN`, `$NS_RHTAS`, `$RHTAS_REKOR_URL`, `$RHTAS_FULCIO_URL`, and all other cluster-specific variables used throughout this module. See `env.sh` for the full variable list.

```bash
# Quick prerequisite check
$OC whoami                           # Expected: admin (or your cluster-admin user)
cosign version                       # Expected: 2.x.x
$OC get ns $NS_RHTAS                 # Expected: trusted-artifact-signer namespace exists
$OC get pods -n $NS_RHTAS | head -5  # Expected: multiple pods Running
$OC get ns $NS_ACS                   # Expected: stackrox namespace exists
```

You also need:

- Module 17 complete with SBOM generation working in your pipelines.
- Module 6 complete with ACS Central reachable and `roxctl` working.
- Jenkins running in `$NS_TOOLS` with the shared library configured.
- At least one service (sampleapi or a Java service) with a successful T2 pipeline run.
- The `cosign` CLI installed locally (check with `cosign version`).

If any prerequisite check fails, go back to the relevant module and resolve it before continuing.

---

## 1. Concepts: Image Signing and Trust

### The Problem: How Do You Know Your Image Is Safe?

You built an image. You scanned it with ACS. You pushed it to the registry. SonarQube says the code is clean. Dependency-Check found no critical CVEs. Everything looks good.

But here is the question nobody asks until it is too late: **how do you know the image in production is the same image your pipeline built?**

Between the time your pipeline pushes an image and the time Kubernetes pulls it, several things could happen:

1. **Registry compromise** -- An attacker gains write access to your registry and replaces the image with a malicious one. The tag stays the same. The SHA changes. Nobody notices.
2. **Man-in-the-middle** -- The image is intercepted and modified during transit from registry to node. Unlikely with TLS, but not impossible if certificates are misconfigured.
3. **Insider threat** -- A rogue developer pushes a backdoored image directly to the registry, bypassing the pipeline entirely. The image was never scanned, never tested, never approved.
4. **Tag mutation** -- Someone pushes a new image with the same tag (`:latest` is the classic example). The deployment pulls the new image on the next restart. No audit trail exists.

These are not theoretical scenarios. The SolarWinds attack (2020) injected malicious code into a build pipeline. The Codecov supply chain attack (2021) modified a build script to exfiltrate secrets. The xz-utils backdoor (2024) was planted by a trusted maintainer over years.

Image signing solves this by creating a **cryptographic proof** that:

- This specific image (identified by SHA256 digest, not tag) was produced by this specific pipeline.
- The signing happened at a specific time, recorded in an immutable transparency log.
- The signer's identity is tied to an OIDC token, not a long-lived key that can be stolen.

If the image is modified after signing, the signature becomes invalid. If someone pushes an unsigned image, the admission controller blocks it. The chain of trust is unbroken from build to deploy.

> **Why this matters:** Image signing is not about compliance checkboxes. It is the only mechanism that answers "is this the exact image my pipeline produced?" with cryptographic certainty. Without it, every other security gate (SAST, SCA, ACS scan) can be bypassed by replacing the image after the gates pass.

### Key-Based vs Keyless Signing

There are two ways to sign container images. Understanding the difference is critical to understanding why RHTAS exists.

| Dimension | Key-Based Signing | Keyless Signing (Sigstore) |
|-----------|-------------------|---------------------------|
| **Identity** | A private key file (e.g., `cosign.key`) | An OIDC identity (e.g., `pipeline@keycloak`) |
| **Key lifetime** | Long-lived (months/years) | Short-lived certificate (~20 minutes) |
| **Key storage** | Must be stored securely (Vault, K8s Secret) | No key to store -- Fulcio issues ephemeral certs |
| **Rotation** | Manual -- must rotate keys and re-sign images | Automatic -- every signing event gets a fresh cert |
| **Compromise impact** | All images signed with that key are suspect | Only the compromised OIDC session is affected |
| **Audit trail** | Optional -- must build your own logging | Built-in -- Rekor records every signing event |
| **Who signed?** | "Someone with the key" | "The CI pipeline authenticated as walker@keycloak" |
| **Revocation** | Complex -- must distribute CRL/OCSP | Simple -- short-lived certs expire automatically |

> **Why this matters:** Key-based signing has the same problem as SSH keys on a shared server -- you know someone with the key signed it, but you do not know who, when, or from where. Keyless signing ties every signature to an OIDC identity and records it in an immutable log. This is why RHTAS uses keyless signing as the primary method.

### The Sigstore Architecture

Keyless signing relies on four components working together. RHTAS deploys all four as enterprise-grade services on OpenShift.

```
  ┌─────────────────────────────────────────────────────────────────────────┐
  │                         SIGNING FLOW                                    │
  │                                                                         │
  │  Pipeline (Jenkins)                                                     │
  │       │                                                                 │
  │       │ 1. "I need to sign image sha256:abc123"                        │
  │       │                                                                 │
  │       ▼                                                                 │
  │  ┌──────────┐     2. client_credentials grant                          │
  │  │ Get OIDC │─────────────────────────────────►┌──────────────┐        │
  │  │ Token    │                                   │  Keycloak    │        │
  │  │          │◄──────────────────────────────────│  (OIDC IdP)  │        │
  │  └──────────┘     id_token for walker client    │  trustify    │        │
  │       │                                         │  realm       │        │
  │       │                                         └──────────────┘        │
  │       │ 3. "Sign this hash using my OIDC identity"                     │
  │       │    (sends id_token + hash)                                      │
  │       ▼                                                                 │
  │  ┌──────────┐     4. Validates OIDC token,     ┌──────────────┐        │
  │  │ cosign   │────────issues short-lived cert───►│   Fulcio     │        │
  │  │ sign     │                                   │   (CA)       │        │
  │  │          │◄────ephemeral signing cert────────│              │        │
  │  └──────────┘     (valid ~20 min)               └──────────────┘        │
  │       │                                                                 │
  │       │ 5. Signs the image digest with the ephemeral cert              │
  │       │    Uploads signature + cert to transparency log                 │
  │       ▼                                                                 │
  │  ┌──────────┐                                   ┌──────────────┐        │
  │  │ Record   │──────────────────────────────────►│   Rekor      │        │
  │  │ in log   │     signature + cert + hash       │   (Log)      │        │
  │  │          │◄────── inclusion proof ───────────│   Immutable  │        │
  │  └──────────┘     (Merkle tree proof)           │   append-only│        │
  │       │                                         └──────────────┘        │
  │       │                                                                 │
  │       │ 6. Also record in Certificate Transparency Log                 │
  │       ▼                                                                 │
  │  ┌──────────┐                                   ┌──────────────┐        │
  │  │ CT proof  │─────────────────────────────────►│   CTLog      │        │
  │  │          │     certificate issued by Fulcio  │   Public log │        │
  │  └──────────┘                                   │   of certs   │        │
  │       │                                         └──────────────┘        │
  │       │                                                                 │
  │       │ 7. Store signature as OCI artifact alongside image             │
  │       ▼                                                                 │
  │  ┌──────────────────────────────────┐                                   │
  │  │ Registry                         │                                   │
  │  │  myapp:v1.0.0       (image)      │                                   │
  │  │  myapp:sha256-abc.sig (signature)│                                   │
  │  │  myapp:sha256-abc.att (SBOM att) │                                   │
  │  └──────────────────────────────────┘                                   │
  └─────────────────────────────────────────────────────────────────────────┘

  ┌─────────────────────────────────────────────────────────────────────────┐
  │                      VERIFICATION FLOW                                  │
  │                                                                         │
  │  Pipeline / Admission Controller / Manual                               │
  │       │                                                                 │
  │       │ 1. "Is this image validly signed?"                             │
  │       ▼                                                                 │
  │  ┌──────────┐     2. Fetch signature from      ┌──────────────┐        │
  │  │ cosign   │────────registry OCI artifacts────►│  Registry    │        │
  │  │ verify   │                                   │              │        │
  │  │          │◄───────signature + cert───────────│              │        │
  │  └──────────┘                                   └──────────────┘        │
  │       │                                                                 │
  │       │ 3. Verify cert chain against Fulcio root CA                    │
  │       │ 4. Check Rekor for inclusion proof (was this logged?)          │
  │       │ 5. Verify OIDC issuer matches expected identity                │
  │       ▼                                                                 │
  │  ┌──────────┐                                                           │
  │  │ RESULT   │ ── PASS: cert chain valid, Rekor entry found,            │
  │  │          │          OIDC issuer matches                              │
  │  │          │ ── FAIL: signature invalid, cert expired,                │
  │  │          │          no Rekor entry, wrong issuer                     │
  │  └──────────┘                                                           │
  └─────────────────────────────────────────────────────────────────────────┘
```

### SBOM Attestation: Binding the SBOM to the Image

Module 17 taught you to generate SBOMs. But a standalone SBOM file has a problem: there is no cryptographic link between the SBOM and the image it describes. Someone could generate an SBOM for a clean image and attach it to a compromised one.

**SBOM attestation** solves this by:

1. Taking the SBOM (CycloneDX JSON) as a "predicate" -- the claim being made about the image.
2. Signing the predicate along with the image digest using `cosign attest`.
3. Recording the attestation in Rekor (just like a regular signature).
4. Storing the attestation as an OCI artifact alongside the image in the registry.

Now the SBOM is cryptographically bound to a specific image digest. You cannot swap the SBOM or the image without invalidating the attestation. Verification checks both: is the image signed, AND does it have a valid SBOM attestation?

```
                    Image: sha256:abc123
                         │
              ┌──────────┼──────────┐
              │          │          │
              ▼          ▼          ▼
         .sig artifact  .att artifact
         (signature)    (SBOM attestation)
              │          │
              │          │
              ▼          ▼
         Signed by      Signed by
         walker@kc      walker@kc
              │          │
              │          │
              ▼          ▼
         Logged in      Logged in
         Rekor          Rekor
              │          │
              └──────────┘
                   │
                   ▼
         Both verifiable with:
         cosign verify
         cosign verify-attestation --type cyclonedx
```

> **Why this matters:** Without attestation, your SBOM is just a JSON file. With attestation, it is a cryptographically signed statement that says "this specific image contains these specific components, and I (the pipeline, authenticated via OIDC) vouch for it." This is what software supply chain compliance frameworks like SLSA Level 2+ require.

---

## 2. Examine the RHTAS Deployment

RHTAS (Red Hat Trusted Artifact Signer) is already deployed on your cluster in the `trusted-artifact-signer` namespace. Before we touch the pipeline code, let's understand what is running and why.

### List All RHTAS Pods

```bash
$OC get pods -n $NS_RHTAS
```

Expected output (your pod names will differ):

```
NAME                                              READY   STATUS    RESTARTS   AGE
ctlog-79bfb5d956-x8k2p                           1/1     Running   0          12d
fulcio-server-6bc69f4d88-qr7mn                    1/1     Running   0          12d
rekor-redis-7b94dbf876-4ht9w                      1/1     Running   0          12d
rekor-server-6b4c9f5d78-js2lb                     1/1     Running   0          12d
rekor-search-ui-5d7c8a9f64-mn3kp                  1/1     Running   0          12d
rhtas-operator-controller-manager-5f6d7e-8r2jq    2/2     Running   0          12d
trillian-db-78bc9d5f64-pd7kq                      1/1     Running   0          12d
trillian-logserver-6c8d9e7f54-tn5mp               1/1     Running   0          12d
trillian-logsigner-5b7c8d9e64-wk4np               1/1     Running   0          12d
tsa-server-7d9e8f6c54-hr2lq                       1/1     Running   0          12d
tuf-8e9f7d6c54-qs8kp                              1/1     Running   0          12d
```

That is a lot of pods. Let's understand what each one does.

### Component-by-Component Breakdown

| Component | Pod Prefix | What It Does | Analogy |
|-----------|-----------|--------------|---------|
| **Fulcio** | `fulcio-server` | Certificate Authority -- issues short-lived signing certificates tied to OIDC identity | A notary who only stamps documents for people who show their ID |
| **Rekor** | `rekor-server` | Transparency log -- immutable, append-only record of every signing event | A public ledger that everyone can read but nobody can erase |
| **Rekor Redis** | `rekor-redis` | Cache layer for Rekor -- speeds up log queries | A quick-access index for the ledger |
| **Rekor Search UI** | `rekor-search-ui` | Web interface for browsing the Rekor transparency log | The public counter where anyone can look up a ledger entry |
| **CTLog** | `ctlog` | Certificate Transparency Log -- records every certificate Fulcio issues | A separate register proving the notary actually issued that stamp |
| **TSA** | `tsa-server` | Timestamp Authority -- cryptographic proof of WHEN a signature was created | A trusted clock that proves you signed at 2:47 PM, not last Tuesday |
| **TUF** | `tuf` | The Update Framework -- distributes trusted root material (public keys, certs) | A locked bulletin board where the root certificates are posted |
| **Trillian DB** | `trillian-db` | PostgreSQL backend storing the Merkle tree data for Rekor and CTLog | The filing cabinet where the ledger pages are physically stored |
| **Trillian Logserver** | `trillian-logserver` | Serves the Merkle tree API -- handles reads and inclusion proofs | The clerk who retrieves entries from the filing cabinet |
| **Trillian Logsigner** | `trillian-logsigner` | Periodically signs the Merkle tree root -- ensures log integrity | The auditor who stamps the running total to prove nothing was deleted |
| **RHTAS Operator** | `rhtas-operator` | Manages the lifecycle of all RHTAS components | The facility manager who keeps everything running |

### Check the Routes

RHTAS exposes three routes that the pipeline uses:

```bash
$OC get routes -n $NS_RHTAS
```

Expected output:

```
NAME               HOST/PORT                                                          PATH   SERVICES           PORT    TERMINATION            WILDCARD
fulcio-server      fulcio-server-trusted-artifact-signer.apps.<domain>                        fulcio-server      <port>  reencrypt/Redirect     None
rekor-server       rekor-server-trusted-artifact-signer.apps.<domain>                         rekor-server       <port>  reencrypt/Redirect     None
rekor-search-ui    rekor-search-ui-trusted-artifact-signer.apps.<domain>                      rekor-search-ui    <port>  reencrypt/Redirect     None
```

Verify they are reachable:

```bash
# Fulcio health
curl -sk "${RHTAS_FULCIO_URL}/healthz"
# Expected: {"status":"ok"} or similar healthy response

# Rekor health
curl -sk "${RHTAS_REKOR_URL}/api/v1/log"
# Expected: JSON with treeID, rootHash, treeSize, etc.

# Rekor Search UI
curl -sk -o /dev/null -w "%{http_code}" "${RHTAS_REKOR_SEARCH_URL}"
# Expected: 200
```

> **Why this matters:** If any of these routes are unreachable, the pipeline's `signImage.groovy` will fail at the keyless signing step and fall back to key-based signing. The routes must be accessible from the Jenkins agent pod, which runs inside the cluster.

### Examine the Keycloak OIDC Integration

RHTAS uses Keycloak as its OIDC identity provider. The `trustify` realm contains a `walker` client that the pipeline uses to obtain OIDC tokens for keyless signing.

```bash
# Check the Keycloak route
KEYCLOAK_URL="https://keycloak.${APPS_DOMAIN}"
curl -sk "${KEYCLOAK_URL}/realms/trustify/.well-known/openid-configuration" | python3 -m json.tool | head -10
```

Expected output:

```json
{
    "issuer": "https://keycloak.apps.<domain>/realms/trustify",
    "authorization_endpoint": "https://keycloak.apps.<domain>/realms/trustify/protocol/openid-connect/auth",
    "token_endpoint": "https://keycloak.apps.<domain>/realms/trustify/protocol/openid-connect/token",
    "introspection_endpoint": "...",
    "userinfo_endpoint": "...",
    ...
}
```

The key fields the pipeline uses:

- **`issuer`** -- This value becomes the `--oidc-issuer` parameter in cosign. The verifier checks that the signing certificate was issued for this OIDC issuer.
- **`token_endpoint`** -- Where `signImage.groovy` sends the `client_credentials` grant to get an OIDC token.

The `walker` client is configured with `client_credentials` grant type and `openid` scope. The pipeline sends the client ID and secret to the token endpoint and receives an `id_token` (or falls back to `access_token`).

### Examine the Trust Material ConfigMap

The pipeline's `verifyImage.groovy` needs public keys and root certificates to verify signatures. These are stored in a ConfigMap that was copied from the RHTAS namespace to the tools namespace:

```bash
$OC get configmap rhtas-ctlog-public-key -n $NS_TOOLS -o yaml
```

Expected output (keys truncated):

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: rhtas-ctlog-public-key
  namespace: devsecops-tools
data:
  ctlog-public.pem: |                    # ← CTLog public key (verifies SCT proofs)
    -----BEGIN RSA PUBLIC KEY-----
    MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8A...
    -----END RSA PUBLIC KEY-----
  fulcio-root.pem: |                     # ← Fulcio root CA cert (verifies signing certs)
    -----BEGIN CERTIFICATE-----
    MIICGjCCAaGgAwIBAgIUQ8s1Y3x9...
    -----END CERTIFICATE-----
  rekor-public.pem: |                    # ← Rekor public key (verifies log inclusion proofs)
    -----BEGIN PUBLIC KEY-----
    MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcD...
    -----END PUBLIC KEY-----
```

These three files form the trust root:

| File | Used By | Proves |
|------|---------|--------|
| `ctlog-public.pem` | cosign SCT verification | The certificate was logged in CT before use |
| `fulcio-root.pem` | cosign certificate chain verification | The signing certificate was issued by our Fulcio CA |
| `rekor-public.pem` | cosign transparency log verification | The signature entry in Rekor is authentic |

If this ConfigMap does not exist in your tools namespace, create it:

```bash
# Export trust material from the RHTAS namespace
CTLOG_KEY=$($OC get secret ctlog-public-key -n $NS_RHTAS -o jsonpath='{.data.public}' | base64 -d 2>/dev/null)
FULCIO_CERT=$($OC get secret fulcio-secret-rh -n $NS_RHTAS -o jsonpath='{.data.cert}' | base64 -d 2>/dev/null)
REKOR_KEY=$($OC get secret rekor-public-key -n $NS_RHTAS -o jsonpath='{.data.public}' | base64 -d 2>/dev/null)

# Create the ConfigMap in the tools namespace
cat <<EOF | $OC apply -f -
apiVersion: v1
kind: ConfigMap
metadata:
  name: rhtas-ctlog-public-key        # ← Name must match what verifyImage.groovy expects
  namespace: $NS_TOOLS                # ← Must be in Jenkins namespace so agent pods can read it
data:
  ctlog-public.pem: |
$(echo "$CTLOG_KEY" | sed 's/^/    /')
  fulcio-root.pem: |
$(echo "$FULCIO_CERT" | sed 's/^/    /')
  rekor-public.pem: |
$(echo "$REKOR_KEY" | sed 's/^/    /')
EOF
```

Verify:

```bash
$OC get configmap rhtas-ctlog-public-key -n $NS_TOOLS
```

Expected output:

```
NAME                      DATA   AGE
rhtas-ctlog-public-key    3      12d
```

> **Why this matters:** Without this ConfigMap, `verifyImage.groovy` cannot verify signatures. It will fall back to `--insecure-ignore-tlog=true` mode, which means it skips transparency log verification. That defeats the purpose of Rekor -- you want to verify that the signature was logged, not just that the bytes match.

---

## 3. Understand signImage.groovy

Now that you understand the architecture, let's walk through the actual implementation. Open `jenkins-shared-lib/vars/signImage.groovy` and follow along. This is a 220-line function that handles the complete signing flow including a key-based fallback.

### Function Signature and Configuration

```groovy
// jenkins-shared-lib/vars/signImage.groovy
// Sign container image + attach SBOM attestation using RHTAS (Sigstore)
//
// Primary: keyless signing via Fulcio (OIDC identity) + Rekor (transparency log)
// Fallback: key-based signing if Fulcio rejects the identity token
//
// Usage:
//   signImage(imageRef: 'registry/ns/app:tag', sbomFile: 'sbom-app-v1.json')

def call(Map config = [:]) {
    def startTime = System.currentTimeMillis()
    def imageRef = config.imageRef ?: ''         // ← REQUIRED: full image reference with digest or tag
    def sbomFile = config.sbomFile ?: ''         // ← Optional: path to CycloneDX SBOM JSON
    def rekorUrl = config.rekorUrl ?: 'https://rekor-server-trusted-artifact-signer.apps...'
    def fulcioUrl = config.fulcioUrl ?: 'https://fulcio-server-trusted-artifact-signer.apps...'
    def oidcIssuer = config.oidcIssuer ?: 'https://keycloak.apps.../realms/trustify'
    def keyCredId = config.keyCredId ?: 'cosign-signing-key'  // ← Jenkins credential ID for key-based fallback
```

The function accepts a Map parameter (as all shared library functions do). The defaults are hardcoded to the cluster's RHTAS URLs. In a multi-cluster setup, you would pass these as parameters from the pipeline orchestrator.

### Step 1: Extract the Cluster CA Certificate

The first thing the function does is solve a TLS trust problem. RHTAS routes use certificates signed by the cluster's internal CA. The `cosign` binary does not trust these certificates by default, so we need to extract them and tell cosign to use them.

```groovy
        // Extract cluster CA cert so cosign trusts Fulcio/Rekor self-signed routes
        sh """
            FULCIO_HOST=\$(echo "${fulcioUrl}" | sed 's|https://||')
            openssl s_client -showcerts -connect \${FULCIO_HOST}:443 </dev/null 2>/dev/null | \
                sed -ne '/-BEGIN CERTIFICATE-/,/-END CERTIFICATE-/p' > /tmp/cluster-ca.crt 2>/dev/null || true
            if [ -s /tmp/cluster-ca.crt ]; then
                # ← THIS IS KEY: merge cluster CA with system CAs so cosign trusts BOTH
                cat /etc/pki/ca-trust/extracted/pem/tls-ca-bundle.pem /tmp/cluster-ca.crt > /tmp/merged-ca-bundle.pem 2>/dev/null
            else
                cp /etc/pki/ca-trust/extracted/pem/tls-ca-bundle.pem /tmp/merged-ca-bundle.pem 2>/dev/null || true
            fi
        """
```

> **Why this matters:** Without the merged CA bundle, every `cosign` command fails with `x509: certificate signed by unknown authority`. This is the most common setup issue with RHTAS on OpenShift. The function handles it automatically so the pipeline operator never sees this error.

### Step 2: Get the CTLog Public Key

```groovy
        // Get RHTAS CTLog public key for SCT verification (from ConfigMap in Jenkins namespace)
        sh """
            oc get configmap rhtas-ctlog-public-key -n devsecops-tools \
                -o jsonpath='{.data.ctlog-public\\.pem}' > /tmp/ctlog-public.pem 2>/dev/null || true
            if [ ! -s /tmp/ctlog-public.pem ]; then
                echo "WARNING: CTLog public key not found — create ConfigMap rhtas-ctlog-public-key in devsecops-tools"
            fi
        """
```

The CTLog public key is needed for SCT (Signed Certificate Timestamp) verification. When Fulcio issues a signing certificate, it must prove that the certificate was logged in the CT log. The SCT is that proof, and we need the CTLog public key to verify it.

### Step 3: Obtain the OIDC Token

This is where the keyless magic starts. The pipeline authenticates to Keycloak as the `walker` client and gets an OIDC token:

```groovy
        // Try keyless signing first (preferred)
        echo "  Obtaining OIDC token from Keycloak..."
        def idToken = ''
        try {
            // ← THIS IS KEY: the walker client secret is stored as a Jenkins credential
            withCredentials([string(credentialsId: 'trustify-walker-token', variable: 'WALKER_SECRET')]) {
                idToken = sh(script: """
                    curl -sfk -X POST "${oidcIssuer}/protocol/openid-connect/token" \\
                        -d "client_id=walker" \\
                        -d "client_secret=\${WALKER_SECRET}" \\
                        -d "grant_type=client_credentials" \\
                        -d "scope=openid" \\
                        -o /tmp/oidc-response.json 2>/dev/null
                    python3 -c "
import json
d = json.load(open('/tmp/oidc-response.json'))
token = d.get('id_token') or d.get('access_token', '')
print(token)
" 2>/dev/null
                """, returnStdout: true).trim()
            }
        } catch (Exception e) {
            echo "  WARNING: Could not get OIDC token: ${e.message}"
        }
```

A few things to notice:

1. **`withCredentials`** wraps the Keycloak client secret. It never appears in the console log.
2. **`client_credentials` grant** -- This is a service-to-service flow. There is no user login, no browser redirect. The pipeline sends its client ID and secret and gets a token back.
3. **`id_token` preference** -- The function prefers `id_token` over `access_token` because `id_token` contains the OIDC claims that Fulcio uses to issue the signing certificate. If Keycloak does not return an `id_token` for `client_credentials` (some configurations do not), it falls back to `access_token`.

### Step 4: Keyless Signing via Fulcio + Rekor

With the OIDC token in hand, the function signs the image:

```groovy
            // Login to internal registry so cosign can access the image
            sh """
                SA_TOKEN=\$(cat /var/run/secrets/kubernetes.io/serviceaccount/token)
                podman login --tls-verify=false -u unused -p "\${SA_TOKEN}" image-registry.openshift-image-registry.svc:5000 2>/dev/null || true
                mkdir -p \$HOME/.docker 2>/dev/null || true
                cp \${XDG_RUNTIME_DIR}/containers/auth.json \$HOME/.docker/config.json 2>/dev/null || \\
                    cp /run/containers/0/auth.json \$HOME/.docker/config.json 2>/dev/null || true
            """

            echo "  [1/2] Signing image (keyless via Fulcio + Rekor)..."

            def signExitCode = sh(script: """
                SSL_CERT_FILE=/tmp/merged-ca-bundle.pem \\
                SIGSTORE_CT_LOG_PUBLIC_KEY_FILE=/tmp/ctlog-public.pem \\
                COSIGN_EXPERIMENTAL=1 cosign sign \\
                    --fulcio-url="${fulcioUrl}" \\          # ← Where to get the signing cert
                    --rekor-url="${rekorUrl}" \\            # ← Where to log the signature
                    --identity-token="${idToken}" \\        # ← OIDC token proving pipeline identity
                    --allow-insecure-registry \\            # ← Required for internal OCP registry (HTTP)
                    --yes \\                                # ← Skip interactive confirmation
                    ${imageRef} 2>&1
            """, returnStatus: true)                       # ← Capture exit code, don't fail immediately
```

The environment variables are critical:

| Variable | Value | Purpose |
|----------|-------|---------|
| `SSL_CERT_FILE` | `/tmp/merged-ca-bundle.pem` | Tells cosign to trust the cluster CA for Fulcio/Rekor TLS |
| `SIGSTORE_CT_LOG_PUBLIC_KEY_FILE` | `/tmp/ctlog-public.pem` | Tells cosign how to verify the SCT from Fulcio |
| `COSIGN_EXPERIMENTAL` | `1` | Enables keyless verification features |

The `--identity-token` flag is where the OIDC magic happens. Cosign sends this token to Fulcio, which:

1. Validates the token against the Keycloak OIDC endpoint.
2. Extracts the identity claims (subject, issuer, audience).
3. Issues a short-lived X.509 certificate with these claims embedded.
4. Returns the certificate to cosign.

Cosign then uses this ephemeral certificate to sign the image digest and uploads everything (signature, certificate, image digest) to Rekor.

### Step 5: Key-Based Fallback

If keyless signing fails (which can happen if Fulcio rejects the `client_credentials` token), the function falls back to key-based signing:

```groovy
        // Key-based fallback if keyless didn't work
        if (signStatus != 'SUCCESS') {
            signStatus = tryKeyBasedSign(imageRef, keyCredId, rekorUrl)
            signMethod = signStatus == 'SUCCESS' ? 'key-based (fallback)' : 'none'
        }

        if (signStatus != 'SUCCESS') {
            error "Image signing failed — both keyless and key-based methods failed"
        }
```

The `tryKeyBasedSign` helper function uses a cosign private key stored as a Jenkins file credential:

```groovy
private String tryKeyBasedSign(String imageRef, String keyCredId, String rekorUrl) {
    try {
        withCredentials([file(credentialsId: keyCredId, variable: 'COSIGN_KEY')]) {
            def exitCode = sh(script: """
                SSL_CERT_FILE=/tmp/merged-ca-bundle.pem \\
                COSIGN_PASSWORD='' cosign sign \\
                    --key "\${COSIGN_KEY}" \\       # ← Uses a long-lived key instead of Fulcio cert
                    --rekor-url="${rekorUrl}" \\     # ← Still logs to Rekor for auditability
                    --allow-insecure-registry \\
                    --yes \\
                    ${imageRef} 2>&1
            """, returnStatus: true)
            // ...
        }
    } catch (Exception e) {
        // Handle missing credential gracefully
    }
}
```

> **Why this matters:** The fallback ensures that signing never silently fails. If keyless signing is misconfigured, the pipeline still signs with a key. If neither method works, the pipeline fails explicitly. This is defense in depth applied to the signing process itself.

### Step 6: SBOM Attestation

After the image is signed, the function attaches the SBOM as a signed attestation:

```groovy
        // ── Step 2: SBOM Attestation ──
        if (sbomFile && fileExists(sbomFile)) {
            echo "  [2/2] Attaching SBOM attestation..."

            if (idToken && signMethod.contains('keyless')) {
                def attestExitCode = sh(script: """
                    SSL_CERT_FILE=/tmp/merged-ca-bundle.pem \\
                    SIGSTORE_CT_LOG_PUBLIC_KEY_FILE=/tmp/ctlog-public.pem \\
                    COSIGN_EXPERIMENTAL=1 cosign attest \\
                        --fulcio-url="${fulcioUrl}" \\
                        --rekor-url="${rekorUrl}" \\
                        --identity-token="${idToken}" \\
                        --allow-insecure-registry \\
                        --predicate ${sbomFile} \\       # ← THIS IS KEY: the SBOM file is the predicate
                        --type cyclonedx \\               # ← Tells cosign the predicate format
                        --yes \\
                        ${imageRef} 2>&1
                """, returnStatus: true)

                attestStatus = attestExitCode == 0 ? 'SUCCESS' : 'FAILED'
            }
        }
```

The `--predicate` flag is the SBOM file generated by `generateSBOM.groovy` in the previous pipeline stage. The `--type cyclonedx` tells cosign how to wrap it in an in-toto attestation envelope. The result is stored as an OCI artifact in the registry alongside the image and signature.

### Return Value

The function returns a structured map that the pipeline orchestrator uses for reporting:

```groovy
        return [
            status: 'SUCCESS',
            duration: duration,
            imageRef: imageRef,
            signMethod: signMethod,          // 'keyless (RHTAS)' or 'key-based (fallback)'
            rekorUrl: rekorUrl,
            attestStatus: attestStatus,      // 'SUCCESS', 'FAILED', or 'SKIPPED'
            sbomAttested: attestStatus == 'SUCCESS'
        ]
```

The pipeline orchestrator (`pipelineMerge.groovy` and `pipelineTag.groovy`) checks `results.signImage.status` and fails the build if signing fails. The `signMethod` field appears in the pipeline summary comment on the GitLab MR, so reviewers can see whether keyless or key-based signing was used.

---

## 4. Understand verifyImage.groovy

Signing without verification is like locking your front door but never checking if anyone picked the lock. `verifyImage.groovy` is the verification step that runs immediately after signing in both T2 and T3 pipelines.

### Function Signature

```groovy
// jenkins-shared-lib/vars/verifyImage.groovy
// Verifies image signature and SBOM attestation using cosign
//
// Two-step verification:
//   1. cosign verify — validates the keyless signature (Fulcio + Rekor)
//   2. cosign verify-attestation --type cyclonedx — validates SBOM attestation
//
// Usage:
//   verifyImage(imageRef: 'registry/ns/app:tag')

def call(Map config = [:]) {
    def startTime = System.currentTimeMillis()
    def imageRef = config.imageRef ?: ''
    def rekorUrl = config.rekorUrl ?: 'https://rekor-server-trusted-artifact-signer.apps...'
    def oidcIssuer = config.oidcIssuer ?: 'https://keycloak.apps.../realms/trustify'
```

### Step 1: Extract Trust Material

The function pulls the same CA certificate and trust material that signing used:

```groovy
        // Extract cluster CA cert for cosign TLS trust
        sh """
            REKOR_HOST=\$(echo "${rekorUrl}" | sed 's|https://||')
            openssl s_client -showcerts -connect \${REKOR_HOST}:443 </dev/null 2>/dev/null | \\
                sed -ne '/-BEGIN CERTIFICATE-/,/-END CERTIFICATE-/p' > /tmp/cluster-ca.crt 2>/dev/null || true
            if [ -s /tmp/cluster-ca.crt ]; then
                cat /etc/pki/ca-trust/extracted/pem/tls-ca-bundle.pem /tmp/cluster-ca.crt > /tmp/merged-ca-bundle.pem 2>/dev/null
            else
                cp /etc/pki/ca-trust/extracted/pem/tls-ca-bundle.pem /tmp/merged-ca-bundle.pem 2>/dev/null || true
            fi
        """

        // Get RHTAS trust material from ConfigMap
        sh """
            oc get configmap rhtas-ctlog-public-key -n devsecops-tools \\
                -o jsonpath='{.data.ctlog-public\\.pem}' > /tmp/ctlog-public.pem 2>/dev/null || true
            oc get configmap rhtas-ctlog-public-key -n devsecops-tools \\
                -o jsonpath='{.data.rekor-public\\.pem}' > /tmp/rekor-public.pem 2>/dev/null || true
            oc get configmap rhtas-ctlog-public-key -n devsecops-tools \\
                -o jsonpath='{.data.fulcio-root\\.pem}' > /tmp/fulcio-root.pem 2>/dev/null || true
        """
```

Three PEM files are extracted from the `rhtas-ctlog-public-key` ConfigMap:

1. **`ctlog-public.pem`** -- Verifies the SCT (Signed Certificate Timestamp) proving the signing cert was logged.
2. **`rekor-public.pem`** -- Verifies the Rekor inclusion proof proving the signature was logged.
3. **`fulcio-root.pem`** -- Verifies the signing certificate was issued by our Fulcio CA, not some other CA.

### Step 2: Verify the Image Signature

```groovy
        // Step 1: Verify image signature
        echo "  [1/2] Verifying image signature..."

        def verifyExitCode = sh(script: """
            SSL_CERT_FILE=/tmp/merged-ca-bundle.pem \\
            SIGSTORE_CT_LOG_PUBLIC_KEY_FILE=/tmp/ctlog-public.pem \\
            SIGSTORE_REKOR_PUBLIC_KEY=/tmp/rekor-public.pem \\
            SIGSTORE_ROOT_FILE=/tmp/fulcio-root.pem \\
            COSIGN_EXPERIMENTAL=1 cosign verify \\
                --rekor-url="${rekorUrl}" \\
                --certificate-identity-regexp=".*" \\        # ← Match any identity in the cert
                --certificate-oidc-issuer="${oidcIssuer}" \\ # ← But only from our Keycloak issuer
                --insecure-ignore-tlog=true \\               # ← See note below
                --allow-insecure-registry \\
                ${imageRef} 2>&1
        """, returnStatus: true)

        if (verifyExitCode == 0) {
            signatureValid = true
            echo "  Signature VALID: ${imageRef}"
        } else {
            echo "  ERROR: Signature verification failed (exit code ${verifyExitCode})"
        }
```

Let's break down the verification flags:

| Flag | Value | What It Checks |
|------|-------|----------------|
| `--certificate-identity-regexp` | `".*"` | Matches any subject in the signing certificate. In production, you would restrict this to your pipeline's specific identity. |
| `--certificate-oidc-issuer` | `${oidcIssuer}` | Ensures the signing certificate was issued for tokens from YOUR Keycloak, not some other OIDC provider. This is the critical trust anchor. |
| `--insecure-ignore-tlog` | `true` | Skips Rekor transparency log verification. Used here because the private RHTAS Rekor instance may not be fully compatible with cosign's public Rekor expectations. In a production deployment with properly configured trust material, this should be `false`. |
| `--allow-insecure-registry` | (flag) | Allows cosign to pull from the internal OCP registry which may not use publicly trusted TLS. |

> **Why this matters:** The `--certificate-oidc-issuer` flag is the most important security control. Without it, anyone with any Fulcio certificate from any issuer could produce a valid-looking signature. By restricting to your Keycloak issuer, you ensure only your pipeline (authenticated as the `walker` client in the `trustify` realm) can sign images that pass verification.

### Step 3: Verify SBOM Attestation

```groovy
        // Step 2: Verify SBOM attestation
        echo "  [2/2] Verifying SBOM attestation..."

        def attestExitCode = sh(script: """
            SSL_CERT_FILE=/tmp/merged-ca-bundle.pem \\
            SIGSTORE_CT_LOG_PUBLIC_KEY_FILE=/tmp/ctlog-public.pem \\
            SIGSTORE_REKOR_PUBLIC_KEY=/tmp/rekor-public.pem \\
            SIGSTORE_ROOT_FILE=/tmp/fulcio-root.pem \\
            COSIGN_EXPERIMENTAL=1 cosign verify-attestation \\
                --rekor-url="${rekorUrl}" \\
                --certificate-identity-regexp=".*" \\
                --certificate-oidc-issuer="${oidcIssuer}" \\
                --insecure-ignore-tlog=true \\
                --type cyclonedx \\                         # ← THIS IS KEY: verify CycloneDX SBOM attestation
                --allow-insecure-registry \\
                ${imageRef} 2>&1
        """, returnStatus: true)

        if (attestExitCode == 0) {
            attestationValid = true
            echo "  SBOM attestation VALID: ${imageRef}"
        } else {
            echo "  WARNING: SBOM attestation verification failed (exit code ${attestExitCode})"
        }
```

The `--type cyclonedx` flag tells cosign to look for an attestation with the CycloneDX predicate type. If the image has no SBOM attestation (for example, on a first T2 run before SBOM generation was added), this step fails gracefully -- it logs a warning but does not block the pipeline.

### Return Value and Failure Semantics

```groovy
        def duration = (System.currentTimeMillis() - startTime) / 1000
        def overallStatus = signatureValid ? 'SUCCESS' : 'FAILURE'  # ← Only signature is mandatory

        return [
            status: overallStatus,
            duration: duration,
            imageRef: imageRef,
            signatureValid: signatureValid,         // ← Pipeline fails if false
            attestationValid: attestationValid,     // ← Logged but doesn't fail pipeline
            oidcIssuer: oidcIssuer
        ]
```

Notice the asymmetry: **signature verification is mandatory** (FAILURE blocks the pipeline), but **attestation verification is optional** (it is logged but does not block). This is intentional -- SBOM attestation may not exist on every image (e.g., images built before SBOM generation was added to the pipeline).

### What Failure Looks Like

When signature verification fails, you see this in the Jenkins console:

```
=== Image Verification (cosign verify) ===
  Image: image-registry.openshift-image-registry.svc:5000/sampleapi-dev/sampleapi:main-abc1234
  OIDC Issuer: https://keycloak.apps.<domain>/realms/trustify
  [1/2] Verifying image signature...
  ERROR: Signature verification failed (exit code 1)
ERROR: Image verification failed — signature invalid
```

Common causes:

1. **Image was modified after signing** -- Someone pushed a new layer or re-tagged the image. The digest changed, invalidating the signature.
2. **Wrong OIDC issuer** -- The signing was done with a different Keycloak realm than the verifier expects.
3. **Trust material mismatch** -- The `rhtas-ctlog-public-key` ConfigMap has stale keys from a previous RHTAS deployment.
4. **Image was never signed** -- The signing step was skipped or failed silently.

---

## 5. Run the Pipeline with Sign + Verify

Now let's see everything in action. We will trigger a T2 pipeline for sampleapi and observe the signing and verification stages.

### Step 1: Confirm Jenkins Credentials Exist

Before triggering the pipeline, verify that the required Jenkins credentials are configured:

```bash
# Check that the trustify-walker-token credential exists in Jenkins
# (This is the Keycloak client secret for the walker client)
curl -sk -u admin:$($OC get secret jenkins-admin-password -n $NS_TOOLS \
    -o jsonpath='{.data.password}' | base64 -d 2>/dev/null || echo 'admin') \
    "${JENKINS_URL}/credentials/store/system/domain/_/credential/trustify-walker-token/api/json" \
    2>/dev/null | python3 -c "import json,sys; d=json.load(sys.stdin); print(f'Credential: {d[\"id\"]} (type: {d[\"typeName\"]})')" 2>/dev/null || echo "trustify-walker-token: NOT FOUND (keyless signing will fail, key-based fallback will be used)"
```

Expected output:

```
Credential: trustify-walker-token (type: Secret text)
```

If the credential does not exist, keyless signing will fail and the pipeline will fall back to key-based signing (using the `cosign-signing-key` credential). Both methods produce valid signatures, but keyless is preferred for the audit trail.

### Step 2: Trigger a T2 Pipeline

Push a small change to the sampleapi source repository to trigger a T2 merge pipeline:

```bash
# Option A: Push a commit to the sampleapi source repo
# (If you have the repo cloned locally)
cd ~/repos/app-source
echo "// Trigger T2 for signing test - $(date)" >> README.md
git add README.md
git commit -m "test: trigger T2 to verify image signing"
git push origin main

# Option B: Trigger the Jenkins job manually via API
curl -sk -X POST -u admin:$(cat ~/.jenkins-token 2>/dev/null || echo admin) \
    "${JENKINS_URL}/job/sampleapi-merge/build"
echo "T2 pipeline triggered for sampleapi"
```

### Step 3: Watch the Sign Image Stage

Open the Jenkins console output for the running build:

```bash
echo "Open in browser: ${JENKINS_URL}/job/sampleapi-merge/lastBuild/console"
```

Look for the signing output. A successful keyless signing looks like this:

```
=== Image Signing & SBOM Attestation (RHTAS) ===
  Image: image-registry.openshift-image-registry.svc:5000/sampleapi-dev/sampleapi:main-a1b2c3d
  SBOM: sbom-sampleapi-main-a1b2c3d.json
  Obtaining OIDC token from Keycloak...
  [1/2] Signing image (keyless via Fulcio + Rekor)...
tlog entry created with index: 42
  Image signed (keyless): image-registry.openshift-image-registry.svc:5000/sampleapi-dev/sampleapi:main-a1b2c3d
  [2/2] Attaching SBOM attestation...
tlog entry created with index: 43
  SBOM attestation: SUCCESS
```

Key things to verify in the output:

| Output Line | What It Means |
|-------------|---------------|
| `tlog entry created with index: 42` | The signature was recorded in Rekor. The index number is the entry ID. |
| `Image signed (keyless)` | Fulcio accepted the OIDC token and issued a signing certificate. |
| `tlog entry created with index: 43` | The SBOM attestation was also recorded in Rekor (separate entry). |
| `SBOM attestation: SUCCESS` | The CycloneDX SBOM was successfully bound to the image digest. |

If you see `Falling back to key-based signing...` instead, it means Fulcio rejected the OIDC token. This is not a failure -- the image is still signed -- but you should investigate the Keycloak configuration (see Common Mistakes section).

### Step 4: Watch the Verify Image Stage

Immediately after signing, the pipeline runs verification:

```
=== Image Verification (cosign verify) ===
  Image: image-registry.openshift-image-registry.svc:5000/sampleapi-dev/sampleapi:main-a1b2c3d
  OIDC Issuer: https://keycloak.apps.<domain>/realms/trustify
  [1/2] Verifying image signature...
  Signature VALID: image-registry.openshift-image-registry.svc:5000/sampleapi-dev/sampleapi:main-a1b2c3d
  [2/2] Verifying SBOM attestation...
  SBOM attestation VALID: image-registry.openshift-image-registry.svc:5000/sampleapi-dev/sampleapi:main-a1b2c3d
```

Both checks passed. The pipeline continues to the ACS scan and GitOps update stages.

### Step 5: Check the Rekor Transparency Log

Every signing event is recorded in Rekor. You can browse the log via the Rekor Search UI or query it via the API.

**Via the Search UI:**

```bash
echo "Open in browser: ${RHTAS_REKOR_SEARCH_URL}"
```

In the search UI, you can search by:

- **Log index** -- The number from `tlog entry created with index: 42`
- **Image hash** -- The SHA256 digest of the signed image
- **Email/identity** -- The OIDC subject that signed the image

**Via the Rekor API:**

```bash
# Get the latest entries from the Rekor log
curl -sk "${RHTAS_REKOR_URL}/api/v1/log/entries?logIndex=42" | python3 -m json.tool | head -20
```

Expected output (abbreviated):

```json
{
    "24296fb24b8ad77a...": {
        "body": "eyJhcGlWZXJzaW9uIjoiMC4...",
        "integratedTime": 1716700800,
        "logID": "c0d23d6a...",
        "logIndex": 42,
        "verification": {
            "inclusionProof": {
                "checkpoint": "rekor.sigstore.dev - ...",
                "hashes": ["abc123...", "def456..."],
                "logIndex": 42,
                "rootHash": "789abc...",
                "treeSize": 43
            },
            "signedEntryTimestamp": "MEUCIQD..."
        }
    }
}
```

The `inclusionProof` field is the Merkle tree proof that this entry exists in the log and has not been tampered with. The `signedEntryTimestamp` is signed by the Rekor server's key, proving the entry was made by Rekor (not forged).

### Step 6: Verify Manually with cosign CLI

You do not have to trust Jenkins. You can verify the signature yourself from your local machine:

```bash
# First, log in to the internal registry (if verifying from outside the cluster,
# you need the external registry route)
REGISTRY_EXT="default-route-openshift-image-registry.${APPS_DOMAIN}"
$OC whoami -t | podman login --tls-verify=false -u unused --password-stdin ${REGISTRY_EXT}

# Get the full image reference (with digest for maximum specificity)
IMAGE_REF="${REGISTRY_EXT}/sampleapi-dev/sampleapi:main-a1b2c3d"
echo "Verifying: ${IMAGE_REF}"

# Extract the Fulcio root CA for verification
FULCIO_ROOT=$($OC get configmap rhtas-ctlog-public-key -n $NS_TOOLS \
    -o jsonpath='{.data.fulcio-root\.pem}')
echo "${FULCIO_ROOT}" > /tmp/fulcio-root.pem

# Verify the signature
COSIGN_EXPERIMENTAL=1 cosign verify \
    --rekor-url="${RHTAS_REKOR_URL}" \
    --certificate-identity-regexp=".*" \
    --certificate-oidc-issuer="https://keycloak.${APPS_DOMAIN}/realms/trustify" \
    --insecure-ignore-tlog=true \
    --allow-insecure-registry \
    ${IMAGE_REF}
```

Expected output:

```
Verification for default-route-openshift-image-registry.apps.<domain>/sampleapi-dev/sampleapi:main-a1b2c3d --
The following checks were performed on each of these signatures:
  - The cosign claims were validated
  - The claims were present in the transparency log
  - The signatures were verified against the specified public key

[{"critical":{"identity":{"docker-reference":"..."},"image":{"docker-manifest-digest":"sha256:abc123..."},"type":"cosign container image signature"},...}]
```

Now verify the SBOM attestation:

```bash
COSIGN_EXPERIMENTAL=1 cosign verify-attestation \
    --rekor-url="${RHTAS_REKOR_URL}" \
    --certificate-identity-regexp=".*" \
    --certificate-oidc-issuer="https://keycloak.${APPS_DOMAIN}/realms/trustify" \
    --insecure-ignore-tlog=true \
    --type cyclonedx \
    --allow-insecure-registry \
    ${IMAGE_REF}
```

Expected output:

```
Verification for default-route-openshift-image-registry.apps.<domain>/sampleapi-dev/sampleapi:main-a1b2c3d --
The following checks were performed on each of these signatures:
  - The cosign claims were validated
  - The claims were present in the transparency log
  - The signatures were verified against the specified public key

{"payloadType":"application/vnd.in-toto+json","payload":"eyJfdHlwZSI6Imh0dHBz...","signatures":[...]}
```

The `payload` field is a base64-encoded in-toto attestation envelope. Decode it to see the CycloneDX SBOM:

```bash
# Extract and decode the attestation payload
COSIGN_EXPERIMENTAL=1 cosign verify-attestation \
    --rekor-url="${RHTAS_REKOR_URL}" \
    --certificate-identity-regexp=".*" \
    --certificate-oidc-issuer="https://keycloak.${APPS_DOMAIN}/realms/trustify" \
    --insecure-ignore-tlog=true \
    --type cyclonedx \
    --allow-insecure-registry \
    ${IMAGE_REF} 2>/dev/null | \
    python3 -c "
import json, sys, base64
data = json.load(sys.stdin)
payload = json.loads(base64.b64decode(data['payload']))
predicate = payload.get('predicate', {})
# Show SBOM metadata
print(f'SBOM Format: {predicate.get(\"specVersion\", \"unknown\")}')
print(f'Components: {len(predicate.get(\"components\", []))}')
print(f'Serial: {predicate.get(\"serialNumber\", \"unknown\")}')
" 2>/dev/null || echo "Could not decode attestation payload (this is normal if the output format differs)"
```

Expected output:

```
SBOM Format: 1.5
Components: 47
Serial: urn:uuid:abc123-def456-...
```

> **Why this matters:** Manual verification proves the signing infrastructure works end-to-end. It is not just Jenkins saying "verification passed" -- you can independently confirm it from any machine with cosign and network access to the registry and Rekor.

### Step 7: Repeat for a Java Service (Optional)

If you have Java services deployed (from Module 9b), trigger a T2 pipeline for one of them:

```bash
# Trigger T2 for order-service
curl -sk -X POST -u admin:$(cat ~/.jenkins-token 2>/dev/null || echo admin) \
    "${JENKINS_URL}/job/order-service-merge/build"
echo "T2 pipeline triggered for order-service"
```

The signing and verification stages work identically for Java services. The `signImage.groovy` and `verifyImage.groovy` functions are language-agnostic -- they operate on the container image, not the source code.

### Step 8: Verify the Pipeline Summary

After the pipeline completes, check the GitLab MR comment (if triggered by a push to main) or the Jenkins build summary. The signing results appear in the security gate report:

```
╔═══════════════════════════════════════════════════════════╗
║               DevSecOps Security Gate Report              ║
╠═══════════════════════════════════════════════════════════╣
║  Stage              │ Status  │ Details                   ║
╠═════════════════════╪═════════╪═══════════════════════════╣
║  Unit Tests         │ PASS    │ 12 passed, 0 failed       ║
║  SonarQube SAST     │ PASS    │ Quality gate: OK          ║
║  Dependency Check   │ PASS    │ 0 critical, 2 medium      ║
║  SBOM Analysis      │ PASS    │ 47 components, 0 critical ║
║  Image Build        │ PASS    │ sampleapi:main-a1b2c3d    ║
║  Image Push         │ PASS    │ Internal registry         ║
║  Image Signing      │ PASS    │ keyless (RHTAS)           ║  ← Signing method shown
║  Image Verify       │ PASS    │ sig:valid, att:valid      ║  ← Both checks passed
║  ACS Scan           │ PASS    │ 0 critical, 3 low         ║
║  GitOps Update      │ PASS    │ dev overlay updated       ║
║  Deploy DEV         │ PASS    │ ArgoCD synced             ║
╚═══════════════════════════════════════════════════════════╝
```

---

## 6. ACS Policy for Signed Images

The final piece of the trust chain is enforcement. We have signed and verified images in the pipeline, but what stops someone from deploying an unsigned image directly to production? The answer is an ACS (StackRox) deploy-time policy.

### The Trust Loop

Here is the complete trust chain we are building:

```
  Pipeline builds image
       │
       ▼
  Pipeline signs image (cosign + Fulcio + Rekor)
       │
       ▼
  Pipeline verifies signature (cosign verify)
       │
       ▼
  Pipeline pushes to registry (signed image + attestation)
       │
       ▼
  GitOps promotes to SIT/UAT/PROD via MR
       │
       ▼
  ┌──────────────────────────────────────────────┐
  │ ACS Admission Controller checks:             │
  │   "Does this image have a valid signature?"  │
  │                                              │
  │   YES → Allow deployment                    │
  │   NO  → Block deployment, raise violation    │
  └──────────────────────────────────────────────┘
       │
       ▼
  Only signed images run in production
```

Without the ACS policy, someone could bypass the pipeline entirely by pushing an image to the registry and updating the GitOps overlay manually. The admission controller is the last line of defense.

### Step 1: Create the ACS Signature Integration

Before creating the policy, ACS needs to know how to verify signatures. This requires a Cosign signature integration that tells ACS where to find the trust material.

```bash
# Get the ACS admin password and API token
ACS_PASS=$($OC get secret central-htpasswd -n $NS_ACS \
    -o jsonpath='{.data.password}' | base64 -d)
export ROX_API_TOKEN=$($OC get secret acs-token -n $NS_TOOLS \
    -o jsonpath='{.data.token}' | base64 -d)

echo "ACS URL: ${ACS_URL}"
```

Create the signature integration via the ACS API:

```bash
# Get the Fulcio root CA from the trust material ConfigMap
FULCIO_ROOT_PEM=$($OC get configmap rhtas-ctlog-public-key -n $NS_TOOLS \
    -o jsonpath='{.data.fulcio-root\.pem}')

# Create the cosign signature integration
cat <<EOF > /tmp/acs-cosign-integration.json
{
  "name": "RHTAS Cosign Verification",
  "cosign": {
    "publicKeys": [
      {
        "name": "RHTAS Fulcio Root CA",
        "publicKeyPemEnc": $(echo "${FULCIO_ROOT_PEM}" | python3 -c "import json,sys; print(json.dumps(sys.stdin.read()))")
      }
    ]
  }
}
EOF

curl -sk -X POST \
    -H "Authorization: Bearer ${ROX_API_TOKEN}" \
    -H "Content-Type: application/json" \
    -d @/tmp/acs-cosign-integration.json \
    "${ACS_URL}/v1/signatureintegrations"
```

Expected output:

```json
{
  "id": "io.stackrox.signatureintegration.abc123...",
  "name": "RHTAS Cosign Verification",
  "cosign": {
    "publicKeys": [
      {
        "name": "RHTAS Fulcio Root CA",
        "publicKeyPemEnc": "-----BEGIN CERTIFICATE-----\nMIIC..."
      }
    ]
  }
}
```

Save the integration ID -- you will need it for the policy:

```bash
# Get the integration ID
COSIGN_INTEGRATION_ID=$(curl -sk \
    -H "Authorization: Bearer ${ROX_API_TOKEN}" \
    "${ACS_URL}/v1/signatureintegrations" | \
    python3 -c "
import json, sys
integrations = json.load(sys.stdin).get('integrations', [])
for i in integrations:
    if 'RHTAS' in i.get('name', ''):
        print(i['id'])
        break
" 2>/dev/null)

echo "Cosign Integration ID: ${COSIGN_INTEGRATION_ID}"
```

### Step 2: Create the Signed Image Policy

Now create an ACS policy that requires images to be signed before deploying to production namespaces:

```bash
cat <<'POLICY_EOF' > /tmp/acs-require-signed-images.json
{
  "name": "DevSecOps - Require Signed Images (PROD)",
  "description": "Block deployment of unsigned container images in production namespaces. Images must be signed via RHTAS (Sigstore/cosign) by the CI pipeline. This policy enforces the final link in the supply chain trust chain.",
  "rationale": "Unsigned images may have been modified after pipeline security gates (SAST, SCA, ACS scan) passed. Image signing provides cryptographic proof that the image was produced by the trusted CI pipeline and has not been tampered with.",
  "remediation": "Ensure the image was built and signed by the Jenkins CI pipeline (T2 or T3). Run 'cosign verify' manually to diagnose signature issues. Do not push images directly to the registry - always go through the pipeline.",
  "categories": [
    "Supply Chain Security"
  ],
  "lifecycleStages": [
    "DEPLOY"
  ],
  "severity": "CRITICAL_SEVERITY",
  "enforcementActions": [
    "SCALE_TO_ZERO_ENFORCEMENT"
  ],
  "policySections": [
    {
      "sectionName": "Image Signature Required",
      "policyGroups": [
        {
          "fieldName": "Image Signature Verified By",
          "booleanOperator": "OR",
          "negate": true,
          "values": [
            {
              "value": "COSIGN_INTEGRATION_ID_PLACEHOLDER"
            }
          ]
        }
      ]
    }
  ],
  "scope": [
    {
      "cluster": "",
      "namespace": "sampleapi-prod",
      "label": null
    },
    {
      "cluster": "",
      "namespace": "javaapp-prod",
      "label": null
    }
  ],
  "disabled": false
}
POLICY_EOF

# Replace the placeholder with the actual integration ID
sed -i "s/COSIGN_INTEGRATION_ID_PLACEHOLDER/${COSIGN_INTEGRATION_ID}/" /tmp/acs-require-signed-images.json

# Import the policy
curl -sk -X POST \
    -H "Authorization: Bearer ${ROX_API_TOKEN}" \
    -H "Content-Type: application/json" \
    -d @/tmp/acs-require-signed-images.json \
    "${ACS_URL}/v1/policies"
```

Expected output:

```json
{
  "id": "policy-abc123...",
  "name": "DevSecOps - Require Signed Images (PROD)",
  "severity": "CRITICAL_SEVERITY",
  ...
}
```

Let's break down the policy:

| Field | Value | Explanation |
|-------|-------|-------------|
| `lifecycleStages` | `DEPLOY` | Evaluated when a deployment is created or updated in the target namespaces |
| `severity` | `CRITICAL_SEVERITY` | This is a critical security control, not just a warning |
| `enforcementActions` | `SCALE_TO_ZERO_ENFORCEMENT` | Scales violating deployments to 0 replicas -- pods are killed but the deployment object remains for debugging |
| `fieldName` | `Image Signature Verified By` | The ACS policy field that checks for cosign signatures |
| `negate` | `true` | The policy triggers when the image is NOT verified by the integration (i.e., unsigned images) |
| `scope` | `sampleapi-prod`, `javaapp-prod` | Only enforced in production namespaces -- DEV/SIT/UAT are not affected |

> **Why this matters:** The `SCALE_TO_ZERO_ENFORCEMENT` action is deliberately chosen over `FAIL_DEPLOYMENT_CREATE_ENFORCEMENT` (which would reject the deployment at admission time). Scale-to-zero lets the deployment object exist (for debugging), but kills all pods. This means ArgoCD can still sync the deployment, but no containers actually run until the violation is resolved.

### Step 3: Verify the Policy Exists in ACS

```bash
# List policies with "Signed" in the name
curl -sk -H "Authorization: Bearer ${ROX_API_TOKEN}" \
    "${ACS_URL}/v1/policies" | \
    python3 -c "
import json, sys
policies = json.load(sys.stdin).get('policies', [])
for p in policies:
    if 'Signed' in p.get('name', '') or 'signed' in p.get('name', ''):
        print(f'{p[\"name\"]}')
        print(f'  Severity: {p[\"severity\"]}')
        print(f'  Lifecycle: {p[\"lifecycleStages\"]}')
        print(f'  Enforcement: {p.get(\"enforcementActions\", [])}')
        print(f'  Disabled: {p.get(\"disabled\", False)}')
"
```

Expected output:

```
DevSecOps - Require Signed Images (PROD)
  Severity: CRITICAL_SEVERITY
  Lifecycle: ['DEPLOY']
  Enforcement: ['SCALE_TO_ZERO_ENFORCEMENT']
  Disabled: False
```

### Step 4: Test with an Unsigned Image

Let's prove the policy works by attempting to deploy an unsigned image to the production namespace:

```bash
# Deploy an unsigned image to PROD
cat <<EOF | $OC apply -n $NS_PROD -f -
apiVersion: apps/v1
kind: Deployment
metadata:
  name: test-unsigned-image                   # ← Test deployment
  labels:
    app: test-unsigned
spec:
  replicas: 1
  selector:
    matchLabels:
      app: test-unsigned
  template:
    metadata:
      labels:
        app: test-unsigned
    spec:
      containers:
      - name: test
        image: registry.access.redhat.com/ubi9/ubi-minimal:latest  # ← NOT signed by our pipeline
        command: ["sleep", "3600"]
        resources:
          requests:
            cpu: 50m
            memory: 64Mi
          limits:
            cpu: 100m
            memory: 128Mi
EOF
```

Wait 30 seconds for ACS to evaluate the deployment, then check for violations:

```bash
# Wait for ACS to process the deployment
sleep 30

# Check for violations on this deployment
curl -sk -H "Authorization: Bearer ${ROX_API_TOKEN}" \
    "${ACS_URL}/v1/alerts?query=Deployment:test-unsigned-image" | \
    python3 -c "
import json, sys
data = json.load(sys.stdin)
alerts = data.get('alerts', [])
if alerts:
    for a in alerts:
        print(f'VIOLATION DETECTED:')
        print(f'  Policy: {a[\"policy\"][\"name\"]}')
        print(f'  Severity: {a[\"policy\"][\"severity\"]}')
        print(f'  Deployment: {a[\"deployment\"][\"name\"]}')
        print(f'  Namespace: {a[\"deployment\"][\"namespace\"]}')
        print(f'  Enforcement: {a.get(\"enforcement\", {}).get(\"action\", \"none\")}')
else:
    print('No violations found — check if the policy is enabled and scoped correctly')
"
```

Expected output:

```
VIOLATION DETECTED:
  Policy: DevSecOps - Require Signed Images (PROD)
  Severity: CRITICAL_SEVERITY
  Deployment: test-unsigned-image
  Namespace: sampleapi-prod
  Enforcement: SCALE_TO_ZERO_ENFORCEMENT
```

Verify the deployment was scaled to zero:

```bash
$OC get deployment test-unsigned-image -n $NS_PROD
```

Expected output:

```
NAME                    READY   UP-TO-DATE   AVAILABLE   AGE
test-unsigned-image     0/0     0            0           45s
```

The deployment exists but has 0 replicas. No unsigned pods are running in production.

### Step 5: Verify Signed Images Are Allowed

Now let's confirm that properly signed images (deployed through the pipeline) are not affected:

```bash
# Check the actual sampleapi deployment in PROD (if deployed)
$OC get deployment sampleapi -n $NS_PROD 2>/dev/null

# Or check for violations on the sampleapi deployment
curl -sk -H "Authorization: Bearer ${ROX_API_TOKEN}" \
    "${ACS_URL}/v1/alerts?query=Deployment:sampleapi+Namespace:${NS_PROD}+Policy:Signed" | \
    python3 -c "
import json, sys
data = json.load(sys.stdin)
alerts = data.get('alerts', [])
if alerts:
    print(f'WARNING: {len(alerts)} violation(s) found on sampleapi in PROD')
    for a in alerts:
        print(f'  {a[\"policy\"][\"name\"]}')
else:
    print('No signature violations on sampleapi in PROD — signed images pass the policy')
"
```

Expected output:

```
No signature violations on sampleapi in PROD — signed images pass the policy
```

### Step 6: Clean Up the Test Deployment

```bash
$OC delete deployment test-unsigned-image -n $NS_PROD 2>/dev/null
echo "Test deployment cleaned up"
```

### The Complete Trust Chain (Recap)

With the ACS policy in place, the trust chain is now complete:

```
  1. Developer pushes code to GitLab
       │
       ▼
  2. Pipeline builds, tests, scans (T2/T3)
       │
       ▼
  3. Pipeline signs image with RHTAS (keyless via Fulcio + Rekor)
       │   └── Signature recorded in immutable Rekor transparency log
       │   └── SBOM attested and bound to image digest
       ▼
  4. Pipeline verifies signature (cosign verify)
       │   └── Confirms Fulcio cert chain and Rekor inclusion
       ▼
  5. Pipeline pushes to registry (image + sig + attestation)
       │
       ▼
  6. GitOps promotes through environments (MR-based approval)
       │
       ▼
  7. ACS admission controller checks signature in PROD
       │   └── Unsigned → SCALE_TO_ZERO (blocked)
       │   └── Signed   → ALLOWED
       ▼
  8. Only pipeline-built, signed images run in production
```

Every link in this chain is enforced:

- **Link 1-2:** GitLab webhook triggers the pipeline automatically. No manual builds.
- **Link 3:** Signing is a mandatory pipeline stage. Failure blocks the build.
- **Link 4:** Verification runs immediately after signing. Failure blocks the build.
- **Link 5-6:** GitOps ensures the image tag in production matches what the pipeline produced.
- **Link 7:** ACS policy ensures nothing bypasses the pipeline.
- **Link 8:** The result -- cryptographic assurance that production images are exactly what the pipeline produced.

---

## What Just Happened?

Let's step back and see what you accomplished in this module.

You started with a pipeline that could build, test, scan, and deploy container images. But there was a gap: after the image left the pipeline, there was no proof that it was the same image that was scanned. Someone could replace the image in the registry, push a rogue image directly, or mutate a tag -- and nothing would stop it.

You closed that gap by understanding and working with Red Hat Trusted Artifact Signer (RHTAS):

1. **You examined the RHTAS deployment** -- 11 pods working together to provide enterprise-grade Sigstore services: Fulcio (ephemeral signing certificates), Rekor (immutable transparency log), CTLog (certificate transparency), TSA (timestamps), TUF (trust distribution), and Trillian (Merkle tree backend).

2. **You understood keyless signing** -- Instead of managing long-lived cryptographic keys (which can be stolen, rotated incorrectly, or shared inappropriately), the pipeline authenticates via OIDC to Keycloak, gets a short-lived certificate from Fulcio, signs the image, and records the event in Rekor. The identity is "the CI pipeline authenticated as walker@keycloak/trustify", not "someone with the key".

3. **You traced signImage.groovy** through six steps: TLS trust extraction, CTLog key retrieval, OIDC token acquisition, keyless signing, key-based fallback, and SBOM attestation. Each step has error handling and the function never fails silently.

4. **You traced verifyImage.groovy** through three steps: trust material extraction from the ConfigMap, signature verification against the Fulcio root CA and OIDC issuer, and SBOM attestation verification. Signature failure blocks the pipeline; attestation failure is logged as a warning.

5. **You ran the pipeline** and observed Sign Image and Verify Image stages producing real signatures in Rekor, then verified the signature manually from outside Jenkins.

6. **You created an ACS policy** that blocks unsigned images in production namespaces, closing the trust loop so that no image can reach production without a valid pipeline signature.

The result: your DevSecOps pipeline now provides **cryptographic supply chain integrity**. Every image in production can be traced back to a specific pipeline run, a specific OIDC identity, a specific point in time, recorded in an immutable transparency log.

---

## Common Mistakes

These are real issues encountered during RHTAS implementation. Each one cost hours of debugging.

### Mistake 1: cosign Fails with "x509: certificate signed by unknown authority"

This is the most common RHTAS error. It happens when cosign cannot verify the TLS certificate on the Fulcio or Rekor route.

```
Error: signing [...]: x509: certificate signed by unknown authority
```

**Root cause:** The RHTAS routes use TLS certificates signed by the OpenShift internal CA. Cosign uses the system CA bundle by default, which does not include the cluster CA.

**Fix:** Set `SSL_CERT_FILE` to the merged CA bundle (system CAs + cluster CA). This is what `signImage.groovy` does automatically:

```bash
# Extract the cluster CA
openssl s_client -showcerts -connect fulcio-server-trusted-artifact-signer.apps.<domain>:443 \
    </dev/null 2>/dev/null | sed -ne '/-BEGIN CERTIFICATE-/,/-END CERTIFICATE-/p' > /tmp/cluster-ca.crt

# Merge with system CAs
cat /etc/pki/ca-trust/extracted/pem/tls-ca-bundle.pem /tmp/cluster-ca.crt > /tmp/merged-ca-bundle.pem

# Now cosign trusts the cluster CA
SSL_CERT_FILE=/tmp/merged-ca-bundle.pem cosign sign ...
```

### Mistake 2: Fulcio Rejects the OIDC Token

```
Error: getting Fulcio certificate: error getting cert: 401 Unauthorized
```

**Root cause:** Fulcio validates the OIDC token against the Keycloak issuer URL. If the token is an `access_token` instead of an `id_token`, or if the token has expired, or if the `iss` claim does not match Fulcio's expected issuer, the request is rejected.

**Why it happens:** The `client_credentials` grant type in Keycloak may not return an `id_token` depending on the client configuration. The `walker` client must have `openid` scope enabled and the `id_token` mapper configured.

**Fix:** Check the Keycloak client configuration:

```bash
# Verify the walker client returns an id_token
KEYCLOAK_URL="https://keycloak.${APPS_DOMAIN}"
WALKER_SECRET="<your-walker-secret>"

curl -sk -X POST "${KEYCLOAK_URL}/realms/trustify/protocol/openid-connect/token" \
    -d "client_id=walker" \
    -d "client_secret=${WALKER_SECRET}" \
    -d "grant_type=client_credentials" \
    -d "scope=openid" | python3 -c "
import json, sys
data = json.load(sys.stdin)
if 'id_token' in data:
    print('OK: id_token present')
elif 'access_token' in data:
    print('WARNING: Only access_token returned (no id_token)')
    print('  Fix: Enable openid scope and id_token mapper on the walker client in Keycloak')
else:
    print(f'ERROR: {data.get(\"error\", \"unknown error\")}')
    print(f'  Description: {data.get(\"error_description\", \"none\")}')
"
```

If `id_token` is missing, the pipeline falls back to key-based signing automatically. To fix keyless signing, configure the Keycloak `walker` client to include the `openid` scope and ensure the "audience" mapper includes the Fulcio client ID.

### Mistake 3: Missing rhtas-ctlog-public-key ConfigMap

```
WARNING: CTLog public key not found — create ConfigMap rhtas-ctlog-public-key in devsecops-tools
```

**Root cause:** The trust material ConfigMap was never copied from the RHTAS namespace to the Jenkins tools namespace.

**Impact:** Without the trust material, signing still works (cosign skips SCT verification), but verification is weakened. The `verifyImage.groovy` function falls back to `--insecure-ignore-tlog=true`, which means it does not check the Rekor transparency log.

**Fix:** See Section 2 above for the ConfigMap creation commands. After creating it, verify:

```bash
$OC get configmap rhtas-ctlog-public-key -n $NS_TOOLS -o jsonpath='{.data}' | python3 -c "
import json, sys
data = json.load(sys.stdin)
for key in ['ctlog-public.pem', 'fulcio-root.pem', 'rekor-public.pem']:
    if key in data and len(data[key]) > 50:
        print(f'  {key}: OK ({len(data[key])} bytes)')
    else:
        print(f'  {key}: MISSING or EMPTY')
"
```

### Mistake 4: Using `|| true` After cosign Commands

```groovy
// WRONG -- swallows the exit code, signing "succeeds" even if cosign failed
sh "cosign sign --fulcio-url=... --rekor-url=... ${imageRef} || true"
```

**Why it is dangerous:** If cosign fails (wrong URL, expired token, network error), `|| true` forces exit code 0. The pipeline reports "signing succeeded" when no signature was actually created. Later, `verifyImage.groovy` fails because there is no signature to verify, and the error message is confusing ("no signatures found" instead of "signing failed").

**Correct approach:** Use `returnStatus: true` to capture the exit code as a variable:

```groovy
// CORRECT -- captures the exit code for the function to evaluate
def exitCode = sh(script: "cosign sign ... ${imageRef} 2>&1", returnStatus: true)
if (exitCode == 0) {
    signStatus = 'SUCCESS'
} else {
    echo "Signing failed (exit ${exitCode}) — trying fallback"
}
```

### Mistake 5: Verifying with the Wrong OIDC Issuer

```
Error: none of the expected identities matched what was in the certificate
```

**Root cause:** The `--certificate-oidc-issuer` in `cosign verify` does not match the `oidcIssuer` used during signing. This happens when the Keycloak URL changed (e.g., after a cluster rebuild) or when verifying on a different cluster than where the signing happened.

**Fix:** The OIDC issuer must be exactly the same in both `signImage.groovy` and `verifyImage.groovy`. Both use the same default from the shared library. If you override it in one, you must override it in both:

```groovy
// CONSISTENT -- same oidcIssuer for sign and verify
signImage(imageRef: img, oidcIssuer: 'https://keycloak.example.com/realms/trustify')
verifyImage(imageRef: img, oidcIssuer: 'https://keycloak.example.com/realms/trustify')
```

### Mistake 6: ACS Policy with Wrong Scope

```
// WRONG: Policy scoped to all namespaces -- blocks unsigned images in DEV
"scope": []   // empty scope = all namespaces
```

If you scope the "require signed images" policy to all namespaces, it will also block deployments in DEV, SIT, and UAT. This breaks the T2 pipeline because the image is deployed to DEV before it can be promoted to SIT/UAT.

**Fix:** Always scope the policy to production namespaces only:

```json
"scope": [
    {"namespace": "sampleapi-prod"},
    {"namespace": "javaapp-prod"}
]
```

DEV and SIT environments should have weaker enforcement (inform only) so developers can iterate quickly. Production is where the hard enforcement gate belongs.

### Mistake 7: Forgetting Registry Authentication for cosign

```
Error: GET https://image-registry...:5000/v2/: unauthorized: authentication required
```

**Root cause:** Cosign needs to pull the image (and push the signature back) to the registry. It uses Docker-compatible authentication, reading from `~/.docker/config.json`. The `signImage.groovy` function handles this by logging in with the ServiceAccount token:

```bash
SA_TOKEN=$(cat /var/run/secrets/kubernetes.io/serviceaccount/token)
podman login --tls-verify=false -u unused -p "${SA_TOKEN}" image-registry.openshift-image-registry.svc:5000
cp ${XDG_RUNTIME_DIR}/containers/auth.json $HOME/.docker/config.json
```

If you are verifying manually from outside the cluster, you need to authenticate to the external registry route:

```bash
oc whoami -t | podman login --tls-verify=false -u unused --password-stdin \
    default-route-openshift-image-registry.${APPS_DOMAIN}
```

---

## Challenge: End-to-End Supply Chain Verification

Now that you understand the full signing flow, test it end-to-end with a T3 tag pipeline:

### Your Task

1. Push a version tag to the sampleapi source repository:

```bash
cd ~/repos/app-source
git tag v1.99.0
git push origin v1.99.0
```

2. Watch the T3 pipeline in Jenkins. It should:
   - Build and test the application
   - Generate the SBOM
   - Build the container image
   - Push to registry
   - Run ACS scan (strict mode)
   - Run OWASP ZAP DAST
   - Run k6 performance test
   - **Sign the image (keyless)**
   - **Attach SBOM attestation**
   - **Verify the signature**
   - **Verify the SBOM attestation**
   - Create a SIT promotion MR

3. After the pipeline completes, verify from your local machine:

```bash
# Set the image reference (use the tag from the pipeline)
IMAGE_REF="default-route-openshift-image-registry.${APPS_DOMAIN}/sampleapi-dev/sampleapi:v1.99.0"

# Verify signature
COSIGN_EXPERIMENTAL=1 cosign verify \
    --rekor-url="${RHTAS_REKOR_URL}" \
    --certificate-identity-regexp=".*" \
    --certificate-oidc-issuer="https://keycloak.${APPS_DOMAIN}/realms/trustify" \
    --insecure-ignore-tlog=true \
    --allow-insecure-registry \
    ${IMAGE_REF}

# Verify SBOM attestation
COSIGN_EXPERIMENTAL=1 cosign verify-attestation \
    --rekor-url="${RHTAS_REKOR_URL}" \
    --certificate-identity-regexp=".*" \
    --certificate-oidc-issuer="https://keycloak.${APPS_DOMAIN}/realms/trustify" \
    --insecure-ignore-tlog=true \
    --type cyclonedx \
    --allow-insecure-registry \
    ${IMAGE_REF}
```

4. Check the Rekor transparency log for the signing events:

```bash
echo "Open: ${RHTAS_REKOR_SEARCH_URL}"
# Search for the image digest or the latest log entries
```

5. Approve the SIT promotion MR in GitLab to trigger T4, then verify that the signed image deploys successfully to SIT (no ACS violations).

### Success Criteria

- [ ] T3 pipeline completed with both Sign Image and Verify Image stages green.
- [ ] Manual `cosign verify` from your local machine returned valid.
- [ ] Manual `cosign verify-attestation --type cyclonedx` returned valid.
- [ ] Rekor transparency log contains entries for both the signature and the attestation.
- [ ] The image deployed to SIT without ACS violations.
- [ ] The pipeline security gate report shows `Image Signing: PASS` and `Image Verify: PASS`.

---

## Self-Assessment

Answer these questions without scrolling back up. If you cannot answer confidently, re-read the relevant section.

1. **What are three supply chain attacks that image signing prevents?** What happens in each attack if images are NOT signed?

2. **What is the difference between key-based and keyless signing?** Why is keyless preferred for CI/CD pipelines?

3. **What does Fulcio do?** What does Rekor do? What happens if either one is unavailable?

4. **What OIDC grant type does signImage.groovy use?** Why `client_credentials` instead of `authorization_code`?

5. **Why does signImage.groovy merge the cluster CA with the system CA bundle?** What error do you get without this?

6. **What is the difference between `cosign sign` and `cosign attest`?** What does `--predicate` do?

7. **In verifyImage.groovy, why is signature verification mandatory but attestation verification optional?** When would attestation be missing?

8. **What does the ACS `SCALE_TO_ZERO_ENFORCEMENT` action do?** Why not use `FAIL_DEPLOYMENT_CREATE_ENFORCEMENT` instead?

9. **Someone pushes an unsigned image directly to the internal registry and updates the GitOps overlay to deploy it to production. What happens?**

10. **The pipeline log shows "Falling back to key-based signing..." What is the most likely cause?** Is the image still validly signed?

<details>
<summary>Answers</summary>

1. **Registry compromise** -- attacker replaces the image in the registry; without signing, the deployment pulls the malicious image because the tag is unchanged. **Man-in-the-middle** -- the image is intercepted during transit; without signing, there is no way to detect modification. **Insider threat** -- a rogue developer pushes a backdoored image, bypassing the pipeline; without signing and an admission policy, it deploys to production undetected. In all three cases, image signing would invalidate the modified/unauthorized image because the signature would not match.

2. Key-based signing uses a long-lived private key file. If the key is stolen, all images signed with it are compromised, and you cannot tell who signed what. Keyless signing uses short-lived certificates tied to OIDC identity -- every signing event gets a fresh certificate, the identity is recorded in Rekor, and there is no key to steal. Keyless is preferred for CI/CD because it eliminates key management (rotation, storage, access control) and provides a built-in audit trail via Rekor.

3. **Fulcio** is a Certificate Authority that issues short-lived signing certificates to entities that prove their identity via OIDC. It validates the OIDC token and embeds the identity in the certificate. **Rekor** is an immutable transparency log that records every signing event (signature, certificate, image digest) with a Merkle tree proof. If Fulcio is unavailable, keyless signing fails and the pipeline falls back to key-based signing. If Rekor is unavailable, cosign cannot record the signature in the transparency log and may fail (depending on flags).

4. `client_credentials` -- this is a service-to-service flow where the pipeline authenticates as the `walker` client with a client secret. `authorization_code` requires a browser redirect for user login, which is impossible in a non-interactive CI/CD pipeline. The pipeline is a machine identity, not a human user.

5. RHTAS routes use TLS certificates signed by the OpenShift internal CA, which is not in the system CA bundle. Without the merged bundle, cosign fails with `x509: certificate signed by unknown authority`. The merge creates a single file that contains both system CAs (for accessing public services) and the cluster CA (for accessing RHTAS routes).

6. `cosign sign` creates a cryptographic signature over the image digest and stores it as an OCI artifact in the registry. `cosign attest` creates an in-toto attestation that wraps a "predicate" (the SBOM) with the image digest and signs the whole thing. `--predicate` specifies the file to use as the attestation predicate (in our case, the CycloneDX SBOM JSON). The attestation cryptographically binds the SBOM to the specific image.

7. Signature verification is mandatory because it proves the image was produced by the trusted pipeline -- this is a fundamental security requirement. Attestation verification is optional because SBOM attestation may not exist on images built before SBOM generation was added to the pipeline, or on first runs where the SBOM stage was skipped. Making attestation mandatory would break backward compatibility with older images.

8. `SCALE_TO_ZERO_ENFORCEMENT` sets the deployment's replica count to 0. The deployment object remains in the namespace (so you can inspect its spec for debugging), but no pods run. `FAIL_DEPLOYMENT_CREATE_ENFORCEMENT` would reject the deployment at the Kubernetes API level, which means ArgoCD would see a sync failure and the deployment object would not exist at all, making debugging harder. Scale-to-zero is a softer enforcement that still prevents unsigned code from running.

9. ACS admission controller evaluates the deployment against the "Require Signed Images (PROD)" policy. The image is not signed by the pipeline's Cosign integration, so the policy violation triggers `SCALE_TO_ZERO_ENFORCEMENT`. The deployment's replicas are set to 0. No pods run. A critical alert appears in ACS Central. The unsigned image never serves traffic.

10. The most likely cause is that Fulcio rejected the OIDC token. This happens when the Keycloak `walker` client does not return an `id_token` for the `client_credentials` grant (only an `access_token`), or the token has an `iss` claim that does not match Fulcio's expected issuer. Yes, the image is still validly signed -- key-based signing produces a cryptographically valid signature that cosign can verify. However, the signature is tied to a static key rather than an OIDC identity, which means the audit trail in Rekor shows "signed by key X" instead of "signed by walker@keycloak/trustify".

</details>

---

## Next Module Preview

**Module 19: SBOM Management with RHTPA (Trustify)** builds on the SBOMs you are now generating and attesting. While Module 17 taught you to generate SBOMs and Module 18 taught you to sign and attest them, Module 19 covers the backend: uploading SBOMs to Red Hat Trusted Profile Analyzer (RHTPA/Trustify), querying vulnerability data via the Trustify API, setting up continuous vulnerability monitoring as new CVEs are published, and integrating Trustify scan results into the pipeline as an additional quality gate. You will also learn how to use the Trustify web UI to browse your software inventory and track which services are affected by newly discovered vulnerabilities -- turning the SBOM from a build-time artifact into a living operational tool.

---
