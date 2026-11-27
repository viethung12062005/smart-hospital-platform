import type { RegistrationInput } from '@/api/clinical';
import { ApiError } from '@/api/client';

export type RegistrationDraft = Omit<RegistrationInput, 'gender'> & { gender: RegistrationInput['gender'] | '' };

/** Vietnamese mobile or landline number: 0xxxxxxxxx or +84xxxxxxxxx (the server checks it again). */
const PHONE = /^(0|\+84)\d{9}$/;
const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

export type FieldErrors = Partial<Record<'family' | 'given' | 'phone' | 'email' | 'gender' | 'birthDate', string>>;

/** Returns i18n keys per invalid field; an empty object means the draft may be submitted. */
export function validateRegistration(d: RegistrationDraft, today: Date = new Date()): FieldErrors {
  const e: FieldErrors = {};
  if (!d.family.trim()) e.family = 'profile.errRequired';
  if (!d.given.trim()) e.given = 'profile.errRequired';
  if (!PHONE.test(d.phone.trim())) e.phone = 'profile.errPhone';
  if (d.email?.trim() && !EMAIL.test(d.email.trim())) e.email = 'profile.errEmail';
  if (!d.gender) e.gender = 'profile.errRequired';
  if (!d.birthDate) e.birthDate = 'profile.errRequired';
  else {
    const born = new Date(`${d.birthDate}T00:00:00`);
    const oldest = new Date(today);
    oldest.setFullYear(oldest.getFullYear() - 120);
    if (Number.isNaN(born.getTime()) || born > today || born < oldest) e.birthDate = 'profile.errBirthDate';
  }
  return e;
}

export function isDuplicate(error: unknown): boolean {
  return error instanceof ApiError && error.code === 'duplicate-patient-suspected';
}
