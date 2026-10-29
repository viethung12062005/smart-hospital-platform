import { Button, Group } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import dayjs from 'dayjs';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { getMe } from '@/api/clinical';
import { formatDateTime } from '@/api/fhir';
import { participantName, searchAppointments } from '@/api/scheduling';
import { useAuth } from '@/auth/useAuth';
import { Card, KpiTile, PageHeader, StatusChip } from '@/components/ui';
import { menusFor } from '@/layout/menus';

/** Trang chủ theo vai trò: KPI nhanh và lối tắt tới các màn hình được phép. */
export function HomePage() {
  const { t } = useTranslation();
  const { user } = useAuth();
  const roles = user?.roles ?? [];
  const isPatient = roles.includes('patient');
  const isStaff = roles.includes('nurse') || roles.includes('doctor');

  const me = useQuery({ queryKey: ['me'], queryFn: getMe });
  const today = dayjs().startOf('day');
  const appts = useQuery({
    queryKey: ['home-appointments', isPatient, me.data?.fhirUser],
    enabled: (isPatient && !!me.data?.fhirUser) || isStaff,
    queryFn: () =>
      isPatient
        ? searchAppointments({ patient: me.data?.fhirUser, status: 'booked', from: dayjs().format() })
        : searchAppointments({
            from: today.format('YYYY-MM-DD'),
            to: today.add(1, 'day').format('YYYY-MM-DD'),
          }),
  });
  const list = appts.data ?? [];
  const links = menusFor(roles)
    .flatMap((g) => g.links)
    .filter((l) => l.href !== '/');

  return (
    <div className="shp-page">
      <PageHeader
        title={t('home.hello', { name: user?.name ?? '' })}
        sub={t('home.sub', {
          role: roles.map((r) => t(`role.${r}`)).join(', '),
          date: dayjs().format('dddd, DD/MM/YYYY'),
        })}
      />
      {(isPatient || isStaff) && (
        <div className="shp-kpis">
          {isPatient ? (
            <KpiTile value={list.length} label={t('home.upcoming')} />
          ) : (
            <>
              <KpiTile value={list.length} label={t('home.todayAppointments')} />
              <KpiTile value={list.filter((a) => a.status === 'booked').length} label={t('status.booked')} />
              <KpiTile value={list.filter((a) => a.status === 'arrived').length} label={t('home.waiting')} />
              <KpiTile value={list.filter((a) => a.status === 'fulfilled').length} label={t('status.fulfilled')} />
            </>
          )}
        </div>
      )}
      <div style={{ display: 'grid', gap: 16, gridTemplateColumns: 'repeat(auto-fit, minmax(320px, 1fr))' }}>
        {(isPatient || isStaff) && (
          <Card title={isPatient ? t('home.upcoming') : t('home.todayAppointments')} count={list.length} flush>
            <table className="shp-tbl">
              <tbody>
                {list.slice(0, 6).map((a) => (
                  <tr key={a.id}>
                    <td>
                      <span className="strong">{formatDateTime(a.start)}</span>
                      <span className="sub">
                        {isPatient ? participantName(a, 'Practitioner') : participantName(a, 'Patient')}
                      </span>
                    </td>
                    <td className="tight">
                      <StatusChip status={a.status} />
                    </td>
                  </tr>
                ))}
                {list.length === 0 && (
                  <tr>
                    <td style={{ color: 'var(--shp-muted)' }}>{t('common.noData')}</td>
                  </tr>
                )}
              </tbody>
            </table>
          </Card>
        )}
        <Card title={t('home.shortcuts')}>
          <Group gap="xs">
            {links.map((l) => (
              <Button key={l.href} component={Link} to={l.href} variant="light" leftSection={l.icon}>
                {t(`nav.${l.label}`)}
              </Button>
            ))}
          </Group>
        </Card>
      </div>
    </div>
  );
}
