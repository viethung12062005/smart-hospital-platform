import type {
  AllergyIntolerance,
  Bundle,
  Condition,
  Encounter,
  Observation,
  Patient,
  Practitioner,
} from '@medplum/fhirtypes';
import { apiRequest, bundleEntries } from './client';

// Endpoint theo docs/03-architecture/detailed-design/09-api-clinical.md (C2, C4, C6, C9, C12).

export interface Me {
  readonly sub: string;
  readonly roles: string[];
  /** Ví dụ Patient/p-001 hoặc Practitioner/pr-001. */
  readonly fhirUser?: string;
  readonly name?: string;
}

/** C2: người dùng hiện tại; nguồn chuẩn của fhirUser (dùng khi đặt lịch cho chính mình). */
export async function getMe(): Promise<Me> {
  const { data } = await apiRequest<Me>('/api/v1/me');
  return data;
}

/**
 * C6: tìm bệnh nhân bằng POST _search, form-urlencoded (tham số không nằm trên URL để không lộ PII vào log).
 * Chuỗi toàn chữ số coi là số điện thoại, dạng BN-... là mã bệnh nhân, còn lại là tên.
 */
export async function searchPatients(text: string): Promise<Patient[]> {
  const q = text.trim();
  const form = new URLSearchParams({ _count: '50' });
  if (/^\+?\d{9,12}$/.test(q)) form.set('phone', q);
  else if (/^BN-/i.test(q)) form.set('identifier', `urn:shp:mrn|${q.toUpperCase()}`);
  else form.set('name', q);
  const { data } = await apiRequest<Bundle>('/fhir/Patient/_search', { method: 'POST', form });
  return bundleEntries<Patient>(data);
}

export async function getPatient(id: string): Promise<Patient> {
  const { data } = await apiRequest<Patient>(`/fhir/Patient/${id}`);
  return data;
}

export async function searchPractitioners(): Promise<Practitioner[]> {
  const { data } = await apiRequest<Bundle>('/fhir/Practitioner?_count=200');
  return bundleEntries<Practitioner>(data);
}

export async function searchEncounters(q: { patient?: string; status?: string }): Promise<Encounter[]> {
  const qs = new URLSearchParams({ _sort: '-date', _count: '100' });
  if (q.patient) qs.set('patient', q.patient);
  if (q.status) qs.set('status', q.status);
  const { data } = await apiRequest<Bundle>(`/fhir/Encounter?${qs}`);
  return bundleEntries<Encounter>(data);
}

export interface PatientSummary {
  readonly conditions: Condition[];
  readonly allergies: AllergyIntolerance[];
  readonly vitals: Observation[];
}

export async function getPatientSummary(patientId: string): Promise<PatientSummary> {
  const p = `patient=Patient/${patientId}`;
  const [c, a, o] = await Promise.all([
    apiRequest<Bundle>(`/fhir/Condition?${p}`),
    apiRequest<Bundle>(`/fhir/AllergyIntolerance?${p}`),
    apiRequest<Bundle>(`/fhir/Observation?${p}&category=vital-signs&_sort=-date&_count=20`),
  ]);
  return {
    conditions: bundleEntries<Condition>(c.data),
    allergies: bundleEntries<AllergyIntolerance>(a.data),
    vitals: bundleEntries<Observation>(o.data),
  };
}
