// build-config/tests/performance/load-test-multi.js
// k6 multi-service load test — SampleApi + NotificationApi
//
// Tests the full request chain: SampleApi → NotificationApi → Redis.
// Verifies that inter-service communication holds under load and that
// the cascading latency stays within acceptable limits.
//
// USAGE:
//   k6 run --env BASE_URL=https://sampleapi-dev.apps.example.com \
//          --env NOTIFICATION_URL=http://notificationapi.sampleapi-dev.svc:8081 \
//          load-test-multi.js
//
// NOTE: NOTIFICATION_URL is typically only reachable from within the cluster.
//       When running from a Jenkins agent pod, it can reach the internal service.
//       When running externally, the test exercises NotificationApi indirectly
//       through SampleApi's /api/WeatherForecast (which calls NotificationApi).
//
import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';
import { checkResponse, checkForecastResponse } from './helpers/checks.js';

// ── Custom metrics ──
const errorRate = new Rate('errors');
const forecastLatency = new Trend('forecast_latency', true);
const notificationLatency = new Trend('notification_latency', true);
const totalRequests = new Counter('total_requests');

export const options = {
  stages: [
    { duration: '30s', target: 20 },     // Ramp up
    { duration: '2m',  target: 40 },     // Sustained load
    { duration: '30s', target: 0 },      // Ramp down
  ],
  thresholds: {
    http_req_duration: [
      'p(95)<1000',                       // Wider tolerance for multi-service
      { threshold: 'p(99)<3000',
        abortOnFail: true,
        delayAbortEval: '30s' },
    ],
    http_req_failed: [
      { threshold: 'rate<0.02',           // 2% error budget for multi-service
        abortOnFail: true },
    ],
    errors: ['rate<0.05'],
    forecast_latency: ['p(95)<800'],      // SampleApi including downstream calls
    notification_latency: ['p(95)<300'],  // NotificationApi directly (if reachable)
  },
  insecureSkipTLSVerify: true,
};

const BASE_URL = __ENV.BASE_URL || 'https://sampleapi-dev.apps.example.com';
// Internal service URL — only works from within the cluster (Jenkins agent pod)
const NOTIFICATION_URL = __ENV.NOTIFICATION_URL || '';

export default function () {
  // ── Group 1: SampleApi full chain (includes NotificationApi call) ──
  group('SampleApi', () => {
    const healthRes = http.get(`${BASE_URL}/healthz`);
    check(healthRes, { 'sampleapi health OK': (r) => r.status === 200 });
    totalRequests.add(1);

    const forecastRes = http.get(`${BASE_URL}/api/WeatherForecast`);
    forecastLatency.add(forecastRes.timings.duration);
    const ok = checkForecastResponse(forecastRes);
    errorRate.add(!ok);
    totalRequests.add(1);
  });

  // ── Group 2: NotificationApi direct (only if internal URL provided) ──
  if (NOTIFICATION_URL) {
    group('NotificationApi', () => {
      const healthRes = http.get(`${NOTIFICATION_URL}/healthz`);
      check(healthRes, { 'notificationapi health OK': (r) => r.status === 200 });
      totalRequests.add(1);

      // POST a test notification
      const payload = JSON.stringify({
        type: 'k6-test',
        message: `Performance test ${new Date().toISOString()}`,
      });
      const params = { headers: { 'Content-Type': 'application/json' } };
      const notifyRes = http.post(`${NOTIFICATION_URL}/api/Notify`, payload, params);
      notificationLatency.add(notifyRes.timings.duration);
      const ok = check(notifyRes, {
        'notify 200': (r) => r.status === 200,
        'notify < 500ms': (r) => r.timings.duration < 500,
      });
      errorRate.add(!ok);
      totalRequests.add(1);
    });
  }

  sleep(1);
}

export function handleSummary(data) {
  return {
    'k6-summary.json': JSON.stringify(data, null, 2),
  };
}
