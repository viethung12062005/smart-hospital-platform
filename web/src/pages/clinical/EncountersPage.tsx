import { SegmentedControl } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { searchEncounters } from '@/api/clinical';
import { conceptText, formatDateTime, refId } from '@/api/fhir';
import { Card, EmptyState, Loading, PageHeader, ProblemAlert, StatusChip } from '@/components/ui';

/** Danh sách lần khám (theo bố cục Encounters của portal PoC trước), lọc theo trạng thái. */
export function EncountersPage() {
  const { t } = useTranslation();
  const [status, setStatus] = useState('all');
  const encounters = useQuery({
    queryKey: ['encounters-list', status],
    queryFn: () => searchEncounters({ status: status === 'all' ? undefined : status }),
  });

  return (
    <div className="shp-page">
      <PageHeader
        title={t('encounter.title')}
        sub={t('encounter.sub')}
        actions={
          <SegmentedControl
            value={status}
            onChange={setStatus}
            data={[
              { value: 'all', label: t('common.all') },
              { value: 'in-progress', label: t('status.in-progress') },
              { value: 'finished', label: t('status.finished') },
            ]}
          />
        }
      />
      <ProblemAlert error={encounters.error} />
      <Card flush count={encounters.data?.length} title={t('encounter.title')}>
        {encounters.isLoading ? (
          <Loading />
        ) : (encounters.data ?? []).length === 0 ? (
          <EmptyState title={t('common.noData')} />
        ) : (
          <table className="shp-tbl">
            <thead>
              <tr>
                <th>{t('encounter.period')}</th>
                <th>{t('appt.patient')}</th>
                <th>{t('encounter.reason')}</th>
                <th>{t('appt.doctor')}</th>
                <th>{t('appt.status')}</th>
              </tr>
            </thead>
            <tbody>
              {(encounters.data ?? []).map((e) => (
                <tr key={e.id}>
                  <td className="strong">{formatDateTime(e.period?.start)}</td>
                  <td>
                    <Link to={`/patients/${refId(e.subject)}`}>{e.subject?.display}</Link>
                  </td>
                  <td>{conceptText(e.reasonCode?.[0])}</td>
                  <td>{e.participant?.[0]?.individual?.display}</td>
                  <td>
                    <StatusChip status={e.status} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>
    </div>
  );
}
