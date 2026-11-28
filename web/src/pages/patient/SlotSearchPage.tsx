import { Button, Group, Select, SimpleGrid, Stack, Text } from '@mantine/core';
import { DatePickerInput, type DatesRangeValue } from '@mantine/dates';
import type { Slot } from '@medplum/fhirtypes';
import { useInfiniteQuery, useQuery } from '@tanstack/react-query';
import dayjs from 'dayjs';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { formatName } from '@/api/fhir';
import { searchDoctors, searchSlotPage } from '@/api/scheduling';
import { SPECIALTIES, specialtyName } from '@/api/terminology';
import { VN_TIMEZONE, vnDay, vnDayLabel, vnTime } from '@/api/vnTime';
import { useAuth } from '@/auth/useAuth';
import { Card, EmptyState, Loading, PageHeader, ProblemAlert } from '@/components/ui';

const MAX_RANGE_DAYS = 31;
const PAGE_SIZE = 20;
/** For a patient the server only returns slots up to now + 30 days. */
const PATIENT_MAX_DAYS_AHEAD = 30;

/** Today's Vietnam calendar day as a local midnight `Date`, the form the date picker works with. */
function vnToday(): dayjs.Dayjs {
  return dayjs(dayjs().tz(VN_TIMEZONE).format('YYYY-MM-DD'));
}

/** Groups slots (already sorted by start) by their day in Vietnam time. */
function groupByDay(slots: readonly Slot[]): [string, Slot[]][] {
  const groups = new Map<string, Slot[]>();
  for (const s of slots) {
    const day = vnDay(s.start);
    groups.set(day, [...(groups.get(day) ?? []), s]);
  }
  return [...groups.entries()];
}

/**
 * Free slot search (S8): specialty, optional doctor and a date range, a keyset-paginated list of times.
 * Only the S8 endpoint serves the slots; times are shown in Vietnam time whatever the browser zone is.
 */
export function SlotSearchPage() {
  const { t } = useTranslation();
  const { user } = useAuth();
  const isPatient = !!user?.roles.includes('patient');
  const today = vnToday();
  const [specialty, setSpecialty] = useState<string | null>(null);
  const [practitioner, setPractitioner] = useState<string | null>(null);
  const [range, setRange] = useState<DatesRangeValue>(() => {
    const start = vnToday();
    return [start.add(1, 'day').toDate(), start.add(7, 'day').toDate()];
  });
  const from = range[0] ? dayjs(range[0]).format('YYYY-MM-DD') : undefined;
  // A single picked day means that day only.
  const to = range[1] ? dayjs(range[1]).format('YYYY-MM-DD') : from;
  const tooLong = !!from && !!to && dayjs(to).diff(dayjs(from), 'day') + 1 > MAX_RANGE_DAYS;

  const doctors = useQuery({
    queryKey: ['doctors', specialty],
    queryFn: () => searchDoctors(specialty ?? undefined),
    enabled: !!specialty,
  });
  const doctorOptions = useMemo(
    () => (doctors.data ?? []).map((d) => ({ value: d.practitioner.id!, label: formatName(d.practitioner.name) })),
    [doctors.data],
  );

  const slots = useInfiniteQuery({
    queryKey: ['slot-search', specialty, practitioner, from, to],
    queryFn: ({ pageParam }) =>
      searchSlotPage({
        specialty: specialty ?? undefined,
        practitioner: practitioner ?? undefined,
        fromDate: from!,
        toDate: to!,
        pageToken: pageParam,
        count: PAGE_SIZE,
      }),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.nextPageToken,
    enabled: !!specialty && !!from && !!to && !tooLong,
  });
  const all = (slots.data?.pages ?? []).flatMap((p) => p.slots);
  const groups = groupByDay(all);

  return (
    <div className="shp-page">
      <PageHeader title={t('slotSearch.title')} sub={t('slotSearch.sub')} />
      <Card>
        <SimpleGrid cols={{ base: 1, sm: 3 }}>
          <Select
            label={t('specialty.label')}
            placeholder={t('specialty.pick')}
            data={SPECIALTIES.map((s) => ({ value: s.code, label: s.display }))}
            value={specialty}
            onChange={(v) => {
              setSpecialty(v);
              setPractitioner(null);
            }}
            searchable
          />
          <Select
            label={t('book.doctor')}
            placeholder={t('slotSearch.doctorAny')}
            data={doctorOptions}
            value={practitioner}
            onChange={setPractitioner}
            disabled={!specialty}
            clearable
          />
          <DatePickerInput
            type="range"
            label={t('slotSearch.dates')}
            value={range}
            onChange={setRange}
            minDate={today.toDate()}
            maxDate={isPatient ? today.add(PATIENT_MAX_DAYS_AHEAD, 'day').toDate() : undefined}
            valueFormat="DD/MM/YYYY"
            error={tooLong ? t('slotSearch.maxRange') : undefined}
          />
        </SimpleGrid>
      </Card>

      <Card title={t('slotSearch.list')}>
        <Stack>
          <ProblemAlert error={slots.error} />
          {!specialty ? (
            <Text size="sm" c="dimmed">
              {t('slotSearch.pickSpecialty')}
            </Text>
          ) : slots.isLoading ? (
            <Loading />
          ) : groups.length === 0 ? (
            <EmptyState title={t('slotSearch.none')} />
          ) : (
            <>
              <Text size="sm" c="dimmed">
                {specialtyName(specialty)}
              </Text>
              {groups.map(([day, daySlots]) => (
                <div key={day}>
                  <Text fw={600} size="sm" mb={6}>
                    {vnDayLabel(day)}
                  </Text>
                  <div className="shp-slots" role="list" aria-label={vnDayLabel(day)}>
                    {daySlots.map((s) => (
                      <span key={s.id} role="listitem" className="shp-slot" title={s.schedule.display}>
                        {vnTime(s.start)}
                        {s.schedule.display ? ` · ${s.schedule.display}` : ''}
                      </span>
                    ))}
                  </div>
                </div>
              ))}
              {slots.hasNextPage && (
                <Group>
                  <Button
                    variant="default"
                    onClick={() => void slots.fetchNextPage()}
                    loading={slots.isFetchingNextPage}
                  >
                    {t('slotSearch.loadMore')}
                  </Button>
                </Group>
              )}
            </>
          )}
        </Stack>
      </Card>
    </div>
  );
}
