import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { mockDb, resetMockDb } from '@/mocks/handlers';
import { server } from '@/mocks/server';
import { renderApp } from '@/test/render';

// Wednesday 04/11/2026 09:00 system time: matches seed dates in createDb().
beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(2026, 10, 4, 9, 0, 0));
  resetMockDb();
});
afterEach(() => {
  vi.useRealTimers();
});

async function openManageTab(user: ReturnType<typeof userEvent.setup>, role: 'nurse' | 'admin' | 'doctor') {
  renderApp('/schedule', role);
  const tab = await screen.findByRole('tab', { name: 'Cấu hình lịch & mẫu tuần' });
  await user.click(tab);
  await screen.findByRole('heading', { level: 3, name: /Danh sách lịch làm việc/ });
}

describe('schedule management (nurse / admin)', () => {
  it('nurse creates a new schedule for a practitioner with matching PractitionerRole (S1)', async () => {
    const user = userEvent.setup();
    await openManageTab(user, 'nurse');

    await user.click(screen.getByRole('button', { name: 'Tạo lịch làm việc' }));
    const dialog = await screen.findByRole('dialog');

    await user.click(within(dialog).getByLabelText(/Bác sĩ/));
    await user.click(await screen.findByRole('option', { name: 'BS. Tạ Công Minh' }));

    // Single specialty role auto-fills; still verify explicit note entry and save.
    await user.type(within(dialog).getByLabelText(/Ghi chú/), 'Phòng khám 308');
    await user.click(within(dialog).getByRole('button', { name: 'Lưu' }));

    expect(await screen.findByText('Đã tạo lịch làm việc cho BS. Tạ Công Minh')).toBeInTheDocument();
    expect(screen.getByText('Phòng khám 308')).toBeInTheDocument();
    expect(
      mockDb().schedules.some(
        (s) => s.actor?.[0]?.reference === 'Practitioner/pr-012' && s.comment === 'Phòng khám 308',
      ),
    ).toBe(true);
  });

  it('shows per-field validation errors on schedule creation and surfaces 409 / 400 server errors', async () => {
    const user = userEvent.setup();
    await openManageTab(user, 'nurse');

    await user.click(screen.getByRole('button', { name: 'Tạo lịch làm việc' }));
    const dialog = await screen.findByRole('dialog');

    // Empty submit triggers per-field errors for doctor and specialty.
    await user.click(within(dialog).getByRole('button', { name: 'Lưu' }));
    expect(await within(dialog).findByText('Vui lòng chọn bác sĩ.')).toBeInTheDocument();
    expect(within(dialog).getByText('Vui lòng chọn chuyên khoa.')).toBeInTheDocument();

    // Comment exceeding 500 chars shows per-field error.
    const commentBox = within(dialog).getByLabelText(/Ghi chú/);
    fireEvent.change(commentBox, { target: { value: 'a'.repeat(501) } });
    await user.click(within(dialog).getByRole('button', { name: 'Lưu' }));
    expect(await within(dialog).findByText('Ghi chú không được vượt quá 500 ký tự.')).toBeInTheDocument();
    fireEvent.change(commentBox, { target: { value: '' } });

    // Duplicate active schedule (pr-001 + cardiology) returns 409 from S1.
    await user.click(within(dialog).getByLabelText(/Bác sĩ/));
    await user.click(await screen.findByRole('option', { name: 'BS. Lê Minh Quân' }));
    await user.click(within(dialog).getByRole('button', { name: 'Lưu' }));
    expect(await within(dialog).findByText('Có lỗi xảy ra')).toBeInTheDocument();

    // Mismatched specialty (pr-012 + cardiology, when pr-012 only has gastroenterology) returns 400 from S1.
    await user.click(within(dialog).getByLabelText(/Bác sĩ/));
    await user.click(await screen.findByRole('option', { name: 'BS. Tạ Công Minh' }));
    await user.click(within(dialog).getByLabelText(/Chuyên khoa/));
    await user.click(await screen.findByRole('option', { name: /Tim mạch/ }));
    await user.click(within(dialog).getByRole('button', { name: 'Lưu' }));
    expect(await within(dialog).findByText('Dữ liệu không hợp lệ.')).toBeInTheDocument();
  });

  it('admin edits schedule active flag and comment with If-Match (S4)', async () => {
    const user = userEvent.setup();
    await openManageTab(user, 'admin');

    await user.click(screen.getByRole('button', { name: 'Sửa lịch của BS. Lê Minh Quân' }));
    const dialog = await screen.findByRole('dialog');

    const activeSwitch = within(dialog).getByLabelText('Kích hoạt lịch làm việc');
    await user.click(activeSwitch);
    const noteBox = within(dialog).getByLabelText(/Ghi chú/);
    await user.clear(noteBox);
    await user.type(noteBox, 'Phòng khám mới 299');
    await user.click(within(dialog).getByRole('button', { name: 'Lưu' }));

    expect(await screen.findByText('Đã cập nhật lịch làm việc')).toBeInTheDocument();
    expect(screen.getByText('Phòng khám mới 299')).toBeInTheDocument();
    const updated = mockDb().schedules.find((s) => s.id === 'sch-pr-001');
    expect(updated?.active).toBe(false);
    expect(updated?.meta?.versionId).toBe('2');
  });

  it('TC-AC-ST.1 weekly template editor shows per-field errors and saves valid shifts via S5', async () => {
    const user = userEvent.setup();
    let putCalls = 0;
    const onRequestStart = ({ request }: { request: Request }) => {
      if (request.method === 'PUT' && request.url.includes('/templates')) putCalls += 1;
    };
    server.events.on('request:start', onRequestStart);

    try {
      await openManageTab(user, 'nurse');
      await screen.findByRole('button', { name: 'Lưu mẫu tuần' });

      // Remove pre-seeded rows down to 1 shift so we can test validation deterministically.
      while (screen.queryAllByRole('group', { name: /Mẫu lịch tuần #/ }).length > 1) {
        const removeButtons = screen.getAllByRole('button', { name: /Xóa khung giờ dòng/ });
        await user.click(removeButtons[removeButtons.length - 1]!);
      }

      const row1 = screen.getByRole('group', { name: 'Mẫu lịch tuần #1' });
      const start1 = within(row1).getByLabelText('Giờ bắt đầu');
      const end1 = within(row1).getByLabelText('Giờ kết thúc');

      // 1. Uneven division: 08:00-11:20 / 15 min -> inline error per field, zero PUT calls.
      await user.clear(start1);
      await user.type(start1, '08:00');
      await user.clear(end1);
      await user.type(end1, '11:20');
      await user.click(screen.getByRole('button', { name: 'Lưu mẫu tuần' }));
      expect(await within(row1).findByText('Khoảng giờ phải chia hết cho 15 phút.')).toBeInTheDocument();
      expect(putCalls).toBe(0);

      // 2. End before start: 11:30-08:00 -> inline error per field.
      await user.clear(start1);
      await user.type(start1, '11:30');
      await user.clear(end1);
      await user.type(end1, '08:00');
      expect(await within(row1).findByText('Giờ kết thúc phải sau giờ bắt đầu.')).toBeInTheDocument();

      // 3. Valid 08:00-11:30 / 15 min + overlapping row -> overlap error on both rows.
      await user.clear(start1);
      await user.type(start1, '08:00');
      await user.clear(end1);
      await user.type(end1, '11:30');
      await user.click(screen.getByRole('button', { name: 'Thêm khung giờ' }));
      const row2 = screen.getByRole('group', { name: 'Mẫu lịch tuần #2' });
      const start2 = within(row2).getByLabelText('Giờ bắt đầu');
      const end2 = within(row2).getByLabelText('Giờ kết thúc');
      await user.clear(start2);
      await user.type(start2, '10:00');
      await user.clear(end2);
      await user.type(end2, '12:00');
      await user.click(screen.getByRole('button', { name: 'Lưu mẫu tuần' }));
      expect(await within(row1).findByText('Khung giờ bị trùng với dòng khác trong cùng thứ.')).toBeInTheDocument();
      expect(within(row2).getByText('Khung giờ bị trùng với dòng khác trong cùng thứ.')).toBeInTheDocument();

      // 4. Date range invalid (validTo < validFrom) -> inline error.
      await user.click(within(row2).getByRole('button', { name: /Xóa khung giờ dòng/ }));
      fireEvent.change(within(row1).getByLabelText('Hiệu lực từ'), { target: { value: '2026-11-10' } });
      fireEvent.change(within(row1).getByLabelText('Hiệu lực đến'), { target: { value: '2026-11-05' } });
      expect(await within(row1).findByText('Ngày kết thúc phải lớn hơn hoặc bằng ngày bắt đầu.')).toBeInTheDocument();

      // 5. Fix validTo and save valid template (14 slots/day) -> calls S5 PUT with If-Match and persists.
      fireEvent.change(within(row1).getByLabelText('Hiệu lực đến'), { target: { value: '2026-12-31' } });
      expect(within(row1).getByText('14 slot/ngày')).toBeInTheDocument();
      await user.click(screen.getByRole('button', { name: 'Lưu mẫu tuần' }));

      expect(await screen.findByText('Đã lưu mẫu lịch tuần')).toBeInTheDocument();
      await waitFor(() => expect(putCalls).toBe(1));
      expect(mockDb().templates['sch-pr-001']).toHaveLength(1);
      expect(mockDb().schedules.find((s) => s.id === 'sch-pr-001')?.meta?.versionId).toBe('2');
    } finally {
      server.events.removeListener('request:start', onRequestStart);
    }
  });

  it('surfaces 412 version-conflict error when template etag is stale', async () => {
    const user = userEvent.setup();
    await openManageTab(user, 'nurse');
    await screen.findByRole('button', { name: 'Lưu mẫu tuần' });

    // Simulate another user updating the schedule concurrently.
    const sch = mockDb().schedules.find((s) => s.id === 'sch-pr-001')!;
    sch.meta = { versionId: '99' };

    await user.click(screen.getByRole('button', { name: 'Lưu mẫu tuần' }));
    expect(await screen.findByText('Dữ liệu đã thay đổi, hãy tải lại.')).toBeInTheDocument();
  });
});

describe('schedule management permissions (doctor / patient)', () => {
  it('doctor sees only their own schedule and read-only weekly template', async () => {
    const user = userEvent.setup();
    await openManageTab(user, 'doctor');

    const main = screen.getByRole('main');
    expect(await within(main).findByText('BS. Lê Minh Quân')).toBeInTheDocument();
    expect(within(main).queryByText('BS. Phạm Thu Trang')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Tạo lịch làm việc' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Sửa lịch/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Thêm khung giờ' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Lưu mẫu tuần' })).not.toBeInTheDocument();
    expect(screen.queryByLabelText('Lọc theo bác sĩ')).not.toBeInTheDocument();
    expect(await within(main).findAllByText('08:00')).toHaveLength(3);
  });

  it('patient is forbidden from /schedule', async () => {
    renderApp('/schedule', 'patient');
    expect(await screen.findByText('Bạn không có quyền xem trang này.')).toBeInTheDocument();
  });
});

describe('weekly slot calendar: generate, block, unblock (S7, S9, S10)', () => {
  // The calendar defaults to the first practitioner; pin three known slots on Wednesday 04/11/2026.
  function pinSlots() {
    const db = mockDb();
    const pid = db.practitioners[0]!.id!;
    const at = (hhmm: string) => db.slots.find((s) => s.id === `s-${pid}-20261104${hhmm}`)!;
    const free = at('0800');
    const booked = at('0830');
    const blocked = at('0900');
    free.status = 'free';
    booked.status = 'busy';
    blocked.status = 'busy-unavailable';
    blocked.comment = 'Hội nghị';
    return { free, booked, blocked };
  }
  const cell = (time: string) =>
    screen.findByRole('button', { name: new RegExp(`: quản lý slot ${time} ngày 04/11/2026$`) });

  it('nurse blocks a free slot with a reason (S9) and unblocks it again (S10)', async () => {
    const { free } = pinSlots();
    const user = userEvent.setup();
    renderApp('/schedule', 'nurse');

    await user.click(await cell('08:00'));
    const dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getByRole('button', { name: 'Chặn slot' }));
    expect(await within(dialog).findByText('Vui lòng nhập lý do (1–200 ký tự).')).toBeInTheDocument();
    await user.type(within(dialog).getByLabelText('Lý do chặn'), 'Bác sĩ nghỉ phép');
    await user.click(within(dialog).getByRole('button', { name: 'Chặn slot' }));

    expect(await screen.findByText('Đã chặn slot')).toBeInTheDocument();
    expect(free.status).toBe('busy-unavailable');
    expect(free.comment).toBe('Bác sĩ nghỉ phép');

    await user.click(await cell('08:00'));
    const second = await screen.findByRole('dialog');
    await user.click(within(second).getByRole('button', { name: 'Mở chặn slot' }));
    expect(await screen.findByText('Đã mở chặn slot')).toBeInTheDocument();
    expect(free.status).toBe('free');
  });

  it('TC-AC-02.2 blocking a booked slot shows the 409 error and keeps the slot booked', async () => {
    const { booked } = pinSlots();
    const user = userEvent.setup();
    renderApp('/schedule', 'admin');

    await user.click(await cell('08:30'));
    const dialog = await screen.findByRole('dialog');
    await user.type(within(dialog).getByLabelText('Lý do chặn'), 'Nghỉ đột xuất');
    await user.click(within(dialog).getByRole('button', { name: 'Chặn slot' }));

    expect(
      await within(dialog).findByText('Slot đã có lịch hẹn, hãy hủy hoặc đổi lịch hẹn trước.'),
    ).toBeInTheDocument();
    expect(booked.status).toBe('busy');
  });

  it('nurse generates slots for the selected doctor (S7) and the result is reported', async () => {
    pinSlots();
    const user = userEvent.setup();
    renderApp('/schedule', 'nurse');

    const generate = await screen.findByRole('button', { name: 'Sinh slot' });
    await waitFor(() => expect(generate).toBeEnabled());
    const before = mockDb().slots.length;
    await user.click(generate);
    const dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText('Từ ngày'), { target: { value: '2026-11-04' } });
    fireEvent.change(within(dialog).getByLabelText('Đến ngày'), { target: { value: '2026-11-06' } });
    await user.click(within(dialog).getByRole('button', { name: 'Sinh slot' }));

    expect(await screen.findByText(/Đã sinh \d+ slot \(\d+ slot đã có\)/)).toBeInTheDocument();
    expect(mockDb().slots.length).toBeGreaterThanOrEqual(before);
  });

  it('generation outside the allowed range shows the server error', async () => {
    pinSlots();
    const user = userEvent.setup();
    renderApp('/schedule', 'nurse');

    const generate = await screen.findByRole('button', { name: 'Sinh slot' });
    await waitFor(() => expect(generate).toBeEnabled());
    await user.click(generate);
    const dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText('Đến ngày'), { target: { value: '2027-03-01' } });
    await user.click(within(dialog).getByRole('button', { name: 'Sinh slot' }));
    expect(await within(dialog).findByText('Dữ liệu không hợp lệ.')).toBeInTheDocument();
  });

  it('doctor views the own calendar read-only: no slot buttons, no generate button', async () => {
    pinSlots();
    renderApp('/schedule', 'doctor');
    await screen.findAllByText('Đã chặn');
    expect(screen.queryByRole('button', { name: /Quản lý slot/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Sinh slot' })).not.toBeInTheDocument();
  });
});
