import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { beforeEach, describe, expect, it } from 'vitest';
import { resetMockDb } from '@/mocks/handlers';
import { server } from '@/mocks/server';
import { renderApp } from '@/test/render';

beforeEach(() => resetMockDb());

async function ask(text: string, role: 'doctor' | 'nurse' = 'doctor') {
  const user = userEvent.setup();
  renderApp('/assistant', role);
  await user.type(await screen.findByLabelText('Câu hỏi'), text);
  await user.click(screen.getByRole('button', { name: 'Hỏi' }));
  return user;
}

describe('assistant (doctor, nurse)', () => {
  it('streams a grounded answer and a citation opens the cited section', async () => {
    const user = await ask('Ngưỡng huyết áp cần chuyển tuyến?');

    expect(await screen.findByText(/chuyển tuyến khi huyết áp ≥ 180\/110/)).toBeInTheDocument();
    await user.click((await screen.findAllByRole('button', { name: /Mở nguồn \[1\]/ }))[0]!);
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText(/4\.2 Chỉ định chuyển tuyến/)).toBeInTheDocument();
    expect(within(dialog).getByText('12')).toBeInTheDocument();
    expect(within(dialog).getByLabelText('Trích đoạn')).toHaveTextContent('≥ 180/110 mmHg');
    expect(within(dialog).getByText(/kb-001/)).toBeInTheDocument();
  });

  it('discards what was streamed when the stream sends reset', async () => {
    await ask('câu hỏi reset');

    expect(await screen.findByText(/Theo phác đồ, chuyển tuyến khi huyết áp ≥ 180/)).toBeInTheDocument();
    expect(screen.queryByText(/phác đồ cũ/)).not.toBeInTheDocument();
  });

  it('shows retrieval-only mode with the related sections and no generated answer', async () => {
    const user = await ask('hỏi khi offline');

    expect(await screen.findByText('Chỉ hiển thị tài liệu liên quan')).toBeInTheDocument();
    expect(screen.getByText('Các mục tài liệu liên quan')).toBeInTheDocument();
    expect(screen.queryByText(/Theo phác đồ/)).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /\[2\] Quy trình/ }));
    expect(within(await screen.findByRole('dialog')).getByText(/2\.1 Tiếp nhận ban đầu/)).toBeInTheDocument();
  });

  it('shows the server message when there is nothing in the documents', async () => {
    await ask('câu hỏi unknown');

    expect(await screen.findByText('Không có thông tin trong tài liệu được phép truy cập.')).toBeInTheDocument();
    expect(screen.queryByText('Nguồn trích dẫn')).not.toBeInTheDocument();
  });

  it('maps ai-unavailable through the standard error alert', async () => {
    await ask('câu hỏi unavailable');

    expect(await screen.findByText('Trợ lý AI tạm thời không khả dụng.')).toBeInTheDocument();
    expect(screen.getByText('Có lỗi xảy ra')).toBeInTheDocument();
  });

  it('maps ai-budget-exceeded and still lists the related sections', async () => {
    await ask('câu hỏi budget');

    expect(await screen.findByText('Đã hết hạn mức AI trong ngày.')).toBeInTheDocument();
    expect(screen.getByText('Chỉ hiển thị tài liệu liên quan')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /\[1\] Phác đồ/ })).toBeInTheDocument();
  });

  it('shows a network error when the stream breaks', async () => {
    server.use(http.post('/api/v1/ai/assistant/ask', () => HttpResponse.error()));
    await ask('câu hỏi bất kỳ');

    expect(await screen.findByText('Không kết nối được máy chủ. Kiểm tra mạng rồi thử lại.')).toBeInTheDocument();
  });

  it('rates an answer, and a second rating (409) is handled and then changed with PUT', async () => {
    const user = await ask('Ngưỡng huyết áp cần chuyển tuyến?');
    const up = await screen.findByRole('button', { name: 'Câu trả lời hữu ích' });
    await user.click(up);
    expect(await screen.findByText('Đã ghi nhận đánh giá của bạn')).toBeInTheDocument();
    expect(up).toHaveAttribute('aria-pressed', 'true');

    // The same message rated again from a fresh screen state: first click gets 409, second click replaces.
    server.use(
      http.post('/api/v1/ai/messages/:id/feedback', () =>
        HttpResponse.json(
          { code: 'invalid-state-transition', detail: 'Đã đánh giá câu trả lời này; dùng PUT để sửa.' },
          { status: 409, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );
    // Loading it from the history gives an unrated turn, so the 409 path is exercised.
    await user.click(await screen.findByRole('button', { name: /\d{2}\/\d{2}\/\d{4}/ }));
    await user.click(await screen.findByRole('button', { name: 'Câu trả lời chưa hữu ích' }));
    expect(await screen.findByText('Bạn đã đánh giá câu trả lời này. Bấm lại để đổi đánh giá.')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Câu trả lời chưa hữu ích' }));
    expect(screen.getByRole('button', { name: 'Câu trả lời chưa hữu ích' })).toHaveAttribute('aria-pressed', 'true');
  });

  it('lists the history and reopens a conversation with its citations', async () => {
    const user = await ask('Ngưỡng huyết áp cần chuyển tuyến?');
    await screen.findByText(/chuyển tuyến khi huyết áp ≥ 180\/110/);
    await user.click(screen.getByRole('button', { name: 'Cuộc trò chuyện mới' }));
    expect(screen.queryByText(/chuyển tuyến khi huyết áp ≥ 180\/110/)).not.toBeInTheDocument();

    await user.click(await screen.findByRole('button', { name: /\d{2}\/\d{2}\/\d{4}/ }));
    expect(await screen.findByText(/chuyển tuyến khi huyết áp ≥ 180\/110/)).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: /Mở nguồn \[1\]/ }).length).toBeGreaterThan(0);
  });

  it('keeps the ask button disabled for a question shorter than 3 characters', async () => {
    const user = userEvent.setup();
    renderApp('/assistant', 'nurse');
    await user.type(await screen.findByLabelText('Câu hỏi'), 'ab');
    expect(screen.getByRole('button', { name: 'Hỏi' })).toBeDisabled();
  });

  it('is not available to a patient', async () => {
    renderApp('/assistant', 'patient');
    expect(await screen.findByText('Bạn không có quyền xem trang này.')).toBeInTheDocument();
  });
});
