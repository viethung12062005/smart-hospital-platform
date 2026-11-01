import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';
import { goClient, loginAs, type Role } from './helpers';

// NFR-U1: WCAG 2.1 AA ở các màn hình bệnh nhân (và kiểm nhanh các màn hình nhân viên đã có).
const PATIENT_SCREENS = ['/doctors', '/book?specialty=cardiology&practitioner=pr-001', '/appointments'];
const STAFF_SCREENS: [Role, string][] = [
  ['nurse', '/queue'],
  ['nurse', '/schedule'],
  ['doctor', '/patients'],
  ['doctor', '/patients/p-001'],
];

async function violations(page: import('@playwright/test').Page) {
  const result = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze();
  return result.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical');
}

test.describe('TC-NFR-U1 trợ năng WCAG 2.1 AA', () => {
  for (const path of PATIENT_SCREENS) {
    test(`bệnh nhân ${path}`, async ({ page }) => {
      await loginAs(page, 'patient');
      await goClient(page, path);
      await page.waitForLoadState('networkidle');
      const found = await violations(page);
      expect(
        found.map((v) => `${v.id}: ${v.nodes.length} phần tử`),
        JSON.stringify(found.map((v) => v.help)),
      ).toEqual([]);
    });
  }
  for (const [role, path] of STAFF_SCREENS) {
    test(`${role} ${path}`, async ({ page }) => {
      await loginAs(page, role);
      await goClient(page, path);
      await page.waitForLoadState('networkidle');
      const found = await violations(page);
      expect(found.map((v) => `${v.id}: ${v.nodes.length} phần tử`)).toEqual([]);
    });
  }
});

test('TC-NFR-U1-LANG đổi ngôn ngữ sang tiếng Anh', async ({ page }) => {
  await loginAs(page, 'patient');
  await goClient(page, '/appointments');
  await expect(page.getByRole('heading', { name: 'Lịch hẹn của tôi' })).toBeVisible();
  await page.getByText('EN', { exact: true }).click();
  await expect(page.getByRole('heading', { name: 'My appointments' })).toBeVisible();
});

test.describe('TC-NFR-I2 dùng được trên điện thoại', () => {
  for (const path of ['/doctors', '/appointments', '/book?specialty=cardiology&practitioner=pr-001']) {
    test(`@mobile không tràn ngang ở ${path}`, async ({ page }) => {
      await loginAs(page, 'patient');
      await goClient(page, path);
      await page.waitForLoadState('networkidle');
      const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
      expect(overflow).toBeLessThanOrEqual(1);
    });
  }
});
