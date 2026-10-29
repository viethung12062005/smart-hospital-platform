import { createContext } from 'react';
import type { Role, SessionUser } from './roles';

export interface AuthState {
  readonly user: SessionUser | undefined;
  readonly loading: boolean;
  readonly mode: 'oidc' | 'mock';
  readonly signIn: (returnTo?: string) => Promise<void>;
  readonly signInAs: (role: Role) => void;
  readonly signOut: () => Promise<void>;
  readonly refresh: () => Promise<void>;
}

export const AuthContext = createContext<AuthState | undefined>(undefined);
