import { onSessionExpired, accessToken, renewAccessToken } from '@/auth/session';
import { getConfig } from '@/config';
import { currentLang } from '@/i18n';
import { ApiError, NetworkError, apiRequest } from './client';

/**
 * AI service API (/api/v1/ai, docs/03-architecture/detailed-design/12-api-ai.md).
 *
 * `apiRequest` only sends JSON and always buffers the body, so the two calls that need more (AI1 as a Server-Sent
 * Events stream over POST with an Authorization header, which EventSource cannot do, and the AI9 multipart upload)
 * go through `aiFetch` below. It follows the same rules as the shared client: Bearer token, Accept-Language, one
 * silent token renewal on 401, Problem Details mapped to `ApiError`.
 */

// ---------------------------------------------------------------------------------------------------------------
// Types (12-api-ai.md)
// ---------------------------------------------------------------------------------------------------------------

export interface Citation {
  readonly index: number;
  readonly documentId: string;
  readonly title: string;
  readonly section?: string;
  readonly page?: number | null;
  readonly chunkId?: string;
  readonly quote?: string;
}

export type AskMode = 'answer' | 'retrieval-only' | 'no-context' | 'guardrail';

/** The final `done` event of AI1 (everything of the JSON response except `answer` and `citations`). */
export interface AskDone {
  readonly conversationId: string;
  readonly messageId: string;
  readonly answered: boolean;
  readonly mode?: AskMode;
  readonly guardrail?: string;
  readonly model?: { readonly provider: string; readonly name: string; readonly fallbackLevel: number };
  readonly usage?: { readonly inputTokens: number; readonly outputTokens: number; readonly costUsd: number };
  readonly disclaimer?: string;
}

export interface AskRequest {
  readonly conversationId?: string | null;
  readonly question: string;
  readonly department?: string;
}

export type AskEvent =
  | { readonly type: 'token'; readonly text: string }
  /** The model failed after streaming started: discard everything streamed so far, the next model re-streams. */
  | { readonly type: 'reset' }
  | { readonly type: 'citations'; readonly citations: readonly Citation[] }
  | ({ readonly type: 'done' } & AskDone);

export interface ConversationSummary {
  readonly id: string;
  readonly kind: 'assistant' | 'booking-bot';
  readonly createdAt: string;
  readonly lastMessageAt: string;
}

export interface ConversationMessage {
  readonly id: string;
  readonly role: 'user' | 'assistant';
  readonly content: string;
  readonly citations: readonly Citation[];
  readonly createdAt: string;
}

export interface Conversation {
  readonly id: string;
  readonly kind: string;
  readonly messages: readonly ConversationMessage[];
}

/** A Problem Details error that also carries the retrieval-only citations (503 ai-budget-exceeded, 12 §2). */
export class AiProblem extends ApiError {
  readonly mode?: string;
  readonly citations: readonly Citation[];

  constructor(base: ApiError, mode: string | undefined, citations: readonly Citation[]) {
    super(base.status, base.code, base.message, base.traceId);
    this.mode = mode;
    this.citations = citations;
  }
}

// ---------------------------------------------------------------------------------------------------------------
// Transport
// ---------------------------------------------------------------------------------------------------------------

export interface AiFetchOptions {
  readonly method?: 'GET' | 'POST' | 'PUT';
  readonly accept?: string;
  /** JSON body, or FormData for an upload (the browser sets the multipart boundary itself). */
  readonly body?: unknown;
  readonly signal?: AbortSignal;
}

async function aiSend(path: string, o: AiFetchOptions, token: string | undefined): Promise<Response> {
  const headers: Record<string, string> = {
    Accept: o.accept ?? 'application/json',
    'Accept-Language': currentLang(),
  };
  if (token) headers['Authorization'] = `Bearer ${token}`;
  let body: BodyInit | undefined;
  if (o.body instanceof FormData) {
    body = o.body;
  } else if (o.body !== undefined) {
    headers['Content-Type'] = 'application/json';
    body = JSON.stringify(o.body);
  }
  try {
    return await fetch(`${getConfig().apiBase}${path}`, { method: o.method ?? 'GET', headers, body, signal: o.signal });
  } catch (e) {
    if ((e as Error).name === 'AbortError') throw e;
    throw new NetworkError();
  }
}

/** Send a request and return the successful Response (body not yet read); throws `ApiError` otherwise. */
export async function aiFetch(path: string, o: AiFetchOptions = {}): Promise<Response> {
  let res = await aiSend(path, o, await accessToken());
  if (res.status === 401 && !o.signal?.aborted) {
    const renewed = await renewAccessToken();
    if (renewed) res = await aiSend(path, o, renewed);
    else await onSessionExpired();
  }
  if (!res.ok) throw await problemOf(res);
  return res;
}

async function problemOf(res: Response): Promise<ApiError> {
  const traceId = res.headers.get('X-Request-Id') ?? undefined;
  let json: unknown;
  try {
    json = JSON.parse(await res.text());
  } catch {
    json = undefined;
  }
  const p = (json && typeof json === 'object' ? json : {}) as {
    code?: string;
    detail?: string;
    title?: string;
    mode?: string;
    citations?: Citation[];
  };
  const err = new ApiError(res.status, p.code ?? `http-${res.status}`, p.detail ?? p.title ?? res.statusText, traceId);
  return p.mode || p.citations ? new AiProblem(err, p.mode, p.citations ?? []) : err;
}

// ---------------------------------------------------------------------------------------------------------------
// SSE
// ---------------------------------------------------------------------------------------------------------------

export interface SseMessage {
  readonly event: string;
  readonly data: string;
}

/** Parse a `text/event-stream` body into messages (handles chunk boundaries, CRLF, multi-line data, comments). */
export async function* readSse(body: ReadableStream<Uint8Array>): AsyncGenerator<SseMessage> {
  const reader = body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  const parse = (block: string): SseMessage | undefined => {
    let event = 'message';
    const data: string[] = [];
    for (const line of block.split('\n')) {
      if (line.startsWith(':')) continue;
      const i = line.indexOf(':');
      const field = i < 0 ? line : line.slice(0, i);
      const value = i < 0 ? '' : line.slice(i + 1).replace(/^ /, '');
      if (field === 'event') event = value;
      else if (field === 'data') data.push(value);
    }
    return data.length > 0 ? { event, data: data.join('\n') } : undefined;
  };
  try {
    for (;;) {
      const { value, done } = await reader.read();
      buffer += decoder.decode(value, { stream: !done }).replace(/\r\n?/g, '\n');
      let cut = buffer.indexOf('\n\n');
      while (cut >= 0) {
        const msg = parse(buffer.slice(0, cut));
        buffer = buffer.slice(cut + 2);
        if (msg) yield msg;
        cut = buffer.indexOf('\n\n');
      }
      if (done) break;
    }
    const rest = parse(buffer);
    if (rest) yield rest;
  } finally {
    reader.releaseLock();
  }
}

function json<T>(text: string): T | undefined {
  try {
    return JSON.parse(text) as T;
  } catch {
    return undefined;
  }
}

/**
 * AI1: ask the knowledge assistant and stream the answer. Yields `token`, `reset`, `citations` and `done` events in
 * the order the server sends them. An `error` event (or an HTTP error before the stream starts) throws an `ApiError`
 * with the Problem Details `code`, so ai-unavailable and ai-budget-exceeded reach `ProblemAlert` like any other error.
 * If the server answers with plain JSON instead of a stream, the same events are produced from it.
 */
export async function* askStream(req: AskRequest, signal?: AbortSignal): AsyncGenerator<AskEvent> {
  const res = await aiFetch('/api/v1/ai/assistant/ask', {
    method: 'POST',
    accept: 'text/event-stream',
    body: req,
    signal,
  });
  const type = res.headers.get('Content-Type') ?? '';
  if (!type.includes('text/event-stream') || !res.body) {
    const full = json<AskDone & { answer: string; citations: Citation[] }>(await res.text());
    if (!full) throw new ApiError(res.status, 'invalid-response', res.statusText);
    const { answer, citations, ...done } = full;
    if (answer) yield { type: 'token', text: answer };
    yield { type: 'citations', citations: citations ?? [] };
    yield { type: 'done', ...done };
    return;
  }
  try {
    for await (const m of readSse(res.body)) {
      if (m.event === 'token') {
        const d = json<{ text?: string }>(m.data);
        if (d?.text) yield { type: 'token', text: d.text };
      } else if (m.event === 'reset') {
        yield { type: 'reset' };
      } else if (m.event === 'citations') {
        yield { type: 'citations', citations: json<Citation[]>(m.data) ?? [] };
      } else if (m.event === 'done') {
        yield { type: 'done', ...(json<AskDone>(m.data) as AskDone) };
        return;
      } else if (m.event === 'error') {
        const d = json<{ code?: string; detail?: string; title?: string }>(m.data) ?? {};
        throw new ApiError(res.status, d.code ?? 'ai-unavailable', d.detail ?? d.title ?? 'error');
      }
    }
  } catch (e) {
    if (e instanceof ApiError || (e as Error).name === 'AbortError') throw e;
    throw new NetworkError(); // the connection broke in the middle of the stream
  }
}

// ---------------------------------------------------------------------------------------------------------------
// AI2, AI3
// ---------------------------------------------------------------------------------------------------------------

/**
 * AI2: rate an answer. The first rating is a POST (409 when the message was already rated); `replace` sends the PUT
 * that changes an existing rating.
 */
export async function sendFeedback(
  messageId: string,
  rating: 'up' | 'down',
  replace = false,
  comment?: string,
): Promise<void> {
  await apiRequest<void>(`/api/v1/ai/messages/${encodeURIComponent(messageId)}/feedback`, {
    method: replace ? 'PUT' : 'POST',
    body: comment ? { rating, comment } : { rating },
  });
}

export async function listConversations(): Promise<ConversationSummary[]> {
  const { data } = await apiRequest<{ items: ConversationSummary[] }>('/api/v1/ai/conversations?kind=assistant');
  return data.items;
}

export async function getConversation(id: string): Promise<Conversation> {
  const { data } = await apiRequest<Conversation>(`/api/v1/ai/conversations/${encodeURIComponent(id)}`);
  return data;
}
