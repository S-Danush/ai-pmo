export const environment = {
  production: true,
  /**
   * Empty = same origin: use Netlify `_redirects` proxy (set BACKEND_URL at build time)
   * or configure `/api/*` → your Render URL in `public/_redirects`.
   * Non-empty = call that origin directly (then set CORS on the API).
   */
  apiBaseUrl: '',
};
