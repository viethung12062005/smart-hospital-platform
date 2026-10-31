import { expect, type Page } from '@playwright/test';

export type Role = 'patient' | 'doctor' | 'nurse' | 'admin' | 'dpo';

const ROLE_LABEL: Record<Role, string> = {
  patient: 'Bệnh nhân',
  doctor: 'Bác sĩ',
  nurse: 'Điều dưỡng',
  admin: 'Quản trị',
  dpo: 'DPO',
};

/** Thứ Tư 04/11/2026 09:00: ngày làm việc, có slot ngày mai và hàng đợi hôm nay (cùng thời điểm với test đơn vị). */
export const NOW = new Date('2026-11-04T09:00:00+07:00');

/**
 * Mở web ở chế độ dữ liệu giả và đăng nhập bằng vai trò đã chọn. Phiên giả nằm trong bộ nhớ trang, nên các bước sau
 * phải điều hướng phía client (goClient) chứ không tải lại trang.
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

/** Chọn slot đầu tiên của bác sĩ pr-001 (Tim mạch) và tới bước xác nhận. */
export async function pickFirstSlot(page: Page) {
  await goClient(page, '/doctors'); // rời trang đặt lịch trước để trình hướng dẫn được dựng lại từ bước đầu
  await goClient(page, '/book?specialty=cardiology&practitioner=pr-001');
  const slots = page.getByRole('option');
  await slots.first().waitFor();
  await slots.first().click();
  await page.getByRole('button', { name: 'Tiếp tục' }).click();
}
