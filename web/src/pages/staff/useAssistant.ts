import { useQueryClient } from '@tanstack/react-query';
import { useCallback, useEffect, useRef, useState } from 'react';
import { askStream, getConversation, type AskDone, type Citation } from '@/api/ai';

/** One question and its answer in the conversation. */
export interface Turn {
  readonly key: string;
  readonly question: string;
  readonly answer: string;
  readonly status: 'streaming' | 'done' | 'error' | 'stopped';
  readonly citations: readonly Citation[];
  readonly done?: AskDone;
  /** Set when the stream failed; shown with ProblemAlert. */
  readonly error?: unknown;
  /** The rating given (or loaded) for this answer. */
  readonly rating?: 'up' | 'down';
  /** The server answered 409 on the first rating: the next click changes it with PUT. */
  readonly rated?: boolean;
  readonly messageId?: string;
}

let counter = 0;
const nextKey = () => `turn-${++counter}`;

/** State of the assistant screen: the turns, the stream of the running question and the loaded conversation. */
export function useAssistant() {
  const queryClient = useQueryClient();
  const [turns, setTurns] = useState<Turn[]>([]);
  const [conversationId, setConversationId] = useState<string | null>(null);
  const conversationRef = useRef<string | null>(null);
  const abortRef = useRef<AbortController | undefined>(undefined);
  const [loadError, setLoadError] = useState<unknown>();

  useEffect(() => () => abortRef.current?.abort(), []);

  const patch = useCallback((key: string, change: Partial<Turn> | ((t: Turn) => Partial<Turn>)) => {
    setTurns((all) =>
      all.map((t) => (t.key === key ? { ...t, ...(typeof change === 'function' ? change(t) : change) } : t)),
    );
  }, []);

  const setConversation = (id: string | null) => {
    conversationRef.current = id;
    setConversationId(id);
  };

  const ask = useCallback(
    async (question: string) => {
      const key = nextKey();
      const abort = new AbortController();
      abortRef.current = abort;
      setTurns((all) => [...all, { key, question, answer: '', status: 'streaming', citations: [] }]);
      try {
        for await (const ev of askStream({ conversationId: conversationRef.current, question }, abort.signal)) {
          if (ev.type === 'token') patch(key, (t) => ({ answer: t.answer + ev.text }));
          else if (ev.type === 'reset') patch(key, { answer: '', citations: [] });
          else if (ev.type === 'citations') patch(key, { citations: ev.citations });
          else {
            const done: AskDone = ev;
            patch(key, { status: 'done', done, messageId: done.messageId });
            setConversation(done.conversationId);
            void queryClient.invalidateQueries({ queryKey: ['ai', 'conversations'] });
          }
        }
        patch(key, (t) => (t.status === 'streaming' ? { status: 'stopped' } : {}));
      } catch (e) {
        if ((e as Error).name === 'AbortError') {
          patch(key, { status: 'stopped' });
        } else {
          // 503 ai-budget-exceeded still carries the related documents (retrieval-only mode).
          const citations = (e as { citations?: readonly Citation[] }).citations;
          patch(key, { status: 'error', error: e, ...(citations ? { citations } : {}) });
        }
      }
    },
    [patch, queryClient],
  );

  const stop = useCallback(() => abortRef.current?.abort(), []);

  const newConversation = useCallback(() => {
    abortRef.current?.abort();
    setTurns([]);
    setConversation(null);
    setLoadError(undefined);
  }, []);

  /** Load a conversation from the history (AI3) and continue it. */
  const open = useCallback(async (id: string) => {
    abortRef.current?.abort();
    setLoadError(undefined);
    try {
      const conv = await getConversation(id);
      const loaded: Turn[] = [];
      let question = '';
      for (const m of conv.messages) {
        if (m.role === 'user') {
          question = m.content;
        } else {
          loaded.push({
            key: nextKey(),
            question,
            answer: m.content,
            status: 'done',
            citations: m.citations,
            messageId: m.id,
          });
          question = '';
        }
      }
      setTurns(loaded);
      setConversation(id);
    } catch (e) {
      setLoadError(e);
    }
  }, []);

  return { turns, conversationId, loadError, ask, stop, newConversation, open, patch };
}
