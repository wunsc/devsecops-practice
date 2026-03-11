// build-config/tests/performance/helpers/checks.js
// Reusable response validation functions for k6 tests.
//
// Centralizes check logic so all test scripts (load, stress, soak, multi)
// use consistent response validation criteria.
//
import { check } from 'k6';

/**
 * Basic HTTP response check — verifies status and response time.
 * @param {Object} res - k6 HTTP response object
 * @param {string} name - Descriptive name for the check (e.g., "healthz")
 * @param {number} maxDurationMs - Maximum acceptable response time (default: 1000ms)
 * @returns {boolean} true if all checks pass
 */
export function checkResponse(res, name = 'endpoint', maxDurationMs = 1000) {
  return check(res, {
    [`${name} status 200`]: (r) => r.status === 200,
    [`${name} < ${maxDurationMs}ms`]: (r) => r.timings.duration < maxDurationMs,
    [`${name} body not empty`]: (r) => r.body && r.body.length > 0,
  });
}

/**
 * WeatherForecast API response check — validates structure and timing.
 * The API returns a JSON array of forecast objects with fields:
 *   date, temperatureC, temperatureF, summary, location
 * @param {Object} res - k6 HTTP response object
 * @returns {boolean} true if all checks pass
 */
export function checkForecastResponse(res) {
  return check(res, {
    'forecast 200': (r) => r.status === 200,
    'forecast has data': (r) => {
      try {
        const body = r.json();
        return Array.isArray(body) && body.length > 0;
      } catch (e) {
        return false;
      }
    },
    'forecast < 1s': (r) => r.timings.duration < 1000,
  });
}

/**
 * Notification API response check — validates POST /api/Notify.
 * @param {Object} res - k6 HTTP response object
 * @returns {boolean} true if all checks pass
 */
export function checkNotifyResponse(res) {
  return check(res, {
    'notify 200': (r) => r.status === 200,
    'notify < 500ms': (r) => r.timings.duration < 500,
  });
}
