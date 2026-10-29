import { Stack } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { useParams } from 'react-router-dom';
import { getPatient, getPatientSummary, searchEncounters } from '@/api/clinical';
import { conceptText, formatDateTime } from '@/api/fhir';
import { PatientBanner, PatientSummary } from '@/components/PatientSummary';
import { Card, EmptyState, Loading, ProblemAlert, StatusChip } from '@/components/ui';

/** Hồ sơ bệnh nhân: banner, tóm tắt (dị ứng, chẩn đoán, sinh hiệu) và dòng thời gian các lần khám. */
export function PatientDetailPage() {
  const { t } = useTranslation();
  const { id = '' } = useParams();
  const patient = useQuery({ queryKey: ['patient', id], queryFn: () => getPatient(id) });
  const summary = useQuery({ queryKey: ['patient-summary', id], queryFn: () => getPatientSummary(id) });
  const encounters = useQuery({
    queryKey: ['encounters', id],
    queryFn: () => searchEncounters({ patient: `Patient/${id}` }),
  });

  if (patient.isLoading) return <Loading />;
  return (
    <div className="shp-page">
      <ProblemAlert error={patient.error ?? summary.error} />
      {patient.data && (
        <Stack gap="md">
          <PatientBanner patient={patient.data} />
          {summary.data && <PatientSummary {...summary.data} />}
          <Card title={t('patient.encounters')} count={encounters.data?.length}>
            {(encounters.data ?? []).length === 0 ? (
              <EmptyState title={t('common.noData')} />
            ) : (
              <ol className="shp-timeline">
                {(encounters.data ?? []).map((e) => (
                  <li key={e.id}>
                    <span className="v">{e.class?.code?.[0]}</span>
                    <div>
                      <div className="tt">
                        {conceptText(e.reasonCode?.[0])} <StatusChip status={e.status} />
                      </div>
                      <div className="td">
                        {formatDateTime(e.period?.start)} · {e.participant?.[0]?.individual?.display}
                      </div>
                    </div>
                  </li>
                ))}
              </ol>
            )}
          </Card>
        </Stack>
      )}
    </div>
  );
}
