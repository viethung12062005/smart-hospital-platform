import { Alert, Button, Group, Modal, Select, Tabs, Text } from '@mantine/core';
import type { Appointment } from '@medplum/fhirtypes';
import { notifications } from '@mantine/notifications';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import dayjs from 'dayjs';
import { useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { getMe } from '@/api/clinical';
import { newIdempotencyKey } from '@/api/client';
import { formatDateTime } from '@/api/fhir';
import { cancelAppointment, participantName, searchAppointments } from '@/api/scheduling';
import { CANCEL_REASONS, specialtyName } from '@/api/terminology';
import { Card, EmptyState, Loading, PageHeader, ProblemAlert, StatusChip } from '@/components/ui';

// Lý do bệnh nhân được chọn khi tự hủy (các lý do khác do hệ thống/nhân viên dùng).
const PATIENT_REASONS = CANCEL_REASONS.filter((r) => ['patient-request', 'other'].includes(r.code));

export function MyAppointmentsPage() {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [tab, setTab] = useState<string | null>('upcoming');
  const [cancelling, setCancelling] = useState<Appointment | undefined>();
  const [reason, setReason] = useState<string | null>('patient-request');
  const idemKey = useRef<string | undefined>(undefined);

  const me = useQuery({ queryKey: ['me'], queryFn: getMe });
  const appts = useQuery({
    queryKey: ['appointments', me.data?.fhirUser],
    queryFn: () => searchAppointments({ patient: me.data?.fhirUser }),
    enabled: !!me.data?.fhirUser,
  });

  const cancel = useMutation({
    mutationFn: () => {
      idemKey.current ??= newIdempotencyKey();
      return cancelAppointment(cancelling!, reason ?? 'patient-request', idemKey.current);
    },
    onSuccess: () => {
      notifications.show({ color: 'teal', message: t('appt.cancelled') });
      setCancelling(undefined);
      idemKey.current = undefined;
      void queryClient.invalidateQueries({ queryKey: ['appointments'] });
      void queryClient.invalidateQueries({ queryKey: ['home-appointments'] });
    },
    onError: () => {
      idemKey.current = undefined;
    },
  });

  const now = dayjs();
  const all = appts.data ?? [];
  const upcoming = all.filter((a) => dayjs(a.start).isAfter(now) && a.status !== 'cancelled');
  const past = all.filter((a) => !upcoming.includes(a)).reverse();
  const rows = tab === 'upcoming' ? upcoming : past;

  return (
    <div className="shp-page">
      <PageHeader
        title={t('appt.title')}
        actions={
          <Button component={Link} to="/book">
            {t('nav.book')}
          </Button>
        }
      />
      <ProblemAlert error={appts.error ?? me.error} />
      <Tabs value={tab} onChange={setTab} mb="md">
        <Tabs.List>
          <Tabs.Tab value="upcoming">
            {t('appt.upcoming')} ({upcoming.length})
          </Tabs.Tab>
          <Tabs.Tab value="past">
            {t('appt.past')} ({past.length})
          </Tabs.Tab>
        </Tabs.List>
      </Tabs>
      <Card flush>
        {appts.isLoading || me.isLoading ? (
          <Loading />
        ) : rows.length === 0 ? (
          <EmptyState title={t('appt.none')} />
        ) : (
          <table className="shp-tbl">
            <thead>
              <tr>
                <th>{t('appt.when')}</th>
                <th>{t('appt.doctor')}</th>
                <th>{t('appt.specialty')}</th>
                <th>{t('appt.status')}</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {rows.map((a) => (
                <tr key={a.id}>
                  <td className="strong">{formatDateTime(a.start)}</td>
                  <td>
                    {participantName(a, 'Practitioner')}
                    {a.description && <span className="sub">{a.description}</span>}
                  </td>
                  <td>{specialtyName(a.serviceType?.[0]?.coding?.[0]?.code)}</td>
                  <td>
                    <StatusChip status={a.status} />
                  </td>
                  <td className="tight">
                    {a.status === 'booked' && tab === 'upcoming' && (
                      <Button size="xs" variant="subtle" color="red" onClick={() => (cancel.reset(), setCancelling(a))}>
                        {t('appt.cancel')}
                      </Button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>

      <Modal opened={!!cancelling} onClose={() => setCancelling(undefined)} title={t('appt.cancelTitle')}>
        {cancelling && (
          <>
            <Text fw={600}>{formatDateTime(cancelling.start)}</Text>
            <Text size="sm" c="dimmed" mb="md">
              {participantName(cancelling, 'Practitioner')}
            </Text>
            <Select
              label={t('appt.cancelReason')}
              data={PATIENT_REASONS.map((r) => ({ value: r.code, label: r.display }))}
              value={reason}
              onChange={setReason}
              mb="sm"
            />
            <Alert color="gray" variant="light" mb="md">
              {t('appt.cancelHint')}
            </Alert>
            <ProblemAlert error={cancel.error} />
            <Group justify="flex-end">
              <Button variant="default" onClick={() => setCancelling(undefined)}>
                {t('common.close')}
              </Button>
              <Button color="red" loading={cancel.isPending} onClick={() => cancel.mutate()}>
                {t('appt.cancel')}
              </Button>
            </Group>
          </>
        )}
      </Modal>
    </div>
  );
}
