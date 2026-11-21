import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { beforeEach, describe, expect, it } from 'vitest';
import { resetMockDb } from '@/mocks/handlers';
import { server } from '@/mocks/server';
import { renderApp } from '@/test/render';

beforeEach(() => resetMockDb());

describe('AI usage (administrator)', () => {
  it('shows usage by task with tokens, cost and fallback rate', async () => {
    renderApp('/ai-usage', 'admin');

    const rag = (await screen.findByText('rag-answer')).closest('tr')!;
    expect(within(rag).getByText('2,480')).toBeInTheDocument();
    expect(within(rag).getByText('6,450,000')).toBeInTheDocument();
    expect(within(rag).getByText('$27.5000')).toBeInTheDocument();
    expect(within(rag).getByText('4.0%')).toBeInTheDocument();
    // total cost 30.7 USD over 2,780 requests
    expect(screen.getByText('$0.0110')).toBeInTheDocument();
  });

  it('regroups by model and by day', async () => {
    const user = userEvent.setup();
    renderApp('/ai-usage', 'admin');
    await screen.findByText('rag-answer');

    await user.click(screen.getByText('Mô hình'));
    expect(await screen.findByText('deepseek/deepseek-chat')).toBeInTheDocument();
    await user.click(screen.getByText('Ngày'));
    expect(await screen.findAllByText(/^\d{4}-\d{2}-\d{2}$/)).toHaveLength(3);
  });

  it('warns when today has used 80 % of the daily budget', async () => {
    renderApp('/ai-usage', 'admin');

    expect(await screen.findByText('Đã dùng từ 80% hạn mức ngày')).toBeInTheDocument();
    expect(screen.getByText('Đã dùng 1,730,000 / 2,000,000 token (86.5%)')).toBeInTheDocument();
  });

  it('shows no warning below 80 %', async () => {
    server.use(
      http.get('/api/v1/ai/usage', () =>
        HttpResponse.json({
          items: [
            { key: 'rag-answer', requests: 5, inputTokens: 1000, outputTokens: 100, costUsd: 0.01, fallbackRate: 0 },
          ],
        }),
      ),
    );
    renderApp('/ai-usage', 'admin');

    expect(await screen.findByText(/Đã dùng 1,100 \/ 2,000,000 token/)).toBeInTheDocument();
    expect(screen.queryByText(/Đã dùng từ 80% hạn mức ngày/)).toBeNull();
  });

  it('shows the standard error when the usage report fails', async () => {
    server.use(
      http.get('/api/v1/ai/usage', () =>
        HttpResponse.json({ code: 'forbidden', detail: 'Không đủ quyền.' }, { status: 403 }),
      ),
    );
    renderApp('/ai-usage', 'admin');
    expect((await screen.findAllByText('Không đủ quyền.')).length).toBeGreaterThan(0);
  });

  it('is not available to a doctor', async () => {
    renderApp('/ai-usage', 'doctor');
    expect(await screen.findByText('Bạn không có quyền xem trang này.')).toBeInTheDocument();
  });
});
