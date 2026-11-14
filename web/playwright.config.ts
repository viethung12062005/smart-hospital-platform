import { defineConfig, devices } from '@playwright/test';

/**
 * E2E against the web UI in mock-data mode (MSW): runs without a backend or Azure.
 * Dev machine: uses the installed Edge/Chrome (PW_CHANNEL=msedge). CI: Chromium downloaded by `npx playwright install chromium`.
 */
const channel = process.env.PW_CHANNEL || undefined;

export default defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  expect: { timeout: 15_000 }, // MSW starts slowly when several browsers run in parallel
  fullyParallel: true,
  workers: process.env.CI ? 2 : 3,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI
    ? [['list'], ['html', { open: 'never' }], ['junit', { outputFile: 'test-results/e2e.xml' }]]
    : 'list',
  use: {
    baseURL: 'http://localhost:5173',
    locale: 'vi-VN',
    timezoneId: 'Asia/Ho_Chi_Minh',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    { name: 'desktop', use: { ...devices['Desktop Chrome'], channel, viewport: { width: 1360, height: 820 } } },
    { name: 'mobile', use: { ...devices['Pixel 7'], channel }, grep: /@mobile/ },
  ],
  webServer: {
    command: 'npm run dev:mock',
    url: 'http://localhost:5173/login',
    reuseExistingServer: !process.env.CI,
    timeout: 60_000,
  },
});
