import { http, HttpResponse } from 'msw';
import { decodeJwtPayload, rolesFromClaims, type Role } from '@/auth/roles';
import type { Citation } from '@/api/ai';

/**
 * Mock of the AI service (12-api-ai.md): AI1 (JSON and SSE), AI2, AI3. The question text selects the scenario so tests
 * and the mock-mode demo can show every state of the assistant screen:
 *   "reset"       the first model fails after streaming started (event: reset), the next model streams again;
 *   "offline"     every model fails: 200 answered=false, mode retrieval-only with the related sections;
 *   "unknown"     nothing above the relevance threshold: answered=false, no citations;
 *   "budget"      503 ai-budget-exceeded with retrieval-only citations;
 *   "unavailable" 503 ai-unavailable;
 *   anything else a grounded answer with one citation.
 */

interface StoredMessage {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  citations: Citation[];
  createdAt: string;
}
interface StoredConversation {
  id: string;
  sub: string;
  createdAt: string;
  lastMessageAt: string;
  messages: StoredMessage[];
}

let conversations = new Map<string, StoredConversation>();
let ratings = new Map<string, 'up' | 'down'>();
let sequence = 0;

export function resetAiMock(): void {
  conversations = new Map();
  ratings = new Map();
  sequence = 0;
}

export const CITATION: Citation = {
  index: 1,
  documentId: 'kb-001',
  title: 'Phác đồ tăng huyết áp 2026',
  section: '4.2 Chỉ định chuyển tuyến',
  page: 12,
  chunkId: 'kb-001:1:3',
  quote: '…chuyển tuyến khi huyết áp ≥ 180/110 mmHg kèm dấu hiệu tổn thương cơ quan đích…',
};
const SECOND_CITATION: Citation = {
  index: 2,
  documentId: 'kb-002',
  title: 'Quy trình xử trí cấp cứu tim mạch',
  section: '2.1 Tiếp nhận ban đầu',
  page: 3,
  chunkId: 'kb-002:1:1',
  quote: '…đo huyết áp hai tay và ghi điện tâm đồ trong 10 phút…',
};

const ANSWER = 'Theo phác đồ, chuyển tuyến khi huyết áp ≥ 180/110 mmHg kèm tổn thương cơ quan đích [1].';
const NO_ANSWER = 'Không có thông tin trong tài liệu được phép truy cập.';
const DISCLAIMER = 'Thông tin tham khảo từ tài liệu nội bộ đã duyệt, không thay thế quyết định của bác sĩ.';

const PROBLEMS: Record<string, [number, string, string]> = {
  'ai-budget-exceeded': [503, 'Đã hết hạn mức AI trong ngày.', 'The daily AI budget is exhausted.'],
  'ai-unavailable': [503, 'Trợ lý AI tạm thời không khả dụng.', 'The AI assistant is temporarily unavailable.'],
  'invalid-state-transition': [
    409,
    'Đã đánh giá câu trả lời này; dùng PUT để sửa.',
    'Already rated; use PUT to change it.',
  ],
  'not-found': [404, 'Không tìm thấy.', 'Not found.'],
  forbidden: [403, 'Bạn không có quyền thực hiện thao tác này.', 'You are not allowed to do this.'],
  unauthenticated: [401, 'Chưa đăng nhập.', 'Not signed in.'],
  'validation-failed': [400, 'Dữ liệu không hợp lệ.', 'The data is not valid.'],
  'file-too-large': [413, 'Tệp quá lớn.', 'The file is too large.'],
  'invalid-file-type': [415, 'Loại tệp không được phép.', 'File type not allowed.'],
};

/** Problem Details (RFC 9457) in the language of the request, like the real service. */
export function problem(request: Request, code: string, extra: Record<string, unknown> = {}) {
  const [status, vi, en] = PROBLEMS[code] ?? [500, 'Lỗi', 'Error'];
  const detail = request.headers.get('Accept-Language')?.startsWith('en') ? en : vi;
  return HttpResponse.json(
    { type: `https://shp.example.org/problems/${code}`, title: detail, status, code, detail, ...extra },
    {
      status,
      headers: { 'Content-Type': 'application/problem+json', 'X-Request-Id': `mock-ai-${Date.now().toString(16)}` },
    },
  );
}

export interface AiCaller {
  readonly sub: string;
  readonly roles: Role[];
}

export function aiCaller(request: Request): AiCaller | undefined {
  const auth = request.headers.get('Authorization');
  if (!auth?.startsWith('Bearer ')) return undefined;
  const claims = decodeJwtPayload(auth.slice(7));
  return { sub: String(claims['sub'] ?? ''), roles: rolesFromClaims(claims) };
}

const isStaff = (c: AiCaller) => c.roles.includes('doctor') || c.roles.includes('nurse');
const wait = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

function sse(events: [string, unknown][]): Response {
  const encoder = new TextEncoder();
  const stream = new ReadableStream<Uint8Array>({
    async start(controller) {
      for (const [name, data] of events) {
        controller.enqueue(encoder.encode(`event: ${name}\ndata: ${JSON.stringify(data)}\n\n`));
        await wait(15);
      }
      controller.close();
    },
  });
  return new Response(stream, { headers: { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' } });
}

const chunks = (text: string): [string, unknown][] =>
  (text.match(/.{1,24}/gs) ?? []).map((part) => ['token', { text: part }]);

export const aiHandlers = [
  http.post('/api/v1/ai/assistant/ask', async ({ request }) => {
    const me = aiCaller(request);
    if (!me) return problem(request, 'unauthenticated');
    if (!isStaff(me)) return problem(request, 'forbidden');
    const body = (await request.json()) as { conversationId?: string | null; question: string };
    const q = body.question.toLowerCase();
    if (q.includes('unavailable')) return problem(request, 'ai-unavailable');
    if (q.includes('budget')) {
      return problem(request, 'ai-budget-exceeded', { mode: 'retrieval-only', citations: [CITATION, SECOND_CITATION] });
    }

    const now = new Date().toISOString();
    let conv = body.conversationId ? conversations.get(body.conversationId) : undefined;
    if (body.conversationId && (!conv || conv.sub !== me.sub)) return problem(request, 'not-found');
    if (!conv) {
      conv = { id: `conv-${++sequence}`, sub: me.sub, createdAt: now, lastMessageAt: now, messages: [] };
      conversations.set(conv.id, conv);
    }
    const retrievalOnly = q.includes('offline');
    const noContext = q.includes('unknown');
    const answer = retrievalOnly ? '' : noContext ? NO_ANSWER : ANSWER;
    const citations = noContext ? [] : retrievalOnly ? [CITATION, SECOND_CITATION] : [CITATION];
    const messageId = `msg-${++sequence}`;
    conv.messages.push(
      { id: `msg-${++sequence}`, role: 'user', content: body.question, citations: [], createdAt: now },
      { id: messageId, role: 'assistant', content: answer, citations, createdAt: now },
    );
    conv.lastMessageAt = now;

    const done = {
      conversationId: conv.id,
      messageId,
      answered: !retrievalOnly && !noContext,
      disclaimer: DISCLAIMER,
      ...(retrievalOnly ? { mode: 'retrieval-only' } : noContext ? { mode: 'no-context' } : {}),
      ...(retrievalOnly || noContext
        ? {}
        : {
            model: { provider: 'deepseek', name: 'deepseek-chat', fallbackLevel: q.includes('reset') ? 1 : 0 },
            usage: { inputTokens: 2870, outputTokens: 96, costUsd: 0.0007 },
          }),
    };
    if (!request.headers.get('Accept')?.includes('text/event-stream')) {
      return HttpResponse.json({ ...done, answer, citations });
    }
    const events: [string, unknown][] = [];
    if (q.includes('reset')) {
      events.push(...chunks('Theo phác đồ cũ, chuyển tuyến khi huyết'), ['reset', {}]);
    }
    events.push(...chunks(answer), ['citations', citations], ['done', done]);
    return sse(events);
  }),

  http.post('/api/v1/ai/messages/:id/feedback', async ({ request, params }) => {
    const me = aiCaller(request);
    if (!me) return problem(request, 'unauthenticated');
    const id = String(params['id']);
    const owned = [...conversations.values()].some((c) => c.sub === me.sub && c.messages.some((m) => m.id === id));
    if (!owned) return problem(request, 'not-found');
    if (ratings.has(`${me.sub}:${id}`)) return problem(request, 'invalid-state-transition');
    ratings.set(`${me.sub}:${id}`, ((await request.json()) as { rating: 'up' | 'down' }).rating);
    return new HttpResponse(null, { status: 204 });
  }),

  http.put('/api/v1/ai/messages/:id/feedback', async ({ request, params }) => {
    const me = aiCaller(request);
    if (!me) return problem(request, 'unauthenticated');
    const key = `${me.sub}:${String(params['id'])}`;
    if (!ratings.has(key)) return problem(request, 'not-found');
    ratings.set(key, ((await request.json()) as { rating: 'up' | 'down' }).rating);
    return new HttpResponse(null, { status: 204 });
  }),

  http.get('/api/v1/ai/conversations', ({ request }) => {
    const me = aiCaller(request);
    if (!me) return problem(request, 'unauthenticated');
    const items = [...conversations.values()]
      .filter((c) => c.sub === me.sub)
      .sort((a, b) => b.lastMessageAt.localeCompare(a.lastMessageAt))
      .map((c) => ({ id: c.id, kind: 'assistant', createdAt: c.createdAt, lastMessageAt: c.lastMessageAt }));
    return HttpResponse.json({ items });
  }),

  http.get('/api/v1/ai/conversations/:id', ({ request, params }) => {
    const me = aiCaller(request);
    if (!me) return problem(request, 'unauthenticated');
    const c = conversations.get(String(params['id']));
    if (!c || c.sub !== me.sub) return problem(request, 'not-found');
    return HttpResponse.json({ id: c.id, kind: 'assistant', messages: c.messages });
  }),
];
