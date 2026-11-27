import type { Appointment, Bundle, OperationOutcome, Parameters, Resource, Schedule } from '@medplum/fhirtypes';
import dayjs from 'dayjs';
import { http, HttpResponse, type HttpResponseResolver } from 'msw';
import type { ScheduleTemplateInput } from '@/api/scheduling';
import { SPECIALTIES } from '@/api/terminology';
import { decodeJwtPayload, rolesFromClaims, type Role } from '@/auth/roles';
import { MOCK_USERS } from '@/auth/session';
import {
  hasTemplateErrors,
  MAX_COMMENT_LENGTH,
  type TemplateRowDraft,
  validateTemplateRows,
} from '@/pages/staff/scheduleValidation';
import { aiHandlers, resetAiMock } from './ai';
import { createDb, type MockDb } from './data';

/**
 * Mock server for mock mode and tests. Mimics the 08-api-scheduling / 09-api-clinical contracts closely enough for the UI:
 * error format (OperationOutcome), Idempotency-Key, If-Match/ETag, rules BR-02/03/04 and 409 with suggested slots.
 * It does not replace backend tests; once the backend has real endpoints, run `npm run dev` (no mock) to try them.
 */
let db: MockDb = createDb();
const idempotency = new Map<string, { body: string; appointmentId: string }>();
let scheduleSeq = 0;

export function resetMockDb(): void {
  db = createDb();
  idempotency.clear();
  scheduleSeq = 0;
  resetAiMock();
}

export function mockDb(): MockDb {
  return db;
}

const MESSAGES: Record<string, [string, string]> = {
  'slot-unavailable': ['Giờ khám này không còn trống.', 'This time slot is no longer available.'],
  'max-future-appointments': ['Bạn đã có 3 lịch hẹn sắp tới.', 'You already have 3 upcoming appointments.'],
  'outside-booking-window': ['Chỉ đặt được lịch trong 30 ngày tới.', 'You can only book within the next 30 days.'],
  'cancellation-too-late': [
    'Chỉ hủy được trước giờ khám ít nhất 2 giờ.',
    'You can only cancel at least 2 hours before.',
  ],
  'invalid-state-transition': [
    'Lịch hẹn không còn ở trạng thái cho phép thao tác này.',
    'The appointment state does not allow this.',
  ],
  'idempotency-key-required': ['Thiếu Idempotency-Key.', 'Idempotency-Key is required.'],
  'idempotency-key-reused': [
    'Idempotency-Key đã dùng cho yêu cầu khác.',
    'Idempotency-Key was used for a different request.',
  ],
  'precondition-required': ['Thiếu If-Match.', 'If-Match is required.'],
  'version-conflict': ['Dữ liệu đã thay đổi, hãy tải lại.', 'The record changed, please reload.'],
  'business-rule': [
    'Ngoài khung giờ check-in (60 phút trước đến 30 phút sau giờ hẹn).',
    'Outside the check-in window.',
  ],
  forbidden: ['Bạn không có quyền thực hiện thao tác này.', 'You are not allowed to do this.'],
  'not-found': ['Không tìm thấy.', 'Not found.'],
  unauthenticated: ['Chưa đăng nhập.', 'Not signed in.'],
  'validation-failed': ['Dữ liệu không hợp lệ.', 'Validation failed.'],
  'slot-has-appointment': [
    'Slot đã có lịch hẹn, hãy hủy hoặc đổi lịch hẹn trước.',
    'The slot has an appointment; cancel or reschedule it first.',
  ],
  'schedule-inactive': ['Lịch làm việc không còn hoạt động.', 'The schedule is inactive.'],
};

function problemJson(request: Request, status: number, code: string) {
  const en = request.headers.get('Accept-Language')?.startsWith('en');
  const msg = MESSAGES[code] ?? ['Lỗi', 'Error'];
  const detail = en ? msg[1] : msg[0];
  return HttpResponse.json(
    { type: `https://shp.example.org/problems/${code}`, title: detail, status, code, detail },
    {
      status,
      headers: { 'Content-Type': 'application/problem+json', 'X-Request-Id': `mock-${Date.now().toString(16)}` },
    },
  );
}

const canReadSchedule = (me: Caller) =>
  me.roles.includes('nurse') || me.roles.includes('admin') || me.roles.includes('doctor');
const canManageSchedule = (me: Caller) => me.roles.includes('nurse') || me.roles.includes('admin');
const isDoctorOnly = (me: Caller) => me.roles.includes('doctor') && !canManageSchedule(me);

function outcome(request: Request, status: number, code: string, extra: Partial<OperationOutcome> = {}) {
  const en = request.headers.get('Accept-Language')?.startsWith('en');
  const msg = MESSAGES[code] ?? ['Lỗi', 'Error'];
  const body: OperationOutcome = {
    resourceType: 'OperationOutcome',
    issue: [
      {
        severity: 'error',
        code: 'processing',
        diagnostics: en ? msg[1] : msg[0],
        details: { coding: [{ system: 'https://shp.example.org/fhir/CodeSystem/error', code }] },
      },
    ],
    ...extra,
  };
  return HttpResponse.json(body, { status, headers: { 'X-Request-Id': `mock-${Date.now().toString(16)}` } });
}

function bundle(resources: Resource[]): Bundle {
  return {
    resourceType: 'Bundle',
    type: 'searchset',
    total: resources.length,
    entry: resources.map((resource) => ({ resource })),
  };
}

function withEtag(resource: Resource, status = 200) {
  return HttpResponse.json(resource, { status, headers: { ETag: `W/"${resource.meta?.versionId ?? '1'}"` } });
}

interface Caller {
  sub: string;
  roles: Role[];
  fhirUser?: string;
}

function caller(request: Request): Caller | undefined {
  const auth = request.headers.get('Authorization');
  if (!auth?.startsWith('Bearer ')) return undefined;
  const claims = decodeJwtPayload(auth.slice(7));
  const roles = rolesFromClaims(claims);
  const known = Object.values(MOCK_USERS).find((u) => u.sub === claims['sub']);
  return { sub: String(claims['sub'] ?? ''), roles, fhirUser: known?.fhirUser };
}

/** Wrap a handler: requires sign-in like the real gateway. */
function authed(
  resolver: (args: { request: Request; params: Record<string, string>; me: Caller }) => Response | Promise<Response>,
): HttpResponseResolver {
  return ({ request, params }) => {
    const me = caller(request);
    if (!me) return outcome(request, 401, 'unauthenticated');
    return resolver({ request, params: params as Record<string, string>, me });
  };
}

/** Filter dates by a parameter's ge/gt/le/lt values. */
function inRange(value: string | undefined, filters: string[]): boolean {
  if (!value) return false;
  const v = dayjs(value);
  return filters.every((f) => {
    const op = f.slice(0, 2);
    const d = dayjs(f.slice(2));
    if (op === 'ge') return !v.isBefore(d);
    if (op === 'gt') return v.isAfter(d);
    if (op === 'le') return !v.isAfter(d);
    if (op === 'lt') return v.isBefore(d);
    return true;
  });
}

const strip = (s: string) => s.normalize('NFD').replace(/[̀-ͯ]/g, '').replace(/đ/gi, 'd').toLowerCase();

function isFutureBooked(a: Appointment): boolean {
  return a.status === 'booked' && dayjs(a.start).isAfter(dayjs());
}

export const handlers = [
  ...aiHandlers,

  http.get(
    '/api/v1/me',
    authed(({ me }) => HttpResponse.json({ sub: me.sub, roles: me.roles, fhirUser: me.fhirUser })),
  ),

  http.get(
    '/fhir/PractitionerRole',
    authed(({ request }) => {
      const specialty = new URL(request.url).searchParams.get('specialty');
      const roles = db.roles.filter((r) => !specialty || r.specialty?.[0]?.coding?.[0]?.code === specialty);
      const ids = new Set(roles.map((r) => r.practitioner?.reference));
      return HttpResponse.json(bundle([...roles, ...db.practitioners.filter((p) => ids.has(`Practitioner/${p.id}`))]));
    }),
  ),

  http.get(
    '/fhir/Practitioner',
    authed(() => HttpResponse.json(bundle(db.practitioners))),
  ),

  http.get(
    '/fhir/Schedule',
    authed(({ request, me }) => {
      if (!canReadSchedule(me)) return outcome(request, 403, 'forbidden');
      const qs = new URL(request.url).searchParams;
      const actorRaw = qs.get('actor');
      let actor = actorRaw ? (actorRaw.startsWith('Practitioner/') ? actorRaw : `Practitioner/${actorRaw}`) : null;
      if (isDoctorOnly(me)) {
        if (actor && actor !== me.fhirUser) return outcome(request, 403, 'forbidden');
        actor = me.fhirUser ?? '__none__';
      }
      const specRaw = qs.get('specialty');
      const specialty = specRaw ? specRaw.split('|').pop()?.toLowerCase() : null;
      const activeRaw = qs.get('active');
      const list = db.schedules.filter(
        (s) =>
          (!actor || s.actor?.[0]?.reference === actor) &&
          (!specialty || s.specialty?.[0]?.coding?.[0]?.code?.toLowerCase() === specialty) &&
          (activeRaw === null || String(!!s.active) === activeRaw),
      );
      return HttpResponse.json(bundle(list));
    }),
  ),

  http.get(
    '/fhir/Schedule/:id',
    authed(({ request, params, me }) => {
      if (!canReadSchedule(me)) return outcome(request, 403, 'forbidden');
      const s = db.schedules.find((x) => x.id === params['id']);
      if (!s) return outcome(request, 404, 'not-found');
      if (isDoctorOnly(me) && s.actor?.[0]?.reference !== me.fhirUser) return outcome(request, 403, 'forbidden');
      return withEtag(s);
    }),
  ),

  http.post(
    '/fhir/Schedule',
    authed(async ({ request, me }) => {
      if (!canManageSchedule(me)) return outcome(request, 403, 'forbidden');
      const body = (await request.json()) as Partial<Schedule>;
      if (body.actor?.length !== 1) return outcome(request, 400, 'validation-failed');
      const actorRef = body.actor[0]?.reference;
      const specCode = body.specialty?.[0]?.coding?.[0]?.code?.trim().toLowerCase();
      if (!actorRef || !specCode || !SPECIALTIES.some((sp) => sp.code === specCode)) {
        return outcome(request, 400, 'validation-failed');
      }
      const hasRole = db.roles.some(
        (r) =>
          r.active !== false &&
          r.practitioner?.reference === actorRef &&
          r.specialty?.[0]?.coding?.[0]?.code === specCode,
      );
      if (!hasRole) return outcome(request, 400, 'validation-failed');
      const comment = body.comment?.trim();
      if (comment && comment.length > MAX_COMMENT_LENGTH) return outcome(request, 400, 'validation-failed');

      const duplicate = db.schedules.some(
        (s) => s.active && s.actor?.[0]?.reference === actorRef && s.specialty?.[0]?.coding?.[0]?.code === specCode,
      );
      if (duplicate) return outcome(request, 409, 'invalid-state-transition');

      const practitioner = db.practitioners.find((p) => `Practitioner/${p.id}` === actorRef);
      const displayName =
        body.actor[0]?.display ??
        (practitioner
          ? [
              ...(practitioner.name?.[0]?.prefix ?? []),
              practitioner.name?.[0]?.family,
              ...(practitioner.name?.[0]?.given ?? []),
            ]
              .filter(Boolean)
              .join(' ')
          : actorRef);
      const now = dayjs().startOf('day');
      const created: Schedule = {
        resourceType: 'Schedule',
        id: `sch-new-${++scheduleSeq}`,
        meta: { versionId: '1', lastUpdated: new Date().toISOString() },
        active: typeof body.active === 'boolean' ? body.active : true,
        actor: [{ reference: actorRef, display: displayName }],
        specialty: body.specialty,
        planningHorizon: {
          start: now.toISOString(),
          end: now.add(30, 'day').hour(23).minute(59).second(59).toISOString(),
        },
        ...(comment ? { comment } : {}),
      };
      db.schedules.push(created);
      db.templates[created.id!] = [];
      return withEtag(created, 201);
    }),
  ),

  http.put(
    '/fhir/Schedule/:id',
    authed(async ({ request, params, me }) => {
      if (!canManageSchedule(me)) return outcome(request, 403, 'forbidden');
      const idx = db.schedules.findIndex((x) => x.id === params['id']);
      const existing = idx >= 0 ? db.schedules[idx] : undefined;
      if (!existing) return outcome(request, 404, 'not-found');
      const ifMatch = request.headers.get('If-Match');
      if (!ifMatch) return outcome(request, 428, 'precondition-required');
      if (ifMatch !== `W/"${existing.meta?.versionId ?? '1'}"`) return outcome(request, 412, 'version-conflict');

      const body = (await request.json()) as Partial<Schedule>;
      if (typeof body.active !== 'boolean') return outcome(request, 400, 'validation-failed');
      const existingActor = existing.actor?.[0]?.reference;
      const existingSpec = existing.specialty?.[0]?.coding?.[0]?.code;
      if (
        body.actor?.length !== 1 ||
        body.actor[0]?.reference !== existingActor ||
        body.specialty?.[0]?.coding?.[0]?.code !== existingSpec
      ) {
        return outcome(request, 400, 'validation-failed');
      }
      const comment = body.comment?.trim();
      if (comment && comment.length > MAX_COMMENT_LENGTH) return outcome(request, 400, 'validation-failed');
      if (body.active) {
        const dup = db.schedules.some(
          (s) =>
            s.id !== existing.id &&
            s.active &&
            s.actor?.[0]?.reference === existingActor &&
            s.specialty?.[0]?.coding?.[0]?.code === existingSpec,
        );
        if (dup) return outcome(request, 409, 'invalid-state-transition');
      }

      const nextVersion = String(Number(existing.meta?.versionId ?? '1') + 1);
      const updated: Schedule = {
        ...existing,
        meta: { versionId: nextVersion, lastUpdated: new Date().toISOString() },
        active: body.active,
        ...(comment ? { comment } : {}),
      };
      if (!comment) delete updated.comment;
      db.schedules[idx] = updated;
      return withEtag(updated);
    }),
  ),

  http.get(
    '/api/v1/scheduling/schedules/:id/templates',
    authed(({ request, params, me }) => {
      if (!canReadSchedule(me)) return problemJson(request, 403, 'forbidden');
      const s = db.schedules.find((x) => x.id === params['id']);
      if (!s) return problemJson(request, 404, 'not-found');
      if (isDoctorOnly(me) && s.actor?.[0]?.reference !== me.fhirUser) return problemJson(request, 403, 'forbidden');
      return HttpResponse.json(
        { templates: db.templates[s.id!] ?? [] },
        { headers: { ETag: `W/"${s.meta?.versionId ?? '1'}"` } },
      );
    }),
  ),

  http.put(
    '/api/v1/scheduling/schedules/:id/templates',
    authed(async ({ request, params, me }) => {
      if (!canManageSchedule(me)) return problemJson(request, 403, 'forbidden');
      const idx = db.schedules.findIndex((x) => x.id === params['id']);
      const existing = idx >= 0 ? db.schedules[idx] : undefined;
      if (!existing) return problemJson(request, 404, 'not-found');
      const ifMatch = request.headers.get('If-Match');
      if (!ifMatch) return problemJson(request, 428, 'precondition-required');
      if (ifMatch !== `W/"${existing.meta?.versionId ?? '1'}"`) return problemJson(request, 412, 'version-conflict');

      const body = (await request.json()) as { templates?: ScheduleTemplateInput[] };
      if (!Array.isArray(body?.templates)) return problemJson(request, 400, 'validation-failed');
      const drafts: TemplateRowDraft[] = body.templates.map((item, i) => ({
        key: String(i),
        weekday: String(item.weekday ?? ''),
        startTime: String(item.startTime ?? ''),
        endTime: String(item.endTime ?? ''),
        slotMinutes: String(item.slotMinutes ?? ''),
        validFrom: String(item.validFrom ?? ''),
        validTo: item.validTo ? String(item.validTo) : '',
      }));
      if (hasTemplateErrors(validateTemplateRows(drafts))) return problemJson(request, 400, 'validation-failed');

      const nextVersion = String(Number(existing.meta?.versionId ?? '1') + 1);
      db.schedules[idx] = { ...existing, meta: { versionId: nextVersion, lastUpdated: new Date().toISOString() } };
      const saved = body.templates.map((t, i) => ({
        id: `tpl-${existing.id}-${++scheduleSeq}-${i}`,
        weekday: t.weekday,
        startTime: t.startTime,
        endTime: t.endTime,
        slotMinutes: t.slotMinutes,
        validFrom: t.validFrom,
        validTo: t.validTo ?? null,
      }));
      db.templates[existing.id!] = saved;
      return HttpResponse.json(
        { templates: saved, affectedFutureSlots: 0 },
        { headers: { ETag: `W/"${nextVersion}"` } },
      );
    }),
  ),

  http.get(
    '/fhir/Slot',
    authed(({ request, me }) => {
      const qs = new URL(request.url).searchParams;
      const specialty = qs.get('specialty');
      const practitioner = qs.get('practitioner')?.replace('Practitioner/', '');
      const isPatient = me.roles.includes('patient');
      const status = isPatient ? 'free' : qs.get('status');
      const slots = db.slots.filter(
        (s) =>
          (!specialty || s.serviceType?.[0]?.coding?.[0]?.code === specialty) &&
          (!practitioner || s.schedule.reference === `Schedule/sch-${practitioner}`) &&
          (!status || status.split(',').includes(s.status)) &&
          inRange(s.start, qs.getAll('start')) &&
          (!isPatient || dayjs(s.start).isAfter(dayjs())),
      );
      return HttpResponse.json(bundle(slots));
    }),
  ),

  http.post(
    '/api/v1/scheduling/schedules/:id/slots\\:generate',
    authed(async ({ request, params, me }) => {
      if (!canManageSchedule(me)) return problemJson(request, 403, 'forbidden');
      const schedule = db.schedules.find((x) => x.id === params['id']);
      if (!schedule) return problemJson(request, 404, 'not-found');
      if (!schedule.active) return problemJson(request, 422, 'schedule-inactive');
      const body = (await request.json()) as { from?: string; to?: string };
      const from = dayjs(body.from);
      const to = dayjs(body.to);
      const today = dayjs().startOf('day');
      if (
        !from.isValid() ||
        !to.isValid() ||
        from.isBefore(today) ||
        to.isBefore(from) ||
        to.diff(from, 'day') >= 31 ||
        to.isAfter(today.add(30, 'day'))
      ) {
        return problemJson(request, 400, 'validation-failed');
      }
      const pid = schedule.actor?.[0]?.reference?.replace('Practitioner/', '') ?? '';
      const specialty = schedule.specialty?.[0]?.coding?.[0]?.code;
      let created = 0;
      let skippedExisting = 0;
      for (let d = from; !d.isAfter(to, 'day'); d = d.add(1, 'day')) {
        for (const [h, m] of [
          [8, 0],
          [8, 30],
        ] as const) {
          const start = d.hour(h).minute(m).second(0).millisecond(0);
          const id = `s-${pid}-${start.format('YYYYMMDDHHmm')}`;
          if (db.slots.some((s) => s.id === id)) {
            skippedExisting++;
            continue;
          }
          db.slots.push({
            resourceType: 'Slot',
            id,
            schedule: { reference: `Schedule/${schedule.id}`, display: schedule.actor?.[0]?.display },
            serviceType: [{ coding: [{ code: specialty }] }],
            status: 'free',
            start: start.format(),
            end: start.add(30, 'minute').format(),
          });
          created++;
        }
      }
      return HttpResponse.json({ created, skippedExisting });
    }),
  ),

  http.post(
    '/fhir/Slot/:id/$block',
    authed(async ({ request, params, me }) => {
      if (!canManageSchedule(me)) return outcome(request, 403, 'forbidden');
      const slot = db.slots.find((s) => s.id === params['id']);
      if (!slot) return outcome(request, 404, 'not-found');
      const ifMatch = request.headers.get('If-Match');
      if (ifMatch && ifMatch !== `W/"${slot.meta?.versionId ?? '1'}"`) return outcome(request, 412, 'version-conflict');
      const body = (await request.json()) as { reason?: string };
      const reason = body.reason?.trim() ?? '';
      if (reason.length < 1 || reason.length > 200) return outcome(request, 400, 'validation-failed');
      if (slot.status === 'busy') return outcome(request, 409, 'slot-has-appointment');
      if (slot.status !== 'free') return outcome(request, 409, 'invalid-state-transition');
      slot.status = 'busy-unavailable';
      slot.comment = reason;
      slot.meta = { versionId: String(Number(slot.meta?.versionId ?? '1') + 1) };
      return withEtag(slot);
    }),
  ),

  http.post(
    '/fhir/Slot/:id/$unblock',
    authed(({ request, params, me }) => {
      if (!canManageSchedule(me)) return outcome(request, 403, 'forbidden');
      const slot = db.slots.find((s) => s.id === params['id']);
      if (!slot) return outcome(request, 404, 'not-found');
      const ifMatch = request.headers.get('If-Match');
      if (ifMatch && ifMatch !== `W/"${slot.meta?.versionId ?? '1'}"`) return outcome(request, 412, 'version-conflict');
      if (slot.status !== 'busy-unavailable') return outcome(request, 409, 'invalid-state-transition');
      slot.status = 'free';
      delete slot.comment;
      slot.meta = { versionId: String(Number(slot.meta?.versionId ?? '1') + 1) };
      return withEtag(slot);
    }),
  ),

  http.post(
    '/fhir/Appointment/$book',
    authed(async ({ request, me }) => {
      const key = request.headers.get('Idempotency-Key');
      if (!key) return outcome(request, 428, 'idempotency-key-required');
      const raw = await request.text();
      const seen = idempotency.get(key);
      if (seen) {
        if (seen.body !== raw) return outcome(request, 422, 'idempotency-key-reused');
        return withEtag(
          db.appointments.find((a) => a.id === seen.appointmentId)!,
          200,
        );
      }
      const params = JSON.parse(raw) as Parameters;
      const get = (n: string) => params.parameter?.find((p) => p.name === n);
      const slotId = get('slot')?.valueReference?.reference?.replace('Slot/', '');
      const patient = get('patient')?.valueReference?.reference;
      if (me.roles.includes('patient') && patient !== me.fhirUser) return outcome(request, 403, 'forbidden');
      const slot = db.slots.find((s) => s.id === slotId);
      if (!slot || !patient) return outcome(request, 404, 'not-found');
      if (slot.status !== 'free' || dayjs(slot.start).isBefore(dayjs())) {
        const suggestions = db.slots
          .filter(
            (s) =>
              s.schedule.reference === slot.schedule.reference &&
              s.status === 'free' &&
              dayjs(s.start).isAfter(dayjs()),
          )
          .slice(0, 3);
        return outcome(request, 409, 'slot-unavailable', {
          extension: [
            {
              url: 'https://shp.example.org/fhir/StructureDefinition/suggested-slots',
              extension: suggestions.map((s) => ({
                url: 'slot',
                valueReference: { reference: `Slot/${s.id}` },
              })),
            },
          ],
        });
      }
      if (dayjs(slot.start).isAfter(dayjs().add(30, 'day'))) return outcome(request, 422, 'outside-booking-window');
      const future = db.appointments.filter(
        (a) => isFutureBooked(a) && a.participant?.some((p) => p.actor?.reference === patient),
      );
      if (future.length >= 3) return outcome(request, 422, 'max-future-appointments');

      slot.status = 'busy';
      const pid = slot.schedule.reference?.replace('Schedule/sch-', '') ?? '';
      const patientRes = db.patients.find((p) => `Patient/${p.id}` === patient);
      const appt: Appointment = {
        resourceType: 'Appointment',
        id: `a-${Date.now().toString(36)}`,
        meta: { versionId: '1' },
        status: 'booked',
        start: slot.start,
        end: slot.end,
        slot: [{ reference: `Slot/${slot.id}` }],
        serviceType: slot.serviceType,
        description: get('comment')?.valueString,
        participant: [
          {
            actor: {
              reference: patient,
              display: patientRes ? `${patientRes.name?.[0]?.family} ${patientRes.name?.[0]?.given?.[0]}` : '',
            },
            status: 'accepted',
          },
          { actor: { reference: `Practitioner/${pid}`, display: slot.schedule.display }, status: 'accepted' },
        ],
      };
      db.appointments.push(appt);
      idempotency.set(key, { body: raw, appointmentId: appt.id! });
      return withEtag(appt, 201);
    }),
  ),

  http.get(
    '/fhir/Appointment',
    authed(({ request, me }) => {
      const qs = new URL(request.url).searchParams;
      const patient = me.roles.includes('patient') ? me.fhirUser : qs.get('patient');
      const practitioner = qs.get('practitioner');
      const status = qs.get('status')?.split(',');
      const list = db.appointments
        .filter(
          (a) =>
            (!patient || a.participant?.some((p) => p.actor?.reference === patient)) &&
            (!practitioner || a.participant?.some((p) => p.actor?.reference === practitioner)) &&
            (!status || status.includes(a.status)) &&
            (qs.getAll('date').length === 0 || inRange(a.start, qs.getAll('date'))),
        )
        .sort((x, y) => dayjs(x.start).valueOf() - dayjs(y.start).valueOf());
      return HttpResponse.json(bundle(list));
    }),
  ),

  http.post(
    '/fhir/Appointment/:id/$cancel',
    authed(async ({ request, params, me }) => {
      if (!request.headers.get('Idempotency-Key')) return outcome(request, 428, 'idempotency-key-required');
      const appt = db.appointments.find((a) => a.id === params['id']);
      if (!appt) return outcome(request, 404, 'not-found');
      const ifMatch = request.headers.get('If-Match');
      if (!ifMatch) return outcome(request, 428, 'precondition-required');
      if (ifMatch !== `W/"${appt.meta?.versionId}"`) return outcome(request, 412, 'version-conflict');
      if (me.roles.includes('patient') && !appt.participant?.some((p) => p.actor?.reference === me.fhirUser)) {
        return outcome(request, 403, 'forbidden');
      }
      if (appt.status !== 'booked') return outcome(request, 409, 'invalid-state-transition');
      if (me.roles.includes('patient') && dayjs(appt.start).diff(dayjs(), 'minute') < 120) {
        return outcome(request, 422, 'cancellation-too-late');
      }
      const body = (await request.json()) as Parameters;
      appt.status = 'cancelled';
      appt.cancelationReason = { coding: [{ code: body.parameter?.[0]?.valueCode }] };
      appt.meta = { versionId: String(Number(appt.meta?.versionId ?? '1') + 1) };
      const slot = db.slots.find((s) => `Slot/${s.id}` === appt.slot?.[0]?.reference);
      if (slot && dayjs(slot.start).isAfter(dayjs())) slot.status = 'free';
      return withEtag(appt);
    }),
  ),

  http.post(
    '/fhir/Appointment/:id/$check-in',
    authed(({ request, params, me }) => {
      if (!me.roles.includes('nurse')) return outcome(request, 403, 'forbidden');
      const appt = db.appointments.find((a) => a.id === params['id']);
      if (!appt) return outcome(request, 404, 'not-found');
      if (request.headers.get('If-Match') !== `W/"${appt.meta?.versionId}"`)
        return outcome(request, 412, 'version-conflict');
      if (appt.status !== 'booked') return outcome(request, 409, 'invalid-state-transition');
      const minutes = dayjs().diff(dayjs(appt.start), 'minute');
      if (minutes < -60 || minutes > 30) return outcome(request, 422, 'business-rule');
      appt.status = 'arrived';
      appt.meta = { versionId: String(Number(appt.meta?.versionId ?? '1') + 1) };
      return withEtag(appt);
    }),
  ),

  http.post(
    '/fhir/Patient/_search',
    authed(async ({ request }) => {
      const form = new URLSearchParams(await request.text());
      const name = form.get('name');
      const phone = form.get('phone');
      const mrn = form.get('identifier')?.split('|')[1];
      const list = db.patients.filter((p) => {
        if (name) return strip(`${p.name?.[0]?.family} ${p.name?.[0]?.given?.join(' ')}`).includes(strip(name));
        if (phone) return p.telecom?.some((t) => t.value === phone);
        if (mrn) return p.identifier?.some((i) => i.value === mrn);
        return false;
      });
      return HttpResponse.json(bundle(list));
    }),
  ),

  http.get(
    '/fhir/Patient/:id',
    authed(({ request, params }) => {
      const p = db.patients.find((x) => x.id === params['id']);
      return p ? withEtag(p) : outcome(request, 404, 'not-found');
    }),
  ),

  http.get(
    '/fhir/Encounter',
    authed(({ request }) => {
      const qs = new URL(request.url).searchParams;
      const patient = qs.get('patient');
      const status = qs.get('status');
      const list = db.encounters
        .filter((e) => (!patient || e.subject?.reference === patient) && (!status || e.status === status))
        .sort((x, y) => dayjs(y.period?.start).valueOf() - dayjs(x.period?.start).valueOf());
      return HttpResponse.json(bundle(list));
    }),
  ),

  http.get(
    '/fhir/Condition',
    authed(({ request }) => {
      const patient = new URL(request.url).searchParams.get('patient');
      return HttpResponse.json(bundle(db.conditions.filter((c) => c.subject?.reference === patient)));
    }),
  ),

  http.get(
    '/fhir/AllergyIntolerance',
    authed(({ request }) => {
      const patient = new URL(request.url).searchParams.get('patient');
      return HttpResponse.json(bundle(db.allergies.filter((a) => a.patient?.reference === patient)));
    }),
  ),

  http.get(
    '/fhir/Observation',
    authed(({ request }) => {
      const patient = new URL(request.url).searchParams.get('patient');
      return HttpResponse.json(bundle(db.observations.filter((o) => o.subject?.reference === patient)));
    }),
  ),
];
