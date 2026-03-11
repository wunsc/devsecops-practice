// jenkins-shared-lib/vars/checkoutBuildConfig.groovy
// Clones the build-config repo into a subdirectory alongside app-source
// Rule 2: Build configs (Dockerfile, sonar config) are in a separate repo
//
// After this, the workspace looks like:
//   ./                     <- app-source (dotnet project root)
//   ./build-config/        <- Dockerfile, sonar-project.properties, etc.
//
// Usage:
//   checkoutBuildConfig()
//   checkoutBuildConfig(branch: 'main', targetDir: 'build-config')
def call(Map config = [:]) {
    def startTime = System.currentTimeMillis()
    def gitUrl = config.gitUrl ?: env.GITLAB_URL ? "${env.GITLAB_URL}/devsecops/build-config.git" : ''
    def credentialsId = config.credentialsId ?: 'gitlab-token'
    def branch = config.branch ?: 'main'
    def targetDir = config.targetDir ?: 'build-config'

    echo "=== Checkout Build Config ==="
    echo "  Repository: ${gitUrl}"
    echo "  Target directory: ${targetDir}"

    try {
        // Clone build-config into a subdirectory within the workspace
        dir(targetDir) {
            checkout([
                $class: 'GitSCM',
                branches: [[name: "*/${branch}"]],
                userRemoteConfigs: [[
                    url: gitUrl,
                    credentialsId: credentialsId
                ]],
                extensions: [
                    [$class: 'CloneOption', depth: 1, shallow: true],
                    [$class: 'CleanBeforeCheckout']
                ]
            ])
        }

        // Verify key files exist
        def hasDockerfile = fileExists("${targetDir}/Dockerfile")
        echo "  Dockerfile found: ${hasDockerfile}"

        def duration = (System.currentTimeMillis() - startTime) / 1000
        echo "Build config checkout completed in ${duration}s"

        return [status: 'SUCCESS', duration: duration, targetDir: targetDir, hasDockerfile: hasDockerfile]
    } catch (Exception e) {
        echo "ERROR: Build config checkout failed — ${e.message}"
        return [status: 'FAILURE', error: e.message]
    }
}
