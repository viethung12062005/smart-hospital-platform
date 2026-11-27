import type {
  AllergyIntolerance,
  Bundle,
  Condition,
  Encounter,
  Observation,
  Patient,
  Practitioner,
} from '@medplum/fhirtypes';
import { apiRequest, bundleEntries, etagOf } from './client';

// Endpoints per docs/03-architecture/detailed-design/09-api-clinical.md (C1, C2, C3, C4, C5, C6, C9, C12).

export interface Me {
  readonly sub: string;
  readonly roles: string[];
  /** For example Patient/p-001 or Practitioner/pr-001. */
  readonly fhirUser?: string;
  readonly name?: string;
}

/** C2: the current user; the canonical source of fhirUser (used when booking for yourself). */
export async function getMe(): Promise<Me> {
  const { data } = await apiRequest<Me>('/api/v1/me');
  return data;
}

/**
 * C6: find patients with POST _search, form-urlencoded (parameters are not in the URL so PII does not leak into logs).
 * An all-digit string is treated as a phone number, a BN-... form as a patient ID, anything else as a name.
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

/** C4 with the version: the ETag is needed as If-Match when the record is edited (C5). */
export async function getPatientWithVersion(id: string): Promise<{ patient: Patient; etag?: string }> {
  const { data, etag } = await apiRequest<Patient>(`/fhir/Patient/${id}`);
  return { patient: data, etag: etag ?? etagOf(data) };
}

/** Administrative fields entered on the registration forms (C1, C3). */
export interface RegistrationInput {
  readonly family: string;
  readonly given: string;
  readonly phone: string;
  readonly email?: string;
  readonly gender: 'male' | 'female' | 'other';
  readonly birthDate: string;
  readonly city?: string;
  readonly district?: string;
  readonly language: 'vi' | 'en';
}

export interface Registration {
  readonly patientId: string;
  readonly mrn: string;
}

/** C1: patient self-registration. 422 duplicate-patient-suspected when phone + date of birth match a record. */
export async function registerSelf(input: RegistrationInput, idempotencyKey: string): Promise<Registration> {
  const { data } = await apiRequest<Registration>('/api/v1/registration', {
    method: 'POST',
    idempotencyKey,
    body: {
      name: { family: input.family.trim(), given: input.given.trim().split(/\s+/).filter(Boolean) },
      phone: input.phone.trim(),
      ...(input.email?.trim() ? { email: input.email.trim() } : {}),
      gender: input.gender,
      birthDate: input.birthDate,
      address: { city: input.city?.trim() ?? '', district: input.district?.trim() ?? '' },
      language: input.language,
    },
  });
  return data;
}

/** C3: a nurse creates a record for a patient who has no account (same duplicate check as C1). */
export async function createPatientAtDesk(input: RegistrationInput, idempotencyKey: string): Promise<Patient> {
  const patient: Patient = {
    resourceType: 'Patient',
    active: true,
    name: [{ family: input.family.trim(), given: input.given.trim().split(/\s+/).filter(Boolean) }],
    gender: input.gender,
    birthDate: input.birthDate,
    telecom: [
      { system: 'phone', value: input.phone.trim() },
      ...(input.email?.trim() ? [{ system: 'email' as const, value: input.email.trim() }] : []),
    ],
    address: [{ city: input.city?.trim() || undefined, district: input.district?.trim() || undefined }],
    communication: [{ language: { coding: [{ code: input.language }] }, preferred: true }],
  };
  const { data } = await apiRequest<Patient>('/fhir/Patient', { method: 'POST', idempotencyKey, body: patient });
  return data;
}

/** C5: edit the administrative fields; `If-Match` carries the version that was read (412 when it is old). */
export async function updatePatient(patient: Patient, etag: string | undefined): Promise<Patient> {
  const { data } = await apiRequest<Patient>(`/fhir/Patient/${patient.id}`, {
    method: 'PUT',
    body: patient,
    ifMatch: etag,
  });
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
