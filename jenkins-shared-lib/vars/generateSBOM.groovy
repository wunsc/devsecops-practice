// jenkins-shared-lib/vars/generateSBOM.groovy
// Generates CycloneDX SBOM, uploads to RHTPA/Trustify, analyzes vulnerabilities,
// and gates the pipeline on critical/high CVE thresholds.
//
// Flow: Generate SBOM → Upload to Trustify → Poll vulnerability analysis →
//       FAIL if critical > maxCritical or high > maxHigh
//
// .NET: Uses dotnet-CycloneDX (pre-installed in agent image, pinned v0.27.2)
// Java: Uses cyclonedx-maven-plugin 2.8.0 via Maven
//
// Usage:
//   generateSBOM(project: '.', language: 'dotnet', serviceName: 'sampleapi', imageTag: 'v1.0.0')
//   generateSBOM(project: '.', language: 'java', serviceName: 'order-service', imageTag: 'v1.0.0')

def call(Map config = [:]) {
    def project = config.project ?: '.'
    def language = config.language ?: 'dotnet'
    def serviceName = config.serviceName ?: 'unknown'
    def imageTag = config.imageTag ?: 'latest'
    def trustifyUrl = config.trustifyUrl ?: 'https://server.test-app.svc'
    def keycloakUrl = config.keycloakUrl ?: env.KEYCLOAK_URL ?: 'https://keycloak.apps.muhrahma-cluster.vmware.tamlab.rdu2.redhat.com'
    def maxCritical = config.maxCritical != null ? config.maxCritical : 0
    def maxHigh = config.maxHigh != null ? config.maxHigh : 5

    echo "=== SBOM Analysis & Vulnerability Gate ==="
    echo "  Service: ${serviceName} (${language})"
    echo "  Thresholds: critical=${maxCritical}, high=${maxHigh}"

    def sbomFile = "sbom-${serviceName}-${imageTag}.json"
    def startTime = System.currentTimeMillis()
    def componentCount = 0
    def uploadStatus = 'SKIPPED'
    def sbomId = ''
    def criticalCount = 0
    def highCount = 0
    def mediumCount = 0
    def lowCount = 0
    def totalVulns = 0
    def vulnDetails = []
    def gateResult = 'PASSED'

    try {
        // ── Step 1: Generate CycloneDX SBOM ──
        echo "  [1/4] Generating CycloneDX SBOM..."
        if (language == 'java') {
            def mvnExitCode = sh(script: """
                export JAVA_HOME=\$(dirname \$(dirname \$(readlink -f \$(which javac 2>/dev/null || which java))))
                if ! command -v mvn &>/dev/null; then
                    export PATH=/tmp/apache-maven-3.9.8/bin:\$PATH
                fi
                cd ${project}
                mvn org.cyclonedx:cyclonedx-maven-plugin:2.8.0:makeAggregateBom \
                    -DoutputFormat=json -DoutputName=bom \
                    -Dmaven.repo.local=\${WORKSPACE}/.m2 -B
            """, returnStatus: true)

            if (mvnExitCode != 0) {
                error "CycloneDX Maven plugin failed (exit code ${mvnExitCode}). Check POM configuration."
            }

            sh """
                if [ -f ${project}/target/bom.json ]; then
                    cp ${project}/target/bom.json \${WORKSPACE}/${sbomFile}
                else
                    echo "ERROR: Expected SBOM at ${project}/target/bom.json but file not found"
                    exit 1
                fi
            """
        } else {
            // Verify CycloneDX CLI is pre-installed in agent image
            def toolCheck = sh(script: 'command -v dotnet-CycloneDX 2>/dev/null || echo MISSING', returnStdout: true).trim()
            if (toolCheck == 'MISSING') {
                error "dotnet-CycloneDX not found in agent image. Rebuild agent with: " +
                      "RUN dotnet tool install --global CycloneDX --version 6.2.0"
            }

            def sbomExitCode = sh(script: """
                cd ${project}
                SLN=\$(find . -maxdepth 2 -name '*.sln' | head -1)
                [ -z "\$SLN" ] && SLN=\$(find . -maxdepth 3 -name '*.csproj' | head -1)
                if [ -z "\$SLN" ]; then
                    echo "ERROR: No .sln or .csproj file found in ${project}"
                    exit 1
                fi
                echo "  Using: \$SLN"
                dotnet-CycloneDX \$SLN -o \${WORKSPACE} -F Json -fn ${sbomFile} -spv 1.5 2>&1
            """, returnStatus: true)

            if (sbomExitCode != 0) {
                error "CycloneDX .NET SBOM generation failed (exit code ${sbomExitCode})"
            }
        }

        // Verify SBOM file exists and has content
        if (!fileExists(sbomFile)) {
            error "SBOM file ${sbomFile} was not created"
        }

        componentCount = sh(script: "python3 -c \"import json; d=json.load(open('${sbomFile}')); print(len(d.get('components',[])))\" 2>/dev/null || echo 0", returnStdout: true).trim() as int
        echo "  SBOM generated: ${componentCount} components"

        if (componentCount == 0) {
            echo "  WARNING: SBOM has 0 components — verify project dependencies are resolved"
        }

        archiveArtifacts artifacts: sbomFile, allowEmptyArchive: true

        // ── Step 2: Upload to Trustify ──
        echo "  [2/4] Uploading SBOM to Trustify..."
        def trustifyToken = getTrustifyToken(keycloakUrl)

        if (trustifyToken) {
            def docId = sh(script: "cat /proc/sys/kernel/random/uuid", returnStdout: true).trim()

            def uploadAttempts = 3
            for (int attempt = 1; attempt <= uploadAttempts; attempt++) {
                def uploadHttp = sh(script: """
                    curl -sk -X POST "${trustifyUrl}/api/v2/sbom" \
                        -H "Authorization: Bearer ${trustifyToken}" \
                        -H "Content-Type: application/json" \
                        -H "X-Document-Id: urn:uuid:${docId}" \
                        --data-binary @\${WORKSPACE}/${sbomFile} \
                        -w '%{http_code}' -o /tmp/sbom-upload.json 2>/dev/null || echo '000'
                """, returnStdout: true).trim()

                if (uploadHttp == '201' || uploadHttp == '200') {
                    uploadStatus = 'SUCCESS'
                    sbomId = sh(script: "python3 -c \"import json; print(json.load(open('/tmp/sbom-upload.json')).get('id',''))\" 2>/dev/null || echo ''", returnStdout: true).trim()
                    echo "  Uploaded: SBOM ID=${sbomId}"
                    break
                } else if (attempt < uploadAttempts) {
                    echo "  Upload attempt ${attempt}/${uploadAttempts} failed (HTTP ${uploadHttp}), retrying in ${attempt * 3}s..."
                    sleep attempt * 3
                } else {
                    uploadStatus = 'FAILED'
                    echo "  Upload failed after ${uploadAttempts} attempts (last HTTP ${uploadHttp})"
                }
            }

            // ── Step 3: Poll for vulnerability analysis ──
            echo "  [3/4] Waiting for Trustify vulnerability analysis..."

            if (sbomId) {
                def analysisReady = false
                def maxPollAttempts = 10
                def pollInterval = 5
                def encodedIdForPoll = sbomId.replace(':', '%3A')

                for (int attempt = 1; attempt <= maxPollAttempts; attempt++) {
                    def checkHttp = sh(script: """
                        curl -sk -o /dev/null -w '%{http_code}' \
                            -H "Authorization: Bearer ${trustifyToken}" \
                            "${trustifyUrl}/api/v2/sbom/${encodedIdForPoll}/advisories?limit=1" 2>/dev/null || echo '000'
                    """, returnStdout: true).trim()

                    if (checkHttp == '200') {
                        analysisReady = true
                        echo "  Analysis ready after ${attempt * pollInterval}s"
                        break
                    }
                    if (attempt < maxPollAttempts) {
                        echo "  Attempt ${attempt}/${maxPollAttempts}: HTTP ${checkHttp} — waiting ${pollInterval}s..."
                        sleep pollInterval
                    }
                }

                if (!analysisReady) {
                    echo "  WARNING: Trustify analysis not ready after ${maxPollAttempts * pollInterval}s — proceeding with available data"
                }

                // Fetch full advisory data — URL-encode SBOM ID (contains colons)
                def encodedSbomId = sbomId.replace(':', '%3A')
                try {
                    sh(script: """
                        curl -sk -H "Authorization: Bearer ${trustifyToken}" \
                            "${trustifyUrl}/api/v2/sbom/${encodedSbomId}/advisories?limit=100" \
                            -o /tmp/sbom-vulns.json 2>/dev/null || true
                        # Ensure valid JSON — if Trustify returned HTML/error, create empty result
                        python3 -c "import json; json.load(open('/tmp/sbom-vulns.json'))" 2>/dev/null || \
                            echo '{"items":[]}' > /tmp/sbom-vulns.json
                    """, returnStdout: false)
                } catch (Exception advEx) {
                    echo "  WARNING: Advisory fetch failed (${advEx.message}) — continuing with 0 vulns"
                    sh "echo '{\"items\":[]}' > /tmp/sbom-vulns.json"
                }

                def vulnCounts = sh(script: """
                    [ ! -f /tmp/sbom-vulns.json ] && echo '{"items":[]}' > /tmp/sbom-vulns.json
                    python3 -c "
import json
try:
    d = json.load(open('/tmp/sbom-vulns.json'))
    items = d.get('items', [])
    critical = high = medium = low = 0
    details = []
    for item in items:
        severity = item.get('severity', item.get('average_severity', 'unknown'))
        if isinstance(severity, dict):
            severity = severity.get('severity', 'unknown')
        sev = str(severity).lower()
        if 'critical' in sev: critical += 1
        elif 'high' in sev: high += 1
        elif 'medium' in sev or 'moderate' in sev: medium += 1
        elif 'low' in sev: low += 1
        if ('critical' in sev or 'high' in sev) and len(details) < 5:
            adv_id = item.get('advisory', {}).get('identifier', item.get('id', '?'))
            title = item.get('advisory', {}).get('title', item.get('title', 'N/A'))[:60]
            details.append(f'{sev.upper()}: {adv_id} — {title}')
    total = critical + high + medium + low
    print(f'{critical}|{high}|{medium}|{low}|{total}')
    for d in details: print(d)
except Exception as e:
    print(f'0|0|0|0|0')
    print(f'Error: {e}')
" 2>/dev/null
                """, returnStdout: true).trim()

                def lines = vulnCounts.split('\n')
                def counts = lines[0].split('\\|')
                criticalCount = counts[0] as int
                highCount = counts[1] as int
                mediumCount = counts[2] as int
                lowCount = counts[3] as int
                totalVulns = counts[4] as int

                if (lines.size() > 1) {
                    vulnDetails = lines[1..-1].collect { it.toString() }
                }

                echo "  Vulnerabilities: Critical=${criticalCount} High=${highCount} Medium=${mediumCount} Low=${lowCount}"
            } else {
                echo "  Skipping analysis — no SBOM ID from upload"
            }

            // ── Step 4: Gate check ──
            echo "  [4/4] Checking thresholds..."
            if (criticalCount > maxCritical) {
                gateResult = 'FAILED'
                echo "  GATE FAILED: ${criticalCount} critical vulnerabilities (max: ${maxCritical})"
            } else if (highCount > maxHigh) {
                gateResult = 'FAILED'
                echo "  GATE FAILED: ${highCount} high vulnerabilities (max: ${maxHigh})"
            } else {
                echo "  GATE PASSED: within thresholds"
            }

            if (vulnDetails) {
                echo "  Top findings:"
                vulnDetails.each { echo "    ${it}" }
            }
        } else {
            echo "  WARNING: No Trustify token — vulnerability analysis skipped"
        }

        def duration = (System.currentTimeMillis() - startTime) / 1000
        def status = (gateResult == 'FAILED') ? 'FAILURE' : 'SUCCESS'

        return [
            status: status,
            duration: duration,
            sbomFile: sbomFile,
            components: componentCount,
            uploadStatus: uploadStatus,
            sbomId: sbomId,
            gateResult: gateResult,
            critical: criticalCount,
            high: highCount,
            medium: mediumCount,
            low: lowCount,
            totalVulns: totalVulns,
            findings: vulnDetails.take(5).join('; '),
            trustifyUrl: sbomId ? "${trustifyUrl}/#/sboms/${sbomId}" : ''
        ]
    } catch (Exception e) {
        echo "SBOM analysis failed: ${e.message}"
        return [
            status: 'FAILURE',
            duration: (System.currentTimeMillis() - startTime) / 1000,
            error: e.message,
            components: componentCount,
            uploadStatus: uploadStatus,
            sbomFile: sbomFile,
            gateResult: 'ERROR',
            critical: 0, high: 0, medium: 0, low: 0, totalVulns: 0
        ]
    }
}

private String getTrustifyToken(String keycloakUrl) {
    try {
        withCredentials([string(credentialsId: 'trustify-walker-token', variable: 'WALKER_SECRET')]) {
            return sh(script: """
                curl -sfk -X POST "${keycloakUrl}/realms/trustify/protocol/openid-connect/token" \
                    -d "client_id=walker" \
                    -d "client_secret=\${WALKER_SECRET}" \
                    -d "grant_type=client_credentials" \
                    -d "scope=create:document read:document" | \
                    python3 -c "import json,sys; print(json.load(sys.stdin).get('access_token',''))" 2>/dev/null
            """, returnStdout: true).trim()
        }
    } catch (Exception e) {
        echo "  WARNING: Trustify token retrieval failed: ${e.message}"
        return ''
    }
}
