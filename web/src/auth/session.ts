import { UserManager, WebStorageStateStore, type User } from 'oidc-client-ts';
import { getConfig } from '@/config';
import { decodeJwtPayload, rolesFromClaims, type Role, type SessionUser } from './roles';

/**
 * Nguồn token duy nhất cho lớp gọi API. Hai chế độ:
 * - oidc: Authorization Code + PKCE với client shp-web (realm Keycloak, giống nhau ở mọi môi trường);
 * - mock: người dùng giả chọn ở màn hình đăng nhập, token giả (MSW không kiểm chữ ký).
 */
let manager: UserManager | undefined;
let mockUser: (SessionUser & { token: string }) | undefined;

function oidc(): UserManager {
  if (!manager) {
    const cfg = getConfig();
    manager = new UserManager({
      authority: cfg.authority,
      client_id: cfg.clientId,
      redirect_uri: `${window.location.origin}/auth/callback`,
      post_logout_redirect_uri: window.location.origin,
      response_type: 'code',
      scope: cfg.scope,
      // sessionStorage: token mất khi đóng tab, giảm rủi ro trên máy dùng chung ở bệnh viện.
      userStore: new WebStorageStateStore({ store: window.sessionStorage }),
      automaticSilentRenew: true,
    });
  }
  return manager;
}

export function toSessionUser(user: User): SessionUser {
  const claims = decodeJwtPayload(user.access_token);
  const profile = user.profile;
  const name = profile.name ?? ([profile.given_name, profile.family_name].filter(Boolean).join(' ') || profile.sub);
  const fhirUser = (claims['fhir_user'] ?? claims['fhirUser'] ?? profile['fhir_user']) as string | undefined;
  return { sub: profile.sub, name, roles: rolesFromClaims(claims), fhirUser };
}

export async function currentUser(): Promise<SessionUser | undefined> {
  if (getConfig().authMode === 'mock') return mockUser;
  const user = await oidc().getUser();
  return user && !user.expired ? toSessionUser(user) : undefined;
}

export async function accessToken(): Promise<string | undefined> {
  if (getConfig().authMode === 'mock') return mockUser?.token;
  const user = await oidc().getUser();
  return user && !user.expired ? user.access_token : undefined;
}

export async function signIn(returnTo = '/'): Promise<void> {
  await oidc().signinRedirect({ state: { returnTo } });
}

export async function completeSignIn(): Promise<string> {
  const user = await oidc().signinRedirectCallback();
  const state = user.state as { returnTo?: string } | undefined;
  return state?.returnTo ?? '/';
}

export async function signOut(): Promise<void> {
  if (getConfig().authMode === 'mock') {
    mockUser = undefined;
    return;
  }
  await oidc().signoutRedirect();
}

/** Người dùng giả cho chế độ mock và test (dữ liệu giả theo docs/00-synthetic-data.md). */
export const MOCK_USERS: Record<Role, SessionUser> = {
  patient: { sub: 'mock-patient', name: 'Nguyễn Thị Lan', roles: ['patient'], fhirUser: 'Patient/p-001' },
  doctor: { sub: 'mock-doctor', name: 'BS. Lê Minh Quân', roles: ['doctor'], fhirUser: 'Practitioner/pr-001' },
  nurse: { sub: 'mock-nurse', name: 'ĐD. Trần Thu Hà', roles: ['nurse'], fhirUser: 'Practitioner/pr-101' },
  admin: { sub: 'mock-admin', name: 'Phạm Quang Huy', roles: ['admin'], fhirUser: 'Practitioner/pr-201' },
  dpo: { sub: 'mock-dpo', name: 'Vũ Hải Yến', roles: ['dpo'], fhirUser: 'Practitioner/pr-301' },
};

export function signInMock(role: Role): SessionUser {
  const user = MOCK_USERS[role];
  mockUser = { ...user, token: `mock.${btoa(JSON.stringify({ sub: user.sub, roles: user.roles }))}.sig` };
  return user;
}
