import { Alert, Stack } from '@mantine/core';
import { IconShieldExclamation } from '@tabler/icons-react';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { useParams } from 'react-router-dom';
import { ApiError } from '@/api/client';
import { getPatient, getPatientSummary, searchEncounters } from '@/api/clinical';
import { conceptText, formatDateTime } from '@/api/fhir';
import { PatientBanner, PatientSummary } from '@/components/PatientSummary';
import { Card, EmptyState, Loading, ProblemAlert, StatusChip } from '@/components/ui';

/** Patient record: banner, summary (allergies, diagnoses, vital signs) and the encounter timeline. */
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
  // C4 403 without a care relationship (AC-06.2): the doctor may find the patient but not open the record. 09 C4
  // names the code no-care-relationship, 15-authorization §5 names it break-glass-required; both mean the same here.
  const noCare =
    patient.error instanceof ApiError &&
    (patient.error.code === 'no-care-relationship' || patient.error.code === 'break-glass-required');
  if (noCare) {
    return (
      <div className="shp-page">
        <Alert color="orange" icon={<IconShieldExclamation size={18} />} title={t('patient.noCareTitle')} mb="md">
          {t('patient.noCareHint')}
        </Alert>
      </div>
    );
  }
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
