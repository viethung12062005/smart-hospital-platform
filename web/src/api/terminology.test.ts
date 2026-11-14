import { describe, expect, it } from 'vitest';
import { CANCEL_REASONS, SPECIALTIES, specialtyName, systemUrl } from './terminology';

describe('code catalogs read from contracts/terminology', () => {
  it('has every specialty and cancellation reason', () => {
    expect(SPECIALTIES.length).toBeGreaterThanOrEqual(12);
    expect(SPECIALTIES.map((s) => s.code)).toContain('cardiology');
    expect(CANCEL_REASONS.map((r) => r.code)).toContain('patient-request');
  });

  it('looks up the display name and the code system URL', () => {
    expect(specialtyName('cardiology')).toBe('Tim mạch');
    expect(specialtyName('khong-co')).toBe('khong-co');
    expect(systemUrl('specialty')).toBe('https://shp.example.org/fhir/CodeSystem/specialty');
  });
});
