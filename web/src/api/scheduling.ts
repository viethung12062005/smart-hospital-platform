import type { Appointment, Bundle, Parameters, Practitioner, PractitionerRole, Slot } from '@medplum/fhirtypes';
import { apiRequest, bundleEntries, etagOf } from './client';

// Endpoint theo docs/03-architecture/detailed-design/08-api-scheduling.md (S8, S11, S13, S14, S16)
// và 09-api-clinical.md (C9: danh bạ bác sĩ).

export interface DoctorEntry {
  readonly practitioner: Practitioner;
  readonly specialty?: string;
}

/** Bác sĩ theo chuyên khoa (C9): PractitionerRole kèm Practitioner. */
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

/** S8: slot trống; khoảng ngày tối đa 31 ngày. */
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

/** S11: đặt lịch. Cùng idempotencyKey + cùng nội dung trả lại đúng lịch hẹn đã tạo (bấm đúp không tạo 2 lịch). */
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

/** S13: phải có ít nhất một trong patient, practitioner, date. */
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

/** S14: hủy; cần If-Match của bản đang hiển thị (tránh hủy nhầm bản đã bị người khác đổi). */
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
