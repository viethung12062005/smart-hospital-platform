import { Alert } from '@mantine/core';
import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { useAuth } from './useAuth';
import { hasAnyRole, type Role } from './roles';

/** Chặn route theo vai trò ở giao diện (máy chủ vẫn kiểm quyền thật ở từng endpoint). */
export function RequireRole(props: { readonly roles: readonly Role[]; readonly children: ReactNode }) {
  const { t } = useTranslation();
  const { user } = useAuth();
  if (!hasAnyRole(user, props.roles)) {
    return (
      <div className="shp-page">
        <Alert color="orange" title="403">
          {t('auth.forbidden')}
        </Alert>
      </div>
    );
  }
  return <>{props.children}</>;
}
