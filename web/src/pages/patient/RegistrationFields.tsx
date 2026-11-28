import { Select, SimpleGrid, TextInput } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import type { FieldErrors, RegistrationDraft } from './registrationForm';

/** The administrative fields shared by self-registration (C1) and front-desk registration (C3). */
export function RegistrationFields(props: {
  readonly draft: RegistrationDraft;
  readonly errors: FieldErrors;
  readonly onChange: (patch: Partial<RegistrationDraft>) => void;
}) {
  const { t } = useTranslation();
  const { draft, errors, onChange } = props;
  const err = (k: keyof FieldErrors) => (errors[k] ? t(errors[k]) : undefined);
  return (
    <SimpleGrid cols={{ base: 1, sm: 2 }}>
      <TextInput
        required
        label={t('profile.family')}
        value={draft.family}
        error={err('family')}
        onChange={(e) => onChange({ family: e.currentTarget.value })}
      />
      <TextInput
        required
        label={t('profile.given')}
        value={draft.given}
        error={err('given')}
        onChange={(e) => onChange({ given: e.currentTarget.value })}
      />
      <TextInput
        required
        type="date"
        label={t('patient.birthDate')}
        value={draft.birthDate}
        error={err('birthDate')}
        onChange={(e) => onChange({ birthDate: e.currentTarget.value })}
      />
      <Select
        required
        label={t('patient.gender')}
        data={(['male', 'female', 'other'] as const).map((g) => ({ value: g, label: t(`gender.${g}`) }))}
        value={draft.gender || null}
        error={err('gender')}
        onChange={(v) => onChange({ gender: (v as RegistrationDraft['gender'] | null) ?? '' })}
      />
      <TextInput
        required
        type="tel"
        label={t('patient.phone')}
        placeholder="0901234567"
        value={draft.phone}
        error={err('phone')}
        onChange={(e) => onChange({ phone: e.currentTarget.value })}
      />
      <TextInput
        type="email"
        label={t('profile.email')}
        value={draft.email ?? ''}
        error={err('email')}
        onChange={(e) => onChange({ email: e.currentTarget.value })}
      />
      <TextInput
        label={t('profile.city')}
        value={draft.city ?? ''}
        onChange={(e) => onChange({ city: e.currentTarget.value })}
      />
      <TextInput
        label={t('profile.district')}
        value={draft.district ?? ''}
        onChange={(e) => onChange({ district: e.currentTarget.value })}
      />
    </SimpleGrid>
  );
}
