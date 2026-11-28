import { Alert, Button, Group, Textarea } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconInfoCircle } from '@tabler/icons-react';
import { useMutation } from '@tanstack/react-query';
import { useRef, useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { createPatientAtDesk, type RegistrationInput } from '@/api/clinical';
import { isClientError, newIdempotencyKey } from '@/api/client';
import { Card, PageHeader, ProblemAlert } from '@/components/ui';
import { RegistrationFields } from '@/pages/patient/RegistrationFields';
import {
  isDuplicate,
  OVERRIDE_REASON_MAX,
  validateOverrideReason,
  validateRegistration,
  type FieldErrors,
  type RegistrationDraft,
} from '@/pages/patient/registrationForm';

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

/** Front-desk registration (C3): a nurse creates a record for a patient who has no account. */
export function RegisterPatientPage() {
  const { t } = useTranslation();
  const [draft, setDraft] = useState<RegistrationDraft>(EMPTY);
  const [errors, setErrors] = useState<FieldErrors>({});
  const [createdId, setCreatedId] = useState<string | undefined>();
  const [reason, setReason] = useState('');
  const [reasonError, setReasonError] = useState<string | undefined>();
  const idemKey = useRef<string | undefined>(undefined);

  const create = useMutation({
    mutationFn: (overrideReason?: string) => {
      idemKey.current ??= newIdempotencyKey();
      return createPatientAtDesk(draft as RegistrationInput, idemKey.current, overrideReason);
    },
    onSuccess: (p) => {
      const mrn = p.identifier?.find((i) => i.system === 'urn:shp:mrn')?.value ?? '';
      notifications.show({ color: 'teal', message: t('profile.deskCreated', { mrn }) });
      setCreatedId(p.id);
      setDraft(EMPTY);
      setReason('');
      idemKey.current = undefined;
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
    setCreatedId(undefined);
    if (Object.keys(found).length === 0) create.mutate(undefined);
  }

  function createAnyway(): void {
    const problem = validateOverrideReason(reason);
    setReasonError(problem);
    if (!problem) create.mutate(reason.trim());
  }

  return (
    <div className="shp-page">
      <PageHeader title={t('profile.deskTitle')} sub={t('profile.deskSub')} />
      <Card title={t('profile.registerTitle')}>
        {isDuplicate(create.error) ? (
          <Alert color="orange" icon={<IconInfoCircle size={18} />} title={t('profile.deskDuplicateTitle')} mb="md">
            {t('profile.deskDuplicateBody')}
            <Textarea
              mt="sm"
              required
              autosize
              minRows={2}
              maxLength={OVERRIDE_REASON_MAX}
              label={t('profile.overrideReason')}
              description={t('profile.overrideReasonHint')}
              value={reason}
              error={reasonError ? t(reasonError) : undefined}
              onChange={(e) => setReason(e.currentTarget.value)}
            />
            <Group mt="sm">
              <Button color="orange" onClick={createAnyway} loading={create.isPending}>
                {t('profile.createAnyway')}
              </Button>
            </Group>
          </Alert>
        ) : (
          <ProblemAlert error={create.error} />
        )}
        <form onSubmit={submit} noValidate>
          <RegistrationFields draft={draft} errors={errors} onChange={(p) => setDraft((d) => ({ ...d, ...p }))} />
          <Group mt="md">
            <Button type="submit" loading={create.isPending}>
              {t('profile.register')}
            </Button>
            {createdId && (
              <Button component={Link} to={`/patients/${createdId}`} variant="light">
                {t('profile.openRecord')}
              </Button>
            )}
          </Group>
        </form>
      </Card>
    </div>
  );
}
