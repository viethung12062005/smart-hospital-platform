import { expect, test } from '@playwright/test';
import { goClient, loginAs, pickFirstSlot } from './helpers';

// Test titles carry the test case ID (TC-...) from docs/06-testing/catalog so traceability tooling recognizes them.

test.describe('Book and cancel an appointment (patient)', () => {
  test('TC-AC-FS.1 find a slot: sees the doctor’s free times in ascending order', async ({ page }) => {
    await loginAs(page, 'patient');
    await goClient(page, '/book?specialty=cardiology&practitioner=pr-001');
    const slots = page.getByRole('option');
    await slots.first().waitFor();
    const times = (await slots.allInnerTexts()).map((t) => t.trim());
    expect(times.length).toBeGreaterThan(3);
    expect(times).toEqual([...times].sort());
  });

  test('TC-AC-03.2 confirming twice in a row creates only one appointment', async ({ page }) => {
    await loginAs(page, 'patient');
    await goClient(page, '/appointments');
    await expect(page.getByRole('tab', { name: /Sắp tới \(1\)/ })).toBeVisible();

    await pickFirstSlot(page);
    await page.getByRole('button', { name: 'Đặt lịch' }).dblclick();
    await expect(page.getByText('Đặt lịch thành công')).toBeVisible();

    await goClient(page, '/appointments');
    await expect(page.getByRole('tab', { name: /Sắp tới \(2\)/ })).toBeVisible();
  });

  test('TC-AC-03.4 a patient who already has 3 future appointments cannot book more', async ({ page }) => {
    await loginAs(page, 'patient');
    for (let i = 0; i < 2; i++) {
      await pickFirstSlot(page);
      await page.getByRole('button', { name: 'Đặt lịch' }).click();
      await expect(page.getByText('Đặt lịch thành công')).toBeVisible();
    }
    await pickFirstSlot(page);
    await page.getByRole('button', { name: 'Đặt lịch' }).click();
    await expect(page.getByText('Bạn đã có 3 lịch hẹn sắp tới.')).toBeVisible();
    await goClient(page, '/appointments');
    await expect(page.getByRole('tab', { name: /Sắp tới \(3\)/ })).toBeVisible();
  });

  test('TC-AC-03.5 with less than 2 hours left a patient cannot cancel the appointment', async ({ page }) => {
    await loginAs(page, 'patient');
    // Appointment a-001 is at 09:00 on the 2nd workday from today (06/11). Move the clock back to 08:00 that day: 1 hour left.
    await page.clock.setFixedTime(new Date('2026-11-06T08:00:00+07:00'));
    await goClient(page, '/appointments');
    await page.getByText('Hủy lịch', { exact: true }).first().click();
    await page.getByRole('dialog').getByRole('button', { name: 'Hủy lịch' }).click();
    await expect(page.getByText(/quá thời hạn|trước giờ khám ít nhất 2 giờ/i).first()).toBeVisible();
    await page.keyboard.press('Escape');
    await goClient(page, '/appointments');
    await expect(page.getByRole('tab', { name: /Sắp tới \(1\)/ })).toBeVisible();
  });

  test('TC-AC-CN.1 cancelling before the deadline succeeds and the upcoming list drops by one', async ({ page }) => {
    await loginAs(page, 'patient');
    await goClient(page, '/appointments');
    await page.getByText('Hủy lịch', { exact: true }).first().click();
    await page.getByRole('dialog').getByRole('button', { name: 'Hủy lịch' }).click();
    await expect(page.getByText('Đã hủy lịch hẹn')).toBeVisible();
    await expect(page.getByRole('tab', { name: /Sắp tới \(0\)/ })).toBeVisible();
  });

  test('TC-AC-MA.1 a patient only sees their own appointments', async ({ page }) => {
    await loginAs(page, 'patient');
    await goClient(page, '/appointments');
    // The mock data has appointments for several patients; p-001 has exactly 1 upcoming and 2 past.
    await expect(page.getByRole('tab', { name: /Sắp tới \(1\)/ })).toBeVisible();
    await expect(page.getByRole('tab', { name: /Đã qua \(2\)/ })).toBeVisible();
  });
});

test.describe('UI security', () => {
  test('TC-NFR-SEC2 a patient visiting a staff page is blocked', async ({ page }) => {
    await loginAs(page, 'patient');
    await goClient(page, '/queue');
    await expect(page.getByText('Bạn không có quyền xem trang này.')).toBeVisible();
    await goClient(page, '/patients');
    await expect(page.getByText('Bạn không có quyền xem trang này.')).toBeVisible();
  });

  test('TC-NFR-SEC2-DPO the DPO does not see the clinical business menu', async ({ page }) => {
    await loginAs(page, 'dpo');
    const nav = page.getByRole('navigation');
    await expect(nav.getByText('Bệnh nhân', { exact: true })).toHaveCount(0);
    await expect(nav.getByText('Lần khám')).toHaveCount(0);
    await goClient(page, '/patients');
    await expect(page.getByText('Bạn không có quyền xem trang này.')).toBeVisible();
  });

  test('TC-SEC-28 the token is not in localStorage', async ({ page }) => {
    await loginAs(page, 'doctor');
    const keys = await page.evaluate(() => Object.keys(window.localStorage));
    expect(keys).toEqual([]);
  });

  test('TC-SEC-08 malicious text in the visit reason is rendered as text, not executed', async ({ page }) => {
    let dialogs = 0;
    page.on('dialog', async (d) => {
      dialogs++;
      await d.dismiss();
    });
    await loginAs(page, 'patient');
    await pickFirstSlot(page);
    await page.getByLabel('Lý do khám').fill('<img src=x onerror="alert(1)"><script>alert(2)</script>');
    await page.getByRole('button', { name: 'Đặt lịch' }).click();
    await expect(page.getByText('Đặt lịch thành công')).toBeVisible();
    await goClient(page, '/appointments');
    await expect(page.getByText('<img src=x', { exact: false })).toBeVisible();
    expect(await page.locator('img[src="x"]').count()).toBe(0);
    expect(dialogs).toBe(0);
  });

  // Not implemented in the UI yet: keep the test as fixme so it is not forgotten (see the WEB-xx backlog).
  test.fixme('TC-NFR-SEC14 staff are signed out automatically after 15 minutes of inactivity', async ({ page }) => {
    await loginAs(page, 'doctor');
    await page.clock.fastForward('16:00');
    await expect(page).toHaveURL(/\/login/);
  });
});

test.describe('Authorization by schedule screen', () => {
  test('TC-AC-02.3 a doctor only sees their own schedule and cannot pick another doctor', async ({ page }) => {
    await loginAs(page, 'doctor');
    await goClient(page, '/schedule');
    await expect(page.getByRole('heading', { name: 'Lịch bác sĩ' })).toBeVisible();
    await expect(page.getByLabel('Bác sĩ')).toHaveCount(0);
  });
});
