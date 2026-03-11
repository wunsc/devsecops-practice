// jenkins-shared-lib/vars/checkoutSource.groovy
// Git checkout for application source code
// Supports branch (T1/T2) and tag (T3) checkout modes
//
// Usage:
//   checkoutSource(branch: 'main')
//   checkoutSource(branch: env.gitlabSourceBranch)  // MR source branch
//   checkoutSource(tag: 'v1.2.0')
def call(Map config = [:]) {
    def startTime = System.currentTimeMillis()
    def gitUrl = config.gitUrl ?: env.GITLAB_URL ? "${env.GITLAB_URL}/devsecops/app-source.git" : ''
    def credentialsId = config.credentialsId ?: 'gitlab-token'
    def branch = config.branch ?: 'main'
    def tag = config.tag ?: ''

    echo "=== Checkout Source ==="
    echo "  Repository: ${gitUrl}"
    echo "  Branch: ${branch}"
    echo "  Tag: ${tag ?: 'none'}"

    try {
        if (tag) {
            // T3: Tag checkout — use specific refspec for tags
            // KNOWN FIX: Must use +refs/tags/* refspec to resolve tag refs
            checkout([
                $class: 'GitSCM',
                branches: [[name: "refs/tags/${tag}"]],
                userRemoteConfigs: [[
                    url: gitUrl,
                    credentialsId: credentialsId,
                    refspec: '+refs/tags/*:refs/remotes/origin/tags/*'
                ]],
                extensions: [
                    [$class: 'CloneOption', depth: 0, noTags: false, shallow: false],
                    [$class: 'CleanBeforeCheckout']
                ]
            ])
        } else {
            // T1/T2: Branch checkout
            checkout([
                $class: 'GitSCM',
                branches: [[name: "*/${branch}"]],
                userRemoteConfigs: [[
                    url: gitUrl,
                    credentialsId: credentialsId
                ]],
                extensions: [
                    [$class: 'CloneOption', depth: 1, noTags: false, shallow: true],
                    [$class: 'CleanBeforeCheckout']
                ]
            ])
        }

        def duration = (System.currentTimeMillis() - startTime) / 1000
        echo "Checkout completed in ${duration}s"

        return [status: 'SUCCESS', duration: duration]
    } catch (Exception e) {
        echo "ERROR: Checkout failed — ${e.message}"
        return [status: 'FAILURE', error: e.message]
    }
}
