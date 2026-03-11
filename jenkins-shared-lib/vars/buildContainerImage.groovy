// jenkins-shared-lib/vars/buildContainerImage.groovy
// Builds container image using Podman with the Dockerfile from build-config repo
//
// KNOWN FIXES:
//   - Must use --storage-driver=vfs on OpenShift (no fuse device)
//   - Must use --isolation=chroot for rootless Podman
//   - Dockerfile is in build-config/ subdirectory, build context is workspace root
//
// Usage:
//   buildContainerImage(imageRef: 'registry/ns/app:tag')
//   buildContainerImage(imageRef: 'registry/ns/app:tag', buildArgs: [PROJECT_NAME: 'NotificationApi', APP_PORT: '8081'])
def call(Map config = [:]) {
    def startTime = System.currentTimeMillis()
    def dockerfile = config.dockerfile ?: 'build-config/Dockerfile'
    def imageRef = config.imageRef ?: ''
    def context = config.context ?: '.'
    def noCache = config.noCache ?: false
    def buildArgs = config.buildArgs ?: [:]

    echo "=== Build Container Image ==="
    echo "  Dockerfile: ${dockerfile}"
    echo "  Image: ${imageRef}"
    echo "  Context: ${context}"

    try {
        if (!imageRef) {
            error "imageRef is required — provide the full image reference (registry/ns/app:tag)"
        }

        // Verify Dockerfile exists
        if (!fileExists(dockerfile)) {
            error "Dockerfile not found: ${dockerfile}. Did checkoutBuildConfig() run?"
        }

        // KNOWN FIX: --storage-driver=vfs and --isolation=chroot for OpenShift
        def noCacheFlag = noCache ? '--no-cache' : ''
        // Build --build-arg flags from the buildArgs map
        def buildArgFlags = buildArgs.collect { k, v -> "--build-arg ${k}=${v}" }.join(' ')
        sh """
            podman build \\
                --storage-driver=vfs \\
                --isolation=chroot \\
                -f ${dockerfile} \\
                -t ${imageRef} \\
                ${noCacheFlag} \\
                ${buildArgFlags} \\
                ${context}
        """

        // Verify image was built
        sh "podman --storage-driver=vfs images ${imageRef}"

        def duration = (System.currentTimeMillis() - startTime) / 1000
        echo "Container image built in ${duration}s"

        return [status: 'SUCCESS', duration: duration, imageRef: imageRef]
    } catch (Exception e) {
        echo "ERROR: Container image build failed — ${e.message}"
        return [status: 'FAILURE', error: e.message]
    }
}
