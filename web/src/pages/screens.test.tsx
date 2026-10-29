import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { mockDb, resetMockDb } from '@/mocks/handlers';
import { renderApp } from '@/test/render';

// Thứ Tư 04/11/2026 09:00 giờ máy: ngày làm việc, có slot ngày mai và hàng đợi hôm nay.
beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(2026, 10, 4, 9, 0, 0));
  resetMockDb();
});
afterEach(() => {
  vi.useRealTimers();
});

describe('đặt lịch (bệnh nhân)', () => {
  it('chọn giờ, xác nhận và đặt thành công', async () => {
    const user = userEvent.setup();
    renderApp('/book?specialty=cardiology&practitioner=pr-001', 'patient');

    const slots = await screen.findAllByRole('option');
    await user.click(slots[0]!);
    await user.click(screen.getByRole('button', { name: 'Tiếp tục' }));
    await user.click(await screen.findByRole('button', { name: 'Đặt lịch' }));

    expect(await screen.findByText('Đặt lịch thành công')).toBeInTheDocument();
    expect(
      mockDb().appointments.filter((a) => a.participant?.some((p) => p.actor?.reference === 'Patient/p-001')),
    ).toHaveLength(4);
  });

  it('slot vừa bị đặt: báo lỗi và gợi ý giờ khác', async () => {
    const user = userEvent.setup();
    renderApp('/book?specialty=cardiology&practitioner=pr-001', 'patient');

    const slots = await screen.findAllByRole('option');
    await user.click(slots[0]!);
    await user.click(screen.getByRole('button', { name: 'Tiếp tục' }));
    // Người khác đặt mất giờ đã chọn (ngày mai 05/11) trước khi bấm xác nhận.
    const tomorrow = mockDb().slots.filter(
      (s) => s.schedule.reference === 'Schedule/sch-pr-001' && s.status === 'free' && s.start.startsWith('2026-11-05'),
    );
    expect(tomorrow.length).toBeGreaterThan(0);
    tomorrow[0]!.status = 'busy';
    await user.click(await screen.findByRole('button', { name: 'Đặt lịch' }));

    expect(await screen.findByText('Giờ khám này không còn trống.')).toBeInTheDocument();
    expect(await screen.findByText('Giờ này vừa có người đặt. Các giờ trống gần nhất:')).toBeInTheDocument();
  });
});

describe('lịch hẹn của tôi', () => {
  it('hủy lịch hẹn sắp tới', async () => {
    const user = userEvent.setup();
    renderApp('/appointments', 'patient');

    await user.click(await screen.findByRole('button', { name: 'Hủy lịch' }));
    const dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getByRole('button', { name: 'Hủy lịch' }));

    await waitFor(() => expect(mockDb().appointments.find((a) => a.id === 'a-001')?.status).toBe('cancelled'));
    expect(await screen.findByText('Đã hủy lịch hẹn')).toBeInTheDocument();
  });
});

describe('hàng đợi (điều dưỡng)', () => {
  it('check-in bệnh nhân trong khung giờ', async () => {
    const user = userEvent.setup();
    renderApp('/queue', 'nurse');

    const row = (await screen.findByText('Hoàng Thị Mai')).closest('tr')!;
    await user.click(within(row as HTMLElement).getByRole('button', { name: 'Check-in' }));

    expect(await screen.findByText('Đã check-in Hoàng Thị Mai')).toBeInTheDocument();
    expect(mockDb().appointments.find((a) => a.id === 'a-q3')?.status).toBe('arrived');
  });
});

describe('phân quyền giao diện', () => {
  it('bệnh nhân không vào được hàng đợi', async () => {
    renderApp('/queue', 'patient');
    expect(await screen.findByText('Bạn không có quyền xem trang này.')).toBeInTheDocument();
  });

  it('bác sĩ xem hồ sơ bệnh nhân có dị ứng và chẩn đoán', async () => {
    renderApp('/patients/p-002', 'doctor');
    expect(await screen.findByText('Trần Văn Bình')).toBeInTheDocument();
    expect(await screen.findByText('Penicillin')).toBeInTheDocument();
    expect(await screen.findByText('Tăng huyết áp vô căn')).toBeInTheDocument();
  });
});
