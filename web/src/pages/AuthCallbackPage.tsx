import { Alert, Button } from '@mantine/core';
import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '@/auth/useAuth';
import { completeSignIn } from '@/auth/session';
import { Loading } from '@/components/ui';

/** Receive the authorization code from Keycloak (redirect URI /auth/callback), exchange it for tokens, then return to the previous page. */
export function AuthCallbackPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { refresh } = useAuth();
  const [failed, setFailed] = useState(false);
  const started = useRef(false);

  useEffect(() => {
    if (started.current) return; // StrictMode runs effects twice; the authorization code can only be used once.
    started.current = true;
    completeSignIn()
      .then(async (returnTo) => {
        await refresh();
        navigate(returnTo, { replace: true });
      })
      .catch((e: unknown) => {
        console.error('Sign-in failed', e);
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
