import { screen, waitFor } from '@testing-library/react';
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
});
