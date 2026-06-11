def call(Map config = [:]) {
    def project = config.project ?: '.'

    echo "=== Build Java Application ==="

    try {
        sh """
            # Install JDK (compiler) if javac not present
            if ! command -v javac &>/dev/null; then
                echo "Installing java-21-openjdk-devel..."
                sudo dnf install -y --setopt=tsflags=nodocs java-21-openjdk-devel 2>/dev/null || \
                    dnf install -y --setopt=tsflags=nodocs java-21-openjdk-devel 2>/dev/null || true
            fi

            export JAVA_HOME=\$(dirname \$(dirname \$(readlink -f \$(which javac 2>/dev/null || which java))))
            echo "JAVA_HOME: \$JAVA_HOME"
            javac -version 2>&1 || echo "WARNING: javac not found"

            # Install Maven if not present
            if ! command -v mvn &>/dev/null; then
                echo "Installing Maven 3.9.8..."
                curl -sL https://archive.apache.org/dist/maven/maven-3/3.9.8/binaries/apache-maven-3.9.8-bin.tar.gz | tar xz -C /tmp
            fi
            export PATH=/tmp/apache-maven-3.9.8/bin:\$PATH

            cd ${project}
            mvn --version
            mvn package -DskipTests -B \
                -Dmaven.repo.local=\${WORKSPACE}/.m2
        """
        return [status: 'SUCCESS']
    } catch (Exception e) {
        echo "Java build failed: ${e.message}"
        return [status: 'FAILURE', error: e.message]
    }
}
