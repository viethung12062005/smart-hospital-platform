import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { signInMock } from '@/auth/session';
import { server } from '@/mocks/server';
import { ApiError } from './client';
import { askStream, readSse, type AskEvent } from './ai';

function streamOf(...chunks: string[]): ReadableStream<Uint8Array> {
  const enc = new TextEncoder();
  return new ReadableStream({
    start(c) {
      chunks.forEach((x) => c.enqueue(enc.encode(x)));
      c.close();
    },
  });
}

async function collect(gen: AsyncGenerator<AskEvent>): Promise<AskEvent[]> {
  const out: AskEvent[] = [];
  for await (const e of gen) out.push(e);
  return out;
}

describe('readSse', () => {
  it('joins events split across chunks, handles CRLF, comments and multi-line data', async () => {
    const msgs = [];
    for await (const m of readSse(
      streamOf('event: token\nda', 'ta: {"text":"a"}\r\n\r\n: ping\n\nevent: x\ndata: 1\ndata: 2\n\n'),
    )) {
      msgs.push(m);
    }
    expect(msgs).toEqual([
      { event: 'token', data: '{"text":"a"}' },
      { event: 'x', data: '1\n2' },
    ]);
  });
});

describe('askStream', () => {
  const sse = (body: string) =>
    http.post(
      '/api/v1/ai/assistant/ask',
      () => new HttpResponse(body, { headers: { 'Content-Type': 'text/event-stream' } }),
    );

  it('sends the token, Accept-Language and the event-stream Accept header, and yields the events in order', async () => {
    signInMock('doctor');
    let headers: Headers | undefined;
    server.use(
      http.post('/api/v1/ai/assistant/ask', ({ request }) => {
        headers = request.headers;
        return new HttpResponse(
          'event: token\ndata: {"text":"Xin "}\n\nevent: token\ndata: {"text":"chào"}\n\nevent: citations\ndata: []\n\nevent: done\ndata: {"conversationId":"c","messageId":"m","answered":true}\n\n',
          { headers: { 'Content-Type': 'text/event-stream' } },
        );
      }),
    );
    const events = await collect(askStream({ question: 'abc' }));
    expect(events.map((e) => e.type)).toEqual(['token', 'token', 'citations', 'done']);
    expect(headers?.get('Authorization')).toMatch(/^Bearer /);
    expect(headers?.get('Accept')).toBe('text/event-stream');
    expect(headers?.get('Accept-Language')).toBe('vi');
  });

  it('turns an error event into an ApiError with the problem code', async () => {
    signInMock('doctor');
    server.use(
      sse('event: token\ndata: {"text":"x"}\n\nevent: error\ndata: {"code":"ai-unavailable","detail":"down"}\n\n'),
    );
    await expect(collect(askStream({ question: 'abc' }))).rejects.toMatchObject({
      code: 'ai-unavailable',
      message: 'down',
    });
  });

  it('falls back to a plain JSON answer', async () => {
    signInMock('doctor');
    server.use(
      http.post('/api/v1/ai/assistant/ask', () =>
        HttpResponse.json({ conversationId: 'c', messageId: 'm', answer: 'Trả lời', citations: [], answered: true }),
      ),
    );
    const events = await collect(askStream({ question: 'abc' }));
    expect(events.map((e) => e.type)).toEqual(['token', 'citations', 'done']);
  });

  it('throws ApiError for an HTTP error before the stream starts', async () => {
    signInMock('doctor');
    server.use(
      http.post('/api/v1/ai/assistant/ask', () =>
        HttpResponse.json({ code: 'ai-budget-exceeded', detail: 'hết' }, { status: 503 }),
      ),
    );
    await expect(collect(askStream({ question: 'abc' }))).rejects.toBeInstanceOf(ApiError);
  });
});
