import { expect, test } from '@playwright/test';

// Check the security headers of the web IMAGE (nginx), not the dev server. Runs when WEB_BASE_URL is set
// (the qa.yml workflow builds the container and points here); skipped on dev machines.
const base = process.env.WEB_BASE_URL;
test.skip(!base, 'WEB_BASE_URL must point at the web container (see .github/workflows/qa.yml, dast-web job)');

const PATHS = ['/', '/index.html', '/config.json', '/doctors', '/healthz'];

test.describe('TC-SEC-09 web security headers', () => {
  for (const path of PATHS) {
    test(`TC-SEC-09 ${path}`, async ({ request }) => {
      const res = await request.get(`${base}${path}`);
      expect(res.status()).toBe(200);
      const h = res.headers();
      const csp = h['content-security-policy'] ?? '';
      expect(csp, 'CSP').toContain("default-src 'self'");
      expect(csp).toContain("frame-ancestors 'none'");
      expect(csp, 'no inline scripts').not.toMatch(/script-src[^;]*'unsafe-inline'/);
      expect(csp).not.toMatch(/script-src[^;]*'unsafe-eval'/);
      expect(h['x-content-type-options']).toBe('nosniff');
      expect(h['x-frame-options']).toBe('DENY');
      expect(h['referrer-policy']).toBe('no-referrer');
      expect(h['permissions-policy']).toContain('camera=()');
      expect(csp, 'connect-src must not use wildcards or the bare https: scheme').not.toMatch(
        /connect-src[^;]*(\*|\shttps:(\s|;|$))/,
      );
      expect(csp, 'form-action same origin only').toMatch(/form-action 'self'(;|$)/);
      expect(h['cross-origin-opener-policy']).toBe('same-origin');
      expect(h['cross-origin-embedder-policy']).toBe('require-corp');
      expect(h['server'] ?? '', 'does not reveal the server version').not.toMatch(/\d/);
    });
  }

  test('TC-SEC-09 hashed JS/CSS files also carry the security headers', async ({ request }) => {
    const html = await (await request.get(`${base}/`)).text();
    const asset = /\/assets\/[^"']+\.js/.exec(html)?.[0];
    expect(asset, 'index.html must reference a JS file under /assets/').toBeTruthy();
    const res = await request.get(`${base}${asset}`);
    expect(res.status()).toBe(200);
    expect(res.headers()['x-content-type-options']).toBe('nosniff');
    expect(res.headers()['cache-control']).toContain('immutable');
  });

  test('TC-SEC-09 config and root page are not cached; no internal details leak on errors', async ({ request }) => {
    expect((await request.get(`${base}/config.json`)).headers()['cache-control']).toContain('no-store');
    expect((await request.get(`${base}/index.html`)).headers()['cache-control']).toContain('no-cache');
    const missing = await request.get(`${base}/assets/khong-ton-tai.js`);
    expect(missing.status()).toBe(404);
    const body = await missing.text();
    expect(body).not.toMatch(/nginx\/\d/i);
  });
});
