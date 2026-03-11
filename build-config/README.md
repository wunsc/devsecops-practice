# Build Config Repository

## Purpose

This repository contains build and scan configurations used by the Jenkins pipeline.
It is **separate from the application source code** (Rule 2) to maintain clean separation of concerns.

## Contents

| File / Directory | Purpose | Used By |
|------------------|---------|---------|
| `Dockerfile` | Multi-stage .NET 8.0 build (SDK → Runtime, non-root, healthcheck). Parameterized via `--build-arg PROJECT_NAME` / `SOLUTION_NAME` / `APP_PORT` for both SampleApi and NotificationApi. | `buildContainerImage.groovy` |
| `sonar-project.properties` | SonarQube scanner settings reference (documentation only) | Reference for `scanSonarQube.groovy` |
| `dependency-check-suppression.xml` | OWASP Dependency-Check false positive suppressions | `scanDependencyCheck.groovy` |
| `zap-scan-config.yaml` | OWASP ZAP DAST scan policy and rules | `scanOWASPZAP.groovy` |
| `tests/performance/` | k6 load/stress/soak test scripts (load-test.js, load-test-multi.js, stress-test.js, soak-test.js, helpers/) | `runPerformanceTest.groovy` (T3 quality gate) |

## How It's Used

The Jenkins pipeline clones this repo alongside the app-source repo:

```
Workspace after checkout:
  ./                        <- app-source (dotnet project root)
  ./build-config/           <- this repo (Dockerfile, scan configs)
```

The `checkoutBuildConfig.groovy` shared library function handles this:

```groovy
checkoutBuildConfig()  // Clones build-config into ./build-config/
```

Then other functions reference files from this directory:

```groovy
buildContainerImage(dockerfile: 'build-config/Dockerfile')
scanDependencyCheck(suppressionFile: 'build-config/dependency-check-suppression.xml')
```

## Important Notes

- **sonar-project.properties**: The .NET SonarScanner (`dotnet-sonarscanner`) does NOT read
  this file. It uses CLI arguments instead. This file serves as documentation of the
  scanner settings. See `scanSonarQube.groovy` for the actual CLI invocation.

- **Dockerfile**: Uses multi-stage build. The final image runs as UID 1001 (non-root),
  which is enforced by the ACS "Block Root User Containers" policy.

- **ZAP config**: The ZAP Automation Framework plan targets the DEV environment endpoint.
  Update the URLs if the cluster domain changes.
