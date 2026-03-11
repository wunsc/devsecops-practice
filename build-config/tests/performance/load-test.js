// build-config/tests/performance/load-test.js
// k6 load test — SampleApi WeatherForecast endpoint
//
// Validates performance thresholds that act as a quality gate in the T3
// (tag/release) pipeline. If any threshold is breached, k6 exits with
// code 99, which the Jenkins pipeline interprets as GATE FAILED.
//
// USAGE:
//   k6 run --env BASE_URL=https://sampleapi-dev.apps.example.com load-test.js
//
// THRESHOLDS (quality gates):
//   http_req_duration p(95) < 800ms     — 95th percentile response time
//   http_req_duration p(99) < 2000ms    — 99th percentile (abort on fail)
//   http_req_failed   rate < 1%         — error rate (abort on fail)
//   forecast_latency  p(95) < 500ms     — business endpoint latency
//   errors            rate < 5%         — custom check error rate
//
// STAGES:
//   30s ramp up to 25 VUs → 3m sustained at 50 VUs → 30s ramp down
//
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';
import { checkResponse, checkForecastResponse } from './helpers/checks.js';

// ── Custom metrics ──
const errorRate = new Rate('errors');
const forecastLatency = new Trend('forecast_latency', true);

export const options = {
  stages: [
    { duration: '30s', target: 25 },     // Ramp up
    { duration: '3m',  target: 50 },     // Sustained load
    { duration: '30s', target: 0 },      // Ramp down
  ],
  thresholds: {
    // ── These are your quality gates ──
    http_req_duration: [
      'p(95)<800',                        // 95th percentile under 800ms
      { threshold: 'p(99)<2000',          // 99th percentile under 2 seconds
        abortOnFail: true,
        delayAbortEval: '30s' },          // Give it 30s to stabilize first
    ],
    http_req_failed: [
      { threshold: 'rate<0.01',           // Less than 1% HTTP errors
        abortOnFail: true },
    ],
    errors: ['rate<0.05'],                // Custom check error rate under 5%
    forecast_latency: ['p(95)<500'],      // Business endpoint under 500ms p95
  },
  // TLS: skip certificate verification for self-signed OCP routes
  insecureSkipTLSVerify: true,
};

const BASE_URL = __ENV.BASE_URL || 'https://sampleapi-dev.apps.example.com';

export default function () {
  // ── Test 1: Health check (baseline) ──
  const healthRes = http.get(`${BASE_URL}/healthz`);
  check(healthRes, { 'health OK': (r) => r.status === 200 });

  // ── Test 2: WeatherForecast API (main business endpoint) ──
  const forecastRes = http.get(`${BASE_URL}/api/WeatherForecast`);
  forecastLatency.add(forecastRes.timings.duration);
  const forecastOk = checkForecastResponse(forecastRes);
  errorRate.add(!forecastOk);

  sleep(1); // Think time — simulate real user pacing
}

// handleSummary writes a JSON summary that Jenkins archives as an artifact
export function handleSummary(data) {
  return {
    'k6-summary.json': JSON.stringify(data, null, 2),
  };
}
