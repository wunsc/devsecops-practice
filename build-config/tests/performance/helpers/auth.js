// build-config/tests/performance/helpers/auth.js
// JWT token generation and authentication helpers for k6 tests.
//
// Currently the SampleApi endpoints are unauthenticated, so this file
// serves as a placeholder for when JWT auth is added. When that happens,
// import this module to generate tokens for authenticated load tests.
//
// USAGE:
//   import { getAuthHeaders } from './helpers/auth.js';
//   const params = { headers: getAuthHeaders() };
//   const res = http.get(`${BASE_URL}/api/secured-endpoint`, params);
//

/**
 * Get authentication headers for API requests.
 * Uses API_KEY env var if set, otherwise returns empty headers.
 * @returns {Object} headers object with Authorization if available
 */
export function getAuthHeaders() {
  const apiKey = __ENV.API_KEY || '';
  if (apiKey) {
    return {
      'Authorization': `Bearer ${apiKey}`,
      'Content-Type': 'application/json',
    };
  }
  return {
    'Content-Type': 'application/json',
  };
}

/**
 * Build a standard JSON POST request params object with auth.
 * @param {Object} additionalHeaders - Extra headers to merge
 * @returns {Object} k6 request params
 */
export function getAuthParams(additionalHeaders = {}) {
  const headers = Object.assign(getAuthHeaders(), additionalHeaders);
  return { headers };
}
