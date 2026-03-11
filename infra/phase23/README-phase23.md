# Phase 23: Performance Testing as Quality Gate

## Overview

Integrates k6 load tests into the T3 (tag/release) pipeline as a quality gate.
If performance thresholds are breached, the pipeline fails and the release image
is not pushed to the registry.

## Architecture

```
T3 Pipeline: ... → ACS Strict → DAST (ZAP) → Performance Test → Sign → Push
                                                     │
                                                     ├── p95 < 800ms?  PASS
                                                     ├── p99 < 2000ms? PASS
                                                     ├── errors < 1%?  PASS
                                                     └── ANY breach?   FAIL → EXIT 99
                                                                       Build FAILS
                                                                       Image NOT pushed
```

## Test Scripts

| Script | Purpose | Duration | Pipeline Gate? |
|--------|---------|----------|---------------|
| `load-test.js` | Standard load test against WeatherForecast API | ~4 min | **Yes** (T3 default) |
| `load-test-multi.js` | Multi-service test (SampleApi + NotificationApi) | ~3 min | Optional |
| `stress-test.js` | Find breaking point under extreme load | ~11 min | No (diagnostic) |
| `soak-test.js` | Long-duration stability test | ~35 min | No (nightly) |

## Quality Gate Thresholds

| Metric | Threshold | k6 Behavior |
|--------|-----------|-------------|
| `http_req_duration p(95)` | < 800ms | Fail at end of test |
| `http_req_duration p(99)` | < 2000ms | Abort immediately (after 30s warmup) |
| `http_req_failed rate` | < 1% | Abort immediately |
| `forecast_latency p(95)` | < 500ms | Fail at end of test |
| `errors rate` | < 5% | Fail at end of test |

## Files Created

### build-config/tests/performance/
- `load-test.js` — Main load test (used by pipeline)
- `load-test-multi.js` — Multi-service test
- `stress-test.js` — Stress test (diagnostic)
- `soak-test.js` — Soak test (nightly)
- `helpers/checks.js` — Reusable response validation
- `helpers/auth.js` — Authentication helpers (placeholder)

### jenkins-shared-lib/vars/
- `runPerformanceTest.groovy` — k6 execution + threshold evaluation
- `pipelineTag.groovy` — Updated with Performance Test stage after DAST

### infra/phase23/
- `grafana-dashboard-k6.yaml` — GrafanaDashboard CRD for test results
- `README-phase23.md` — This file

## Pipeline Integration

The performance test runs after DAST (ZAP) and before image signing in T3:

```groovy
stage('Performance Test') {
    steps {
        script {
            results.perfTest = runPerformanceTest(
                targetUrl: devRoute,
                testScript: 'build-config/tests/performance/load-test.js',
                serviceName: serviceName
            )
        }
    }
}
```

## Running Tests Manually

```bash
# From Jenkins agent pod or local machine with k6 installed:

# Standard load test
k6 run --env BASE_URL=https://sampleapi-dev.apps.cluster-pmqwq.pmqwq.sandbox270.opentlc.com \
  build-config/tests/performance/load-test.js

# Multi-service test (from within cluster)
k6 run --env BASE_URL=https://sampleapi-dev.apps.example.com \
       --env NOTIFICATION_URL=http://notificationapi.sampleapi-dev.svc:8081 \
  build-config/tests/performance/load-test-multi.js

# Stress test (diagnostic)
k6 run --env BASE_URL=https://sampleapi-dev.apps.example.com \
  build-config/tests/performance/stress-test.js

# Soak test (long-running, use screen/tmux)
k6 run --env BASE_URL=https://sampleapi-dev.apps.example.com \
       --env SOAK_DURATION_MINUTES=60 \
  build-config/tests/performance/soak-test.js
```

## Verify

```bash
# k6 available in Jenkins agent
oc exec deploy/jenkins -n devsecops-tools -- k6 version

# Run a quick smoke test (10s, 5 VUs)
k6 run --env BASE_URL=https://sampleapi-dev.${APPS_DOMAIN} \
  --duration 10s --vus 5 \
  build-config/tests/performance/load-test.js

# After T3 pipeline, check Jenkins artifacts:
# Build → Artifacts → k6-summary.json, k6-results.json, k6-output.log
```

## Grafana Dashboard

Apply the k6 dashboard to visualize server-side metrics during load tests:

```bash
oc apply -f infra/phase23/grafana-dashboard-k6.yaml -n devsecops-tools
```

The dashboard shows: request rate, error rate, response time percentiles,
CPU/memory usage under load, and quality gate threshold status panels.
