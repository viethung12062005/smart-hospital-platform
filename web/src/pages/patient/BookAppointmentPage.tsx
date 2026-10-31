import { Alert, Button, Group, Select, SimpleGrid, Stack, Stepper, Text, Textarea } from '@mantine/core';
import { DatePickerInput } from '@mantine/dates';
import type { Slot } from '@medplum/fhirtypes';
import { IconCircleCheck } from '@tabler/icons-react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import dayjs from 'dayjs';
import { useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useSearchParams } from 'react-router-dom';
import { getMe } from '@/api/clinical';
import { ApiError, newIdempotencyKey } from '@/api/client';
import { formatDateTime, formatName, formatTime } from '@/api/fhir';
import { bookAppointment, searchDoctors, searchSlots } from '@/api/scheduling';
import { SPECIALTIES, specialtyName } from '@/api/terminology';
import { Card, EmptyState, Loading, PageHeader, ProblemAlert } from '@/components/ui';

/**
 * Đặt lịch 3 bước (theo luồng BookAppointment của portal PoC trước): chuyên khoa → bác sĩ và giờ → xác nhận.
 * Chống bấm đúp: một Idempotency-Key cho mỗi lần xác nhận; bấm lại cùng nội dung máy chủ trả đúng lịch đã tạo.
 * 409 slot-unavailable: hiện các giờ gợi ý máy chủ trả về.
 */
export function BookAppointmentPage() {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [params] = useSearchParams();
  const [specialty, setSpecialty] = useState<string | null>(params.get('specialty'));
  const [practitioner, setPractitioner] = useState<string | null>(params.get('practitioner'));
  const [date, setDate] = useState<Date | null>(dayjs().add(1, 'day').toDate());
  const [slot, setSlot] = useState<Slot | undefined>();
  const [comment, setComment] = useState('');
  const [step, setStep] = useState(params.get('specialty') ? 1 : 0);
  const idemKey = useRef<string | undefined>(undefined);

  const me = useQuery({ queryKey: ['me'], queryFn: getMe });
  const doctors = useQuery({
    queryKey: ['doctors', specialty],
    queryFn: () => searchDoctors(specialty ?? undefined),
    enabled: !!specialty,
  });
  const day = date ? dayjs(date).startOf('day') : undefined;
  const slots = useQuery({
    queryKey: ['slots', specialty, practitioner, day?.format('YYYY-MM-DD')],
    queryFn: () =>
      searchSlots({
        specialty: specialty ?? undefined,
        practitioner: practitioner ?? undefined,
        from: day!.format('YYYY-MM-DD'),
        to: day!.add(1, 'day').format('YYYY-MM-DD'),
        status: 'free',
      }),
    enabled: !!specialty && !!practitioner && !!day,
  });

  const book = useMutation({
    mutationFn: () => {
      idemKey.current ??= newIdempotencyKey();
      return bookAppointment({
        slotId: slot!.id!,
        patient: me.data!.fhirUser!,
        comment: comment.trim() || undefined,
        idempotencyKey: idemKey.current,
      });
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['appointments'] });
      void queryClient.invalidateQueries({ queryKey: ['home-appointments'] });
      // Slot vừa đặt phải biến khỏi danh sách giờ trống ngay, không chờ hết hạn bộ nhớ đệm.
      void queryClient.invalidateQueries({ queryKey: ['slots'] });
      setStep(3);
    },
    onError: (e) => {
      // Lỗi nghiệp vụ là kết quả cuối của yêu cầu này: lần thử sau là yêu cầu mới, cần key mới.
      if (e instanceof ApiError && e.status < 500) idemKey.current = undefined;
      if (e instanceof ApiError && e.code === 'slot-unavailable') void slots.refetch();
    },
  });

  /** Đổi lựa chọn = yêu cầu khác: bỏ key cũ và lỗi cũ. */
  function choose(next: () => void): void {
    next();
    idemKey.current = undefined;
    book.reset();
  }

  const doctorOptions = useMemo(
    () => (doctors.data ?? []).map((d) => ({ value: d.practitioner.id!, label: formatName(d.practitioner.name) })),
    [doctors.data],
  );
  const doctorLabel = doctorOptions.find((d) => d.value === practitioner)?.label ?? '';
  const suggestedIds =
    book.error instanceof ApiError ? book.error.suggestedSlots.map((r) => r.replace('Slot/', '')) : [];
  // Máy chủ chỉ trả tham chiếu Slot/{id}: tra giờ của chúng trong slot trống 31 ngày tới của cùng bác sĩ.
  const suggestedLookup = useQuery({
    queryKey: ['slots-suggested', practitioner, suggestedIds.join(',')],
    queryFn: () =>
      searchSlots({
        practitioner: practitioner ?? undefined,
        from: dayjs().format('YYYY-MM-DD'),
        to: dayjs().add(31, 'day').format('YYYY-MM-DD'),
        status: 'free',
      }),
    enabled: suggestedIds.length > 0 && !!practitioner,
  });
  const suggested = (suggestedLookup.data ?? []).filter((s) => suggestedIds.includes(s.id ?? ''));

  return (
    <div className="shp-page">
      <PageHeader title={t('book.title')} sub={t('book.sub')} />
      <Card>
        <Stepper active={step} onStepClick={(s) => s < step && step < 3 && setStep(s)} size="sm" mb="lg">
          <Stepper.Step label={t('book.step1')} />
          <Stepper.Step label={t('book.step2')} />
          <Stepper.Step label={t('book.step3')} />
        </Stepper>

        {step === 0 && (
          <Stack>
            <Select
              label={t('specialty.label')}
              placeholder={t('specialty.pick')}
              data={SPECIALTIES.map((s) => ({ value: s.code, label: s.display }))}
              value={specialty}
              onChange={(v) => choose(() => (setSpecialty(v), setPractitioner(null), setSlot(undefined)))}
              searchable
              maw={420}
            />
            <Group>
              <Button disabled={!specialty} onClick={() => setStep(1)}>
                {t('common.next')}
              </Button>
            </Group>
          </Stack>
        )}

        {step === 1 && (
          <Stack>
            <SimpleGrid cols={{ base: 1, sm: 2 }} maw={720}>
              <Select
                label={t('book.doctor')}
                placeholder={specialtyName(specialty ?? undefined)}
                data={doctorOptions}
                value={practitioner}
                onChange={(v) => choose(() => (setPractitioner(v), setSlot(undefined)))}
              />
              <DatePickerInput
                label={t('book.date')}
                value={date}
                onChange={(v) => choose(() => (setDate(v), setSlot(undefined)))}
                minDate={new Date()}
                maxDate={dayjs().add(30, 'day').toDate()}
                valueFormat="DD/MM/YYYY"
              />
            </SimpleGrid>
            <Text fw={600} size="sm">
              {t('book.slots')}
            </Text>
            <ProblemAlert error={slots.error} />
            {!practitioner ? (
              <Text size="sm" c="dimmed">
                {t('book.doctor')}…
              </Text>
            ) : slots.isLoading ? (
              <Loading />
            ) : (slots.data ?? []).length === 0 ? (
              <EmptyState title={t('book.noSlots')} />
            ) : (
              <div className="shp-slots" role="listbox" aria-label={t('book.slots')}>
                {(slots.data ?? []).map((s) => (
                  <button
                    key={s.id}
                    type="button"
                    role="option"
                    aria-selected={slot?.id === s.id}
                    className={`shp-slot ${slot?.id === s.id ? 'is-on' : ''}`}
                    onClick={() => choose(() => setSlot(s))}
                  >
                    {formatTime(s.start)}
                  </button>
                ))}
              </div>
            )}
            <Group>
              <Button variant="default" onClick={() => setStep(0)}>
                {t('common.back')}
              </Button>
              <Button disabled={!slot} onClick={() => setStep(2)}>
                {t('common.next')}
              </Button>
              {!slot && (
                <Text size="xs" c="dimmed">
                  {t('book.pickSlot')}
                </Text>
              )}
            </Group>
          </Stack>
        )}

        {step === 2 && slot && (
          <Stack maw={640}>
            <Text fw={600}>{t('book.summary')}</Text>
            <dl className="shp-kv">
              <dt>{t('specialty.label')}</dt>
              <dd>{specialtyName(specialty ?? undefined)}</dd>
              <dt>{t('book.doctor')}</dt>
              <dd>{doctorLabel}</dd>
              <dt>{t('book.when')}</dt>
              <dd className="strong">{formatDateTime(slot.start)}</dd>
            </dl>
            <Textarea
              label={t('book.comment')}
              description={t('book.commentHint')}
              maxLength={500}
              autosize
              minRows={3}
              value={comment}
              onChange={(e) => {
                const v = e.currentTarget.value;
                choose(() => setComment(v));
              }}
            />
            <ProblemAlert error={book.error} />
            {suggested.length > 0 && (
              <Alert color="orange" title={t('book.suggested')}>
                <Group gap="xs">
                  {suggested.map((s) => (
                    <Button key={s.id} size="xs" variant="light" onClick={() => choose(() => setSlot(s))}>
                      {formatDateTime(s.start)}
                    </Button>
                  ))}
                </Group>
              </Alert>
            )}
            <Group>
              <Button variant="default" onClick={() => setStep(1)} disabled={book.isPending}>
                {t('common.back')}
              </Button>
              <Button onClick={() => book.mutate()} loading={book.isPending} disabled={!me.data?.fhirUser}>
                {t('book.submit')}
              </Button>
            </Group>
          </Stack>
        )}

        {step === 3 && book.data && (
          <Stack align="flex-start">
            <Alert color="teal" icon={<IconCircleCheck />} title={t('book.success')}>
              <Text size="sm">
                {formatDateTime(book.data.start)} · {doctorLabel}
              </Text>
              <Text size="sm" c="dimmed" mt={4}>
                {t('book.successSub')}
              </Text>
            </Alert>
            <Button component={Link} to="/appointments">
              {t('book.toMyAppointments')}
            </Button>
          </Stack>
        )}
      </Card>
    </div>
  );
}
