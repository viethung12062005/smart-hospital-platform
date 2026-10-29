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
  /** Khóa i18n trong nhóm nav. */
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
 * Menu theo vai trò (ma trận phân quyền: docs/02-requirements/access-control-matrix.md).
 * Menu chỉ để điều hướng; quyền thật do máy chủ kiểm ở mọi endpoint.
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

/** Link khớp dài nhất với đường dẫn hiện tại (ví dụ /patients/p-001 → /patients). */
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
