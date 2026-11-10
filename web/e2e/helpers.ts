import { expect, type Page } from '@playwright/test';

export type Role = 'patient' | 'doctor' | 'nurse' | 'admin' | 'dpo';

const ROLE_LABEL: Record<Role, string> = {
  patient: 'Bệnh nhân',
  doctor: 'Bác sĩ',
  nurse: 'Điều dưỡng',
  admin: 'Quản trị',
  dpo: 'DPO',
};

/** Wednesday 04/11/2026 09:00: a workday with a slot tomorrow and today's queue (same time as the unit tests). */
export const NOW = new Date('2026-11-04T09:00:00+07:00');

/**
 * Open the web app in mock-data mode and sign in with the chosen role. The mock session lives in page memory, so later steps
 * must navigate client-side (goClient) instead of reloading the page.
 */
export async function loginAs(page: Page, role: Role, now: Date = NOW) {
  await page.clock.setFixedTime(now);
  await page.goto('/login');
  await page.locator('.shp-kpi', { hasText: ROLE_LABEL[role] }).first().click();
  await expect(page.getByRole('navigation').or(page.locator('header')).first()).toBeVisible();
}

export async function goClient(page: Page, path: string) {
  await page.evaluate((p) => {
    window.history.pushState({}, '', p);
    window.dispatchEvent(new PopStateEvent('popstate'));
  }, path);
}

/** Pick doctor pr-001's first slot (Cardiology) and go to the confirmation step. */
export async function pickFirstSlot(page: Page) {
  await goClient(page, '/doctors'); // leave the booking page first so the wizard is rebuilt from the first step
  await goClient(page, '/book?specialty=cardiology&practitioner=pr-001');
  const slots = page.getByRole('option');
  await slots.first().waitFor();
  await slots.first().click();
  await page.getByRole('button', { name: 'Tiếp tục' }).click();
}
