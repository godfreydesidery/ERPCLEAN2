/**
 * Runtime config. The API base points at the Spring Boot app; in dev the CLI proxy (proxy.conf.json)
 * forwards /api to http://localhost:8080 so the browser stays same-origin and avoids CORS.
 */
export const environment = {
  production: false,
  apiBaseUrl: '/api/v1',
};
