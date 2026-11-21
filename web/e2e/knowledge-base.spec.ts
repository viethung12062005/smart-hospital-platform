import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';
import { goClient, loginAs } from './helpers';

// Knowledge base management (US-052, AI9) and AI usage (AI10, NFR-C2) against the MSW mock of the AI service.

const row = (page: Page, title: string) => page.getByRole('row', { name: new RegExp(title) });

async function noSeriousViolations(page: Page) {
  const result = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze();
  const found = result.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical');
  expect(found.map((v) => `${v.id}: ${v.nodes.map((n) => n.html).join(' | ')}`)).toEqual([]);
}

test.describe('Knowledge base (administrator)', () => {
  test('TC-AC-11.1 upload, approve and remove a document through its lifecycle', async ({ page }) => {
    await loginAs(page, 'admin');
    await goClient(page, '/knowledge');
    await expect(row(page, 'Phác đồ tăng huyết áp 2026')).toContainText('Đã lập chỉ mục');
    await expect(row(page, 'Quy trình vệ sinh tay')).toContainText('Bản nháp');
    await noSeriousViolations(page);

    // Upload -> draft
    await page.getByRole('button', { name: 'Tải tài liệu lên' }).click();
    const dialog = page.getByRole('dialog');
    await dialog.getByLabel(/Tiêu đề/).fill('Quy trình tiếp nhận cấp cứu');
    await dialog.getByLabel(/Nguồn/).fill('Quy chế bệnh viện');
    await dialog.locator('input[type="file"]').setInputFiles({
      name: 'quy-trinh.md',
      mimeType: 'text/markdown',
      buffer: Buffer.from('# Quy trình\n\nNội dung thử nghiệm.'),
    });
    await dialog.getByRole('button', { name: 'Tải tài liệu lên' }).click();
    await expect(row(page, 'Quy trình tiếp nhận cấp cứu')).toContainText('Bản nháp');

    // Approve -> indexed
    await page.getByRole('button', { name: 'Duyệt tài liệu Quy trình tiếp nhận cấp cứu' }).click();
    await expect(row(page, 'Quy trình tiếp nhận cấp cứu')).toContainText('Đã lập chỉ mục');

    // Remove -> retired, the record stays
    await page.getByRole('button', { name: 'Gỡ tài liệu Quy trình tiếp nhận cấp cứu' }).click();
    await page.getByRole('dialog').getByRole('button', { name: 'Gỡ', exact: true }).click();
    await expect(row(page, 'Quy trình tiếp nhận cấp cứu')).toContainText('Đã gỡ');
  });

  test('TC-AC-11.1 a doctor approves only their own department and cannot upload or remove', async ({ page }) => {
    await loginAs(page, 'doctor');
    await goClient(page, '/knowledge');
    await expect(row(page, 'Phác đồ suy tim 2026')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Tải tài liệu lên' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: /Gỡ tài liệu/ })).toHaveCount(0);
    await page.getByRole('button', { name: 'Duyệt tài liệu Phác đồ suy tim 2026' }).click();
    await expect(row(page, 'Phác đồ suy tim 2026')).toContainText('Đã lập chỉ mục');
  });
});

test.describe('AI usage (administrator)', () => {
  test('TC-NFR-C2 shows the usage table, the cost per request and the 80 % budget warning', async ({ page }) => {
    await loginAs(page, 'admin');
    await goClient(page, '/ai-usage');
    await expect(row(page, 'rag-answer')).toContainText('6,450,000');
    await expect(page.getByText('Đã dùng từ 80% hạn mức ngày')).toBeVisible();
    await expect(page.getByText('Đã dùng 1,730,000 / 2,000,000 token (86.5%)')).toBeVisible();

    await page.getByText('Mô hình', { exact: true }).click();
    await expect(row(page, 'deepseek/deepseek-chat')).toBeVisible();
    await noSeriousViolations(page);
  });
});
