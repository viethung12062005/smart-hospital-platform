import { http, HttpResponse } from 'msw';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { server } from '@/mocks/server';
import { ApiError, apiRequest } from './client';

// WEB-02: when the session expires, the client renews it once and replays the request; if it cannot, the user is
// sent to sign in. The session module is mocked so both outcomes can be driven from the test.
const session = vi.hoisted(() => ({
  accessToken: vi.fn<() => Promise<string | undefined>>(),
  renewAccessToken: vi.fn<() => Promise<string | undefined>>(),
  onSessionExpired: vi.fn<() => Promise<void>>(),
}));
vi.mock('@/auth/session', () => session);

const url = (p: string) => `${window.location.origin}${p}`;
const unauthenticated = () =>
  HttpResponse.json(
    { type: 'x', title: 'unauthenticated', status: 401, code: 'unauthenticated', detail: 'Authentication required.' },
    { status: 401 },
  );

describe('apiRequest when the session expires', () => {
  beforeEach(() => {
    session.accessToken.mockReset().mockResolvedValue('old-token');
    session.renewAccessToken.mockReset();
    session.onSessionExpired.mockReset().mockResolvedValue();
  });

  it('renews the token once and replays the request with the same Idempotency-Key', async () => {
    session.renewAccessToken.mockResolvedValue('new-token');
    const seen: { auth: string | null; key: string | null }[] = [];
    server.use(
      http.post(url('/fhir/Appointment/$book'), ({ request }) => {
        seen.push({ auth: request.headers.get('Authorization'), key: request.headers.get('Idempotency-Key') });
        return request.headers.get('Authorization') === 'Bearer new-token'
          ? HttpResponse.json({ resourceType: 'Appointment', id: 'a-9' }, { status: 201 })
          : unauthenticated();
      }),
    );

    const res = await apiRequest<{ id: string }>('/fhir/Appointment/$book', {
      method: 'POST',
      body: { resourceType: 'Parameters' },
      idempotencyKey: 'click-1',
    });

    expect(res.status).toBe(201);
    expect(res.data.id).toBe('a-9');
    expect(seen).toEqual([
      { auth: 'Bearer old-token', key: 'click-1' },
      { auth: 'Bearer new-token', key: 'click-1' },
    ]);
    expect(session.renewAccessToken).toHaveBeenCalledTimes(1);
    expect(session.onSessionExpired).not.toHaveBeenCalled();
  });

  it('sends the user to sign in when the session cannot be renewed', async () => {
    session.renewAccessToken.mockResolvedValue(undefined);
    let calls = 0;
    server.use(
      http.get(url('/api/v1/me'), () => {
        calls++;
        return unauthenticated();
      }),
    );

    const e = (await apiRequest('/api/v1/me').catch((x: unknown) => x)) as ApiError;

    expect(e).toBeInstanceOf(ApiError);
    expect(e.code).toBe('unauthenticated');
    expect(calls).toBe(1); // no replay without a new token
    expect(session.onSessionExpired).toHaveBeenCalledTimes(1);
  });

  it('replays at most once, even if the renewed token is also rejected', async () => {
    session.renewAccessToken.mockResolvedValue('still-bad');
    let calls = 0;
    server.use(
      http.get(url('/api/v1/me'), () => {
        calls++;
        return unauthenticated();
      }),
    );

    await expect(apiRequest('/api/v1/me')).rejects.toBeInstanceOf(ApiError);
    expect(calls).toBe(2);
    expect(session.renewAccessToken).toHaveBeenCalledTimes(1);
  });

  it('leaves other errors alone', async () => {
    server.use(http.get(url('/fhir/Patient/x'), () => HttpResponse.json({}, { status: 403 })));
    await expect(apiRequest('/fhir/Patient/x')).rejects.toBeInstanceOf(ApiError);
    expect(session.renewAccessToken).not.toHaveBeenCalled();
  });
});
