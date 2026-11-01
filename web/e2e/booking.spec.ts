import { expect, test } from '@playwright/test';
import { goClient, loginAs, pickFirstSlot } from './helpers';

// Tiêu đề test mang mã test case (TC-...) của docs/06-testing/catalog để công cụ truy vết tự nhận ra.

test.describe('Đặt, hủy lịch khám (bệnh nhân)', () => {
  test('TC-AC-FS.1 tìm slot: thấy các giờ trống của bác sĩ theo thứ tự tăng dần', async ({ page }) => {
    await loginAs(page, 'patient');
    await goClient(page, '/book?specialty=cardiology&practitioner=pr-001');
    const slots = page.getByRole('option');
    await slots.first().waitFor();
    const times = (await slots.allInnerTexts()).map((t) => t.trim());
    expect(times.length).toBeGreaterThan(3);
    expect(times).toEqual([...times].sort());
  });

  test('TC-AC-03.2 bấm xác nhận hai lần liên tiếp chỉ tạo một lịch hẹn', async ({ page }) => {
    await loginAs(page, 'patient');
    await goClient(page, '/appointments');
    await expect(page.getByRole('tab', { name: /Sắp tới \(1\)/ })).toBeVisible();

    await pickFirstSlot(page);
    await page.getByRole('button', { name: 'Đặt lịch' }).dblclick();
    await expect(page.getByText('Đặt lịch thành công')).toBeVisible();

    await goClient(page, '/appointments');
    await expect(page.getByRole('tab', { name: /Sắp tới \(2\)/ })).toBeVisible();
  });

  test('TC-AC-03.4 bệnh nhân đã có 3 lịch hẹn tương lai không đặt thêm được', async ({ page }) => {
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

  test('TC-AC-03.5 còn dưới 2 giờ thì bệnh nhân không hủy được lịch hẹn', async ({ page }) => {
    await loginAs(page, 'patient');
    // Lịch hẹn a-001 lúc 09:00 ngày làm việc thứ 2 kể từ hôm nay (06/11). Lùi đồng hồ tới 08:00 ngày đó: còn 1 giờ.
    await page.clock.setFixedTime(new Date('2026-11-06T08:00:00+07:00'));
    await goClient(page, '/appointments');
    await page.getByText('Hủy lịch', { exact: true }).first().click();
    await page.getByRole('dialog').getByRole('button', { name: 'Hủy lịch' }).click();
    await expect(page.getByText(/quá thời hạn|trước giờ khám ít nhất 2 giờ/i).first()).toBeVisible();
    await page.keyboard.press('Escape');
    await goClient(page, '/appointments');
    await expect(page.getByRole('tab', { name: /Sắp tới \(1\)/ })).toBeVisible();
  });

  test('TC-AC-CN.1 hủy trước hạn thành công và danh sách sắp tới giảm đi một', async ({ page }) => {
    await loginAs(page, 'patient');
    await goClient(page, '/appointments');
    await page.getByText('Hủy lịch', { exact: true }).first().click();
    await page.getByRole('dialog').getByRole('button', { name: 'Hủy lịch' }).click();
    await expect(page.getByText('Đã hủy lịch hẹn')).toBeVisible();
    await expect(page.getByRole('tab', { name: /Sắp tới \(0\)/ })).toBeVisible();
  });

  test('TC-AC-MA.1 bệnh nhân chỉ thấy lịch hẹn của chính mình', async ({ page }) => {
    await loginAs(page, 'patient');
    await goClient(page, '/appointments');
    // Dữ liệu giả có lịch hẹn của nhiều bệnh nhân; p-001 có đúng 1 sắp tới và 2 đã qua.
    await expect(page.getByRole('tab', { name: /Sắp tới \(1\)/ })).toBeVisible();
    await expect(page.getByRole('tab', { name: /Đã qua \(2\)/ })).toBeVisible();
  });
});

test.describe('Bảo mật giao diện', () => {
  test('TC-NFR-SEC2 bệnh nhân vào trang nhân viên thì bị chặn', async ({ page }) => {
    await loginAs(page, 'patient');
    await goClient(page, '/queue');
    await expect(page.getByText('Bạn không có quyền xem trang này.')).toBeVisible();
    await goClient(page, '/patients');
    await expect(page.getByText('Bạn không có quyền xem trang này.')).toBeVisible();
  });

  test('TC-NFR-SEC2-DPO DPO không thấy menu nghiệp vụ lâm sàng', async ({ page }) => {
    await loginAs(page, 'dpo');
    const nav = page.getByRole('navigation');
    await expect(nav.getByText('Bệnh nhân', { exact: true })).toHaveCount(0);
    await expect(nav.getByText('Lần khám')).toHaveCount(0);
    await goClient(page, '/patients');
    await expect(page.getByText('Bạn không có quyền xem trang này.')).toBeVisible();
  });

  test('TC-SEC-28 token không nằm trong localStorage', async ({ page }) => {
    await loginAs(page, 'doctor');
    const keys = await page.evaluate(() => Object.keys(window.localStorage));
    expect(keys).toEqual([]);
  });

  test('TC-SEC-08 văn bản độc hại trong lý do khám được hiển thị như chữ, không thực thi', async ({ page }) => {
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

  // Chưa hiện thực trong giao diện: giữ test ở trạng thái fixme để không quên (xem backlog WEB-xx).
  test.fixme('TC-NFR-SEC14 nhân viên tự đăng xuất sau 15 phút không thao tác', async ({ page }) => {
    await loginAs(page, 'doctor');
    await page.clock.fastForward('16:00');
    await expect(page).toHaveURL(/\/login/);
  });
});

test.describe('Phân quyền theo màn hình lịch', () => {
  test('TC-AC-02.3 bác sĩ chỉ xem lịch của chính mình, không chọn được bác sĩ khác', async ({ page }) => {
    await loginAs(page, 'doctor');
    await goClient(page, '/schedule');
    await expect(page.getByRole('heading', { name: 'Lịch bác sĩ' })).toBeVisible();
    await expect(page.getByLabel('Bác sĩ')).toHaveCount(0);
  });
});
