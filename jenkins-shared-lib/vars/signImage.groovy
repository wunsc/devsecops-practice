// jenkins-shared-lib/vars/signImage.groovy
// Sign container image + attach SBOM attestation using RHTAS (Sigstore)
//
// Primary: keyless signing via Fulcio (OIDC identity) + Rekor (transparency log)
// Fallback: key-based signing if Fulcio rejects the identity token
//           (client_credentials grant may not produce a valid id_token)
//
// Flow:
//   1. Obtain OIDC token from Keycloak (trustify realm, walker client)
//   2. cosign sign (keyless via Fulcio + Rekor)
//   3. If keyless fails, try key-based with cosign-signing-key credential
//   4. cosign attest --predicate sbom.json --type cyclonedx (SBOM attestation)
//   5. All recorded in Rekor transparency log
//
// Usage:
//   signImage(imageRef: 'registry/ns/app:tag', sbomFile: 'sbom-app-v1.json')

def call(Map config = [:]) {
    def startTime = System.currentTimeMillis()
    def imageRef = config.imageRef ?: ''
    def sbomFile = config.sbomFile ?: ''
    def rekorUrl = config.rekorUrl ?: 'https://rekor-server-trusted-artifact-signer.apps.muhrahma-cluster.vmware.tamlab.rdu2.redhat.com'
    def fulcioUrl = config.fulcioUrl ?: 'https://fulcio-server-trusted-artifact-signer.apps.muhrahma-cluster.vmware.tamlab.rdu2.redhat.com'
    def oidcIssuer = config.oidcIssuer ?: 'https://keycloak.apps.muhrahma-cluster.vmware.tamlab.rdu2.redhat.com/realms/trustify'
    def keyCredId = config.keyCredId ?: 'cosign-signing-key'

    echo "=== Image Signing & SBOM Attestation (RHTAS) ==="
    echo "  Image: ${imageRef}"
    echo "  SBOM: ${sbomFile ?: 'none'}"

    if (!imageRef) {
        echo "ERROR: No imageRef provided"
        return [status: 'FAILURE', error: 'No image reference']
    }

    def signStatus = 'FAILED'
    def signMethod = 'none'
    def attestStatus = 'SKIPPED'

    try {
        // Extract cluster CA cert so cosign trusts Fulcio/Rekor self-signed routes
        sh """
            FULCIO_HOST=\$(echo "${fulcioUrl}" | sed 's|https://||')
            openssl s_client -showcerts -connect \${FULCIO_HOST}:443 </dev/null 2>/dev/null | \
                sed -ne '/-BEGIN CERTIFICATE-/,/-END CERTIFICATE-/p' > /tmp/cluster-ca.crt 2>/dev/null || true
            if [ -s /tmp/cluster-ca.crt ]; then
                cat /etc/pki/ca-trust/extracted/pem/tls-ca-bundle.pem /tmp/cluster-ca.crt > /tmp/merged-ca-bundle.pem 2>/dev/null
            else
                cp /etc/pki/ca-trust/extracted/pem/tls-ca-bundle.pem /tmp/merged-ca-bundle.pem 2>/dev/null || true
            fi
        """

        // Get RHTAS CTLog public key for SCT verification (from ConfigMap in Jenkins namespace)
        sh """
            oc get configmap rhtas-ctlog-public-key -n devsecops-tools \
                -o jsonpath='{.data.ctlog-public\\.pem}' > /tmp/ctlog-public.pem 2>/dev/null || true
            if [ ! -s /tmp/ctlog-public.pem ]; then
                echo "WARNING: CTLog public key not found — create ConfigMap rhtas-ctlog-public-key in devsecops-tools"
            fi
        """

        // Try keyless signing first (preferred)
        echo "  Obtaining OIDC token from Keycloak..."
        def idToken = ''
        try {
            withCredentials([string(credentialsId: 'trustify-walker-token', variable: 'WALKER_SECRET')]) {
                idToken = sh(script: """
                    curl -sfk -X POST "${oidcIssuer}/protocol/openid-connect/token" \
                        -d "client_id=walker" \
                        -d "client_secret=\${WALKER_SECRET}" \
                        -d "grant_type=client_credentials" \
                        -d "scope=openid" \
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

        if (idToken) {
            // ── Keyless signing via Fulcio + Rekor ──
            // Login to internal registry so cosign can access the image
            sh """
                SA_TOKEN=\$(cat /var/run/secrets/kubernetes.io/serviceaccount/token)
                podman login --tls-verify=false -u unused -p "\${SA_TOKEN}" image-registry.openshift-image-registry.svc:5000 2>/dev/null || true
                mkdir -p \$HOME/.docker 2>/dev/null || true
                cp \${XDG_RUNTIME_DIR}/containers/auth.json \$HOME/.docker/config.json 2>/dev/null || \
                    cp /run/containers/0/auth.json \$HOME/.docker/config.json 2>/dev/null || true
            """

            echo "  [1/2] Signing image (keyless via Fulcio + Rekor)..."

            def signExitCode = sh(script: """
                SSL_CERT_FILE=/tmp/merged-ca-bundle.pem \
                SIGSTORE_CT_LOG_PUBLIC_KEY_FILE=/tmp/ctlog-public.pem \
                COSIGN_EXPERIMENTAL=1 cosign sign \
                    --fulcio-url="${fulcioUrl}" \
                    --rekor-url="${rekorUrl}" \
                    --identity-token="${idToken}" \
                    --allow-insecure-registry \
                    --yes \
                    ${imageRef} 2>&1
            """, returnStatus: true)

            if (signExitCode == 0) {
                signStatus = 'SUCCESS'
                signMethod = 'keyless (RHTAS)'
                echo "  Image signed (keyless): ${imageRef}"
            } else {
                echo "  WARNING: Keyless signing failed (exit ${signExitCode}) — Fulcio may reject client_credentials token"
                echo "  Falling back to key-based signing..."
            }
        }

        // Key-based fallback if keyless didn't work
        if (signStatus != 'SUCCESS') {
            signStatus = tryKeyBasedSign(imageRef, keyCredId, rekorUrl)
            signMethod = signStatus == 'SUCCESS' ? 'key-based (fallback)' : 'none'
        }

        if (signStatus != 'SUCCESS') {
            error "Image signing failed — both keyless and key-based methods failed"
        }

        // ── Step 2: SBOM Attestation ──
        if (sbomFile && fileExists(sbomFile)) {
            echo "  [2/2] Attaching SBOM attestation..."

            if (idToken && signMethod.contains('keyless')) {
                def attestExitCode = sh(script: """
                    SSL_CERT_FILE=/tmp/merged-ca-bundle.pem \
                    SIGSTORE_CT_LOG_PUBLIC_KEY_FILE=/tmp/ctlog-public.pem \
                    COSIGN_EXPERIMENTAL=1 cosign attest \
                        --fulcio-url="${fulcioUrl}" \
                        --rekor-url="${rekorUrl}" \
                        --identity-token="${idToken}" \
                        --allow-insecure-registry \
                        --predicate ${sbomFile} \
                        --type cyclonedx \
                        --yes \
                        ${imageRef} 2>&1
                """, returnStatus: true)

                attestStatus = attestExitCode == 0 ? 'SUCCESS' : 'FAILED'
            } else {
                // Key-based attestation
                try {
                    withCredentials([file(credentialsId: keyCredId, variable: 'COSIGN_KEY')]) {
                        def attestExitCode = sh(script: """
                            COSIGN_PASSWORD='' cosign attest \
                                --key "\${COSIGN_KEY}" \
                                --allow-insecure-registry \
                                --predicate ${sbomFile} \
                                --type cyclonedx \
                                --yes \
                                ${imageRef} 2>&1
                        """, returnStatus: true)
                        attestStatus = attestExitCode == 0 ? 'SUCCESS' : 'FAILED'
                    }
                } catch (Exception e) {
                    attestStatus = 'SKIPPED'
                    echo "  SBOM attestation skipped: ${e.message}"
                }
            }
            echo "  SBOM attestation: ${attestStatus}"
        } else {
            echo "  [2/2] No SBOM file — skipping attestation"
        }

        def duration = (System.currentTimeMillis() - startTime) / 1000

        return [
            status: 'SUCCESS',
            duration: duration,
            imageRef: imageRef,
            signMethod: signMethod,
            rekorUrl: rekorUrl,
            attestStatus: attestStatus,
            sbomAttested: attestStatus == 'SUCCESS'
        ]
    } catch (Exception e) {
        echo "ERROR: Signing failed — ${e.message}"
        return [status: 'FAILURE', error: e.message, attestStatus: 'FAILED']
    }
}

private String tryKeyBasedSign(String imageRef, String keyCredId, String rekorUrl) {
    try {
        withCredentials([file(credentialsId: keyCredId, variable: 'COSIGN_KEY')]) {
            def exitCode = sh(script: """
                SSL_CERT_FILE=/tmp/merged-ca-bundle.pem \
                COSIGN_PASSWORD='' cosign sign \
                    --key "\${COSIGN_KEY}" \
                    --rekor-url="${rekorUrl}" \
                    --allow-insecure-registry \
                    --yes \
                    ${imageRef} 2>&1
            """, returnStatus: true)
            if (exitCode == 0) {
                echo "  Image signed (key-based): ${imageRef}"
                return 'SUCCESS'
            }
            echo "  Key-based signing failed (exit ${exitCode})"
            return 'FAILED'
        }
    } catch (Exception e) {
        if (e.message?.contains('credentials') || e.message?.contains('CredentialNotFoundException')) {
            echo "  No signing key configured (cosign-signing-key) — signing not possible"
            return 'FAILED'
        }
        echo "  Key-based signing error: ${e.message}"
        return 'FAILED'
    }
}
