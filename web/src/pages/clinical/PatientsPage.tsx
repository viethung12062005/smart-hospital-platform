import { Button, Group, TextInput } from '@mantine/core';
import { IconSearch } from '@tabler/icons-react';
import { useQuery } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { searchPatients } from '@/api/clinical';
import { formatDate, formatName } from '@/api/fhir';
import { Card, EmptyState, Loading, PageHeader, ProblemAlert } from '@/components/ui';

export function PatientsPage() {
  const { t } = useTranslation();
  const [input, setInput] = useState('');
  const [query, setQuery] = useState('');
  const patients = useQuery({
    queryKey: ['patients', query],
    queryFn: () => searchPatients(query),
    enabled: query.length >= 2,
  });

  function submit(e: FormEvent): void {
    e.preventDefault();
    setQuery(input.trim());
  }

  return (
    <div className="shp-page">
      <PageHeader title={t('patient.title')} sub={t('patient.searchHint')} />
      <form onSubmit={submit}>
        <Group mb="md" align="flex-end">
          <TextInput
            style={{ flex: 1, maxWidth: 480 }}
            leftSection={<IconSearch size={16} />}
            placeholder="Nguyen Lan · 0900010001 · BN-2026-000001"
            value={input}
            onChange={(e) => setInput(e.currentTarget.value)}
            aria-label={t('common.search')}
          />
          <Button type="submit" disabled={input.trim().length < 2}>
            {t('common.search')}
          </Button>
        </Group>
      </form>
      <ProblemAlert error={patients.error} />
      <Card flush count={patients.data?.length} title={query ? `“${query}”` : undefined}>
        {!query ? (
          <EmptyState title={t('patient.startSearch')} />
        ) : patients.isLoading ? (
          <Loading />
        ) : (patients.data ?? []).length === 0 ? (
          <EmptyState title={t('common.noData')} />
        ) : (
          <table className="shp-tbl">
            <thead>
              <tr>
                <th>{t('patient.mrn')}</th>
                <th>{t('patient.name')}</th>
                <th>{t('patient.birthDate')}</th>
                <th>{t('patient.gender')}</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {(patients.data ?? []).map((p) => (
                <tr key={p.id}>
                  <td style={{ fontFamily: 'var(--shp-mono)' }}>
                    {p.identifier?.find((i) => i.system === 'urn:shp:mrn')?.value}
                  </td>
                  <td className="strong">{formatName(p.name)}</td>
                  <td>{formatDate(p.birthDate)}</td>
                  <td>{t(`gender.${p.gender ?? 'unknown'}`)}</td>
                  <td className="tight">
                    <Button component={Link} to={`/patients/${p.id}`} size="xs" variant="light">
                      {t('common.view')}
                    </Button>
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
