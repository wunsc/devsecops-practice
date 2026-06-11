def call(Map config = [:]) {
    def project = config.project ?: '.'
    def sonarUrl = config.sonarUrl ?: env.SONARQUBE_URL ?: 'http://sonarqube.devsecops-tools.svc:9000'
    def projectKey = config.projectKey ?: 'java-service'
    def tokenCredId = config.tokenCredId ?: 'sonarqube-token'
    def projectVersion = config.projectVersion ?: '1.0.0'

    echo "=== SonarQube Analysis (Java) ==="

    try {
        withCredentials([string(credentialsId: tokenCredId, variable: 'SONAR_TOKEN')]) {
            sh """
                export JAVA_HOME=\$(dirname \$(dirname \$(readlink -f \$(which javac 2>/dev/null || which java))))
                if ! command -v mvn &>/dev/null; then
                    export PATH=/tmp/apache-maven-3.9.8/bin:\$PATH
                fi

                cd ${project}
                mvn sonar:sonar -B \
                    -Dsonar.projectKey=${projectKey} \
                    -Dsonar.projectVersion=${projectVersion} \
                    -Dsonar.host.url=${sonarUrl} \
                    -Dsonar.token=\${SONAR_TOKEN} \
                    -Dmaven.repo.local=\${WORKSPACE}/.m2 \
                    || true
            """
        }
        return [status: 'SUCCESS']
    } catch (Exception e) {
        echo "SonarQube analysis failed: ${e.message}"
        return [status: 'UNSTABLE', error: e.message]
    }
}
