import { Button, Group, Modal, Stack, Text, Textarea, TextInput } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import type { Slot } from '@medplum/fhirtypes';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import dayjs from 'dayjs';
import { useRef, useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { newIdempotencyKey } from '@/api/client';
import { formatTime } from '@/api/fhir';
import { blockSlot, generateSlots, unblockSlot } from '@/api/scheduling';
import { ProblemAlert } from '@/components/ui';

export const MAX_REASON_LENGTH = 200;

/** S7: generate slots for a date range of the selected doctor's active schedule. */
export function GenerateSlotsModal(props: { scheduleId: string; onClose: () => void }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const today = dayjs().startOf('day');
  const [from, setFrom] = useState(today.format('YYYY-MM-DD'));
  const [to, setTo] = useState(today.add(30, 'day').format('YYYY-MM-DD'));

  const mut = useMutation({
    mutationFn: () => generateSlots(props.scheduleId, from, to),
    onSuccess: (r) => {
      notifications.show({
        color: 'teal',
        message: t('schedule.generated', { created: r.created, skipped: r.skippedExisting }),
      });
      void queryClient.invalidateQueries({ queryKey: ['schedule'] });
      props.onClose();
    },
  });

  return (
    <Modal opened onClose={props.onClose} title={t('schedule.generateTitle')} centered>
      <form
        noValidate
        onSubmit={(e: FormEvent) => {
          e.preventDefault();
          mut.mutate();
        }}
      >
        <Stack gap="sm">
          <ProblemAlert error={mut.error} />
          <Text size="sm" c="dimmed">
            {t('schedule.generateHint')}
          </Text>
          <TextInput
            type="date"
            label={t('schedule.generateFrom')}
            value={from}
            onChange={(e) => setFrom(e.currentTarget.value)}
          />
          <TextInput
            type="date"
            label={t('schedule.generateTo')}
            value={to}
            onChange={(e) => setTo(e.currentTarget.value)}
          />
          <Group justify="flex-end">
            <Button variant="default" onClick={props.onClose}>
              {t('common.cancel')}
            </Button>
            <Button type="submit" loading={mut.isPending}>
              {t('schedule.generateSubmit')}
            </Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  );
}

/** S9 / S10: block a free slot with a reason, or unblock a blocked one. A booked slot returns 409 and the error is shown. */
export function SlotModal(props: { slot: Slot; onClose: () => void }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const { slot } = props;
  const [reason, setReason] = useState('');
  const [submitted, setSubmitted] = useState(false);
  // One key per user click, reused when the same click is retried.
  const key = useRef(newIdempotencyKey());
  const blocked = slot.status === 'busy-unavailable';

  const done = (message: string) => {
    notifications.show({ color: 'teal', message });
    void queryClient.invalidateQueries({ queryKey: ['schedule'] });
    props.onClose();
  };
  const blockMut = useMutation({
    mutationFn: () => blockSlot(slot, reason.trim(), key.current),
    onSuccess: () => done(t('schedule.slotBlocked')),
    onSettled: () => {
      key.current = newIdempotencyKey();
    },
  });
  const unblockMut = useMutation({
    mutationFn: () => unblockSlot(slot, key.current),
    onSuccess: () => done(t('schedule.slotUnblocked')),
    onSettled: () => {
      key.current = newIdempotencyKey();
    },
  });

  const trimmed = reason.trim();
  const reasonInvalid = trimmed.length < 1 || trimmed.length > MAX_REASON_LENGTH;

  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (blocked) {
      unblockMut.mutate();
      return;
    }
    setSubmitted(true);
    if (!reasonInvalid) blockMut.mutate();
  };

  return (
    <Modal
      opened
      onClose={props.onClose}
      centered
      title={t('schedule.slotDialogTitle', {
        time: `${formatTime(slot.start)}–${formatTime(slot.end)}`,
        date: dayjs(slot.start).format('DD/MM/YYYY'),
      })}
    >
      <form noValidate onSubmit={submit}>
        <Stack gap="sm">
          <ProblemAlert error={blockMut.error ?? unblockMut.error} />
          <Text size="sm">
            {t('schedule.slotStatus')}: {t(`status.${slot.status}`)}
          </Text>
          {blocked ? (
            slot.comment && <Text size="sm">{slot.comment}</Text>
          ) : (
            <Textarea
              label={t('schedule.slotReason')}
              placeholder={t('schedule.slotReasonPlaceholder')}
              value={reason}
              onChange={(e) => setReason(e.currentTarget.value)}
              error={submitted && reasonInvalid ? t('schedule.errReasonRequired') : undefined}
              autosize
              minRows={2}
            />
          )}
          <Group justify="flex-end">
            <Button variant="default" onClick={props.onClose}>
              {t('common.cancel')}
            </Button>
            <Button type="submit" loading={blockMut.isPending || unblockMut.isPending}>
              {blocked ? t('schedule.unblock') : t('schedule.block')}
            </Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  );
}
