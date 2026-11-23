import type {
  Appointment,
  Bundle,
  Parameters,
  Practitioner,
  PractitionerRole,
  Schedule,
  Slot,
} from '@medplum/fhirtypes';
import { apiRequest, bundleEntries, etagOf } from './client';
import { specialtyName, systemUrl } from './terminology';

// Endpoints per docs/03-architecture/detailed-design/08-api-scheduling.md (S1-S6, S8, S11, S13, S14, S16)
// and 09-api-clinical.md (C9: doctor directory).

export interface DoctorEntry {
  readonly practitioner: Practitioner;
  readonly specialty?: string;
}

/** Doctors by specialty (C9): PractitionerRole with Practitioner. */
export async function searchDoctors(specialty: string | undefined): Promise<DoctorEntry[]> {
  const qs = new URLSearchParams({ _include: 'PractitionerRole:practitioner' });
  if (specialty) qs.set('specialty', specialty);
  const { data } = await apiRequest<Bundle>(`/fhir/PractitionerRole?${qs}`);
  const resources = bundleEntries<PractitionerRole | Practitioner>(data);
  const practitioners = new Map(
    resources
      .filter((r): r is Practitioner => r.resourceType === 'Practitioner')
      .map((p) => [`Practitioner/${p.id}`, p]),
  );
  return resources
    .filter((r): r is PractitionerRole => r.resourceType === 'PractitionerRole')
    .map((role) => ({
      practitioner: practitioners.get(role.practitioner?.reference ?? '') ?? { resourceType: 'Practitioner' },
      specialty: role.specialty?.[0]?.coding?.[0]?.code,
    }))
    .filter((d) => d.practitioner.id);
}

export interface SlotQuery {
  readonly specialty?: string;
  readonly practitioner?: string;
  readonly from: string;
  readonly to: string;
  readonly status?: string;
}

/** S8: free slots; maximum date range 31 days. */
export async function searchSlots(q: SlotQuery): Promise<Slot[]> {
  const qs = new URLSearchParams();
  if (q.specialty) qs.set('specialty', q.specialty);
  if (q.practitioner) qs.set('practitioner', `Practitioner/${q.practitioner}`);
  if (q.status) qs.set('status', q.status);
  qs.append('start', `ge${q.from}`);
  qs.append('start', `lt${q.to}`);
  qs.set('_count', '200');
  const { data } = await apiRequest<Bundle>(`/fhir/Slot?${qs}`);
  return bundleEntries<Slot>(data);
}

/** S11: book an appointment. Same idempotencyKey + same content returns the already-created appointment (double click does not create 2 appointments). */
export async function bookAppointment(input: {
  slotId: string;
  patient: string;
  comment?: string;
  idempotencyKey: string;
}): Promise<Appointment> {
  const body: Parameters = {
    resourceType: 'Parameters',
    parameter: [
      { name: 'slot', valueReference: { reference: `Slot/${input.slotId}` } },
      { name: 'patient', valueReference: { reference: input.patient } },
      ...(input.comment ? [{ name: 'comment', valueString: input.comment }] : []),
      { name: 'channel', valueCode: 'web' },
    ],
  };
  const { data } = await apiRequest<Appointment>('/fhir/Appointment/$book', {
    method: 'POST',
    body,
    idempotencyKey: input.idempotencyKey,
  });
  return data;
}

export interface AppointmentQuery {
  readonly patient?: string;
  readonly practitioner?: string;
  readonly status?: string;
  readonly from?: string;
  readonly to?: string;
}

/** S13: at least one of patient, practitioner, date is required. */
export async function searchAppointments(q: AppointmentQuery): Promise<Appointment[]> {
  const qs = new URLSearchParams({ _sort: 'date', _count: '100' });
  if (q.patient) qs.set('patient', q.patient);
  if (q.practitioner) qs.set('practitioner', q.practitioner);
  if (q.status) qs.set('status', q.status);
  if (q.from) qs.append('date', `ge${q.from}`);
  if (q.to) qs.append('date', `lt${q.to}`);
  const { data } = await apiRequest<Bundle>(`/fhir/Appointment?${qs}`);
  return bundleEntries<Appointment>(data);
}

/** S14: cancel; needs the If-Match of the version being displayed (avoids cancelling a version someone else changed). */
export async function cancelAppointment(
  appt: Appointment,
  reason: string,
  idempotencyKey: string,
): Promise<Appointment> {
  const { data } = await apiRequest<Appointment>(`/fhir/Appointment/${appt.id}/$cancel`, {
    method: 'POST',
    body: { resourceType: 'Parameters', parameter: [{ name: 'reason', valueCode: reason }] },
    idempotencyKey,
    ifMatch: etagOf(appt),
  });
  return data;
}

/** S16: Nurse check-in (booked → arrived). */
export async function checkIn(appt: Appointment): Promise<Appointment> {
  const { data } = await apiRequest<Appointment>(`/fhir/Appointment/${appt.id}/$check-in`, {
    method: 'POST',
    ifMatch: etagOf(appt),
  });
  return data;
}

export function participantRef(appt: Appointment, type: 'Patient' | 'Practitioner'): string | undefined {
  return appt.participant?.find((p) => p.actor?.reference?.startsWith(`${type}/`))?.actor?.reference;
}

export function participantName(appt: Appointment, type: 'Patient' | 'Practitioner'): string {
  return appt.participant?.find((p) => p.actor?.reference?.startsWith(`${type}/`))?.actor?.display ?? '';
}

export interface ScheduleQuery {
  readonly actor?: string;
  readonly specialty?: string;
  readonly active?: boolean;
}

/** S3: find schedules (Doctors see only their own schedule). Backend caps _count at 100. */
export async function searchSchedules(q: ScheduleQuery = {}): Promise<Schedule[]> {
  const qs = new URLSearchParams({ _count: '100' });
  if (q.actor) qs.set('actor', q.actor.startsWith('Practitioner/') ? q.actor : `Practitioner/${q.actor}`);
  if (q.specialty) qs.set('specialty', q.specialty);
  if (q.active !== undefined) qs.set('active', String(q.active));
  const { data } = await apiRequest<Bundle>(`/fhir/Schedule?${qs}`);
  return bundleEntries<Schedule>(data);
}

/** S2: read a schedule by ID. */
export async function getSchedule(id: string): Promise<Schedule> {
  const { data } = await apiRequest<Schedule>(`/fhir/Schedule/${id}`);
  return data;
}

export interface CreateScheduleInput {
  readonly practitionerId: string;
  readonly practitionerDisplay?: string;
  readonly specialty: string;
  readonly active: boolean;
  readonly comment?: string;
}

/** S1: create a doctor's work schedule (Nurse, Admin). */
export async function createSchedule(input: CreateScheduleInput): Promise<Schedule> {
  const comment = input.comment?.trim();
  const body: Schedule = {
    resourceType: 'Schedule',
    active: input.active,
    actor: [
      {
        reference: `Practitioner/${input.practitionerId}`,
        ...(input.practitionerDisplay ? { display: input.practitionerDisplay } : {}),
      },
    ],
    specialty: [
      {
        coding: [
          {
            system: systemUrl('specialty'),
            code: input.specialty,
            display: specialtyName(input.specialty),
          },
        ],
      },
    ],
    ...(comment ? { comment } : {}),
  };
  const { data } = await apiRequest<Schedule>('/fhir/Schedule', { method: 'POST', body });
  return data;
}

export interface UpdateScheduleInput {
  readonly schedule: Schedule;
  readonly active: boolean;
  readonly comment?: string;
}

/** S4: edit a schedule's active flag and notes (Nurse, Admin). Requires If-Match. */
export async function updateSchedule(input: UpdateScheduleInput): Promise<Schedule> {
  const comment = input.comment?.trim();
  const body: Schedule = {
    resourceType: 'Schedule',
    id: input.schedule.id,
    active: input.active,
    actor: input.schedule.actor,
    specialty: input.schedule.specialty,
    ...(input.schedule.planningHorizon ? { planningHorizon: input.schedule.planningHorizon } : {}),
    ...(comment ? { comment } : {}),
  };
  const { data } = await apiRequest<Schedule>(`/fhir/Schedule/${input.schedule.id}`, {
    method: 'PUT',
    body,
    ifMatch: etagOf(input.schedule),
  });
  return data;
}

export interface ScheduleTemplateInput {
  readonly weekday: number;
  readonly startTime: string;
  readonly endTime: string;
  readonly slotMinutes: number;
  readonly validFrom: string;
  readonly validTo: string | null;
}

export interface ScheduleTemplate extends ScheduleTemplateInput {
  readonly id: string;
}

export interface ScheduleTemplatesResult {
  readonly templates: ScheduleTemplate[];
  readonly etag?: string;
}

export interface ReplaceTemplatesResult {
  readonly templates: ScheduleTemplate[];
  readonly affectedFutureSlots: number;
  readonly etag?: string;
}

/** S6: read a schedule's weekly template rows along with its ETag. */
export async function getScheduleTemplates(scheduleId: string): Promise<ScheduleTemplatesResult> {
  const { data, etag } = await apiRequest<{ templates: ScheduleTemplate[] }>(
    `/api/v1/scheduling/schedules/${scheduleId}/templates`,
  );
  return { templates: data.templates, etag };
}

/** S5: replace a schedule's entire weekly template in one transaction (Nurse, Admin). */
export async function replaceScheduleTemplates(
  scheduleId: string,
  templates: readonly ScheduleTemplateInput[],
  ifMatch: string | undefined,
): Promise<ReplaceTemplatesResult> {
  const { data, etag } = await apiRequest<{ templates: ScheduleTemplate[]; affectedFutureSlots: number }>(
    `/api/v1/scheduling/schedules/${scheduleId}/templates`,
    {
      method: 'PUT',
      body: { templates },
      ifMatch,
    },
  );
  return { templates: data.templates, affectedFutureSlots: data.affectedFutureSlots, etag };
}

export function schedulePractitionerId(schedule: Schedule): string | undefined {
  const ref = schedule.actor?.[0]?.reference;
  return ref?.startsWith('Practitioner/') ? ref.slice('Practitioner/'.length) : ref?.split('/')[1];
}

export function scheduleSpecialtyCode(schedule: Schedule): string | undefined {
  return schedule.specialty?.[0]?.coding?.[0]?.code;
}
