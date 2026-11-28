import { Button, Checkbox, Group, SimpleGrid, Text, TextInput } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import type { Patient } from '@medplum/fhirtypes';
import { IconLock } from '@tabler/icons-react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useRef, useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { getMe, getPatientWithVersion, registerSelf, updatePatient, type RegistrationInput } from '@/api/clinical';
import { ApiError, isClientError, newIdempotencyKey } from '@/api/client';
import { formatDate, formatName } from '@/api/fhir';
import { currentLang } from '@/i18n';
import { Card, Loading, PageHeader, ProblemAlert } from '@/components/ui';
import { RegistrationFields } from './RegistrationFields';
import { DuplicateNotice } from './DuplicateNotice';
import { isDuplicate, validateRegistration, type FieldErrors, type RegistrationDraft } from './registrationForm';

const EMPTY: RegistrationDraft = {
  family: '',
  given: '',
  phone: '',
  email: '',
  gender: '',
  birthDate: '',
  city: '',
  district: '',
  language: 'vi',
};

/** US-001 / US-002 / US-003: register when there is no record yet, otherwise view and edit "my profile". */
export function ProfilePage() {
  const { t } = useTranslation();
  const me = useQuery({ queryKey: ['me'], queryFn: getMe });
  const patientId = me.data?.fhirUser?.startsWith('Patient/') ? me.data.fhirUser.split('/')[1] : undefined;

  return (
    <div className="shp-page">
      <PageHeader
        title={t('profile.title')}
        sub={t(patientId || me.isLoading ? 'profile.sub' : 'profile.subRegister')}
      />
      <ProblemAlert error={me.error} />
      {me.isLoading ? <Loading /> : patientId ? <EditProfile patientId={patientId} /> : me.data && <SelfRegister />}
    </div>
  );
}

function SelfRegister() {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [draft, setDraft] = useState<RegistrationDraft>({ ...EMPTY, language: currentLang() === 'en' ? 'en' : 'vi' });
  const [consent, setConsent] = useState(false);
  const [errors, setErrors] = useState<FieldErrors>({});
  const idemKey = useRef<string | undefined>(undefined);

  const register = useMutation({
    mutationFn: () => {
      idemKey.current ??= newIdempotencyKey();
      return registerSelf(draft as RegistrationInput, idemKey.current);
    },
    onSuccess: (r) => {
      notifications.show({ color: 'teal', message: t('profile.registered', { mrn: r.mrn }) });
      idemKey.current = undefined;
      void queryClient.invalidateQueries({ queryKey: ['me'] });
    },
    onError: (error) => {
      // A 4xx was a definite answer; after a network error or a 5xx a retry must be the same request.
      if (isClientError(error)) idemKey.current = undefined;
    },
  });

  function submit(e: FormEvent): void {
    e.preventDefault();
    const found = validateRegistration(draft);
    setErrors(found);
    if (Object.keys(found).length === 0 && consent) register.mutate();
  }

  const duplicate = isDuplicate(register.error);
  return (
    <Card title={t('profile.registerTitle')}>
      {duplicate ? <DuplicateNotice /> : <ProblemAlert error={register.error} />}
      <form onSubmit={submit} noValidate>
        <RegistrationFields draft={draft} errors={errors} onChange={(p) => setDraft((d) => ({ ...d, ...p }))} />
        <Checkbox
          mt="md"
          label={t('profile.consent')}
          checked={consent}
          onChange={(e) => setConsent(e.currentTarget.checked)}
        />
        <Group mt="md">
          <Button type="submit" loading={register.isPending} disabled={!consent}>
            {t('profile.register')}
          </Button>
        </Group>
      </form>
    </Card>
  );
}

interface EditDraft {
  phone: string;
  email: string;
  city: string;
  district: string;
}

function draftOf(p: Patient): EditDraft {
  return {
    phone: p.telecom?.find((x) => x.system === 'phone')?.value ?? '',
    email: p.telecom?.find((x) => x.system === 'email')?.value ?? '',
    city: p.address?.[0]?.city ?? '',
    district: p.address?.[0]?.district ?? '',
  };
}

/** A patient may only change telecom and address; name, date of birth, gender and MRN are locked (C5). */
function EditProfile({ patientId }: { readonly patientId: string }) {
  const record = useQuery({ queryKey: ['patient', patientId], queryFn: () => getPatientWithVersion(patientId) });
  // Bumped after each successful save so the form restarts from the saved record. A refetch after a 412 does not
  // restart it: the typed changes and the conflict message stay, and the next save uses the fresh version.
  const [saves, setSaves] = useState(0);
  if (record.isLoading) return <Loading />;
  if (record.error) return <ProblemAlert error={record.error} />;
  if (!record.data) return null;
  return <EditForm key={saves} patientId={patientId} record={record.data} onSaved={() => setSaves((n) => n + 1)} />;
}

function EditForm({
  patientId,
  record,
  onSaved,
}: {
  readonly patientId: string;
  readonly record: { patient: Patient; etag?: string };
  readonly onSaved: () => void;
}) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [draft, setDraft] = useState<EditDraft>(() => draftOf(record.patient));
  const [errors, setErrors] = useState<FieldErrors>({});

  const save = useMutation({
    mutationFn: () => {
      const { patient, etag } = record;
      const d = draft;
      const keep = (patient.telecom ?? []).filter((x) => x.system !== 'phone' && x.system !== 'email');
      const next: Patient = {
        ...patient,
        telecom: [
          ...keep,
          { system: 'phone', value: d.phone.trim() },
          ...(d.email.trim() ? [{ system: 'email' as const, value: d.email.trim() }] : []),
        ],
        address: [{ ...patient.address?.[0], city: d.city.trim(), district: d.district.trim() }],
      };
      return updatePatient(next, etag);
    },
    onSuccess: (saved) => {
      notifications.show({ color: 'teal', message: t('profile.saved') });
      // The form restarts from the saved version at once; the refetch below only confirms it.
      queryClient.setQueryData(['patient', patientId], saved);
      void queryClient.invalidateQueries({ queryKey: ['patient', patientId] });
      onSaved();
    },
    onError: (error) => {
      // 412: the record changed since it was read. Reload it so that a retry sends the current version as If-Match.
      if (error instanceof ApiError && error.status === 412) {
        void queryClient.invalidateQueries({ queryKey: ['patient', patientId] });
      }
    },
  });

  const p = record.patient;
  const mrn = p.identifier?.find((i) => i.system === 'urn:shp:mrn')?.value ?? '';

  function submit(e: FormEvent): void {
    e.preventDefault();
    const found: FieldErrors = {};
    if (!/^(0|\+84)\d{9}$/.test(draft.phone.trim())) found.phone = 'profile.errPhone';
    if (draft.email.trim() && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(draft.email.trim())) found.email = 'profile.errEmail';
    setErrors(found);
    if (Object.keys(found).length === 0) save.mutate();
  }

  const set = (patch: Partial<EditDraft>) => setDraft((d) => ({ ...d, ...patch }));
  const locked = (label: string, value: string) => (
    <TextInput
      label={label}
      value={value}
      readOnly
      disabled
      rightSection={<IconLock size={14} aria-hidden />}
      description={t('profile.lockedHint')}
    />
  );
  return (
    <Card title={t('profile.myRecord')}>
      <ProblemAlert error={save.error} />
      <Text size="sm" c="dimmed" mb="md">
        {t('profile.lockedNote')}
      </Text>
      <form onSubmit={submit} noValidate>
        <SimpleGrid cols={{ base: 1, sm: 2 }}>
          {locked(t('patient.mrn'), mrn)}
          {locked(t('patient.name'), formatName(p.name))}
          {locked(t('patient.birthDate'), formatDate(p.birthDate))}
          {locked(t('patient.gender'), t(`gender.${p.gender ?? 'unknown'}`))}
          <TextInput
            required
            type="tel"
            label={t('patient.phone')}
            value={draft.phone}
            error={errors.phone ? t(errors.phone) : undefined}
            onChange={(e) => set({ phone: e.currentTarget.value })}
          />
          <TextInput
            type="email"
            label={t('profile.email')}
            value={draft.email}
            error={errors.email ? t(errors.email) : undefined}
            onChange={(e) => set({ email: e.currentTarget.value })}
          />
          <TextInput
            label={t('profile.city')}
            value={draft.city}
            onChange={(e) => set({ city: e.currentTarget.value })}
          />
          <TextInput
            label={t('profile.district')}
            value={draft.district}
            onChange={(e) => set({ district: e.currentTarget.value })}
          />
        </SimpleGrid>
        <Group mt="md">
          <Button type="submit" loading={save.isPending}>
            {t('common.save')}
          </Button>
        </Group>
      </form>
    </Card>
  );
}
