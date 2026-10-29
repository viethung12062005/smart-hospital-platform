import { describe, expect, it } from 'vitest';
import { CANCEL_REASONS, SPECIALTIES, specialtyName, systemUrl } from './terminology';

describe('danh mục mã đọc từ contracts/terminology', () => {
  it('có đủ chuyên khoa và lý do hủy', () => {
    expect(SPECIALTIES.length).toBeGreaterThanOrEqual(12);
    expect(SPECIALTIES.map((s) => s.code)).toContain('cardiology');
    expect(CANCEL_REASONS.map((r) => r.code)).toContain('patient-request');
  });

  it('tra tên hiển thị và URL hệ mã', () => {
    expect(specialtyName('cardiology')).toBe('Tim mạch');
    expect(specialtyName('khong-co')).toBe('khong-co');
    expect(systemUrl('specialty')).toBe('https://shp.example.org/fhir/CodeSystem/specialty');
  });
});
