import { Badge, Group, SimpleGrid, Text } from '@mantine/core';
import type { AllergyIntolerance, Condition, Observation, Patient } from '@medplum/fhirtypes';
import { useTranslation } from 'react-i18next';
import { ageFrom, conceptText, formatDate, formatDateTime, formatName } from '@/api/fhir';
import { Card } from './ui';

/**
 * Tóm tắt hồ sơ bệnh nhân: theo bố cục PatientSummary của portal PoC trước (khối dị ứng, chẩn đoán, sinh hiệu),
 * viết lại cho dữ liệu FHIR của SHP. Dị ứng mức cao hiển thị đỏ để bác sĩ thấy ngay.
 */
export function PatientBanner({ patient }: { readonly patient: Patient }) {
  const { t } = useTranslation();
  const age = ageFrom(patient.birthDate);
  const mrn = patient.identifier?.find((i) => i.system === 'urn:shp:mrn')?.value;
  return (
    <Card>
      <Group justify="space-between" wrap="wrap">
        <div>
          <Text fw={700} size="lg">
            {formatName(patient.name)}
          </Text>
          <Text size="sm" c="dimmed">
            {t(`gender.${patient.gender ?? 'unknown'}`)} · {formatDate(patient.birthDate)}
            {age !== undefined && ` (${t('patient.age', { count: age })})`}
          </Text>
        </div>
        <dl className="shp-kv" style={{ gridTemplateColumns: 'auto auto' }}>
          <dt>{t('patient.mrn')}</dt>
          <dd style={{ fontFamily: 'var(--shp-mono)' }}>{mrn}</dd>
          <dt>{t('patient.phone')}</dt>
          <dd>{patient.telecom?.find((c) => c.system === 'phone')?.value}</dd>
        </dl>
      </Group>
    </Card>
  );
}

export function PatientSummary(props: {
  readonly conditions: Condition[];
  readonly allergies: AllergyIntolerance[];
  readonly vitals: Observation[];
}) {
  const { t } = useTranslation();
  const none = (
    <Text size="sm" c="dimmed">
      {t('patient.none')}
    </Text>
  );
  return (
    <SimpleGrid cols={{ base: 1, md: 3 }} spacing="md">
      <Card title={t('patient.allergies')} count={props.allergies.length}>
        {props.allergies.length === 0
          ? none
          : props.allergies.map((a) => (
              <Group key={a.id} justify="space-between" mb={6}>
                <Text size="sm">{conceptText(a.code)}</Text>
                <Badge color={a.criticality === 'high' ? 'red' : 'gray'} variant="light" size="sm">
                  {a.criticality}
                </Badge>
              </Group>
            ))}
      </Card>
      <Card title={t('patient.conditions')} count={props.conditions.length}>
        {props.conditions.length === 0
          ? none
          : props.conditions.map((c) => (
              <div key={c.id} style={{ marginBottom: 8 }}>
                <Text size="sm" fw={600}>
                  {conceptText(c.code)}
                </Text>
                <Text size="xs" c="dimmed">
                  <span className="shp-chip shp-chip--muted" style={{ height: 18, marginRight: 6 }}>
                    {c.code?.coding?.[0]?.code}
                  </span>
                  {formatDate(c.onsetDateTime)}
                </Text>
              </div>
            ))}
      </Card>
      <Card title={t('patient.vitals')} count={props.vitals.length}>
        {props.vitals.length === 0 ? (
          none
        ) : (
          <dl className="shp-kv" style={{ gridTemplateColumns: 'minmax(0,1fr) auto' }}>
            {props.vitals.map((o) => (
              <div key={o.id} style={{ display: 'contents' }}>
                <dt title={formatDateTime(o.effectiveDateTime)}>{conceptText(o.code)}</dt>
                <dd style={{ fontVariantNumeric: 'tabular-nums', fontWeight: 600 }}>
                  {o.valueQuantity?.value} {o.valueQuantity?.unit}
                </dd>
              </div>
            ))}
          </dl>
        )}
      </Card>
    </SimpleGrid>
  );
}
