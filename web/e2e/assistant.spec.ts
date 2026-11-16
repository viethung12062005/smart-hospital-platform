import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';
import { goClient, loginAs } from './helpers';

// Staff assistant screen (US-053, US-056) against the MSW mock of the AI service.
// The question text selects the mock scenario: "reset", "offline" (retrieval-only), "unknown", "budget".

async function ask(page: Page, question: string) {
  await goClient(page, '/assistant');
  await page.getByLabel('Câu hỏi').fill(question);
  await page.getByRole('button', { name: 'Hỏi', exact: true }).click();
}

test.describe('Knowledge assistant (doctor)', () => {
  test('TC-AC-11.1 a streamed answer carries a citation that opens the cited section', async ({ page }) => {
    await loginAs(page, 'doctor');
    await ask(page, 'Ngưỡng huyết áp cần chuyển tuyến?');
    await expect(page.getByText(/chuyển tuyến khi huyết áp ≥ 180\/110/)).toBeVisible();

    await page
      .getByRole('button', { name: /Mở nguồn \[1\]/ })
      .first()
      .click();
    const dialog = page.getByRole('dialog');
    await expect(dialog.getByText('4.2 Chỉ định chuyển tuyến')).toBeVisible();
    await expect(dialog.getByLabel('Trích đoạn')).toContainText('180/110');
    await dialog.getByRole('button', { name: 'Đóng' }).click();

    // Rating, then the history lists the conversation.
    await page.getByRole('button', { name: 'Câu trả lời hữu ích' }).click();
    await expect(page.getByText('Đã ghi nhận đánh giá của bạn')).toBeVisible();
    await expect(page.getByRole('button', { name: /\d{2}\/\d{2}\/\d{4}/ })).toBeVisible();
  });

  test('TC-AC-11.3 retrieval-only mode lists the related sections without a generated answer', async ({ page }) => {
    await loginAs(page, 'doctor');
    await ask(page, 'hỏi khi offline');
    await expect(page.getByText('Chỉ hiển thị tài liệu liên quan')).toBeVisible();
    await expect(page.getByText('Các mục tài liệu liên quan', { exact: true })).toBeVisible();
    await expect(page.getByText(/Theo phác đồ/)).toHaveCount(0);
    await page.getByRole('button', { name: /\[2\] Quy trình/ }).click();
    await expect(page.getByRole('dialog').getByText('2.1 Tiếp nhận ban đầu')).toBeVisible();
  });

  test('TC-AC-11.3 a stream reset discards the partial text and the daily budget error is shown', async ({ page }) => {
    await loginAs(page, 'doctor');
    await ask(page, 'câu hỏi reset');
    await expect(page.getByText(/Theo phác đồ, chuyển tuyến khi huyết áp ≥ 180/)).toBeVisible();
    await expect(page.getByText(/phác đồ cũ/)).toHaveCount(0);

    await ask(page, 'câu hỏi budget');
    await expect(page.getByText('Đã hết hạn mức AI trong ngày.')).toBeVisible();
  });

  test('TC-NFR-U1 the assistant screen has no serious accessibility violations', async ({ page }) => {
    await loginAs(page, 'nurse');
    await ask(page, 'Ngưỡng huyết áp cần chuyển tuyến?');
    await expect(page.getByText(/chuyển tuyến khi huyết áp ≥ 180\/110/)).toBeVisible();
    const result = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze();
    const found = result.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical');
    expect(found.map((v) => `${v.id}: ${v.nodes.map((n) => n.html).join(' | ')}`)).toEqual([]);
  });
});
