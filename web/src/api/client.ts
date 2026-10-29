import type { Bundle, OperationOutcome, Resource } from '@medplum/fhirtypes';
import { accessToken } from '@/auth/session';
import { getConfig } from '@/config';
import { currentLang } from '@/i18n';

/**
 * Lỗi từ API theo quy ước 07-api-conventions §5:
 * - /fhir/*  → OperationOutcome, mã ở issue[0].details.coding[0].code, thông điệp ở diagnostics;
 * - còn lại  → Problem Details (RFC 9457) với `code` và `detail`.
 * Máy chủ đã dịch thông điệp theo Accept-Language, nên giao diện hiển thị `message` trực tiếp.
 */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly traceId?: string;
  /** 409 slot-unavailable: các slot trống gợi ý (extension suggested-slots). */
  readonly suggestedSlots: string[];

  constructor(status: number, code: string, message: string, traceId?: string, suggestedSlots: string[] = []) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.traceId = traceId;
    this.suggestedSlots = suggestedSlots;
  }
}

/** Lỗi mạng (không có phản hồi HTTP). */
export class NetworkError extends Error {
  constructor() {
    super('network');
    this.name = 'NetworkError';
  }
}

export interface RequestOptions {
  readonly method?: 'GET' | 'POST' | 'PUT' | 'DELETE';
  readonly body?: unknown;
  /** Body application/x-www-form-urlencoded (FHIR _search). Không dùng cùng `body`. */
  readonly form?: URLSearchParams;
  /** Bắt buộc với $book, $cancel, $reschedule. Dùng newIdempotencyKey() một lần cho mỗi lần người dùng bấm. */
  readonly idempotencyKey?: string;
  /** Bắt buộc với PUT và thao tác đổi trạng thái: giá trị ETag của bản đã đọc, ví dụ W/"3". */
  readonly ifMatch?: string;
  readonly signal?: AbortSignal;
}

export interface ApiResponse<T> {
  readonly data: T;
  readonly etag?: string;
  readonly status: number;
}

export function newIdempotencyKey(): string {
  return crypto.randomUUID();
}

/** ETag dạng FHIR từ meta.versionId khi máy chủ không trả header ETag. */
export function etagOf(resource: Resource | undefined): string | undefined {
  const v = resource?.meta?.versionId;
  return v ? `W/"${v}"` : undefined;
}

export async function apiRequest<T>(path: string, options: RequestOptions = {}): Promise<ApiResponse<T>> {
  const headers: Record<string, string> = {
    Accept: path.startsWith('/fhir') ? 'application/fhir+json' : 'application/json',
    'Accept-Language': currentLang(),
  };
  const token = await accessToken();
  if (token) headers['Authorization'] = `Bearer ${token}`;
  let body: string | undefined;
  if (options.form) {
    headers['Content-Type'] = 'application/x-www-form-urlencoded';
    body = options.form.toString();
  } else if (options.body !== undefined) {
    headers['Content-Type'] = path.startsWith('/fhir') ? 'application/fhir+json' : 'application/json';
    body = JSON.stringify(options.body);
  }
  if (options.idempotencyKey) headers['Idempotency-Key'] = options.idempotencyKey;
  if (options.ifMatch) headers['If-Match'] = options.ifMatch;

  let res: Response;
  try {
    res = await fetch(`${getConfig().apiBase}${path}`, {
      method: options.method ?? 'GET',
      headers,
      body,
      signal: options.signal,
    });
  } catch (e) {
    if ((e as Error).name === 'AbortError') throw e;
    throw new NetworkError();
  }

  const text = await res.text();
  const json: unknown = text ? safeJson(text) : undefined;
  if (!res.ok) {
    throw toApiError(res, json);
  }
  return { data: json as T, etag: res.headers.get('ETag') ?? undefined, status: res.status };
}

function safeJson(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return undefined;
  }
}

function toApiError(res: Response, body: unknown): ApiError {
  const traceId = res.headers.get('X-Request-Id') ?? undefined;
  if (isOperationOutcome(body)) {
    const issue = body.issue?.[0];
    const code = issue?.details?.coding?.[0]?.code ?? issue?.code ?? 'exception';
    const suggested =
      body.extension
        ?.filter((e) => e.url.endsWith('suggested-slots'))
        .flatMap((e) => e.extension ?? [])
        .map((e) => e.valueReference?.reference)
        .filter((r): r is string => !!r) ?? [];
    return new ApiError(res.status, code, issue?.diagnostics ?? res.statusText, traceId, suggested);
  }
  if (body && typeof body === 'object' && 'code' in body) {
    const p = body as { code: string; detail?: string; title?: string };
    return new ApiError(res.status, p.code, p.detail ?? p.title ?? res.statusText, traceId);
  }
  return new ApiError(res.status, `http-${res.status}`, res.statusText, traceId);
}

function isOperationOutcome(body: unknown): body is OperationOutcome {
  return !!body && typeof body === 'object' && (body as Resource).resourceType === 'OperationOutcome';
}

/** Lấy các resource trong Bundle searchset. */
export function bundleEntries<T extends Resource>(bundle: Bundle | undefined): T[] {
  return (bundle?.entry ?? []).map((e) => e.resource as T).filter((r): r is T => !!r);
}
