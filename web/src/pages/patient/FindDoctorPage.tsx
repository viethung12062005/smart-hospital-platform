import { Avatar, Button, Group, Radio, ScrollArea, SimpleGrid, Stack, Text, TextInput } from '@mantine/core';
import { IconSearch } from '@tabler/icons-react';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useSearchParams } from 'react-router-dom';
import { formatName } from '@/api/fhir';
import { searchDoctors } from '@/api/scheduling';
import { SPECIALTIES, specialtyName } from '@/api/terminology';
import { useAuth } from '@/auth/useAuth';
import { Card, EmptyState, Loading, PageHeader, ProblemAlert } from '@/components/ui';

/** Tìm bác sĩ: bộ lọc chuyên khoa bên trái, danh sách bác sĩ bên phải (theo bố cục FindDoctor của portal PoC trước). */
export function FindDoctorPage() {
  const { t } = useTranslation();
  const { user } = useAuth();
  const [params, setParams] = useSearchParams();
  const specialty = params.get('specialty') ?? SPECIALTIES[0]?.code ?? '';
  const [text, setText] = useState('');
  const doctors = useQuery({ queryKey: ['doctors', specialty], queryFn: () => searchDoctors(specialty) });

  const filtered = (doctors.data ?? []).filter((d) =>
    formatName(d.practitioner.name).toLowerCase().includes(text.trim().toLowerCase()),
  );
  const canBook = user?.roles.includes('patient');

  return (
    <div className="shp-page">
      <PageHeader title={t('doctor.title')} sub={t('doctor.sub')} />
      <div className="shp-with-side">
        <Card title={t('specialty.label')}>
          <ScrollArea.Autosize mah={480}>
            <Radio.Group value={specialty} onChange={(v) => setParams({ specialty: v })}>
              <Stack gap={8}>
                {SPECIALTIES.map((s) => (
                  <Radio key={s.code} value={s.code} label={s.display} size="sm" />
                ))}
              </Stack>
            </Radio.Group>
          </ScrollArea.Autosize>
        </Card>
        <Stack gap="md">
          <TextInput
            leftSection={<IconSearch size={16} />}
            placeholder={t('doctor.searchName')}
            value={text}
            onChange={(e) => setText(e.currentTarget.value)}
          />
          <ProblemAlert error={doctors.error} />
          {doctors.isLoading ? (
            <Loading />
          ) : filtered.length === 0 ? (
            <Card>
              <EmptyState title={t('doctor.noneFound')} />
            </Card>
          ) : (
            <>
              <Text size="sm" c="dimmed">
                {t('doctor.count', { count: filtered.length })} · {specialtyName(specialty)}
              </Text>
              <SimpleGrid cols={{ base: 1, sm: 2, lg: 3 }} spacing="md">
                {filtered.map(({ practitioner, specialty: sp }) => (
                  <Card key={practitioner.id}>
                    <Group wrap="nowrap" align="flex-start">
                      <Avatar color="shp" radius="xl" size={44}>
                        {practitioner.name?.[0]?.given?.[0]?.split(' ').at(-1)?.[0]}
                      </Avatar>
                      <div style={{ flex: 1, minWidth: 0 }}>
                        <Text fw={600}>{formatName(practitioner.name)}</Text>
                        <span className="shp-chip shp-chip--teal" style={{ marginTop: 6 }}>
                          {specialtyName(sp)}
                        </span>
                      </div>
                    </Group>
                    {canBook && (
                      <Button
                        component={Link}
                        to={`/book?specialty=${sp}&practitioner=${practitioner.id}`}
                        fullWidth
                        mt="md"
                        variant="light"
                      >
                        {t('doctor.book')}
                      </Button>
                    )}
                  </Card>
                ))}
              </SimpleGrid>
            </>
          )}
        </Stack>
      </div>
    </div>
  );
}
