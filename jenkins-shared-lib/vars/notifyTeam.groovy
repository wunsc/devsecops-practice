// jenkins-shared-lib/vars/notifyTeam.groovy
// Send pipeline notifications (email, Slack, or console log)
// Configurable notification channels
//
// Usage:
//   notifyTeam(message: 'Pipeline completed', status: 'SUCCESS')
//   notifyTeam(channel: '#devsecops', message: 'Build failed', status: 'FAILURE')
def call(Map config = [:]) {
    def message = config.message ?: "Pipeline ${env.JOB_NAME} #${env.BUILD_NUMBER}"
    def status = config.status ?: currentBuild.currentResult ?: 'UNKNOWN'
    def channel = config.channel ?: ''
    def emailTo = config.emailTo ?: ''

    echo "=== Notify Team ==="
    echo "  Status: ${status}"
    echo "  Message: ${message}"

    try {
        // Build notification message with pipeline details
        def fullMessage = """
Pipeline: ${env.JOB_NAME} #${env.BUILD_NUMBER}
Status: ${status}
Duration: ${currentBuild.durationString ?: 'unknown'}
URL: ${env.BUILD_URL ?: 'N/A'}
Message: ${message}
"""

        // Console log (always)
        echo fullMessage

        // Slack notification (if plugin is available and channel is set)
        if (channel) {
            try {
                def color = status == 'SUCCESS' ? 'good' : (status == 'FAILURE' ? 'danger' : 'warning')
                slackSend(
                    channel: channel,
                    color: color,
                    message: fullMessage
                )
                echo "Slack notification sent to ${channel}"
            } catch (Exception e) {
                echo "WARNING: Slack notification failed — ${e.message}"
                echo "  (Slack plugin may not be configured)"
            }
        }

        // Email notification (if configured)
        if (emailTo) {
            try {
                mail(
                    to: emailTo,
                    subject: "[${status}] ${env.JOB_NAME} #${env.BUILD_NUMBER}",
                    body: fullMessage
                )
                echo "Email notification sent to ${emailTo}"
            } catch (Exception e) {
                echo "WARNING: Email notification failed — ${e.message}"
            }
        }

        return [status: 'SUCCESS']
    } catch (Exception e) {
        echo "WARNING: Notification failed — ${e.message}"
        return [status: 'FAILURE', error: e.message]
    }
}
