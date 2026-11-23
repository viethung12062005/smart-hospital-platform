import { Button, Group, Modal, Select, SimpleGrid, Stack, Switch, Text, Textarea, TextInput } from '@mantine/core';
import type { Practitioner, Schedule } from '@medplum/fhirtypes';
import { notifications } from '@mantine/notifications';
import { IconCalendarCog, IconEdit, IconPlus, IconTrash } from '@tabler/icons-react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import dayjs from 'dayjs';
import type { TFunction } from 'i18next';
import { useMemo, useRef, useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { etagOf } from '@/api/client';
import { searchPractitioners } from '@/api/clinical';
import { formatDate, formatName } from '@/api/fhir';
import {
  createSchedule,
  getScheduleTemplates,
  replaceScheduleTemplates,
  schedulePractitionerId,
  scheduleSpecialtyCode,
  searchDoctors,
  searchSchedules,
  updateSchedule,
  type ScheduleTemplate,
  type ScheduleTemplateInput,
} from '@/api/scheduling';
import { SPECIALTIES, specialtyName } from '@/api/terminology';
import { Card, EmptyState, Loading, ProblemAlert } from '@/components/ui';
import {
  ALLOWED_SLOT_MINUTES,
  hasScheduleErrors,
  hasTemplateErrors,
  slotCountPreview,
  toTemplateInputs,
  validateScheduleForm,
  validateTemplateRows,
  WEEKDAYS,
  type FieldError,
  type TemplateRowDraft,
} from './scheduleValidation';

type ModalState =
  | { readonly mode: 'create'; readonly seq: number }
  | { readonly mode: 'edit'; readonly schedule: Schedule; readonly seq: number };

function renderErr(t: TFunction, err: FieldError | undefined): string | undefined {
  return err ? t(err.key, err.params) : undefined;
}

function toDraftRow(tpl: ScheduleTemplate, key: string): TemplateRowDraft {
  return {
    key,
    weekday: String(tpl.weekday),
    startTime: tpl.startTime,
    endTime: tpl.endTime,
    slotMinutes: String(tpl.slotMinutes),
    validFrom: tpl.validFrom,
    validTo: tpl.validTo ?? '',
  };
}

/**
 * Doctor work schedules (S1–S4) and weekly schedule templates (S5–S6).
 * Nurses and administrators can filter, create and edit schedules and replace weekly templates;
 * doctors view only their own schedule and read-only templates.
 */
export function ScheduleManagementPanel({ canManage }: { readonly canManage: boolean }) {
  const { t } = useTranslation();
  const [filterDoctor, setFilterDoctor] = useState<string | null>(null);
  const [filterSpecialty, setFilterSpecialty] = useState<string | null>(null);
  const [filterStatus, setFilterStatus] = useState<string>('all');
  const [selectedId, setSelectedId] = useState<string | undefined>();
  const [modalState, setModalState] = useState<ModalState | undefined>();
  const modalSeq = useRef(0);

  const schedules = useQuery({
    queryKey: ['schedules', filterDoctor, filterSpecialty, filterStatus],
    queryFn: () =>
      searchSchedules({
        actor: filterDoctor ?? undefined,
        specialty: filterSpecialty ?? undefined,
        active: filterStatus === 'all' ? undefined : filterStatus === 'active',
      }),
  });

  const doctors = useQuery({
    queryKey: ['doctors', 'all'],
    queryFn: () => searchDoctors(undefined),
    enabled: canManage,
  });
  const practitioners = useQuery({
    queryKey: ['practitioners'],
    queryFn: searchPractitioners,
    enabled: canManage,
  });

  const doctorMeta = useMemo(() => {
    const names = new Map<string, string>();
    const specs = new Map<string, string[]>();
    for (const p of practitioners.data ?? []) {
      if (p.id) names.set(p.id, formatName(p.name));
    }
    for (const entry of doctors.data ?? []) {
      const id = entry.practitioner.id;
      if (!id) continue;
      if (!names.has(id)) names.set(id, formatName(entry.practitioner.name));
      if (entry.specialty) {
        const list = specs.get(id) ?? [];
        if (!list.includes(entry.specialty)) specs.set(id, [...list, entry.specialty]);
      }
    }
    const options = [...names.entries()].map(([value, label]) => ({ value, label }));
    return { options, specs, names };
  }, [doctors.data, practitioners.data]);

  const rows = schedules.data ?? [];
  const selectedSchedule = rows.find((s) => s.id === selectedId) ?? rows[0];

  const templatesQuery = useQuery({
    queryKey: ['schedule-templates', selectedSchedule?.id],
    queryFn: () => getScheduleTemplates(selectedSchedule!.id!),
    enabled: !!selectedSchedule?.id,
  });

  const openCreate = () => {
    modalSeq.current += 1;
    setModalState({ mode: 'create', seq: modalSeq.current });
  };
  const openEdit = (schedule: Schedule) => {
    modalSeq.current += 1;
    setModalState({ mode: 'edit', schedule, seq: modalSeq.current });
  };

  return (
    <Stack gap="md">
      {canManage && (
        <Group justify="space-between" align="flex-end" wrap="wrap" gap="sm">
          <Group gap="sm" align="flex-end" wrap="wrap">
            <Select
              size="sm"
              w={220}
              label={t('schedule.filterDoctor')}
              placeholder={t('common.all')}
              data={doctorMeta.options}
              value={filterDoctor}
              onChange={setFilterDoctor}
              searchable
              clearable
            />
            <Select
              size="sm"
              w={200}
              label={t('schedule.filterSpecialty')}
              placeholder={t('common.all')}
              data={SPECIALTIES.map((s) => ({ value: s.code, label: s.display }))}
              value={filterSpecialty}
              onChange={setFilterSpecialty}
              searchable
              clearable
            />
            <Select
              size="sm"
              w={180}
              label={t('schedule.filterStatus')}
              allowDeselect={false}
              data={[
                { value: 'all', label: t('schedule.statusAll') },
                { value: 'active', label: t('schedule.statusActive') },
                { value: 'inactive', label: t('schedule.statusInactive') },
              ]}
              value={filterStatus}
              onChange={(v) => setFilterStatus(v ?? 'all')}
            />
          </Group>
          <Button leftSection={<IconPlus size={16} />} onClick={openCreate}>
            {t('schedule.create')}
          </Button>
        </Group>
      )}

      <ProblemAlert error={schedules.error ?? doctors.error ?? practitioners.error} />

      <Card title={t('schedule.schedulesTitle')} count={rows.length} flush>
        {schedules.isLoading ? (
          <Loading />
        ) : rows.length === 0 ? (
          <EmptyState title={t('schedule.noSchedules')} />
        ) : (
          <div style={{ overflowX: 'auto' }}>
            <table className="shp-tbl">
              <thead>
                <tr>
                  <th>{t('schedule.practitioner')}</th>
                  <th>{t('specialty.label')}</th>
                  <th>{t('appt.status')}</th>
                  <th>{t('schedule.horizon')}</th>
                  <th>{t('schedule.comment')}</th>
                  <th>{t('schedule.actions')}</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((s) => {
                  const pid = schedulePractitionerId(s);
                  const doctorName =
                    s.actor?.[0]?.display || (pid ? doctorMeta.names.get(pid) : undefined) || pid || '';
                  const specCode = scheduleSpecialtyCode(s);
                  const isSelected = selectedSchedule?.id === s.id;
                  return (
                    <tr key={s.id} style={isSelected ? { background: 'var(--shp-teal-soft)' } : undefined}>
                      <td>
                        <div className="strong">{doctorName}</div>
                        <span className="sub">v{s.meta?.versionId ?? '1'}</span>
                      </td>
                      <td>{specialtyName(specCode)}</td>
                      <td>
                        <span className={`shp-chip shp-chip--${s.active ? 'ok' : 'muted'}`}>
                          <span className="dot" />
                          {s.active ? t('schedule.active') : t('schedule.inactive')}
                        </span>
                      </td>
                      <td>
                        {s.planningHorizon?.start
                          ? `${formatDate(s.planningHorizon.start)} – ${formatDate(s.planningHorizon.end)}`
                          : '—'}
                      </td>
                      <td>{s.comment || '—'}</td>
                      <td className="tight">
                        <Group gap={6} wrap="nowrap">
                          <Button
                            size="xs"
                            variant={isSelected ? 'filled' : 'default'}
                            leftSection={<IconCalendarCog size={14} />}
                            aria-label={t('schedule.manageTemplatesOf', { name: doctorName })}
                            onClick={() => setSelectedId(s.id)}
                          >
                            {t('schedule.manageTemplates')}
                          </Button>
                          {canManage && (
                            <Button
                              size="xs"
                              variant="default"
                              leftSection={<IconEdit size={14} />}
                              aria-label={t('schedule.editScheduleOf', { name: doctorName })}
                              onClick={() => openEdit(s)}
                            >
                              {t('schedule.edit')}
                            </Button>
                          )}
                        </Group>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      {selectedSchedule && (
        <Card
          title={`${t('schedule.templatesTitle')} — ${selectedSchedule.actor?.[0]?.display || schedulePractitionerId(selectedSchedule) || ''} (${specialtyName(scheduleSpecialtyCode(selectedSchedule))})`}
          count={`v${selectedSchedule.meta?.versionId ?? '1'}`}
        >
          {templatesQuery.isLoading ? (
            <Loading />
          ) : templatesQuery.error ? (
            <ProblemAlert error={templatesQuery.error} />
          ) : (
            <WeeklyTemplateEditor
              key={`${selectedSchedule.id}:${templatesQuery.data?.etag ?? selectedSchedule.meta?.versionId ?? ''}`}
              schedule={selectedSchedule}
              initialTemplates={templatesQuery.data?.templates ?? []}
              serverEtag={templatesQuery.data?.etag ?? etagOf(selectedSchedule)}
              canManage={canManage}
            />
          )}
        </Card>
      )}

      {!selectedSchedule && rows.length > 0 && (
        <Text size="sm" c="dimmed">
          {t('schedule.selectScheduleHint')}
        </Text>
      )}

      <Modal
        opened={!!modalState}
        onClose={() => setModalState(undefined)}
        title={modalState?.mode === 'edit' ? t('schedule.editTitle') : t('schedule.createTitle')}
        size="md"
      >
        {modalState && (
          <ScheduleFormBody
            key={
              modalState.mode === 'edit'
                ? `edit-${modalState.schedule.id}-${modalState.schedule.meta?.versionId ?? '1'}-${modalState.seq}`
                : `create-${modalState.seq}`
            }
            modalState={modalState}
            practitioners={practitioners.data ?? []}
            doctorOptions={doctorMeta.options}
            practitionerSpecialties={doctorMeta.specs}
            onClose={() => setModalState(undefined)}
            onSaved={(saved) => {
              setSelectedId(saved.id);
              setModalState(undefined);
            }}
          />
        )}
      </Modal>
    </Stack>
  );
}

function ScheduleFormBody(props: {
  readonly modalState: ModalState;
  readonly practitioners: Practitioner[];
  readonly doctorOptions: { value: string; label: string }[];
  readonly practitionerSpecialties: Map<string, string[]>;
  readonly onClose: () => void;
  readonly onSaved: (saved: Schedule) => void;
}) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const isEdit = props.modalState.mode === 'edit';
  const editing = props.modalState.mode === 'edit' ? props.modalState.schedule : undefined;

  const [practitionerId, setPractitionerId] = useState<string | null>(() =>
    editing ? (schedulePractitionerId(editing) ?? null) : null,
  );
  const [specialty, setSpecialty] = useState<string | null>(() =>
    editing ? (scheduleSpecialtyCode(editing) ?? null) : null,
  );
  const [active, setActive] = useState<boolean>(() => editing?.active ?? true);
  const [comment, setComment] = useState<string>(() => editing?.comment ?? '');
  const [submitted, setSubmitted] = useState(false);

  const createMut = useMutation({
    mutationFn: createSchedule,
    onSuccess: (saved) => {
      const display = saved.actor?.[0]?.display ?? practitionerId ?? '';
      notifications.show({ color: 'teal', message: t('schedule.created', { name: display }) });
      void queryClient.invalidateQueries({ queryKey: ['schedules'] });
      props.onSaved(saved);
    },
  });

  const updateMut = useMutation({
    mutationFn: updateSchedule,
    onSuccess: (saved) => {
      notifications.show({ color: 'teal', message: t('schedule.updated') });
      void queryClient.invalidateQueries({ queryKey: ['schedules'] });
      void queryClient.invalidateQueries({ queryKey: ['schedule-templates', saved.id] });
      props.onSaved(saved);
    },
  });

  const mode = isEdit ? 'edit' : 'create';
  const errors = submitted ? validateScheduleForm({ practitionerId, specialty, comment }, mode) : {};
  const activeError = isEdit ? updateMut.error : createMut.error;
  const isPending = createMut.isPending || updateMut.isPending;

  const handleDoctorChange = (nextId: string | null) => {
    setPractitionerId(nextId);
    createMut.reset();
    if (!isEdit && nextId && !specialty) {
      const specs = props.practitionerSpecialties.get(nextId);
      if (specs?.length === 1 && specs[0]) {
        setSpecialty(specs[0]);
      }
    }
  };

  const handleSpecialtyChange = (nextSpec: string | null) => {
    setSpecialty(nextSpec);
    createMut.reset();
  };

  const handleSubmit = (e: FormEvent) => {
    e.preventDefault();
    setSubmitted(true);
    const currentErrors = validateScheduleForm({ practitionerId, specialty, comment }, mode);
    if (hasScheduleErrors(currentErrors)) return;

    if (editing) {
      updateMut.mutate({ schedule: editing, active, comment });
    } else if (practitionerId && specialty) {
      const prac = props.practitioners.find((p) => p.id === practitionerId);
      const display =
        (prac ? formatName(prac.name) : undefined) ??
        props.doctorOptions.find((o) => o.value === practitionerId)?.label;
      createMut.mutate({ practitionerId, practitionerDisplay: display, specialty, active, comment });
    }
  };

  return (
    <form noValidate onSubmit={handleSubmit}>
      <Stack gap="sm">
        <ProblemAlert error={activeError} />
        {isEdit && editing ? (
          <dl className="shp-kv">
            <dt>{t('schedule.practitioner')}</dt>
            <dd className="strong">{editing.actor?.[0]?.display || schedulePractitionerId(editing)}</dd>
            <dt>{t('specialty.label')}</dt>
            <dd>{specialtyName(scheduleSpecialtyCode(editing))}</dd>
          </dl>
        ) : (
          <>
            <Select
              label={t('schedule.practitioner')}
              placeholder={t('schedule.practitioner')}
              data={props.doctorOptions}
              value={practitionerId}
              onChange={handleDoctorChange}
              error={renderErr(t, errors.practitionerId)}
              searchable
              allowDeselect={false}
              required
            />
            <Select
              label={t('specialty.label')}
              placeholder={t('specialty.pick')}
              data={SPECIALTIES.map((s) => ({ value: s.code, label: s.display }))}
              value={specialty}
              onChange={handleSpecialtyChange}
              error={renderErr(t, errors.specialty)}
              searchable
              allowDeselect={false}
              required
            />
          </>
        )}
        <Switch
          label={t('schedule.activeLabel')}
          checked={active}
          onChange={(e) => setActive(e.currentTarget.checked)}
        />
        <Textarea
          label={t('schedule.comment')}
          placeholder={t('schedule.commentPlaceholder')}
          value={comment}
          onChange={(e) => setComment(e.currentTarget.value)}
          error={renderErr(t, errors.comment)}
          autosize
          minRows={2}
        />
        <Group justify="flex-end" mt="xs">
          <Button type="button" variant="default" onClick={props.onClose} disabled={isPending}>
            {t('common.cancel')}
          </Button>
          <Button type="submit" loading={isPending}>
            {t('common.save')}
          </Button>
        </Group>
      </Stack>
    </form>
  );
}

function WeeklyTemplateEditor(props: {
  readonly schedule: Schedule;
  readonly initialTemplates: readonly ScheduleTemplate[];
  readonly serverEtag?: string;
  readonly canManage: boolean;
}) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const rowSeq = useRef(0);
  const [rows, setRows] = useState<TemplateRowDraft[]>(() =>
    props.initialTemplates.map((tpl, i) => toDraftRow(tpl, tpl.id || `init-${i}`)),
  );
  const [submitted, setSubmitted] = useState(false);

  const saveMut = useMutation({
    mutationFn: (inputs: readonly ScheduleTemplateInput[]) =>
      replaceScheduleTemplates(props.schedule.id!, inputs, props.serverEtag),
    onSuccess: () => {
      notifications.show({ color: 'teal', message: t('schedule.templatesSaved') });
      void queryClient.invalidateQueries({ queryKey: ['schedules'] });
      void queryClient.invalidateQueries({ queryKey: ['schedule-templates', props.schedule.id] });
    },
  });

  if (!props.canManage) {
    if (props.initialTemplates.length === 0) {
      return <EmptyState title={t('schedule.templatesEmpty')} />;
    }
    return (
      <div style={{ overflowX: 'auto' }}>
        <table className="shp-tbl">
          <thead>
            <tr>
              <th>{t('schedule.weekday')}</th>
              <th>{t('schedule.startTime')}</th>
              <th>{t('schedule.endTime')}</th>
              <th>{t('schedule.slotMinutes')}</th>
              <th>{t('schedule.validFrom')}</th>
              <th>{t('schedule.validTo')}</th>
            </tr>
          </thead>
          <tbody>
            {props.initialTemplates.map((tpl) => (
              <tr key={tpl.id}>
                <td className="strong">{t(`schedule.weekdays.${tpl.weekday}`)}</td>
                <td>{tpl.startTime}</td>
                <td>{tpl.endTime}</td>
                <td>{t('common.minutes', { count: tpl.slotMinutes })}</td>
                <td>{formatDate(tpl.validFrom)}</td>
                <td>{tpl.validTo ? formatDate(tpl.validTo) : '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    );
  }

  const errorsByKey = submitted ? validateTemplateRows(rows) : {};
  const weekdayOptions = WEEKDAYS.map((w) => ({ value: String(w), label: t(`schedule.weekdays.${w}`) }));
  const slotMinuteOptions = ALLOWED_SLOT_MINUTES.map((m) => ({
    value: String(m),
    label: t('common.minutes', { count: m }),
  }));

  const updateRow = (key: string, field: keyof Omit<TemplateRowDraft, 'key'>, value: string) => {
    setRows((prev) => prev.map((r) => (r.key === key ? { ...r, [field]: value } : r)));
  };

  const addRow = () => {
    rowSeq.current += 1;
    setRows((prev) => [
      ...prev,
      {
        key: `draft-${rowSeq.current}`,
        weekday: '1',
        startTime: '08:00',
        endTime: '11:30',
        slotMinutes: '15',
        validFrom: dayjs().format('YYYY-MM-DD'),
        validTo: '',
      },
    ]);
  };

  const removeRow = (key: string) => {
    setRows((prev) => prev.filter((r) => r.key !== key));
  };

  const handleSubmit = (e: FormEvent) => {
    e.preventDefault();
    setSubmitted(true);
    const currentErrors = validateTemplateRows(rows);
    if (hasTemplateErrors(currentErrors)) return;
    saveMut.mutate(toTemplateInputs(rows));
  };

  return (
    <form noValidate onSubmit={handleSubmit}>
      <Stack gap="sm">
        <Text size="xs" c="dimmed">
          {t('schedule.templatesHint')}
        </Text>
        <ProblemAlert error={saveMut.error} />

        {rows.length === 0 ? (
          <EmptyState title={t('schedule.templatesEmpty')} />
        ) : (
          <Stack gap="xs">
            {rows.map((row, idx) => {
              const rowErr = errorsByKey[row.key];
              const preview = slotCountPreview(row.startTime, row.endTime, row.slotMinutes);
              return (
                <div
                  key={row.key}
                  role="group"
                  aria-label={`${t('schedule.templatesTitle')} #${idx + 1}`}
                  style={{ padding: 12, border: '1px solid var(--shp-hair)', borderRadius: 8 }}
                >
                  <SimpleGrid cols={{ base: 1, sm: 2, md: 6 }} spacing="xs">
                    <Select
                      size="xs"
                      label={t('schedule.weekday')}
                      data={weekdayOptions}
                      value={row.weekday}
                      onChange={(v) => updateRow(row.key, 'weekday', v ?? '')}
                      error={renderErr(t, rowErr?.weekday)}
                      allowDeselect={false}
                    />
                    <TextInput
                      size="xs"
                      label={t('schedule.startTime')}
                      placeholder="08:00"
                      value={row.startTime}
                      onChange={(e) => updateRow(row.key, 'startTime', e.currentTarget.value)}
                      error={renderErr(t, rowErr?.startTime)}
                    />
                    <TextInput
                      size="xs"
                      label={t('schedule.endTime')}
                      placeholder="11:30"
                      value={row.endTime}
                      onChange={(e) => updateRow(row.key, 'endTime', e.currentTarget.value)}
                      error={renderErr(t, rowErr?.endTime)}
                    />
                    <Select
                      size="xs"
                      label={t('schedule.slotMinutes')}
                      data={slotMinuteOptions}
                      value={row.slotMinutes}
                      onChange={(v) => updateRow(row.key, 'slotMinutes', v ?? '')}
                      error={renderErr(t, rowErr?.slotMinutes)}
                      allowDeselect={false}
                    />
                    <TextInput
                      size="xs"
                      type="date"
                      label={t('schedule.validFrom')}
                      value={row.validFrom}
                      onChange={(e) => updateRow(row.key, 'validFrom', e.currentTarget.value)}
                      error={renderErr(t, rowErr?.validFrom)}
                    />
                    <TextInput
                      size="xs"
                      type="date"
                      label={t('schedule.validTo')}
                      value={row.validTo}
                      onChange={(e) => updateRow(row.key, 'validTo', e.currentTarget.value)}
                      error={renderErr(t, rowErr?.validTo)}
                    />
                  </SimpleGrid>
                  <Group justify="space-between" mt="xs">
                    <Text size="xs" c="dimmed">
                      {preview !== null ? t('schedule.slotsPerDay', { count: preview }) : ''}
                    </Text>
                    <Button
                      type="button"
                      size="xs"
                      variant="default"
                      style={{ color: '#a61e1e' }}
                      leftSection={<IconTrash size={14} />}
                      aria-label={t('schedule.removeShiftRow', { index: idx + 1 })}
                      onClick={() => removeRow(row.key)}
                    >
                      {t('schedule.removeShift')}
                    </Button>
                  </Group>
                </div>
              );
            })}
          </Stack>
        )}

        <Group justify="space-between" pt="xs">
          <Button type="button" variant="default" leftSection={<IconPlus size={16} />} onClick={addRow}>
            {t('schedule.addShift')}
          </Button>
          <Button type="submit" loading={saveMut.isPending}>
            {t('schedule.saveTemplates')}
          </Button>
        </Group>
      </Stack>
    </form>
  );
}
