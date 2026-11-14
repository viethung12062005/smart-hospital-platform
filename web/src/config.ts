/**
 * Runtime configuration: reads /config.json so one image works for every environment (local, uat, prod).
 * On the cluster, this file is overridden by a ConfigMap; in dev it defaults to public/config.json.
 */
export interface AppConfig {
  /** 'oidc' (Keycloak) or 'mock' (pick a role, no backend needed). */
  readonly authMode: 'oidc' | 'mock';
  /** OIDC issuer, for example http://localhost:18080/realms/shp */
  readonly authority: string;
  readonly clientId: string;
  /**
   * Scope requested at sign-in. Local Keycloak: the shp-api audience mapper is already attached, so only openid is needed.
   * Entra ID: you MUST add the API's scope (e.g. api://shp-api-uat/access_as_user), otherwise the access token is a Graph token and the gateway returns 401.
   */
  readonly scope: string;
  /** API prefix; empty = same origin (Vite proxy in dev, gateway when running on the cluster). */
  readonly apiBase: string;
}

const DEFAULTS: AppConfig = {
  authMode: 'oidc',
  authority: 'http://localhost:18080/realms/shp',
  clientId: 'shp-web',
  scope: 'openid profile email',
  apiBase: '',
};

let current: AppConfig = DEFAULTS;

/** Mock-data mode: `npm run dev:mock` (VITE_MOCK=true). Enables MSW and mock sign-in. */
export const isMockMode = import.meta.env.VITE_MOCK === 'true';

export async function loadConfig(): Promise<AppConfig> {
  if (isMockMode) {
    current = { ...DEFAULTS, authMode: 'mock' };
    return current;
  }
  try {
    const res = await fetch('/config.json', { cache: 'no-store' });
    if (res.ok) {
      current = { ...DEFAULTS, ...((await res.json()) as Partial<AppConfig>) };
    }
  } catch {
    // No config.json: use the local defaults.
  }
  return current;
}

export function getConfig(): AppConfig {
  return current;
}

export function setConfigForTest(config: Partial<AppConfig>): void {
  current = { ...DEFAULTS, ...config };
}
