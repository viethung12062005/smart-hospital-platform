import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { signInMock } from '@/auth/session';
import { setLanguage } from '@/i18n';
import { server } from '@/mocks/server';
import { ApiError, apiRequest, etagOf, NetworkError } from './client';

const url = (p: string) => `${window.location.origin}${p}`;

describe('apiRequest', () => {
  it('gửi token, Accept-Language, Idempotency-Key và If-Match', async () => {
    signInMock('patient');
    setLanguage('en');
    let seen: Headers | undefined;
    server.use(
      http.post(url('/fhir/Appointment/a-1/$cancel'), ({ request }) => {
        seen = request.headers;
        return HttpResponse.json({ resourceType: 'Appointment', id: 'a-1' }, { headers: { ETag: 'W/"2"' } });
      }),
    );
    const res = await apiRequest('/fhir/Appointment/a-1/$cancel', {
      method: 'POST',
      body: { resourceType: 'Parameters' },
      idempotencyKey: 'key-123',
      ifMatch: 'W/"1"',
    });
    setLanguage('vi');
    expect(seen?.get('Authorization')).toMatch(/^Bearer mock\./);
    expect(seen?.get('Accept-Language')).toBe('en');
    expect(seen?.get('Idempotency-Key')).toBe('key-123');
    expect(seen?.get('If-Match')).toBe('W/"1"');
    expect(seen?.get('Content-Type')).toBe('application/fhir+json');
    expect(res.etag).toBe('W/"2"');
  });

  it('gửi form-urlencoded cho _search', async () => {
    let body = '';
    let type = '';
    server.use(
      http.post(url('/fhir/Patient/_search'), async ({ request }) => {
        body = await request.text();
        type = request.headers.get('Content-Type') ?? '';
        return HttpResponse.json({ resourceType: 'Bundle', type: 'searchset' });
      }),
    );
    await apiRequest('/fhir/Patient/_search', { method: 'POST', form: new URLSearchParams({ name: 'lan' }) });
    expect(type).toBe('application/x-www-form-urlencoded');
    expect(body).toBe('name=lan');
  });

  it('đọc OperationOutcome: mã, thông điệp, mã truy vết và slot gợi ý', async () => {
    server.use(
      http.post(url('/fhir/Appointment/$book'), () =>
        HttpResponse.json(
          {
            resourceType: 'OperationOutcome',
            issue: [
              {
                severity: 'error',
                code: 'conflict',
                diagnostics: 'Giờ khám này không còn trống.',
                details: { coding: [{ code: 'slot-unavailable' }] },
              },
            ],
            extension: [
              {
                url: 'https://shp.example.org/fhir/StructureDefinition/suggested-slots',
                extension: [{ url: 'slot', valueReference: { reference: 'Slot/s-9' } }],
              },
            ],
          },
          { status: 409, headers: { 'X-Request-Id': 'trace-1' } },
        ),
      ),
    );
    const err = await apiRequest('/fhir/Appointment/$book', { method: 'POST', body: {} }).catch((e: unknown) => e);
    expect(err).toBeInstanceOf(ApiError);
    const e = err as ApiError;
    expect(e.status).toBe(409);
    expect(e.code).toBe('slot-unavailable');
    expect(e.message).toBe('Giờ khám này không còn trống.');
    expect(e.traceId).toBe('trace-1');
    expect(e.suggestedSlots).toEqual(['Slot/s-9']);
  });

  it('đọc Problem Details cho /api', async () => {
    server.use(
      http.get(url('/api/v1/me'), () =>
        HttpResponse.json(
          {
            type: 'x',
            title: 'unauthenticated',
            status: 401,
            code: 'unauthenticated',
            detail: 'Chưa đăng nhập',
          },
          { status: 401 },
        ),
      ),
    );
    const e = (await apiRequest('/api/v1/me').catch((x: unknown) => x)) as ApiError;
    expect(e.code).toBe('unauthenticated');
    expect(e.message).toBe('Chưa đăng nhập');
  });

  it('báo lỗi mạng riêng', async () => {
    server.use(http.get(url('/api/v1/me'), () => HttpResponse.error()));
    await expect(apiRequest('/api/v1/me')).rejects.toBeInstanceOf(NetworkError);
  });

  it('tạo ETag từ meta.versionId', () => {
    expect(etagOf({ resourceType: 'Appointment', meta: { versionId: '3' } } as never)).toBe('W/"3"');
    expect(etagOf(undefined)).toBeUndefined();
  });
});
