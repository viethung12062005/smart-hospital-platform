import type { Bundle, OperationOutcome, Resource } from '@medplum/fhirtypes';
import { accessToken } from '@/auth/session';
import { getConfig } from '@/config';
import { currentLang } from '@/i18n';

/**
 * API errors per convention 07-api-conventions §5:
 * - /fhir/*  → OperationOutcome, code in issue[0].details.coding[0].code, message in diagnostics;
 * - everything else → Problem Details (RFC 9457) with `code` and `detail`.
 * The server has already translated the message per Accept-Language, so the UI displays `message` directly.
 */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly traceId?: string;
  /** 409 slot-unavailable: the suggested free slots (extension suggested-slots). */
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

/** Network error (no HTTP response). */
export class NetworkError extends Error {
  constructor() {
    super('network');
    this.name = 'NetworkError';
  }
}

export interface RequestOptions {
  readonly method?: 'GET' | 'POST' | 'PUT' | 'DELETE';
  readonly body?: unknown;
  /** application/x-www-form-urlencoded body (FHIR _search). Do not use together with `body`. */
  readonly form?: URLSearchParams;
  /** Required for $book, $cancel, $reschedule. Use newIdempotencyKey() once per user click. */
  readonly idempotencyKey?: string;
  /** Required for PUT and status-changing operations: the ETag of the version that was read, for example W/"3". */
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

/** FHIR-style ETag from meta.versionId when the server does not return an ETag header. */
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

/** Get the resources from a searchset Bundle. */
export function bundleEntries<T extends Resource>(bundle: Bundle | undefined): T[] {
  return (bundle?.entry ?? []).map((e) => e.resource as T).filter((r): r is T => !!r);
}
