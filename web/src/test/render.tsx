import { render } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { AppRoutes, Providers } from '@/App';
import type { Role } from '@/auth/roles';
import { signInMock } from '@/auth/session';
import { createQueryClient } from '@/queryClient';

/** Render the whole app at a route, already signed in as the mock user for the given role. */
export function renderApp(path: string, role: Role) {
  signInMock(role);
  const queryClient = createQueryClient();
  queryClient.setDefaultOptions({ queries: { retry: false, staleTime: 0 } });
  return render(
    <Providers queryClient={queryClient}>
      <MemoryRouter initialEntries={[path]}>
        <AppRoutes />
      </MemoryRouter>
    </Providers>,
  );
}
