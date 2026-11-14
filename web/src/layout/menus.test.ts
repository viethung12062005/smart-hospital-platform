import { describe, expect, it } from 'vitest';
import { decodeJwtPayload, rolesFromClaims } from '@/auth/roles';
import { activeHref, menusFor } from './menus';

const hrefs = (roles: Parameters<typeof menusFor>[0]) => menusFor(roles).flatMap((g) => g.links.map((l) => l.href));

describe('menu by role', () => {
  it('a patient only sees booking, not the clinical screens', () => {
    expect(hrefs(['patient'])).toEqual(['/', '/doctors', '/book', '/appointments']);
  });

  it('a doctor sees the queue, schedule, patients and encounters', () => {
    expect(hrefs(['doctor'])).toEqual(['/', '/queue', '/schedule', '/patients', '/encounters']);
  });

  it('the DPO has no business menu besides the home page', () => {
    expect(hrefs(['dpo'])).toEqual(['/']);
  });

  it('picks the longest matching link', () => {
    const menus = menusFor(['doctor']);
    expect(activeHref('/patients/p-001', menus)).toBe('/patients');
    expect(activeHref('/', menus)).toBe('/');
    expect(activeHref('/khong-co', menus)).toBeUndefined();
  });
});

describe('reading roles from the access token', () => {
  it('takes the roles claim and drops unknown roles', () => {
    const payload = btoa(JSON.stringify({ sub: 'u1', roles: ['doctor', 'offline_access'] }))
      .replace(/\+/g, '-')
      .replace(/\//g, '_')
      .replace(/=+$/, '');
    expect(rolesFromClaims(decodeJwtPayload(`h.${payload}.s`))).toEqual(['doctor']);
  });
});
