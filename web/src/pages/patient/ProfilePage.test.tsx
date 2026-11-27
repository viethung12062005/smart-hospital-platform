import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { beforeEach, describe, expect, it } from 'vitest';
import { mockDb, resetMockDb } from '@/mocks/handlers';
import { server } from '@/mocks/server';
import { renderApp } from '@/test/render';
import { validateRegistration } from './registrationForm';

beforeEach(() => {
  resetMockDb();
});

/** A signed-in patient account that has no medical record yet (no fhirUser). */
function unregistered(): void {
  server.use(http.get('/api/v1/me', () => HttpResponse.json({ sub: 'mock-patient', roles: ['patient'] })));
}

async function fillForm(user: ReturnType<typeof userEvent.setup>, phone: string, birthDate: string): Promise<void> {
  await user.type(await screen.findByLabelText(/^Họ/), 'Pham');
  await user.type(screen.getByLabelText(/^Tên đệm/), 'Minh Chau');
  await user.type(screen.getByLabelText(/^Ngày sinh/), birthDate);
  await user.click(screen.getByRole('textbox', { name: /^Giới tính/ }));
  await user.click(await screen.findByRole('option', { name: 'Nữ' }));
  await user.type(screen.getByLabelText(/^Điện thoại/), phone);
}

describe('registration form validation', () => {
  it('rejects empty, malformed and impossible values', () => {
    const e = validateRegistration(
      { family: '', given: '', phone: '123', email: 'x', gender: '', birthDate: '2999-01-01', language: 'vi' },
      new Date(2026, 10, 4),
    );
    expect(Object.keys(e).sort()).toEqual(['birthDate', 'email', 'family', 'gender', 'given', 'phone']);
    expect(
      validateRegistration(
        { family: 'A', given: 'B', phone: '+84901234567', gender: 'male', birthDate: '1985-03-14', language: 'vi' },
        new Date(2026, 10, 4),
      ),
    ).toEqual({});
  });
});

// TC-AC-01.2: duplicate phone + date of birth is refused without creating a record or leaking the old one.
describe('self-registration (patient)', () => {
  it('registers a new account and creates the record', async () => {
    unregistered();
    const user = userEvent.setup();
    renderApp('/profile', 'patient');
    const before = mockDb().patients.length;

    await fillForm(user, '0911222333', '1990-05-20');
    await user.click(screen.getByLabelText(/đồng ý với điều khoản/));
    await user.click(screen.getByRole('button', { name: 'Đăng ký' }));

    await waitFor(() => expect(mockDb().patients).toHaveLength(before + 1));
    expect(mockDb().patients.at(-1)?.name?.[0]?.family).toBe('Pham');
  });

  it('shows the duplicate notice on 422 and creates nothing', async () => {
    unregistered();
    const user = userEvent.setup();
    renderApp('/profile', 'patient');
    const existing = mockDb().patients[0]!;
    const before = mockDb().patients.length;

    await fillForm(user, existing.telecom![0]!.value!, existing.birthDate!);
    await user.click(screen.getByLabelText(/đồng ý với điều khoản/));
    await user.click(screen.getByRole('button', { name: 'Đăng ký' }));

    expect(await screen.findByText('Có thể đã có hồ sơ')).toBeInTheDocument();
    expect(mockDb().patients).toHaveLength(before);
  });

  it('does not submit without the terms consent', async () => {
    unregistered();
    renderApp('/profile', 'patient');
    expect(await screen.findByRole('button', { name: 'Đăng ký' })).toBeDisabled();
  });

  it('shows a field error for an invalid phone number', async () => {
    unregistered();
    const user = userEvent.setup();
    renderApp('/profile', 'patient');
    await fillForm(user, '123', '1990-05-20');
    await user.click(screen.getByLabelText(/đồng ý với điều khoản/));
    await user.click(screen.getByRole('button', { name: 'Đăng ký' }));
    expect(await screen.findByText(/Nhập số điện thoại Việt Nam hợp lệ/)).toBeInTheDocument();
  });
});

// TC-AC-01.1 / TC-AC-01.3: a patient sees only their own record; each edit is a new version.
describe('my profile (patient)', () => {
  it('shows the own record with the identity fields locked', async () => {
    renderApp('/profile', 'patient');
    const name = await screen.findByLabelText('Họ tên');
    expect(name).toHaveValue('Nguyễn Thị Lan');
    expect(name).toBeDisabled();
    expect(screen.getByLabelText('Ngày sinh')).toBeDisabled();
    expect(screen.getByLabelText('Giới tính')).toBeDisabled();
    expect(screen.getByLabelText('Mã BN')).toBeDisabled();
    expect(screen.getByLabelText(/^Điện thoại/)).toBeEnabled();
  });

  it('saves a new phone number as a new version', async () => {
    const user = userEvent.setup();
    renderApp('/profile', 'patient');
    const phone = await screen.findByLabelText(/^Điện thoại/);
    await user.clear(phone);
    await user.type(phone, '0987654321');
    await user.click(screen.getByRole('button', { name: 'Lưu' }));

    expect(await screen.findByText('Đã lưu hồ sơ')).toBeInTheDocument();
    const p = mockDb().patients.find((x) => x.id === 'p-001')!;
    expect(p.meta?.versionId).toBe('2');
    expect(p.telecom?.find((t) => t.system === 'phone')?.value).toBe('0987654321');
  });

  it('keeps the form and reports a stale version (412)', async () => {
    const user = userEvent.setup();
    renderApp('/profile', 'patient');
    const phone = await screen.findByLabelText(/^Điện thoại/);
    mockDb().patients.find((x) => x.id === 'p-001')!.meta = { versionId: '5' };
    await user.clear(phone);
    await user.type(phone, '0987654321');
    await user.click(screen.getByRole('button', { name: 'Lưu' }));
    expect(await screen.findByText('Dữ liệu đã thay đổi, hãy tải lại.')).toBeInTheDocument();
  });
});

describe('front-desk registration (nurse)', () => {
  it('creates a record and offers to open it', async () => {
    const user = userEvent.setup();
    renderApp('/registration', 'nurse');
    const before = mockDb().patients.length;
    await fillForm(user, '0933444555', '1975-01-02');
    await user.click(screen.getByRole('button', { name: 'Đăng ký' }));
    expect(await screen.findByRole('link', { name: 'Mở hồ sơ' })).toBeInTheDocument();
    expect(mockDb().patients).toHaveLength(before + 1);
  });

  it('reports a duplicate on 422', async () => {
    const user = userEvent.setup();
    renderApp('/registration', 'nurse');
    const existing = mockDb().patients[0]!;
    const before = mockDb().patients.length;
    await fillForm(user, existing.telecom![0]!.value!, existing.birthDate!);
    await user.click(screen.getByRole('button', { name: 'Đăng ký' }));
    expect(await screen.findByText('Có thể đã có hồ sơ')).toBeInTheDocument();
    expect(mockDb().patients).toHaveLength(before);
  });

  it('is not available to a patient', async () => {
    renderApp('/registration', 'patient');
    expect(await screen.findByText('Bạn không có quyền xem trang này.')).toBeInTheDocument();
  });
});
