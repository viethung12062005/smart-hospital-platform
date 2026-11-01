import { expect, test } from '@playwright/test';

// Kiểm tiêu đề bảo mật của IMAGE web (nginx), không phải máy chủ phát triển. Chạy khi có WEB_BASE_URL
// (workflow qa.yml dựng container rồi trỏ tới đây); trên máy dev bỏ qua.
const base = process.env.WEB_BASE_URL;
test.skip(!base, 'Cần WEB_BASE_URL trỏ tới container web (xem .github/workflows/qa.yml, job dast-web)');

const PATHS = ['/', '/index.html', '/config.json', '/doctors', '/healthz'];

test.describe('TC-SEC-09 tiêu đề bảo mật của web', () => {
  for (const path of PATHS) {
    test(`TC-SEC-09 ${path}`, async ({ request }) => {
      const res = await request.get(`${base}${path}`);
      expect(res.status()).toBe(200);
      const h = res.headers();
      const csp = h['content-security-policy'] ?? '';
      expect(csp, 'CSP').toContain("default-src 'self'");
      expect(csp).toContain("frame-ancestors 'none'");
      expect(csp, 'không cho script nội tuyến').not.toMatch(/script-src[^;]*'unsafe-inline'/);
      expect(csp).not.toMatch(/script-src[^;]*'unsafe-eval'/);
      expect(h['x-content-type-options']).toBe('nosniff');
      expect(h['x-frame-options']).toBe('DENY');
      expect(h['referrer-policy']).toBe('no-referrer');
      expect(h['permissions-policy']).toContain('camera=()');
      expect(csp, 'connect-src không dùng wildcard hay scheme https: trần').not.toMatch(
        /connect-src[^;]*(\*|\shttps:(\s|;|$))/,
      );
      expect(csp, 'form-action chỉ cùng origin').toMatch(/form-action 'self'(;|$)/);
      expect(h['cross-origin-opener-policy']).toBe('same-origin');
      expect(h['cross-origin-embedder-policy']).toBe('require-corp');
      expect(h['server'] ?? '', 'không lộ phiên bản máy chủ').not.toMatch(/\d/);
    });
  }

  test('TC-SEC-09 tệp JS/CSS có hash cũng mang tiêu đề bảo mật', async ({ request }) => {
    const html = await (await request.get(`${base}/`)).text();
    const asset = /\/assets\/[^"']+\.js/.exec(html)?.[0];
    expect(asset, 'index.html phải tham chiếu một tệp JS trong /assets/').toBeTruthy();
    const res = await request.get(`${base}${asset}`);
    expect(res.status()).toBe(200);
    expect(res.headers()['x-content-type-options']).toBe('nosniff');
    expect(res.headers()['cache-control']).toContain('immutable');
  });

  test('TC-SEC-09 cấu hình và trang gốc không được cache; không lộ thông tin nội bộ khi lỗi', async ({ request }) => {
    expect((await request.get(`${base}/config.json`)).headers()['cache-control']).toContain('no-store');
    expect((await request.get(`${base}/index.html`)).headers()['cache-control']).toContain('no-cache');
    const missing = await request.get(`${base}/assets/khong-ton-tai.js`);
    expect(missing.status()).toBe(404);
    const body = await missing.text();
    expect(body).not.toMatch(/nginx\/\d/i);
  });
});
