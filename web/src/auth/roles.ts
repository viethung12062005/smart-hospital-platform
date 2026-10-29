export const ROLES = ['patient', 'doctor', 'nurse', 'admin', 'dpo'] as const;
export type Role = (typeof ROLES)[number];

export function isRole(value: unknown): value is Role {
  return typeof value === 'string' && (ROLES as readonly string[]).includes(value);
}

export interface SessionUser {
  readonly sub: string;
  readonly name: string;
  readonly roles: readonly Role[];
  /** Tham chiếu FHIR của người dùng, ví dụ Patient/123 (claim fhir_user / fhirUser). */
  readonly fhirUser?: string;
}

/** Đọc payload của JWT (không kiểm chữ ký: việc đó do gateway làm; ở đây chỉ để hiển thị menu). */
export function decodeJwtPayload(token: string): Record<string, unknown> {
  const part = token.split('.')[1];
  if (!part) return {};
  const json = atob(
    part
      .replace(/-/g, '+')
      .replace(/_/g, '/')
      .padEnd(Math.ceil(part.length / 4) * 4, '='),
  );
  const bytes = Uint8Array.from(json, (c) => c.charCodeAt(0));
  return JSON.parse(new TextDecoder().decode(bytes)) as Record<string, unknown>;
}

export function rolesFromClaims(claims: Record<string, unknown>): Role[] {
  const raw = claims['roles'];
  return Array.isArray(raw) ? raw.filter(isRole) : [];
}

export function hasAnyRole(user: SessionUser | undefined, allowed: readonly Role[]): boolean {
  return !!user && user.roles.some((r) => allowed.includes(r));
}
