import { Button, Group, Select, Tabs, Tooltip, UnstyledButton } from '@mantine/core';
import type { Slot } from '@medplum/fhirtypes';
import { IconCalendarPlus, IconChevronLeft, IconChevronRight } from '@tabler/icons-react';
import { useQuery } from '@tanstack/react-query';
import dayjs from 'dayjs';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { getMe, searchPractitioners } from '@/api/clinical';
import { formatName, formatTime, refId } from '@/api/fhir';
import { scheduleSpecialtyCode, searchSchedules, searchSlots } from '@/api/scheduling';
import { specialtyName } from '@/api/terminology';
import { useAuth } from '@/auth/useAuth';
import { Card, Loading, PageHeader, ProblemAlert } from '@/components/ui';
import { ScheduleManagementPanel } from './ScheduleManagementPanel';
import { GenerateSlotsModal, SlotModal } from './SlotDialogs';

const COLOR: Record<string, string> = { free: 'teal', busy: 'info', 'busy-unavailable': 'muted' };

/**
 * Weekly doctor schedule and schedule/template configuration (WEB-06 S1–S6 + weekly slot calendar).
 * Doctors view their own slots and template; nurses and administrators manage schedules and templates.
 */
export function SchedulePage() {
  const { t } = useTranslation();
  const { user } = useAuth();
  const canManage = !!user?.roles.some((r) => r === 'nurse' || r === 'admin');
  const isDoctor = user?.roles.includes('doctor') && !canManage;
  const [tab, setTab] = useState<string>('calendar');
  const [week, setWeek] = useState(() =>
    dayjs()
      .startOf('day')
      .subtract((dayjs().day() + 6) % 7, 'day'),
  );
  const [chosen, setChosen] = useState<string | null>(null);
  const [chosenSchedule, setChosenSchedule] = useState<string | null>(null);
  const [generating, setGenerating] = useState(false);
  const [selected, setSelected] = useState<Slot | null>(null);

  const me = useQuery({ queryKey: ['me'], queryFn: getMe, enabled: !!isDoctor });
  const practitioners = useQuery({ queryKey: ['practitioners'], queryFn: searchPractitioners, enabled: !isDoctor });
  const practitioner = isDoctor ? refId(me.data?.fhirUser) : (chosen ?? practitioners.data?.[0]?.id ?? null);

  const slots = useQuery({
    queryKey: ['schedule', practitioner, week.format('YYYY-MM-DD')],
    queryFn: () =>
      searchSlots({
        practitioner: practitioner!,
        from: week.format('YYYY-MM-DD'),
        to: week.add(7, 'day').format('YYYY-MM-DD'),
        status: 'free,busy,busy-unavailable',
      }),
    enabled: tab === 'calendar' && !!practitioner,
  });

  const activeSchedules = useQuery({
    queryKey: ['schedules', 'active', practitioner],
    queryFn: () => searchSchedules({ actor: practitioner!, active: true }),
    enabled: canManage && tab === 'calendar' && !!practitioner,
  });
  // A doctor has at most one active schedule per specialty; with several, the user picks the one to generate for.
  const schedules = activeSchedules.data ?? [];
  const scheduleId = schedules.find((s) => s.id === chosenSchedule)?.id ?? schedules[0]?.id;

  const days = Array.from({ length: 7 }, (_, i) => week.add(i, 'day'));
  const byKey = new Map<string, Slot>();
  for (const s of slots.data ?? []) byKey.set(dayjs(s.start).format('YYYY-MM-DD HH:mm'), s);
  const times = [...new Set((slots.data ?? []).map((s) => formatTime(s.start)))].sort();

  const calendarActions =
    tab === 'calendar' ? (
      <Group gap="xs">
        {!isDoctor && (
          <Select
            size="sm"
            w={240}
            aria-label={t('schedule.practitioner')}
            data={(practitioners.data ?? []).map((p) => ({
              value: p.id!,
              label: formatName(p.name),
            }))}
            value={practitioner}
            onChange={(v) => {
              setChosen(v);
              setChosenSchedule(null);
            }}
            searchable
          />
        )}
        {canManage && schedules.length > 1 && (
          <Select
            size="sm"
            w={200}
            aria-label={t('schedule.generateSchedule')}
            data={schedules.map((s) => ({
              value: s.id!,
              label: specialtyName(scheduleSpecialtyCode(s)) || s.specialty?.[0]?.coding?.[0]?.display || s.id!,
            }))}
            value={scheduleId ?? null}
            onChange={setChosenSchedule}
            allowDeselect={false}
          />
        )}
        {canManage && (
          <Button
            variant="light"
            size="sm"
            leftSection={<IconCalendarPlus size={16} />}
            disabled={!scheduleId}
            title={activeSchedules.isSuccess && !scheduleId ? t('schedule.noActiveSchedule') : undefined}
            onClick={() => setGenerating(true)}
          >
            {t('schedule.generate')}
          </Button>
        )}
        <Button
          variant="default"
          size="sm"
          onClick={() => setWeek(week.subtract(7, 'day'))}
          aria-label={t('schedule.prevWeek')}
        >
          <IconChevronLeft size={16} />
        </Button>
        <Button
          variant="default"
          size="sm"
          onClick={() =>
            setWeek(
              dayjs()
                .startOf('day')
                .subtract((dayjs().day() + 6) % 7, 'day'),
            )
          }
        >
          {t('schedule.thisWeek')}
        </Button>
        <Button
          variant="default"
          size="sm"
          onClick={() => setWeek(week.add(7, 'day'))}
          aria-label={t('schedule.nextWeek')}
        >
          <IconChevronRight size={16} />
        </Button>
      </Group>
    ) : undefined;

  return (
    <div className="shp-page">
      <PageHeader title={t('schedule.title')} sub={t('schedule.sub')} actions={calendarActions} />

      <Tabs value={tab} onChange={(v) => setTab(v ?? 'calendar')} keepMounted={false}>
        <Tabs.List mb="md">
          <Tabs.Tab value="calendar">{t('schedule.tabCalendar')}</Tabs.Tab>
          <Tabs.Tab value="manage">{t('schedule.tabManage')}</Tabs.Tab>
        </Tabs.List>

        <Tabs.Panel value="calendar">
          <ProblemAlert error={slots.error ?? practitioners.error ?? me.error ?? activeSchedules.error} />
          <Card
            title={`${week.format('DD/MM')} – ${week.add(6, 'day').format('DD/MM/YYYY')}`}
            flush
            actions={
              <Group gap={6}>
                <span className="shp-chip shp-chip--teal">{t('schedule.legendFree')}</span>
                <span className="shp-chip shp-chip--info">{t('schedule.legendBusy')}</span>
                <span className="shp-chip shp-chip--muted">{t('schedule.legendBlocked')}</span>
              </Group>
            }
          >
            {slots.isLoading ? (
              <Loading />
            ) : (
              <div style={{ overflowX: 'auto' }}>
                <div className="shp-week" style={{ minWidth: 760 }}>
                  <div className="h" />
                  {days.map((d) => (
                    <div
                      key={d.format()}
                      className="h"
                      style={d.isSame(dayjs(), 'day') ? { background: 'var(--shp-teal-d)' } : undefined}
                    >
                      {d.format('ddd DD/MM')}
                    </div>
                  ))}
                  {times.map((time) => (
                    <Row
                      key={time}
                      time={time}
                      days={days}
                      byKey={byKey}
                      onPick={canManage ? setSelected : undefined}
                    />
                  ))}
                </div>
              </div>
            )}
          </Card>
        </Tabs.Panel>

        <Tabs.Panel value="manage">
          <ScheduleManagementPanel canManage={canManage} />
        </Tabs.Panel>
      </Tabs>
      {generating && scheduleId && <GenerateSlotsModal scheduleId={scheduleId} onClose={() => setGenerating(false)} />}
      {selected && <SlotModal slot={selected} onClose={() => setSelected(null)} />}
    </div>
  );
}

function Row(props: { time: string; days: dayjs.Dayjs[]; byKey: Map<string, Slot>; onPick?: (slot: Slot) => void }) {
  const { t } = useTranslation();
  return (
    <>
      <div className="t">{props.time}</div>
      {props.days.map((d) => {
        const s = props.byKey.get(`${d.format('YYYY-MM-DD')} ${props.time}`);
        return (
          <div key={d.format()} className="c">
            {s && (
              <Tooltip label={`${formatTime(s.start)}–${formatTime(s.end)} · ${t(`status.${s.status}`)}`}>
                {props.onPick ? (
                  <UnstyledButton
                    w="100%"
                    aria-label={t('schedule.openSlot', {
                      // The visible chip text starts the name (WCAG 2.5.3, Label in Name).
                      status: t(`status.${s.status}`),
                      time: formatTime(s.start),
                      date: d.format('DD/MM/YYYY'),
                    })}
                    onClick={() => props.onPick?.(s)}
                  >
                    <span
                      className={`shp-chip shp-chip--${COLOR[s.status] ?? 'muted'}`}
                      style={{ width: '100%', justifyContent: 'center' }}
                    >
                      {t(`status.${s.status}`)}
                    </span>
                  </UnstyledButton>
                ) : (
                  <span
                    className={`shp-chip shp-chip--${COLOR[s.status] ?? 'muted'}`}
                    style={{ width: '100%', justifyContent: 'center' }}
                  >
                    {t(`status.${s.status}`)}
                  </span>
                )}
              </Tooltip>
            )}
          </div>
        );
      })}
    </>
  );
}
