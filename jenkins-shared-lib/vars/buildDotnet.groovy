// jenkins-shared-lib/vars/buildDotnet.groovy
// Runs dotnet restore + build + publish for the .NET application
//
// Usage:
//   buildDotnet()
//   buildDotnet(project: '.', configuration: 'Release')
def call(Map config = [:]) {
    def startTime = System.currentTimeMillis()
    // KNOWN FIX: Default project is '.' (workspace root), not appName
    def project = config.project ?: '.'
    def configuration = config.configuration ?: 'Release'
    def outputDir = config.outputDir ?: 'publish'

    echo "=== Build .NET Application ==="
    echo "  Project: ${project}"
    echo "  Configuration: ${configuration}"

    try {
        // Restore NuGet packages
        sh "dotnet restore ${project} --no-cache"

        // Build the solution
        sh "dotnet build ${project} --configuration ${configuration} --no-restore"

        // Publish artifacts for container image
        sh "dotnet publish ${project} --configuration ${configuration} --no-build --output ${outputDir}"

        def duration = (System.currentTimeMillis() - startTime) / 1000
        echo "Build completed in ${duration}s"

        return [status: 'SUCCESS', duration: duration, outputDir: outputDir]
    } catch (Exception e) {
        echo "ERROR: .NET build failed — ${e.message}"
        return [status: 'FAILURE', error: e.message]
    }
}
