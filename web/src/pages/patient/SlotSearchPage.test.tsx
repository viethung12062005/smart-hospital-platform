import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { nextPageToken } from '@/api/scheduling';
import { vnDay, vnTime } from '@/api/vnTime';
import { mockDb, resetMockDb } from '@/mocks/handlers';
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

describe('Vietnam time helpers', () => {
  it('shows an instant in UTC+7 whatever the browser zone is', () => {
    expect(vnTime('2026-11-05T01:30:00Z')).toBe('08:30');
    expect(vnDay('2026-11-05T18:00:00Z')).toBe('2026-11-06');
  });

  it('reads the page token of the next link', () => {
    const bundle = {
      resourceType: 'Bundle' as const,
      type: 'searchset' as const,
      link: [{ relation: 'next', url: 'https://x.example/fhir/Slot?status=free&_page_token=abc%3D' }],
    };
    expect(nextPageToken(bundle)).toBe('abc=');
    expect(nextPageToken({ ...bundle, link: [] })).toBeUndefined();
  });
});

describe('slot search screen', () => {
  it('asks for a specialty first, then lists free slots by day and pages with "Xem thêm"', async () => {
    const user = userEvent.setup();
    renderApp('/slots', 'patient');

    expect(await screen.findByText('Chọn chuyên khoa để tìm.')).toBeInTheDocument();
    await user.click(screen.getByRole('textbox', { name: 'Chuyên khoa' }));
    await user.click(await screen.findByRole('option', { name: /Tim mạch/i }));

    const firstPage = await screen.findAllByRole('listitem');
    expect(firstPage).toHaveLength(20);
    const free = mockDb().slots.filter(
      (s) => s.serviceType?.[0]?.coding?.[0]?.code === 'cardiology' && s.status === 'free',
    );
    expect(free.length).toBeGreaterThan(20);

    await user.click(screen.getByRole('button', { name: 'Xem thêm' }));
    await waitFor(() => expect(screen.getAllByRole('listitem').length).toBeGreaterThan(20));
  });

  /** Opens the range picker, moves to December 2026 and returns the button of a December day. */
  async function decemberDay(user: ReturnType<typeof userEvent.setup>, day: number) {
    await user.click(await screen.findByRole('button', { name: 'Khoảng ngày' }));
    const dialog = await screen.findByRole('dialog');
    await user.click(dialog.querySelector<HTMLButtonElement>('[data-direction="next"]')!);
    await within(dialog).findByText(/12\/2026|tháng 12|December/i);
    const buttons = within(dialog)
      .getAllByRole('button')
      .filter((b) => b.textContent === String(day) && !b.hasAttribute('data-outside'));
    expect(buttons).toHaveLength(1);
    return buttons[0]!;
  }

  it('limits a patient to today + 30 days (the server cap)', async () => {
    const user = userEvent.setup();
    renderApp('/slots', 'patient');
    // Today is 04/11/2026: 04/12 is the last day a patient can pick.
    expect(await decemberDay(user, 4)).not.toHaveAttribute('data-disabled');
    expect(await decemberDay(user, 5)).toHaveAttribute('data-disabled');
  });

  it('leaves the range open for staff', async () => {
    const user = userEvent.setup();
    renderApp('/slots', 'nurse');
    expect(await decemberDay(user, 5)).not.toHaveAttribute('data-disabled');
  });

  it('starts the default range on the Vietnam day, not the browser day', async () => {
    // 18:30 UTC on 04/11 is already 05/11 01:30 in Vietnam, so the range starts tomorrow in Vietnam: 06/11.
    vi.setSystemTime(new Date('2026-11-04T18:30:00Z'));
    renderApp('/slots', 'patient');
    expect(await screen.findByRole('button', { name: 'Khoảng ngày' })).toHaveTextContent('06/11/2026 – 12/11/2026');
  });
});
