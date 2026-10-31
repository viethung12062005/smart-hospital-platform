import { Alert, Button } from '@mantine/core';
import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '@/auth/useAuth';
import { completeSignIn } from '@/auth/session';
import { Loading } from '@/components/ui';

/** Nhận mã ủy quyền từ Keycloak (redirect URI /auth/callback), đổi lấy token rồi quay về trang cũ. */
export function AuthCallbackPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { refresh } = useAuth();
  const [failed, setFailed] = useState(false);
  const started = useRef(false);

  useEffect(() => {
    if (started.current) return; // StrictMode gọi effect 2 lần; mã ủy quyền chỉ dùng được một lần.
    started.current = true;
    completeSignIn()
      .then(async (returnTo) => {
        await refresh();
        navigate(returnTo, { replace: true });
      })
      .catch((e: unknown) => {
        console.error('Đăng nhập thất bại', e);
        setFailed(true);
      });
  }, [navigate, refresh]);

  if (failed) {
    return (
      <div className="shp-page">
        <Alert color="red" title={t('error.title')}>
          {t('auth.sessionExpired')}
          <div style={{ marginTop: 12 }}>
            <Button size="xs" onClick={() => navigate('/login', { replace: true })}>
              {t('auth.signIn')}
            </Button>
          </div>
        </Alert>
      </div>
    );
  }
  return <Loading />;
}
