// jenkins-shared-lib/vars/deployToEnvironment.groovy
// Triggers ArgoCD sync for the target environment and waits for health
//
// KNOWN FIX: Use argocd login with admin password, NOT JWT tokens (expire after 24h)
//            Jenkins credential 'argocd-token' stores the admin password
//
// Usage:
//   deployToEnvironment(app: 'sampleapi-dev')
//   deployToEnvironment(app: 'sampleapi-dev', waitTimeoutSeconds: 300)
def call(Map config = [:]) {
    def startTime = System.currentTimeMillis()
    def app = config.app ?: ''
    def argocdServer = config.argocdServer ?: env.ARGOCD_SERVER ?: ''
    def tokenCredId = config.tokenCredId ?: 'argocd-token'
    def waitTimeout = config.waitTimeoutSeconds ?: 300
    def prune = config.prune ?: true

    echo "=== Deploy to Environment ==="
    echo "  ArgoCD Application: ${app}"
    echo "  ArgoCD Server: ${argocdServer}"
    echo "  Wait timeout: ${waitTimeout}s"

    if (!app) {
        error "app is required (e.g., 'sampleapi-dev')"
    }

    try {
        withCredentials([string(credentialsId: tokenCredId, variable: 'ARGOCD_PASSWORD')]) {
            // KNOWN FIX: Login with admin password, not JWT
            // Use internal ClusterIP service when running inside the cluster
            def internalServer = config.internalServer ?: 'openshift-gitops-server.openshift-gitops.svc:443'
            sh """
                argocd login ${internalServer} \\
                    --username admin \\
                    --password "\${ARGOCD_PASSWORD}" \\
                    --insecure \\
                    --grpc-web
            """

            // Sync the application
            def pruneFlag = prune ? '--prune' : ''
            sh """
                argocd app sync ${app} \\
                    --force \\
                    ${pruneFlag} \\
                    --grpc-web \\
                    --timeout ${waitTimeout}
            """

            // Wait for health
            sh """
                argocd app wait ${app} \\
                    --health \\
                    --timeout ${waitTimeout} \\
                    --grpc-web
            """

            // Get final status
            def status = sh(
                script: "argocd app get ${app} --grpc-web -o json 2>/dev/null | jq -r '.status.sync.status' || echo 'Unknown'",
                returnStdout: true
            ).trim()

            def health = sh(
                script: "argocd app get ${app} --grpc-web -o json 2>/dev/null | jq -r '.status.health.status' || echo 'Unknown'",
                returnStdout: true
            ).trim()

            echo "ArgoCD App Status: Sync=${status}, Health=${health}"

            def duration = (System.currentTimeMillis() - startTime) / 1000
            echo "Deployment completed in ${duration}s"

            return [status: 'SUCCESS', duration: duration, syncStatus: status, healthStatus: health, app: app]
        }
    } catch (Exception e) {
        echo "ERROR: Deployment failed — ${e.message}"
        return [status: 'FAILURE', error: e.message, app: app]
    }
}
