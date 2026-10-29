import { describe, expect, it } from 'vitest';
import { decodeJwtPayload, rolesFromClaims } from '@/auth/roles';
import { activeHref, menusFor } from './menus';

const hrefs = (roles: Parameters<typeof menusFor>[0]) => menusFor(roles).flatMap((g) => g.links.map((l) => l.href));

describe('menu theo vai trò', () => {
  it('bệnh nhân chỉ thấy đặt lịch, không thấy màn hình lâm sàng', () => {
    expect(hrefs(['patient'])).toEqual(['/', '/doctors', '/book', '/appointments']);
  });

  it('bác sĩ thấy hàng đợi, lịch, bệnh nhân, lần khám', () => {
    expect(hrefs(['doctor'])).toEqual(['/', '/queue', '/schedule', '/patients', '/encounters']);
  });

  it('DPO không có menu nghiệp vụ nào ngoài trang chủ', () => {
    expect(hrefs(['dpo'])).toEqual(['/']);
  });

  it('chọn link khớp dài nhất', () => {
    const menus = menusFor(['doctor']);
    expect(activeHref('/patients/p-001', menus)).toBe('/patients');
    expect(activeHref('/', menus)).toBe('/');
    expect(activeHref('/khong-co', menus)).toBeUndefined();
  });
});

describe('đọc vai trò từ access token', () => {
  it('lấy claim roles, bỏ vai trò lạ', () => {
    const payload = btoa(JSON.stringify({ sub: 'u1', roles: ['doctor', 'offline_access'] }))
      .replace(/\+/g, '-')
      .replace(/\//g, '_')
      .replace(/=+$/, '');
    expect(rolesFromClaims(decodeJwtPayload(`h.${payload}.s`))).toEqual(['doctor']);
  });
});
