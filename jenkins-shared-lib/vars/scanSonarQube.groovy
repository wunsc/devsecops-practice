// jenkins-shared-lib/vars/scanSonarQube.groovy
// SonarQube SAST analysis + quality gate polling
//
// KNOWN FIXES:
//   - .NET SonarScanner is a dotnet global tool, NOT standalone sonar-scanner
//   - Cannot use sonar-project.properties file with .NET scanner
//   - All config passed via CLI args to dotnet sonarscanner
//   - sonar.branch.name requires Developer Edition (omit on CE)
//
// BRANCH/VERSION SEPARATION (SonarQube CE):
//   CE doesn't support sonar.branch.name. We use sonar.projectVersion to tag
//   each scan with its context, and sonar.analysis.* custom properties for
//   pipeline metadata visible in the Activity view.
//
//   T1 (MR):    projectVersion = "MR-{iid}-{branchName}"
//   T2 (Merge): projectVersion = "main-{shortSha}"
//   T3 (Tag):   projectVersion = "{tagName}" (e.g., "v1.1.0")
//
//   SonarQube's "New Code" definition uses version boundaries, so each scan
//   context gets its own baseline for quality gate evaluation.
//
// Usage:
//   scanSonarQube(projectVersion: 'MR-42-feature/login', analysisMode: 'mr', branchName: 'feature/login')
//   scanSonarQube(projectVersion: 'main-abc1234', analysisMode: 'merge')
//   scanSonarQube(projectVersion: 'v1.1.0', analysisMode: 'tag')
def call(Map config = [:]) {
    def startTime = System.currentTimeMillis()
    def projectKey = config.projectKey ?: env.SONAR_PROJECT_KEY ?: 'sampleapi'
    def sonarUrl = config.sonarUrl ?: env.SONARQUBE_URL ?: ''
    def tokenCredId = config.tokenCredId ?: 'sonarqube-token'
    def project = config.project ?: '.'
    def coverageDir = config.coverageDir ?: 'coverage-results'
    def timeoutMinutes = config.timeoutMinutes ?: 5

    // Version and context identification for scan separation
    def projectVersion = config.projectVersion ?: '1.0'
    def analysisMode = config.analysisMode ?: 'default'   // 'mr', 'merge', 'tag', 'default'
    def branchName = config.branchName ?: ''               // feature branch name (T1)
    def mrIid = config.mrIid ?: ''                         // MR IID (T1)

    echo "=== SonarQube Analysis ==="
    echo "  Project key: ${projectKey}"
    echo "  Project version: ${projectVersion}"
    echo "  Analysis mode: ${analysisMode}"
    if (branchName) echo "  Branch: ${branchName}"
    if (mrIid) echo "  MR IID: ${mrIid}"
    echo "  SonarQube URL: ${sonarUrl}"

    try {
        withCredentials([string(credentialsId: tokenCredId, variable: 'SONAR_TOKEN')]) {
            // Build the sonarscanner begin command with version and analysis metadata
            // sonar.projectVersion tags the analysis in SonarQube Activity view
            // sonar.analysis.* properties add custom metadata visible in Activity details
            def scannerArgs = """
                dotnet sonarscanner begin \\
                    /k:"${projectKey}" \\
                    /v:"${projectVersion}" \\
                    /d:sonar.host.url="${sonarUrl}" \\
                    /d:sonar.token="\${SONAR_TOKEN}" \\
                    /d:sonar.cs.opencover.reportsPaths="**/coverage.opencover.xml" \\
                    /d:sonar.coverage.exclusions="**/Tests/**,**/test/**,**/*Tests*/**" \\
                    /d:sonar.exclusions="**/bin/**,**/obj/**,**/publish/**" \\
                    /d:sonar.qualitygate.wait=false \\
                    /d:sonar.analysis.pipeline="${analysisMode}" \\
                    /d:sonar.analysis.buildNumber="${env.BUILD_NUMBER ?: ''}"
            """.trim()

            // Add branch/MR context as analysis properties
            if (branchName) {
                scannerArgs += """ \\
                    /d:sonar.analysis.branch="${branchName}"
                """.trim()
            }
            if (mrIid) {
                scannerArgs += """ \\
                    /d:sonar.analysis.mrIid="${mrIid}"
                """.trim()
            }

            sh scannerArgs

            // Build (required between begin and end)
            sh "dotnet build ${project} --no-incremental"

            // End analysis — uploads results to SonarQube
            sh """
                dotnet sonarscanner end \\
                    /d:sonar.token="\${SONAR_TOKEN}"
            """

            // Poll quality gate result (pass SONAR_TOKEN env var for API auth)
            echo "Waiting for SonarQube quality gate (timeout: ${timeoutMinutes}min)..."
            def gateStatus = pollQualityGate(sonarUrl, projectKey, timeoutMinutes, env.SONAR_TOKEN)

            def duration = (System.currentTimeMillis() - startTime) / 1000
            echo "SonarQube analysis completed in ${duration}s — Gate: ${gateStatus} — Version: ${projectVersion}"

            def dashboardUrl = "${sonarUrl}/dashboard?id=${projectKey}"

            if (gateStatus != 'OK') {
                return [
                    status: 'FAILURE',
                    duration: duration,
                    gateStatus: gateStatus,
                    report: dashboardUrl,
                    projectVersion: projectVersion,
                    error: "Quality gate status: ${gateStatus}"
                ]
            }

            return [
                status: 'SUCCESS',
                duration: duration,
                gateStatus: gateStatus,
                report: dashboardUrl,
                projectVersion: projectVersion
            ]
        }
    } catch (Exception e) {
        echo "ERROR: SonarQube analysis failed — ${e.message}"
        return [status: 'FAILURE', error: e.message]
    }
}

/**
 * Poll SonarQube API for quality gate status
 * Retries until gate result is available or timeout
 */
def pollQualityGate(String sonarUrl, String projectKey, int timeoutMinutes, String sonarToken = '') {
    def maxAttempts = timeoutMinutes * 6  // Check every 10 seconds
    def gateStatus = 'UNKNOWN'

    for (int i = 0; i < maxAttempts; i++) {
        sleep(10)
        try {
            // Auth with SONAR_TOKEN env var (set by withCredentials in caller)
            def response = sh(
                script: """
                    curl -sf -u "\${SONAR_TOKEN}:" "${sonarUrl}/api/qualitygates/project_status?projectKey=${projectKey}" 2>/dev/null || echo '{"projectStatus":{"status":"NONE"}}'
                """,
                returnStdout: true
            ).trim()

            def statusMatch = (response =~ /"status"\s*:\s*"([^"]+)"/)
            if (statusMatch) {
                gateStatus = statusMatch[0][1]
                if (gateStatus != 'NONE' && gateStatus != 'IN_PROGRESS') {
                    echo "Quality gate result: ${gateStatus}"
                    return gateStatus
                }
            }
        } catch (Exception e) {
            echo "  Polling attempt ${i + 1}/${maxAttempts} — waiting..."
        }
    }

    echo "WARNING: Quality gate polling timed out after ${timeoutMinutes} minutes"
    return gateStatus
}
