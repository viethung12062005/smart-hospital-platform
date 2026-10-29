import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';
import { getConfig } from '@/config';
import { AuthContext, type AuthState } from './context';
import type { SessionUser } from './roles';
import * as session from './session';

export function AuthProvider({ children }: { readonly children: ReactNode }) {
  const [user, setUser] = useState<SessionUser | undefined>();
  const [loading, setLoading] = useState(true);
  const mode = getConfig().authMode;

  const refresh = useCallback(async () => {
    setUser(await session.currentUser());
    setLoading(false);
  }, []);

  useEffect(() => {
    let active = true;
    void session.currentUser().then((u) => {
      if (!active) return;
      setUser(u);
      setLoading(false);
    });
    return () => {
      active = false;
    };
  }, []);

  const value = useMemo<AuthState>(
    () => ({
      user,
      loading,
      mode,
      refresh,
      signIn: (returnTo) => session.signIn(returnTo),
      signInAs: (role) => setUser(session.signInMock(role)),
      signOut: async () => {
        await session.signOut();
        setUser(undefined);
      },
    }),
    [user, loading, mode, refresh],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
