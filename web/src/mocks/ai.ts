import { http, HttpResponse } from 'msw';
import { decodeJwtPayload, rolesFromClaims, type Role } from '@/auth/roles';
import type { Citation, KbDocument, UsageRow } from '@/api/ai';

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
let kb: KbDocument[] = seedKb();
/** The mock doctor (Practitioner/pr-001) works in Cardiology: AI9 lets a doctor approve only their own department. */
const DOCTOR_DEPARTMENT = 'cardiology';

function seedKb(): KbDocument[] {
  const base = {
    source: 'Bộ Y tế',
    allowedRoles: ['doctor', 'nurse'],
    contentType: 'application/pdf',
    sizeBytes: 1_200_000,
  };
  const at = '2026-11-02T08:00:00Z';
  return [
    {
      ...base,
      id: 'kb-001',
      title: 'Phác đồ tăng huyết áp 2026',
      department: 'cardiology',
      version: 1,
      status: 'indexed',
      indexedVersion: 1,
      chunkCount: 42,
      updatedAt: at,
    },
    {
      ...base,
      id: 'kb-002',
      title: 'Quy trình xử trí cấp cứu tim mạch',
      department: 'cardiology',
      version: 2,
      status: 'indexed',
      indexedVersion: 2,
      chunkCount: 18,
      updatedAt: at,
    },
    {
      ...base,
      id: 'kb-003',
      title: 'Quy trình vệ sinh tay',
      department: 'hospital-wide',
      version: 1,
      status: 'draft',
      updatedAt: at,
    },
    {
      ...base,
      id: 'kb-004',
      title: 'Hướng dẫn tiêm chủng cho trẻ',
      department: 'pediatrics',
      version: 1,
      status: 'draft',
      updatedAt: at,
    },
    {
      ...base,
      id: 'kb-005',
      title: 'Phác đồ đái tháo đường típ 2',
      department: 'endocrinology',
      version: 1,
      status: 'approved',
      indexError: 'EmbeddingUnavailable',
      updatedAt: at,
    },
    {
      ...base,
      id: 'kb-006',
      title: 'Phác đồ suy tim 2026',
      department: 'cardiology',
      version: 1,
      status: 'draft',
      updatedAt: at,
    },
    {
      ...base,
      id: 'kb-007',
      title: 'Quy trình cũ về truyền máu',
      department: 'hospital-wide',
      version: 3,
      status: 'retired',
      updatedAt: at,
    },
  ];
}

export function resetAiMock(): void {
  conversations = new Map();
  ratings = new Map();
  sequence = 0;
  kb = seedKb();
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

const isAdmin = (c: AiCaller) => c.roles.includes('admin');
const isStaff = (c: AiCaller) => c.roles.includes('doctor') || c.roles.includes('nurse');
interface Multipart {
  readonly fields: Map<string, string[]>;
  readonly file?: { readonly name: string; readonly type: string; readonly size: number };
}

/**
 * Minimal multipart/form-data reader. `request.formData()` cannot be used because jsdom's File is not the File that the
 * Node HTTP layer expects when the tests run; this works the same in Vitest and in the browser.
 */
async function readMultipart(request: Request): Promise<Multipart> {
  const boundary = /boundary=(?:"([^"]+)"|([^;]+))/.exec(request.headers.get('Content-Type') ?? '');
  const marker = `--${boundary?.[1] ?? boundary?.[2] ?? ''}`;
  const fields = new Map<string, string[]>();
  let file: Multipart['file'];
  for (const part of (await request.text()).split(marker).slice(1)) {
    const split = part.indexOf('\r\n\r\n');
    if (split < 0) continue;
    const head = part.slice(0, split);
    const name = /name="([^"]*)"/.exec(head)?.[1];
    const body = part.slice(split + 4).replace(/\r\n$/, '');
    if (!name) continue;
    const filename = /filename="([^"]*)"/.exec(head)?.[1];
    if (filename !== undefined) {
      file = { name: filename, type: /Content-Type: ([^\r\n]+)/i.exec(head)?.[1] ?? '', size: body.length };
    } else fields.set(name, [...(fields.get(name) ?? []), body]);
  }
  return { fields, file };
}

/** The real service checks the file signature; the mock accepts by extension or declared type. */
function allowedFile(file: NonNullable<Multipart['file']>): boolean {
  return (
    /\.(pdf|docx|md)$/i.test(file.name) || /^(application\/pdf|text\/markdown|.*wordprocessingml.*)$/i.test(file.type)
  );
}

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

  // ---- AI9: knowledge base ----
  http.get('/api/v1/ai/kb/documents', ({ request }) => {
    const me = aiCaller(request);
    if (!me) return problem(request, 'unauthenticated');
    if (!isAdmin(me) && !me.roles.includes('doctor')) return problem(request, 'forbidden');
    // The background indexing of an approved document finishes between two list calls.
    kb = kb.map((d) =>
      d.status === 'approved' && !d.indexError
        ? { ...d, status: 'indexed', indexedVersion: d.version, chunkCount: 12, indexedAt: new Date().toISOString() }
        : d,
    );
    const status = new URL(request.url).searchParams.get('status');
    const items = kb
      .filter((d) => isAdmin(me) || d.department === DOCTOR_DEPARTMENT || d.department === 'hospital-wide')
      .filter((d) => !status || d.status === status);
    return HttpResponse.json({ items });
  }),

  http.post('/api/v1/ai/kb/documents', async ({ request }) => {
    const me = aiCaller(request);
    if (!me) return problem(request, 'unauthenticated');
    if (!isAdmin(me)) return problem(request, 'forbidden');
    const { fields, file } = await readMultipart(request);
    const title = fields.get('title')?.[0] ?? '';
    const source = fields.get('source')?.[0] ?? '';
    const department = fields.get('department')?.[0] ?? '';
    const roles = fields.get('allowedRoles') ?? [];
    if (!file || title.length < 3 || source.length < 3 || !department || roles.length === 0) {
      return problem(request, 'validation-failed');
    }
    if (file.size > 30 * 1024 * 1024) return problem(request, 'file-too-large');
    if (!allowedFile(file)) return problem(request, 'invalid-file-type');
    const doc: KbDocument = {
      id: `kb-new-${++sequence}`,
      title,
      source,
      department,
      allowedRoles: roles,
      version: 1,
      status: 'draft',
      sizeBytes: file.size,
      updatedAt: new Date().toISOString(),
    };
    kb = [doc, ...kb];
    return HttpResponse.json(doc, { status: 201 });
  }),

  http.put('/api/v1/ai/kb/documents/:id', async ({ request, params }) => {
    const me = aiCaller(request);
    if (!me) return problem(request, 'unauthenticated');
    if (!isAdmin(me)) return problem(request, 'forbidden');
    const cur = kb.find((d) => d.id === params['id']);
    if (!cur) return problem(request, 'not-found');
    if (cur.status === 'retired') return problem(request, 'invalid-state-transition');
    const { file } = await readMultipart(request);
    if (!file) return problem(request, 'validation-failed');
    if (!allowedFile(file)) return problem(request, 'invalid-file-type');
    const next: KbDocument = {
      ...cur,
      version: cur.version + 1,
      status: 'draft',
      indexError: null,
      updatedAt: new Date().toISOString(),
    };
    kb = kb.map((d) => (d.id === cur.id ? next : d));
    return HttpResponse.json(next);
  }),

  http.post('/api/v1/ai/kb/documents/:id/approve', ({ request, params }) => {
    const me = aiCaller(request);
    if (!me) return problem(request, 'unauthenticated');
    const cur = kb.find((d) => d.id === params['id']);
    if (!cur) return problem(request, 'not-found');
    if (!isAdmin(me) && (!me.roles.includes('doctor') || cur.department !== DOCTOR_DEPARTMENT)) {
      return problem(request, 'forbidden');
    }
    if (cur.status !== 'draft') return problem(request, 'invalid-state-transition');
    const next: KbDocument = { ...cur, status: 'approved', approvedAt: new Date().toISOString() };
    kb = kb.map((d) => (d.id === cur.id ? next : d));
    return HttpResponse.json(next);
  }),

  http.post('/api/v1/ai/kb/documents/:id/retire', ({ request, params }) => {
    const me = aiCaller(request);
    if (!me) return problem(request, 'unauthenticated');
    if (!isAdmin(me)) return problem(request, 'forbidden');
    const cur = kb.find((d) => d.id === params['id']);
    if (!cur) return problem(request, 'not-found');
    const next: KbDocument = { ...cur, status: 'retired', indexedVersion: null, chunkCount: null };
    kb = kb.map((d) => (d.id === cur.id ? next : d));
    return HttpResponse.json(next);
  }),

  // ---- AI10: usage ----
  http.get('/api/v1/ai/usage', ({ request }) => {
    const me = aiCaller(request);
    if (!me) return problem(request, 'unauthenticated');
    if (!isAdmin(me)) return problem(request, 'forbidden');
    const qs = new URL(request.url).searchParams;
    const groupBy = qs.get('groupBy') ?? 'task';
    const today = new Date();
    const day = (back: number) => {
      const d = new Date(today);
      d.setDate(d.getDate() - back);
      return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
    };
    const row = (key: string, requests: number, inTok: number, outTok: number, cost: number, fb: number): UsageRow => ({
      key,
      requests,
      inputTokens: inTok,
      outputTokens: outTok,
      costUsd: cost,
      fallbackRate: fb,
    });
    // Today is at 86.5 % of the documented 2,000,000-token daily budget, so the 80 % warning shows.
    const days = [
      row(day(2), 900, 2_100_000, 90_000, 9.4, 0.01),
      row(day(1), 1180, 3_200_000, 120_000, 14.2, 0.03),
      row(day(0), 700, 1_650_000, 80_000, 7.1, 0.05),
    ];
    const items =
      groupBy === 'day'
        ? days.filter((r) => (!qs.get('from') || r.key >= qs.get('from')!) && (!qs.get('to') || r.key <= qs.get('to')!))
        : groupBy === 'model'
          ? [
              row('deepseek/deepseek-chat', 2500, 6_500_000, 270_000, 28.9, 0.02),
              row('gemini/gemini-flash', 280, 450_000, 20_000, 1.8, 0.4),
            ]
          : [
              row('booking-agent', 300, 500_000, 40_000, 3.2, 0),
              row('rag-answer', 2480, 6_450_000, 250_000, 27.5, 0.04),
            ];
    return HttpResponse.json({ items });
  }),
];
