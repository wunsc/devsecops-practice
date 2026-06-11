def call(Map config = [:]) {
    def project = config.project ?: '.'

    echo "=== Run Java Unit Tests ==="

    try {
        sh """
            export JAVA_HOME=\$(dirname \$(dirname \$(readlink -f \$(which javac 2>/dev/null || which java))))
            if ! command -v mvn &>/dev/null; then
                export PATH=/tmp/apache-maven-3.9.8/bin:\$PATH
            fi

            cd ${project}
            mvn test -B \
                -Dmaven.repo.local=\${WORKSPACE}/.m2
        """

        junit allowEmptyResults: true, testResults: "${project}/target/surefire-reports/*.xml"
        return [status: 'SUCCESS', coverage: 0.0]
    } catch (Exception e) {
        echo "Java tests failed: ${e.message}"
        junit allowEmptyResults: true, testResults: "${project}/target/surefire-reports/*.xml"
        return [status: 'FAILURE', error: e.message]
    }
}
