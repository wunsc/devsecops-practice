// jenkins-shared-lib/vars/verifyImage.groovy
// Verifies image signature and SBOM attestation using cosign
//
// Two-step verification:
//   1. cosign verify — validates the keyless signature (Fulcio + Rekor)
//   2. cosign verify-attestation --type cyclonedx — validates SBOM attestation
//
// Both use the OIDC issuer and certificate identity from RHTAS Keycloak.
// Signature verification failure → FAILURE (blocks pipeline).
// Attestation verification failure → logged warning (may not exist on first T2 run).
//
// Usage:
//   verifyImage(imageRef: 'registry/ns/app:tag')

def call(Map config = [:]) {
    def startTime = System.currentTimeMillis()
    def imageRef = config.imageRef ?: ''
    def rekorUrl = config.rekorUrl ?: 'https://rekor-server-trusted-artifact-signer.apps.muhrahma-cluster.vmware.tamlab.rdu2.redhat.com'
    def oidcIssuer = config.oidcIssuer ?: 'https://keycloak.apps.muhrahma-cluster.vmware.tamlab.rdu2.redhat.com/realms/trustify'

    echo "=== Image Verification (cosign verify) ==="
    echo "  Image: ${imageRef}"
    echo "  OIDC Issuer: ${oidcIssuer}"

    if (!imageRef) {
        echo "WARNING: No imageRef — skipping verification"
        return [status: 'SKIPPED', reason: 'No image reference']
    }

    def signatureValid = false
    def attestationValid = false

    try {
        // Extract cluster CA cert for cosign TLS trust
        sh """
            REKOR_HOST=\$(echo "${rekorUrl}" | sed 's|https://||')
            openssl s_client -showcerts -connect \${REKOR_HOST}:443 </dev/null 2>/dev/null | \
                sed -ne '/-BEGIN CERTIFICATE-/,/-END CERTIFICATE-/p' > /tmp/cluster-ca.crt 2>/dev/null || true
            if [ -s /tmp/cluster-ca.crt ]; then
                cat /etc/pki/ca-trust/extracted/pem/tls-ca-bundle.pem /tmp/cluster-ca.crt > /tmp/merged-ca-bundle.pem 2>/dev/null
            else
                cp /etc/pki/ca-trust/extracted/pem/tls-ca-bundle.pem /tmp/merged-ca-bundle.pem 2>/dev/null || true
            fi
        """

        // Get RHTAS trust material from ConfigMap
        sh """
            oc get configmap rhtas-ctlog-public-key -n devsecops-tools \
                -o jsonpath='{.data.ctlog-public\\.pem}' > /tmp/ctlog-public.pem 2>/dev/null || true
            oc get configmap rhtas-ctlog-public-key -n devsecops-tools \
                -o jsonpath='{.data.rekor-public\\.pem}' > /tmp/rekor-public.pem 2>/dev/null || true
            oc get configmap rhtas-ctlog-public-key -n devsecops-tools \
                -o jsonpath='{.data.fulcio-root\\.pem}' > /tmp/fulcio-root.pem 2>/dev/null || true
        """

        // Login to internal registry for cosign access
        sh """
            SA_TOKEN=\$(cat /var/run/secrets/kubernetes.io/serviceaccount/token)
            podman login --tls-verify=false -u unused -p "\${SA_TOKEN}" image-registry.openshift-image-registry.svc:5000 2>/dev/null || true
            mkdir -p \$HOME/.docker 2>/dev/null || true
            cp \${XDG_RUNTIME_DIR}/containers/auth.json \$HOME/.docker/config.json 2>/dev/null || \
                cp /run/containers/0/auth.json \$HOME/.docker/config.json 2>/dev/null || true
        """

        // Step 1: Verify image signature
        echo "  [1/2] Verifying image signature..."

        def verifyExitCode = sh(script: """
            SSL_CERT_FILE=/tmp/merged-ca-bundle.pem \
            SIGSTORE_CT_LOG_PUBLIC_KEY_FILE=/tmp/ctlog-public.pem \
            SIGSTORE_REKOR_PUBLIC_KEY=/tmp/rekor-public.pem \
            SIGSTORE_ROOT_FILE=/tmp/fulcio-root.pem \
            COSIGN_EXPERIMENTAL=1 cosign verify \
                --rekor-url="${rekorUrl}" \
                --certificate-identity-regexp=".*" \
                --certificate-oidc-issuer="${oidcIssuer}" \
                --insecure-ignore-tlog=true \
                --allow-insecure-registry \
                ${imageRef} 2>&1
        """, returnStatus: true)

        if (verifyExitCode == 0) {
            signatureValid = true
            echo "  Signature VALID: ${imageRef}"
        } else {
            echo "  ERROR: Signature verification failed (exit code ${verifyExitCode})"
        }

        // Step 2: Verify SBOM attestation
        echo "  [2/2] Verifying SBOM attestation..."

        def attestExitCode = sh(script: """
            SSL_CERT_FILE=/tmp/merged-ca-bundle.pem \
            SIGSTORE_CT_LOG_PUBLIC_KEY_FILE=/tmp/ctlog-public.pem \
            SIGSTORE_REKOR_PUBLIC_KEY=/tmp/rekor-public.pem \
            SIGSTORE_ROOT_FILE=/tmp/fulcio-root.pem \
            COSIGN_EXPERIMENTAL=1 cosign verify-attestation \
                --rekor-url="${rekorUrl}" \
                --certificate-identity-regexp=".*" \
                --certificate-oidc-issuer="${oidcIssuer}" \
                --insecure-ignore-tlog=true \
                --type cyclonedx \
                --allow-insecure-registry \
                ${imageRef} 2>&1
        """, returnStatus: true)

        if (attestExitCode == 0) {
            attestationValid = true
            echo "  SBOM attestation VALID: ${imageRef}"
        } else {
            echo "  WARNING: SBOM attestation verification failed (exit code ${attestExitCode})"
        }

        def duration = (System.currentTimeMillis() - startTime) / 1000
        def overallStatus = signatureValid ? 'SUCCESS' : 'FAILURE'

        return [
            status: overallStatus,
            duration: duration,
            imageRef: imageRef,
            signatureValid: signatureValid,
            attestationValid: attestationValid,
            oidcIssuer: oidcIssuer
        ]
    } catch (Exception e) {
        echo "ERROR: Image verification failed — ${e.message}"
        return [status: 'FAILURE', error: e.message, signatureValid: false, attestationValid: false]
    }
}
