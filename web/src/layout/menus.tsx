import {
  IconCalendarPlus,
  IconCalendarTime,
  IconClipboardHeart,
  IconHome,
  IconListCheck,
  IconStethoscope,
  IconUserSearch,
  IconUsers,
} from '@tabler/icons-react';
import type { ReactNode } from 'react';
import type { Role } from '@/auth/roles';

export interface MenuLink {
  readonly href: string;
  /** i18n key within the nav group. */
  readonly label: string;
  readonly icon: ReactNode;
  readonly roles: readonly Role[];
}

export interface MenuGroup {
  readonly title: string;
  readonly links: readonly MenuLink[];
}

const ALL: readonly Role[] = ['patient', 'doctor', 'nurse', 'admin', 'dpo'];

/**
 * Menu by role (permission matrix: docs/02-requirements/access-control-matrix.md).
 * The menu is for navigation only; real permissions are enforced by the server on every endpoint.
 */
export const MENU: readonly MenuGroup[] = [
  { title: 'groupMain', links: [{ href: '/', label: 'home', icon: <IconHome />, roles: ALL }] },
  {
    title: 'groupBooking',
    links: [
      { href: '/doctors', label: 'findDoctor', icon: <IconUserSearch />, roles: ['patient', 'nurse'] },
      { href: '/book', label: 'book', icon: <IconCalendarPlus />, roles: ['patient'] },
      { href: '/appointments', label: 'myAppointments', icon: <IconListCheck />, roles: ['patient'] },
    ],
  },
  {
    title: 'groupClinic',
    links: [
      { href: '/queue', label: 'queue', icon: <IconClipboardHeart />, roles: ['nurse', 'doctor'] },
      { href: '/schedule', label: 'schedule', icon: <IconCalendarTime />, roles: ['doctor', 'nurse', 'admin'] },
    ],
  },
  {
    title: 'groupClinical',
    links: [
      { href: '/patients', label: 'patients', icon: <IconUsers />, roles: ['doctor', 'nurse'] },
      { href: '/encounters', label: 'encounters', icon: <IconStethoscope />, roles: ['doctor', 'nurse'] },
    ],
  },
];

/** The longest link matching the current path (for example /patients/p-001 → /patients). */
export function activeHref(pathname: string, menus: readonly MenuGroup[]): string | undefined {
  let best: string | undefined;
  for (const m of menus) {
    for (const l of m.links) {
      const match = l.href === '/' ? pathname === '/' : pathname === l.href || pathname.startsWith(`${l.href}/`);
      if (match && (!best || l.href.length > best.length)) best = l.href;
    }
  }
  return best;
}

export function menusFor(roles: readonly Role[]): MenuGroup[] {
  return MENU.map((g) => ({ ...g, links: g.links.filter((l) => l.roles.some((r) => roles.includes(r))) })).filter(
    (g) => g.links.length > 0,
  );
}
