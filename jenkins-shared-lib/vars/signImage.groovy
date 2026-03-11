// jenkins-shared-lib/vars/signImage.groovy
// Sign container image with Cosign (Sigstore) — optional stage
//
// KNOWN FIX: Needs cosign-signing-key file credential; gracefully skip if missing
//
// Usage:
//   signImage(imageRef: 'registry/ns/app:tag')
def call(Map config = [:]) {
    def startTime = System.currentTimeMillis()
    def imageRef = config.imageRef ?: ''
    def keyCredId = config.keyCredId ?: 'cosign-signing-key'

    echo "=== Image Signing (Cosign) ==="
    echo "  Image: ${imageRef}"

    if (!imageRef) {
        echo "WARNING: No imageRef provided — skipping signing"
        return [status: 'SKIPPED']
    }

    try {
        // KNOWN FIX: Gracefully skip if cosign key credential doesn't exist
        withCredentials([file(credentialsId: keyCredId, variable: 'COSIGN_KEY')]) {
            // Sign the image in the registry
            sh """
                COSIGN_PASSWORD='' cosign sign \\
                    --key "\${COSIGN_KEY}" \\
                    --tls-verify=false \\
                    --yes \\
                    ${imageRef}
            """

            echo "Image signed successfully: ${imageRef}"

            def duration = (System.currentTimeMillis() - startTime) / 1000
            return [status: 'SUCCESS', duration: duration, imageRef: imageRef]
        }
    } catch (hudson.AbortException e) {
        // Credential not found — skip gracefully
        if (e.message?.contains('credentials') || e.message?.contains('CredentialNotFoundException')) {
            echo "WARNING: Cosign signing key not configured — skipping image signing"
            echo "  To enable signing, create a '${keyCredId}' file credential with the cosign private key"
            return [status: 'SKIPPED', reason: 'Signing key credential not found']
        }
        echo "ERROR: Image signing failed — ${e.message}"
        return [status: 'FAILURE', error: e.message]
    } catch (Exception e) {
        echo "WARNING: Image signing failed — ${e.message}"
        echo "  This is an optional stage; pipeline continues"
        return [status: 'UNSTABLE', error: e.message]
    }
}
