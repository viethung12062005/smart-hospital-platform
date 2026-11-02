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

// WEB-01 (US-066): màn hình đăng nhập/trang chủ/callback — 3 trang nền tảng khi chạy ở chế độ mock.
describe('đăng nhập (chế độ mock)', () => {
  it('hiển thị đủ 5 vai trò giả và đăng nhập chuyển sang trang chủ', async () => {
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

describe('trang chủ', () => {
  it('bệnh nhân thấy KPI lịch hẹn sắp tới và lối tắt', async () => {
    renderApp('/', 'patient');

    expect(await screen.findByText('Xin chào, Nguyễn Thị Lan')).toBeInTheDocument();
    expect(screen.getAllByText('Lịch hẹn sắp tới').length).toBeGreaterThan(0);
    expect(screen.getAllByRole('link', { name: 'Tìm bác sĩ' }).length).toBeGreaterThan(0);
  });

  it('điều dưỡng thấy KPI lịch hẹn hôm nay theo trạng thái', async () => {
    renderApp('/', 'nurse');

    await screen.findByText('Đang chờ khám');
    expect(screen.getAllByText('Lịch hẹn hôm nay').length).toBeGreaterThan(0);
  });
});

describe('callback đăng nhập', () => {
  it('callback không có phiên đăng nhập hợp lệ: báo lỗi và cho đăng nhập lại', async () => {
    renderApp('/auth/callback', 'patient');

    expect(await screen.findByText('Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Đăng nhập' })).toBeInTheDocument();
  });
});

// WEB-07 (US-011, US-012, US-013): lịch bác sĩ theo tuần.
describe('lịch bác sĩ', () => {
  it('bác sĩ xem lịch của chính mình, không có bộ chọn bác sĩ', async () => {
    renderApp('/schedule', 'doctor');

    expect(await screen.findByText('Trống', { selector: 'span' })).toBeInTheDocument();
    expect(screen.queryByLabelText('Bác sĩ')).not.toBeInTheDocument();
  });

  it('điều dưỡng chọn bác sĩ và chuyển sang tuần sau', async () => {
    const user = userEvent.setup();
    renderApp('/schedule', 'nurse');

    expect((await screen.findAllByLabelText('Bác sĩ')).length).toBeGreaterThan(0);
    const before = screen.getByRole('heading', { level: 3 }).textContent;
    await user.click(screen.getByRole('button', { name: 'Tuần sau' }));

    await waitFor(() => expect(screen.getByRole('heading', { level: 3 }).textContent).not.toBe(before));
  });
});

// WEB-20 (US-031): tìm bệnh nhân.
describe('tìm bệnh nhân', () => {
  it('chưa nhập từ khóa: gợi ý nhập từ khóa, chưa gọi API tìm kiếm', async () => {
    renderApp('/patients', 'nurse');
    expect(await screen.findByText('Nhập từ khóa để tìm bệnh nhân.')).toBeInTheDocument();
  });

  it('tìm theo tên ra đúng bệnh nhân', async () => {
    const user = userEvent.setup();
    renderApp('/patients', 'nurse');

    await user.type(await screen.findByLabelText('Tìm kiếm'), 'Lan');
    await user.click(screen.getByRole('button', { name: 'Tìm kiếm' }));

    const row = (await screen.findByText('Nguyễn Thị Lan')).closest('tr')!;
    expect(within(row as HTMLElement).getByText('BN-2026-000001')).toBeInTheDocument();
  });
});

// WEB-22 (US-024, US-025): danh sách lần khám.
describe('danh sách lần khám', () => {
  it('hiển thị lần khám gần nhất, lọc theo trạng thái', async () => {
    const user = userEvent.setup();
    renderApp('/encounters', 'doctor');

    expect(await screen.findByText('Tăng huyết áp tái khám')).toBeInTheDocument();

    await user.click(screen.getByRole('radio', { name: 'Hoàn tất' }));

    await waitFor(() => expect(screen.getAllByText('Hoàn tất').length).toBeGreaterThan(0));
    expect(screen.queryByText('Hồi hộp, khó thở')).not.toBeInTheDocument(); // đang khám, bị lọc bỏ
  });
});

// WEB-03/WEB-06 phạm vi tìm bác sĩ (US-014 giai đoạn chọn bác sĩ trước khi xem slot).
describe('tìm bác sĩ', () => {
  it('mặc định hiện bác sĩ tim mạch, lọc theo tên và có nút đặt lịch cho bệnh nhân', async () => {
    const user = userEvent.setup();
    renderApp('/doctors?specialty=cardiology', 'patient');

    expect(await screen.findByText('BS. Lê Minh Quân')).toBeInTheDocument();
    expect(screen.getByText('BS. Phạm Thu Trang')).toBeInTheDocument();
    expect(screen.getAllByRole('link', { name: 'Đặt lịch' }).length).toBeGreaterThan(0);

    await user.type(screen.getByPlaceholderText('Tên bác sĩ'), 'Quân');

    await waitFor(() => expect(screen.queryByText('BS. Phạm Thu Trang')).not.toBeInTheDocument());
    expect(screen.getByText('BS. Lê Minh Quân')).toBeInTheDocument();
  });

  it('điều dưỡng không thấy nút đặt lịch', async () => {
    renderApp('/doctors?specialty=cardiology', 'nurse');
    expect(await screen.findByText('BS. Lê Minh Quân')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Đặt lịch' })).not.toBeInTheDocument();
  });
});
