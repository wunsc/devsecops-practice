// build-config/tests/performance/stress-test.js
// k6 stress test — find the breaking point of SampleApi
//
// Ramps VUs far beyond normal load to discover:
//   - At what concurrency the app starts returning errors
//   - At what point latency degrades beyond acceptable limits
//   - Whether the app recovers after load drops (resilience)
//
// This is NOT a quality gate — it's a diagnostic tool.
// Run manually to establish baseline and capacity limits.
//
// USAGE:
//   k6 run --env BASE_URL=https://sampleapi-dev.apps.example.com stress-test.js
//
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';
import { checkResponse, checkForecastResponse } from './helpers/checks.js';

const errorRate = new Rate('errors');
const forecastLatency = new Trend('forecast_latency', true);

export const options = {
  stages: [
    // Normal load baseline
    { duration: '1m', target: 20 },
    // Ramp to moderate stress
    { duration: '2m', target: 50 },
    // Ramp to high stress
    { duration: '2m', target: 100 },
    // Push to breaking point
    { duration: '2m', target: 200 },
    // Sustain at peak
    { duration: '1m', target: 200 },
    // Recovery — drop to baseline
    { duration: '2m', target: 20 },
    // Ramp down
    { duration: '1m', target: 0 },
  ],
  thresholds: {
    // Intentionally lenient — stress test is diagnostic, not a gate
    http_req_duration: ['p(95)<5000'],    // 5s — just to flag total breakdown
    http_req_failed: ['rate<0.50'],       // 50% — tolerate some errors under extreme load
  },
  insecureSkipTLSVerify: true,
};

const BASE_URL = __ENV.BASE_URL || 'https://sampleapi-dev.apps.example.com';

export default function () {
  const forecastRes = http.get(`${BASE_URL}/api/WeatherForecast`);
  forecastLatency.add(forecastRes.timings.duration);
  const ok = checkForecastResponse(forecastRes);
  errorRate.add(!ok);

  // Minimal think time — stress test pushes hard
  sleep(0.3);
}

export function handleSummary(data) {
  return {
    'k6-summary.json': JSON.stringify(data, null, 2),
  };
}
