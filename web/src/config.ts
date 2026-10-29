/**
 * Cấu hình lúc chạy: đọc /config.json để một image dùng được cho mọi môi trường (local, uat, prod).
 * Trên cụm, file này do ConfigMap ghi đè; khi dev, mặc định nằm ở public/config.json.
 */
export interface AppConfig {
  /** 'oidc' (Keycloak/Entra) hoặc 'mock' (chọn vai trò, không cần backend). */
  readonly authMode: 'oidc' | 'mock';
  /** Issuer OIDC, ví dụ http://localhost:18080/realms/shp */
  readonly authority: string;
  readonly clientId: string;
  /**
   * Scope xin khi đăng nhập. Keycloak local: mapper audience shp-api đã gắn sẵn nên chỉ cần openid.
   * Entra ID: PHẢI thêm scope của API (vd. api://shp-api-uat/access_as_user), nếu không access token là của Graph và gateway trả 401.
   */
  readonly scope: string;
  /** Tiền tố API; rỗng = cùng origin (Vite proxy khi dev, gateway khi chạy trên cụm). */
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

/** Chế độ dữ liệu giả: `npm run dev:mock` (VITE_MOCK=true). Bật MSW và đăng nhập giả. */
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
    // Không có config.json: dùng mặc định cho local.
  }
  return current;
}

export function getConfig(): AppConfig {
  return current;
}

export function setConfigForTest(config: Partial<AppConfig>): void {
  current = { ...DEFAULTS, ...config };
}
