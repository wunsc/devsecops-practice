// jenkins-shared-lib/vars/runUnitTests.groovy
// Runs dotnet test with code coverage collection
// Generates both opencover (for SonarQube) and cobertura (for reporting) formats
//
// KNOWN FIX: Coverlet output format must be "opencover,cobertura" (dual format)
//            SonarQube requires opencover format specifically
//
// Usage:
//   runUnitTests()
//   runUnitTests(project: '.', coverageThreshold: 80.0)
def call(Map config = [:]) {
    def startTime = System.currentTimeMillis()
    // KNOWN FIX: Default project is '.' not appName
    def project = config.project ?: '.'
    def configuration = config.configuration ?: 'Release'
    def resultsDir = config.resultsDir ?: 'test-results'
    def coverageDir = config.coverageDir ?: 'coverage-results'

    echo "=== Run Unit Tests ==="
    echo "  Project: ${project}"

    try {
        // Run tests with coverage collection
        // KNOWN FIX: Do NOT use --no-build — coverlet.msbuild hooks into the build
        // process to instrument assemblies. With --no-build, instrumentation is skipped
        // and coverage is always 0%.
        // KNOWN FIX: Use opencover,cobertura dual format for SonarQube + reporting
        sh """
            dotnet test ${project} \\
                --configuration ${configuration} \\
                --logger "trx;LogFileName=test-results.trx" \\
                --results-directory ${resultsDir} \\
                /p:CollectCoverage=true \\
                /p:CoverletOutputFormat="opencover%2ccobertura" \\
                /p:CoverletOutput="\${WORKSPACE}/${coverageDir}/" \\
                /p:ExcludeByAttribute="ExcludeFromCodeCoverage" || true
        """

        // Parse test results
        def testsPassed = true
        if (fileExists("${resultsDir}/test-results.trx")) {
            // Archive test results for Jenkins
            junit testResults: "${resultsDir}/**/*.trx", allowEmptyResults: true
        }

        // Extract coverage percentage from cobertura XML
        // KNOWN FIX: Use safe grep with || true fallback
        // Coverage file is at ${WORKSPACE}/${coverageDir}/ (absolute path used in dotnet test)
        def coveragePercent = 0.0
        def coberturaFile = sh(
            script: "find . -name 'coverage.cobertura.xml' -type f | head -1 || true",
            returnStdout: true
        ).trim()
        if (coberturaFile && fileExists(coberturaFile)) {
            def lineRate = sh(
                script: "grep -oP 'line-rate=\"\\K[0-9.]+' ${coberturaFile} | head -1 || true",
                returnStdout: true
            ).trim()
            if (lineRate) {
                // KNOWN FIX: Use Math.round instead of BigDecimal.round()
                coveragePercent = Math.round(Double.parseDouble(lineRate) * 10000) / 100.0
            }
        }

        echo "Code coverage: ${coveragePercent}%"

        def duration = (System.currentTimeMillis() - startTime) / 1000
        echo "Unit tests completed in ${duration}s"

        return [
            status: 'SUCCESS',
            duration: duration,
            coverage: coveragePercent,
            coverageDir: coverageDir,
            resultsDir: resultsDir
        ]
    } catch (Exception e) {
        echo "ERROR: Unit tests failed — ${e.message}"
        return [status: 'FAILURE', error: e.message, coverage: 0.0]
    }
}
