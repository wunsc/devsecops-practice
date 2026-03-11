// jenkins-shared-lib/vars/updateGitOps.groovy
// Updates the image tag in the app-gitops overlay for the target environment
// Uses kustomize edit set image to update the kustomization.yaml
// Then commits and pushes the change to GitLab
//
// Usage:
//   updateGitOps(environment: 'dev', imageRef: 'registry/ns/app:tag')
//   updateGitOps(environment: 'dev', imageRef: '...', additionalImages: [notificationapi: 'registry/ns/notificationapi:tag'])
def call(Map config = [:]) {
    def startTime = System.currentTimeMillis()
    def environment = config.environment ?: 'dev'
    def imageRef = config.imageRef ?: ''
    def gitopsRepo = config.gitopsRepo ?: env.GITLAB_URL ? "${env.GITLAB_URL}/devsecops/app-gitops.git" : ''
    def credentialsId = config.credentialsId ?: 'gitlab-token'
    def branch = config.branch ?: 'main'
    def appName = config.appName ?: 'sampleapi'
    // Additional images to update in the same overlay (e.g., notificationapi)
    def additionalImages = config.additionalImages ?: [:]

    echo "=== Update GitOps Repository ==="
    echo "  Environment: ${environment}"
    echo "  Image: ${imageRef}"
    echo "  GitOps repo: ${gitopsRepo}"

    if (!imageRef) {
        error "imageRef is required"
    }

    try {
        // Clone the gitops repo into a temporary directory
        dir('gitops-update') {
            withCredentials([usernamePassword(credentialsId: credentialsId, usernameVariable: 'GIT_USER', passwordVariable: 'GIT_PASS')]) {
                // Clone with embedded credentials for push
                // Use .replace() not .replaceAll() — replaceAll treats $ as regex backreference
                def repoUrl = gitopsRepo.replace('https://', "https://${GIT_USER}:${GIT_PASS}@")
                sh "git clone ${repoUrl} . 2>/dev/null"
                sh "git config user.email 'jenkins@devsecops.local'"
                sh "git config user.name 'Jenkins CI'"

                // Update image tag in the per-service overlay
                // Structure: services/{service}/overlays/{env}/kustomization.yaml
                def overlayDir = "services/${appName}/overlays/${environment}"
                if (!fileExists(overlayDir)) {
                    error "Overlay directory not found: ${overlayDir}"
                }

                dir(overlayDir) {
                    // Update primary image (sampleapi)
                    sh "kustomize edit set image ${appName}=${imageRef}"
                    echo "Updated ${overlayDir}/kustomization.yaml: ${appName}=${imageRef}"

                    // Update additional images (e.g., notificationapi)
                    additionalImages.each { name, ref ->
                        sh "kustomize edit set image ${name}=${ref}"
                        echo "Updated ${overlayDir}/kustomization.yaml: ${name}=${ref}"
                    }
                }

                // Commit and push (with retry for concurrent pipeline race condition)
                sh "git add -A"
                def allImages = ["${appName}"] + additionalImages.keySet().toList()
                def commitMsg = "chore(${environment}): update ${allImages.join(', ')} images to tag ${imageRef.substring(imageRef.lastIndexOf(':') + 1)}"
                sh "git diff --cached --quiet || git commit -m '${commitMsg}'"

                // Retry push up to 3 times — concurrent T2 pipelines may push to
                // the same branch simultaneously, causing "fetch first" rejections.
                def pushSuccess = false
                for (int attempt = 1; attempt <= 3; attempt++) {
                    def pushExit = sh(script: "git push origin ${branch} 2>&1", returnStatus: true)
                    if (pushExit == 0) {
                        pushSuccess = true
                        break
                    }
                    echo "Push attempt ${attempt} failed — pulling and rebasing..."
                    sh "git pull --rebase origin ${branch}"
                }
                if (!pushSuccess) {
                    error "GitOps push failed after 3 attempts"
                }

                echo "GitOps update pushed to ${branch}"
            }
        }

        // Clean up
        sh 'rm -rf gitops-update'

        def duration = (System.currentTimeMillis() - startTime) / 1000
        echo "GitOps update completed in ${duration}s"

        return [status: 'SUCCESS', duration: duration, environment: environment, imageRef: imageRef]
    } catch (Exception e) {
        echo "ERROR: GitOps update failed — ${e.message}"
        sh 'rm -rf gitops-update || true'
        return [status: 'FAILURE', error: e.message]
    }
}
