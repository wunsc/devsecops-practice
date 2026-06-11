// jenkins-shared-lib/vars/reportToGitLab.groovy
// Reports pipeline status back to the GitLab Merge Request
// Two methods:
//   1. updateGitlabCommitStatus (GitLab plugin step) — uses job's GitLab connection
//   2. GitLab API fallback — uses commit status API with PRIVATE-TOKEN
//
// KNOWN FIXES:
//   - Use env.gitlabMergeRequestLastCommit for commit SHA (available before checkout)
//   - Must pass gitlabUrl explicitly (env.GITLAB_URL not set in Jenkins)
//   - GitLab API state values: pending, running, success, failed, canceled
//   - updateGitlabCommitStatus uses: pending, running, success, failed, canceled
//
// Usage:
//   reportToGitLab(state: 'running', description: 'Pipeline started')
//   reportToGitLab(state: 'success', description: 'All checks passed', commitSha: sha)
def call(Map config = [:]) {
    def startTime = System.currentTimeMillis()
    def state = config.state ?: 'success'
    def description = config.description ?: ''
    def context = config.context ?: 'jenkins-ci'
    def gitlabUrl = config.gitlabUrl ?: env.GITLAB_URL ?: ''
    def projectId = config.projectId ?: env.GITLAB_PROJECT_ID ?: '1'
    def tokenCredId = config.tokenCredId ?: 'gitlab-api-token'
    // Commit SHA: explicit > webhook MR commit > checkout GIT_COMMIT > git rev-parse
    def commitSha = config.commitSha ?: env.gitlabMergeRequestLastCommit ?: env.GIT_COMMIT ?: ''

    echo "=== Report to GitLab ==="
    echo "  State: ${state}"
    echo "  Description: ${description}"
    echo "  Commit: ${commitSha ?: 'unknown'}"

    // Method 1: Use GitLab plugin's updateGitlabCommitStatus (preferred)
    // This works when the job has a gitLabConnection configured and was triggered by GitLab
    try {
        updateGitlabCommitStatus name: context, state: state
        echo "GitLab status updated via plugin: ${state}"
        def duration = (System.currentTimeMillis() - startTime) / 1000
        return [status: 'SUCCESS', duration: duration, state: state]
    } catch (Exception pluginErr) {
        echo "GitLab plugin updateGitlabCommitStatus not available (${pluginErr.message}), falling back to API"
    }

    // Method 2: Fallback to GitLab API
    if (!commitSha) {
        echo "WARNING: No commit SHA available — skipping GitLab status report"
        return [status: 'SKIPPED', reason: 'No commit SHA']
    }

    if (!gitlabUrl) {
        echo "WARNING: No gitlabUrl provided — skipping GitLab status report"
        return [status: 'SKIPPED', reason: 'No GitLab URL']
    }

    try {
        withCredentials([string(credentialsId: tokenCredId, variable: 'GITLAB_TOKEN')]) {
            def apiUrl = "${gitlabUrl}/api/v4/projects/${projectId}/statuses/${commitSha}"
            def buildUrl = env.BUILD_URL ?: ''

            sh """
                curl -sfk -X POST "${apiUrl}" \\
                    -H "PRIVATE-TOKEN: \${GITLAB_TOKEN}" \\
                    -d "state=${state}" \\
                    -d "description=${description}" \\
                    -d "context=${context}" \\
                    -d "target_url=${buildUrl}" || echo "WARNING: Failed to update GitLab commit status via API"
            """

            echo "GitLab status updated via API: ${state}"
        }

        def duration = (System.currentTimeMillis() - startTime) / 1000
        return [status: 'SUCCESS', duration: duration, state: state]
    } catch (Exception e) {
        echo "WARNING: Failed to report to GitLab — ${e.message}"
        return [status: 'FAILURE', error: e.message]
    }
}
