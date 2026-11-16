import { ActionIcon, Alert, Button, Group, Modal, Stack, Text, Textarea } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconInfoCircle, IconPlus, IconSend, IconSquare, IconThumbDown, IconThumbUp } from '@tabler/icons-react';
import { useQuery } from '@tanstack/react-query';
import dayjs from 'dayjs';
import { Fragment, useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { listConversations, sendFeedback, type Citation } from '@/api/ai';
import { ApiError } from '@/api/client';
import { Card, EmptyState, Loading, PageHeader, ProblemAlert } from '@/components/ui';
import styles from './Assistant.module.css';
import { useAssistant, type Turn } from './useAssistant';

const MIN_LENGTH = 3;
const MAX_LENGTH = 1000;

/** Knowledge assistant (US-053, US-056): ask, streamed answer with clickable citations, rating and history. */
export function AssistantPage() {
  const { t } = useTranslation();
  const chat = useAssistant();
  const [question, setQuestion] = useState('');
  const [opened, setOpened] = useState<Citation | undefined>();
  const busy = chat.turns.some((x) => x.status === 'streaming');
  const valid = question.trim().length >= MIN_LENGTH;

  const history = useQuery({ queryKey: ['ai', 'conversations'], queryFn: listConversations });

  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (!valid || busy) return;
    const q = question.trim();
    setQuestion('');
    void chat.ask(q);
  };

  const rate = async (turn: Turn, rating: 'up' | 'down') => {
    if (!turn.messageId) return;
    try {
      await sendFeedback(turn.messageId, rating, turn.rated === true);
      chat.patch(turn.key, { rating, rated: true });
      notifications.show({ color: 'teal', message: t('assistant.rated') });
    } catch (e) {
      if (e instanceof ApiError && e.status === 409) {
        // Already rated: the next click changes the rating (PUT).
        chat.patch(turn.key, { rated: true });
        notifications.show({ color: 'yellow', message: t('assistant.alreadyRated') });
      } else {
        notifications.show({ color: 'red', message: e instanceof ApiError ? e.message : t('error.title') });
      }
    }
  };

  return (
    <div className="shp-page">
      <PageHeader
        title={t('assistant.title')}
        sub={t('assistant.sub')}
        actions={
          <Button variant="default" size="xs" leftSection={<IconPlus size={14} />} onClick={chat.newConversation}>
            {t('assistant.newConversation')}
          </Button>
        }
      />
      <div className={styles.layout}>
        <div className={styles.main}>
          <ProblemAlert error={chat.loadError} />
          <Card flush>
            <div className={styles.thread} aria-live="polite">
              {chat.turns.length === 0 ? (
                <EmptyState title={t('assistant.empty')}>{t('assistant.emptyHint')}</EmptyState>
              ) : (
                chat.turns.map((turn) => (
                  <TurnView key={turn.key} turn={turn} onCitation={setOpened} onRate={(r) => void rate(turn, r)} />
                ))
              )}
            </div>
            <form onSubmit={submit} className={styles.form}>
              <Textarea
                label={t('assistant.question')}
                placeholder={t('assistant.placeholder')}
                value={question}
                onChange={(e) => setQuestion(e.currentTarget.value)}
                maxLength={MAX_LENGTH}
                autosize
                minRows={2}
                maxRows={6}
                description={t('assistant.limit', { min: MIN_LENGTH, max: MAX_LENGTH })}
              />
              <Group justify="flex-end" mt="xs">
                {busy && (
                  <Button variant="default" leftSection={<IconSquare size={14} />} onClick={chat.stop}>
                    {t('assistant.stop')}
                  </Button>
                )}
                <Button type="submit" leftSection={<IconSend size={14} />} disabled={!valid || busy}>
                  {t('assistant.ask')}
                </Button>
              </Group>
            </form>
          </Card>
        </div>
        <Card title={t('assistant.history')} count={history.data?.length} flush>
          {history.isLoading ? (
            <Loading />
          ) : (history.data ?? []).length === 0 ? (
            <EmptyState title={t('assistant.noHistory')} />
          ) : (
            <ul className={styles.history}>
              {(history.data ?? []).map((c) => (
                <li key={c.id}>
                  <Button
                    variant={c.id === chat.conversationId ? 'light' : 'subtle'}
                    size="xs"
                    fullWidth
                    justify="flex-start"
                    onClick={() => void chat.open(c.id)}
                  >
                    {dayjs(c.lastMessageAt).format('DD/MM/YYYY HH:mm')}
                  </Button>
                </li>
              ))}
            </ul>
          )}
          <Text size="xs" c="dimmed" p="sm">
            {t('assistant.retention')}
          </Text>
        </Card>
      </div>
      <Modal
        opened={!!opened}
        onClose={() => setOpened(undefined)}
        title={opened ? `[${opened.index}] ${opened.title}` : ''}
        size="lg"
        closeButtonProps={{ 'aria-label': t('common.close') }}
      >
        {opened && <CitationDetail citation={opened} />}
      </Modal>
    </div>
  );
}

function CitationDetail({ citation }: { readonly citation: Citation }) {
  const { t } = useTranslation();
  return (
    <Stack gap="xs">
      <Text size="sm">
        <b>{t('assistant.section')}:</b> {citation.section || '-'}
      </Text>
      {citation.page != null && (
        <Text size="sm">
          <b>{t('assistant.page')}:</b> {citation.page}
        </Text>
      )}
      {citation.quote && (
        <blockquote className={styles.quote} aria-label={t('assistant.quote')}>
          {citation.quote}
        </blockquote>
      )}
      <Text size="xs" c="dimmed">
        {t('assistant.document')}: {citation.documentId}
      </Text>
    </Stack>
  );
}

/** Render the answer with the [n] markers turned into buttons that open the cited section. */
function AnswerText(props: {
  readonly text: string;
  readonly citations: readonly Citation[];
  readonly onCitation: (c: Citation) => void;
}) {
  const { t } = useTranslation();
  const parts = props.text.split(/(\[\d+\])/g);
  return (
    <p className={styles.answer}>
      {parts.map((part, i) => {
        const n = /^\[(\d+)\]$/.exec(part)?.[1];
        const cite = n ? props.citations.find((c) => c.index === Number(n)) : undefined;
        return cite ? (
          <button
            key={i}
            type="button"
            className={styles.ref}
            aria-label={t('assistant.openCitation', { n: cite.index, title: cite.title })}
            onClick={() => props.onCitation(cite)}
          >
            {part}
          </button>
        ) : (
          <Fragment key={i}>{part}</Fragment>
        );
      })}
    </p>
  );
}

function TurnView(props: {
  readonly turn: Turn;
  readonly onCitation: (c: Citation) => void;
  readonly onRate: (r: 'up' | 'down') => void;
}) {
  const { t } = useTranslation();
  const { turn } = props;
  const retrievalOnly = turn.done?.mode === 'retrieval-only' || (turn.status === 'error' && turn.citations.length > 0);
  const unanswered = turn.done && !turn.done.answered && !retrievalOnly;
  return (
    <div className={styles.turn}>
      <div className={styles.q}>{turn.question}</div>
      {turn.status === 'streaming' && !turn.answer && (
        <Text size="sm" c="dimmed" aria-busy="true">
          {t('assistant.thinking')}
        </Text>
      )}
      {turn.answer && !retrievalOnly && (
        <div className={unanswered ? styles.none : undefined}>
          <AnswerText text={turn.answer} citations={turn.citations} onCitation={props.onCitation} />
        </div>
      )}
      {retrievalOnly && (
        <Alert color="yellow" icon={<IconInfoCircle size={18} />} title={t('assistant.retrievalOnly')} mb="xs">
          {t('assistant.retrievalOnlyHint')}
        </Alert>
      )}
      {turn.status === 'stopped' && (
        <Text size="xs" c="dimmed">
          {t('assistant.stopped')}
        </Text>
      )}
      <ProblemAlert error={turn.error} />
      {turn.citations.length > 0 && (
        <div>
          <Text size="xs" fw={600} c="dimmed" mb={4}>
            {retrievalOnly ? t('assistant.relatedSections') : t('assistant.sources')}
          </Text>
          <ul className={styles.sources}>
            {turn.citations.map((c) => (
              <li key={c.index}>
                <Button variant="light" size="compact-sm" onClick={() => props.onCitation(c)}>
                  [{c.index}] {c.title}
                  {c.section ? ` - ${c.section}` : ''}
                </Button>
              </li>
            ))}
          </ul>
        </div>
      )}
      {turn.status === 'done' && turn.messageId && (
        <Group gap="xs" mt="xs" role="group" aria-label={t('assistant.rate')}>
          <ActionIcon
            variant={turn.rating === 'up' ? 'filled' : 'default'}
            aria-label={t('assistant.helpful')}
            aria-pressed={turn.rating === 'up'}
            onClick={() => props.onRate('up')}
          >
            <IconThumbUp size={16} />
          </ActionIcon>
          <ActionIcon
            variant={turn.rating === 'down' ? 'filled' : 'default'}
            aria-label={t('assistant.notHelpful')}
            aria-pressed={turn.rating === 'down'}
            onClick={() => props.onRate('down')}
          >
            <IconThumbDown size={16} />
          </ActionIcon>
          {turn.done?.disclaimer && (
            <Text size="xs" c="dimmed">
              {turn.done.disclaimer}
            </Text>
          )}
        </Group>
      )}
    </div>
  );
}
