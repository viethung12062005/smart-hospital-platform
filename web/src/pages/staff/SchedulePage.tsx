import { Button, Group, Select, Tooltip } from '@mantine/core';
import type { Slot } from '@medplum/fhirtypes';
import { IconChevronLeft, IconChevronRight } from '@tabler/icons-react';
import { useQuery } from '@tanstack/react-query';
import dayjs from 'dayjs';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { getMe, searchPractitioners } from '@/api/clinical';
import { formatName, formatTime, refId } from '@/api/fhir';
import { searchSlots } from '@/api/scheduling';
import { useAuth } from '@/auth/useAuth';
import { Card, Loading, PageHeader, ProblemAlert } from '@/components/ui';

const COLOR: Record<string, string> = { free: 'teal', busy: 'info', 'busy-unavailable': 'muted' };

/**
 * Lịch bác sĩ theo tuần (theo bố cục DoctorSchedule của portal PoC trước): cột là ngày, hàng là giờ, ô là slot.
 * Bác sĩ chỉ xem lịch của mình; điều dưỡng và quản trị chọn bác sĩ.
 */
export function SchedulePage() {
  const { t } = useTranslation();
  const { user } = useAuth();
  const isDoctor = user?.roles.includes('doctor') && !user.roles.includes('admin');
  const [week, setWeek] = useState(() =>
    dayjs()
      .startOf('day')
      .subtract((dayjs().day() + 6) % 7, 'day'),
  );
  const [chosen, setChosen] = useState<string | null>(null);

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
    enabled: !!practitioner,
  });

  const days = Array.from({ length: 7 }, (_, i) => week.add(i, 'day'));
  const byKey = new Map<string, Slot>();
  for (const s of slots.data ?? []) byKey.set(dayjs(s.start).format('YYYY-MM-DD HH:mm'), s);
  const times = [...new Set((slots.data ?? []).map((s) => formatTime(s.start)))].sort();

  return (
    <div className="shp-page">
      <PageHeader
        title={t('schedule.title')}
        sub={t('schedule.sub')}
        actions={
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
                onChange={setChosen}
                searchable
              />
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
        }
      />
      <ProblemAlert error={slots.error ?? practitioners.error ?? me.error} />
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
                <Row key={time} time={time} days={days} byKey={byKey} />
              ))}
            </div>
          </div>
        )}
      </Card>
    </div>
  );
}

function Row(props: { time: string; days: dayjs.Dayjs[]; byKey: Map<string, Slot> }) {
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
                <span
                  className={`shp-chip shp-chip--${COLOR[s.status] ?? 'muted'}`}
                  style={{ width: '100%', justifyContent: 'center' }}
                >
                  {t(`status.${s.status}`)}
                </span>
              </Tooltip>
            )}
          </div>
        );
      })}
    </>
  );
}
