import { Button, Group, TextInput } from '@mantine/core';
import type { Appointment } from '@medplum/fhirtypes';
import { notifications } from '@mantine/notifications';
import { IconSearch } from '@tabler/icons-react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import dayjs from 'dayjs';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { ApiError } from '@/api/client';
import { formatTime, refId } from '@/api/fhir';
import { checkIn, participantName, participantRef, searchAppointments } from '@/api/scheduling';
import { specialtyName } from '@/api/terminology';
import { useAuth } from '@/auth/useAuth';
import { Card, EmptyState, KpiTile, Loading, PageHeader, ProblemAlert, StatusChip } from '@/components/ui';

const STATUSES = ['booked', 'arrived', 'fulfilled', 'noshow', 'cancelled'] as const;

/** Hàng đợi hôm nay (theo bố cục AppointmentQueue của portal PoC trước): KPI theo trạng thái làm bộ lọc, check-in. */
export function QueuePage() {
  const { t } = useTranslation();
  const { user } = useAuth();
  const queryClient = useQueryClient();
  const [status, setStatus] = useState<string | undefined>();
  const [text, setText] = useState('');
  const today = dayjs().startOf('day');

  const appts = useQuery({
    queryKey: ['queue', today.format('YYYY-MM-DD')],
    queryFn: () =>
      searchAppointments({ from: today.format('YYYY-MM-DD'), to: today.add(1, 'day').format('YYYY-MM-DD') }),
    refetchInterval: 30_000,
  });

  const checkInMut = useMutation({
    mutationFn: (a: Appointment) => checkIn(a),
    onSuccess: (a) => {
      notifications.show({
        color: 'teal',
        message: t('queue.checkedIn', { name: participantName(a, 'Patient') }),
      });
      void queryClient.invalidateQueries({ queryKey: ['queue'] });
    },
    onError: (e) => {
      notifications.show({ color: 'red', message: e instanceof ApiError ? e.message : t('error.title') });
    },
  });

  const all = appts.data ?? [];
  const rows = all.filter(
    (a) =>
      (!status || a.status === status) &&
      participantName(a, 'Patient').toLowerCase().includes(text.trim().toLowerCase()),
  );
  const canCheckIn = user?.roles.includes('nurse');

  return (
    <div className="shp-page">
      <PageHeader title={t('queue.title')} sub={`${today.format('dddd, DD/MM/YYYY')} · ${t('queue.sub')}`} />
      <div className="shp-kpis">
        <KpiTile value={all.length} label={t('common.all')} active={!status} onClick={() => setStatus(undefined)} />
        {STATUSES.map((s) => (
          <KpiTile
            key={s}
            value={all.filter((a) => a.status === s).length}
            label={t(`status.${s}`)}
            active={status === s}
            onClick={() => setStatus(status === s ? undefined : s)}
          />
        ))}
      </div>
      <ProblemAlert error={appts.error} />
      <Card
        title={t('home.todayAppointments')}
        count={rows.length}
        flush
        actions={
          <TextInput
            size="xs"
            leftSection={<IconSearch size={14} />}
            placeholder={t('patient.name')}
            value={text}
            onChange={(e) => setText(e.currentTarget.value)}
          />
        }
      >
        {appts.isLoading ? (
          <Loading />
        ) : rows.length === 0 ? (
          <EmptyState title={t('common.noData')} />
        ) : (
          <table className="shp-tbl">
            <thead>
              <tr>
                <th>{t('queue.time')}</th>
                <th>{t('appt.patient')}</th>
                <th>{t('appt.doctor')}</th>
                <th>{t('appt.specialty')}</th>
                <th>{t('appt.status')}</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {rows.map((a) => {
                const patientId = refId(participantRef(a, 'Patient'));
                return (
                  <tr key={a.id}>
                    <td className="strong">{formatTime(a.start)}</td>
                    <td>
                      {patientId ? (
                        <Link to={`/patients/${patientId}`}>{participantName(a, 'Patient')}</Link>
                      ) : (
                        participantName(a, 'Patient')
                      )}
                    </td>
                    <td>{participantName(a, 'Practitioner')}</td>
                    <td>{specialtyName(a.serviceType?.[0]?.coding?.[0]?.code)}</td>
                    <td>
                      <StatusChip status={a.status} />
                    </td>
                    <td className="tight">
                      <Group gap={4} wrap="nowrap">
                        {canCheckIn && a.status === 'booked' && (
                          <Button
                            size="xs"
                            loading={checkInMut.isPending && checkInMut.variables?.id === a.id}
                            onClick={() => checkInMut.mutate(a)}
                          >
                            {t('queue.checkIn')}
                          </Button>
                        )}
                      </Group>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        )}
      </Card>
    </div>
  );
}
