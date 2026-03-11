// build-config/tests/performance/soak-test.js
// k6 soak test — long-duration stability test for SampleApi
//
// Runs at moderate load for an extended period to detect:
//   - Memory leaks (gradually increasing response times)
//   - Connection pool exhaustion (PostgreSQL/Redis)
//   - File descriptor leaks
//   - Gradual degradation patterns
//
// This is NOT a quality gate — it's a diagnostic tool for pre-release validation.
// Run manually or as a scheduled nightly job.
//
// USAGE:
//   k6 run --env BASE_URL=https://sampleapi-dev.apps.example.com soak-test.js
//
// DURATION: ~35 minutes total (adjust SOAK_DURATION env var for longer runs)
//
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';
import { checkResponse, checkForecastResponse } from './helpers/checks.js';

const errorRate = new Rate('errors');
const forecastLatency = new Trend('forecast_latency', true);

// Allow overriding soak duration via env var (default: 30m)
const SOAK_MINUTES = __ENV.SOAK_DURATION_MINUTES || '30';

export const options = {
  stages: [
    { duration: '2m', target: 30 },                    // Ramp up to moderate load
    { duration: `${SOAK_MINUTES}m`, target: 30 },      // Sustained moderate load
    { duration: '2m', target: 0 },                     // Ramp down
  ],
  thresholds: {
    http_req_duration: [
      'p(95)<1000',                       // Should stay stable throughout
      'p(99)<2000',
    ],
    http_req_failed: [
      { threshold: 'rate<0.01',           // Zero tolerance over long duration
        abortOnFail: true,
        delayAbortEval: '5m' },           // Allow initial stabilization
    ],
    errors: ['rate<0.02'],
  },
  insecureSkipTLSVerify: true,
};

const BASE_URL = __ENV.BASE_URL || 'https://sampleapi-dev.apps.example.com';

export default function () {
  // Alternate between health check and business endpoint
  // to exercise different code paths over the long run
  const healthRes = http.get(`${BASE_URL}/healthz`);
  check(healthRes, { 'health OK': (r) => r.status === 200 });

  const forecastRes = http.get(`${BASE_URL}/api/WeatherForecast`);
  forecastLatency.add(forecastRes.timings.duration);
  const ok = checkForecastResponse(forecastRes);
  errorRate.add(!ok);

  // Readiness probe — catches DB/Redis connection issues
  const readyRes = http.get(`${BASE_URL}/readyz`);
  check(readyRes, { 'ready OK': (r) => r.status === 200 });

  sleep(2); // Longer think time — soak tests simulate realistic pacing
}

export function handleSummary(data) {
  return {
    'k6-summary.json': JSON.stringify(data, null, 2),
  };
}
