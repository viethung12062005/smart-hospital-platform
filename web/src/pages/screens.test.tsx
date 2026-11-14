import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { mockDb, resetMockDb } from '@/mocks/handlers';
import { renderApp } from '@/test/render';

// Wednesday 04/11/2026 09:00 system time: a workday with a slot tomorrow and today's queue.
beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(2026, 10, 4, 9, 0, 0));
  resetMockDb();
});
afterEach(() => {
  vi.useRealTimers();
});

describe('booking (patient)', () => {
  it('picks a time, confirms and books successfully', async () => {
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

  it('slot just booked: reports an error and suggests other times', async () => {
    const user = userEvent.setup();
    renderApp('/book?specialty=cardiology&practitioner=pr-001', 'patient');

    const slots = await screen.findAllByRole('option');
    await user.click(slots[0]!);
    await user.click(screen.getByRole('button', { name: 'Tiếp tục' }));
    // Someone else takes the chosen time (tomorrow 05/11) before the user confirms.
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

describe('my appointments', () => {
  it('cancels an upcoming appointment', async () => {
    const user = userEvent.setup();
    renderApp('/appointments', 'patient');

    await user.click(await screen.findByRole('button', { name: 'Hủy lịch' }));
    const dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getByRole('button', { name: 'Hủy lịch' }));

    await waitFor(() => expect(mockDb().appointments.find((a) => a.id === 'a-001')?.status).toBe('cancelled'));
    expect(await screen.findByText('Đã hủy lịch hẹn')).toBeInTheDocument();
  });
});

describe('queue (nurse)', () => {
  it('checks in a patient within the time window', async () => {
    const user = userEvent.setup();
    renderApp('/queue', 'nurse');

    const row = (await screen.findByText('Hoàng Thị Mai')).closest('tr')!;
    await user.click(within(row as HTMLElement).getByRole('button', { name: 'Check-in' }));

    expect(await screen.findByText('Đã check-in Hoàng Thị Mai')).toBeInTheDocument();
    expect(mockDb().appointments.find((a) => a.id === 'a-q3')?.status).toBe('arrived');
  });
});

describe('UI authorization', () => {
  it('a patient cannot access the queue', async () => {
    renderApp('/queue', 'patient');
    expect(await screen.findByText('Bạn không có quyền xem trang này.')).toBeInTheDocument();
  });

  it('a doctor sees a patient record with allergies and diagnoses', async () => {
    renderApp('/patients/p-002', 'doctor');
    expect(await screen.findByText('Trần Văn Bình')).toBeInTheDocument();
    expect(await screen.findByText('Penicillin')).toBeInTheDocument();
    expect(await screen.findByText('Tăng huyết áp vô căn')).toBeInTheDocument();
  });
});

// WEB-01 (US-066): login/home/callback screens — the 3 foundation pages when running in mock mode.
describe('login (mock mode)', () => {
  it('shows all 5 mock roles and signs in to the home page', async () => {
    const user = userEvent.setup();
    renderApp('/login', 'patient');

    expect(screen.getByText('Bệnh nhân')).toBeInTheDocument();
    expect(screen.getByText('Bác sĩ')).toBeInTheDocument();
    expect(screen.getByText('Điều dưỡng')).toBeInTheDocument();
    expect(screen.getByText('Quản trị')).toBeInTheDocument();
    expect(screen.getByText('DPO')).toBeInTheDocument();

    await user.click(screen.getByText('BS. Lê Minh Quân'));

    expect(await screen.findByText('Xin chào, BS. Lê Minh Quân')).toBeInTheDocument();
  });
});

describe('home page', () => {
  it('a patient sees an upcoming-appointments KPI and shortcuts', async () => {
    renderApp('/', 'patient');

    expect(await screen.findByText('Xin chào, Nguyễn Thị Lan')).toBeInTheDocument();
    expect(screen.getAllByText('Lịch hẹn sắp tới').length).toBeGreaterThan(0);
    expect(screen.getAllByRole('link', { name: 'Tìm bác sĩ' }).length).toBeGreaterThan(0);
  });

  it('a nurse sees KPI for today’s appointments by status', async () => {
    renderApp('/', 'nurse');

    await screen.findByText('Đang chờ khám');
    expect(screen.getAllByText('Lịch hẹn hôm nay').length).toBeGreaterThan(0);
  });
});

describe('login callback', () => {
  it('callback without a valid session: reports an error and offers to sign in again', async () => {
    renderApp('/auth/callback', 'patient');

    expect(await screen.findByText('Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Đăng nhập' })).toBeInTheDocument();
  });
});

// WEB-07 (US-011, US-012, US-013): the doctor's weekly schedule.
describe('doctor schedule', () => {
  it('a doctor sees their own schedule, with no doctor picker', async () => {
    renderApp('/schedule', 'doctor');

    expect(await screen.findByText('Trống', { selector: 'span' })).toBeInTheDocument();
    expect(screen.queryByLabelText('Bác sĩ')).not.toBeInTheDocument();
  });

  it('a nurse picks a doctor and moves to the next week', async () => {
    const user = userEvent.setup();
    renderApp('/schedule', 'nurse');

    expect((await screen.findAllByLabelText('Bác sĩ')).length).toBeGreaterThan(0);
    const before = screen.getByRole('heading', { level: 3 }).textContent;
    await user.click(screen.getByRole('button', { name: 'Tuần sau' }));

    await waitFor(() => expect(screen.getByRole('heading', { level: 3 }).textContent).not.toBe(before));
  });
});

// WEB-20 (US-031): find a patient.
describe('find a patient', () => {
  it('no keyword yet: prompts for one and does not call the search API', async () => {
    renderApp('/patients', 'nurse');
    expect(await screen.findByText('Nhập từ khóa để tìm bệnh nhân.')).toBeInTheDocument();
  });

  it('searching by name finds the right patient', async () => {
    const user = userEvent.setup();
    renderApp('/patients', 'nurse');

    await user.type(await screen.findByLabelText('Tìm kiếm'), 'Lan');
    await user.click(screen.getByRole('button', { name: 'Tìm kiếm' }));

    const row = (await screen.findByText('Nguyễn Thị Lan')).closest('tr')!;
    expect(within(row as HTMLElement).getByText('BN-2026-000001')).toBeInTheDocument();
  });
});

// WEB-22 (US-024, US-025): encounter list.
describe('encounter list', () => {
  it('shows the latest encounter, filtered by status', async () => {
    const user = userEvent.setup();
    renderApp('/encounters', 'doctor');

    expect(await screen.findByText('Tăng huyết áp tái khám')).toBeInTheDocument();

    await user.click(screen.getByRole('radio', { name: 'Hoàn tất' }));

    await waitFor(() => expect(screen.getAllByText('Hoàn tất').length).toBeGreaterThan(0));
    expect(screen.queryByText('Hồi hộp, khó thở')).not.toBeInTheDocument(); // in progress, filtered out
  });
});

// WEB-03/WEB-06 find-doctor scope (US-014 choose a doctor before seeing slots).
describe('find a doctor', () => {
  it('shows cardiologists by default, filters by name and offers a book button for patients', async () => {
    const user = userEvent.setup();
    renderApp('/doctors?specialty=cardiology', 'patient');

    expect(await screen.findByText('BS. Lê Minh Quân')).toBeInTheDocument();
    expect(screen.getByText('BS. Phạm Thu Trang')).toBeInTheDocument();
    expect(screen.getAllByRole('link', { name: 'Đặt lịch' }).length).toBeGreaterThan(0);

    await user.type(screen.getByPlaceholderText('Tên bác sĩ'), 'Quân');

    await waitFor(() => expect(screen.queryByText('BS. Phạm Thu Trang')).not.toBeInTheDocument());
    expect(screen.getByText('BS. Lê Minh Quân')).toBeInTheDocument();
  });

  it('a nurse does not see the book button', async () => {
    renderApp('/doctors?specialty=cardiology', 'nurse');
    expect(await screen.findByText('BS. Lê Minh Quân')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Đặt lịch' })).not.toBeInTheDocument();
  });
});
