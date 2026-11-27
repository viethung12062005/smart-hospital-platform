import { Alert } from '@mantine/core';
import { IconInfoCircle } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';

/** The 422 duplicate-patient-suspected answer is not a failure of the form: it tells the user how to continue. */
export function DuplicateNotice() {
  const { t } = useTranslation();
  return (
    <Alert color="orange" icon={<IconInfoCircle size={18} />} title={t('profile.duplicateTitle')} mb="md">
      {t('profile.duplicateBody')}
    </Alert>
  );
}
