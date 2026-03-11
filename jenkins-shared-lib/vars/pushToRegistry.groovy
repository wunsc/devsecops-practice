// jenkins-shared-lib/vars/pushToRegistry.groovy
// Push container image to the OCP internal registry (or external registry)
//
// KNOWN FIX: Image must be pushed BEFORE ACS scan — ACS pulls from registry
//
// Usage:
//   pushToRegistry(imageRef: 'registry/ns/app:tag')
//   pushToRegistry(imageRef: 'registry/ns/app:tag', additionalTags: ['latest-release'])
def call(Map config = [:]) {
    def startTime = System.currentTimeMillis()
    def imageRef = config.imageRef ?: ''
    def additionalTags = config.additionalTags ?: []
    def registryCredId = config.registryCredId ?: ''  // Empty for internal registry (uses SA token)

    echo "=== Push to Registry ==="
    echo "  Image: ${imageRef}"
    echo "  Additional tags: ${additionalTags}"

    if (!imageRef) {
        error "imageRef is required"
    }

    try {
        // For internal registry, use the Jenkins SA token (auto-mounted)
        // For external registry, use credentials
        if (registryCredId) {
            withCredentials([usernamePassword(credentialsId: registryCredId, usernameVariable: 'REG_USER', passwordVariable: 'REG_PASS')]) {
                def registry = imageRef.split('/')[0]
                sh "podman --storage-driver=vfs login -u \${REG_USER} -p \${REG_PASS} ${registry}"
                sh "podman --storage-driver=vfs push --tls-verify=false ${imageRef}"
            }
        } else {
            // Internal registry — authenticate with SA token
            sh """
                SA_TOKEN=\$(cat /var/run/secrets/kubernetes.io/serviceaccount/token)
                podman --storage-driver=vfs login \\
                    -u serviceaccount \\
                    -p "\${SA_TOKEN}" \\
                    --tls-verify=false \\
                    ${imageRef.split('/')[0]}
            """
            sh "podman --storage-driver=vfs push --tls-verify=false ${imageRef}"
        }

        echo "Primary image pushed: ${imageRef}"

        // Push additional tags (e.g., latest-release for T3)
        def baseImage = imageRef.substring(0, imageRef.lastIndexOf(':'))
        additionalTags.each { tag ->
            def additionalRef = "${baseImage}:${tag}"
            sh "podman --storage-driver=vfs tag ${imageRef} ${additionalRef}"
            sh "podman --storage-driver=vfs push --tls-verify=false ${additionalRef}"
            echo "Additional tag pushed: ${additionalRef}"
        }

        def duration = (System.currentTimeMillis() - startTime) / 1000
        echo "Push completed in ${duration}s"

        return [status: 'SUCCESS', duration: duration, imageRef: imageRef, additionalTags: additionalTags]
    } catch (Exception e) {
        echo "ERROR: Push to registry failed — ${e.message}"
        return [status: 'FAILURE', error: e.message]
    }
}
