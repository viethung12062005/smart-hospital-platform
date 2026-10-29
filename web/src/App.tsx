import { MantineProvider } from '@mantine/core';
import { DatesProvider } from '@mantine/dates';
import { Notifications } from '@mantine/notifications';
import { QueryClientProvider, type QueryClient } from '@tanstack/react-query';
import { lazy, Suspense, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { BrowserRouter, Navigate, Route, Routes, useLocation, type RouterProps } from 'react-router-dom';
import { AuthProvider } from '@/auth/AuthProvider';
import { useAuth } from '@/auth/useAuth';
import { RequireRole } from '@/auth/RequireRole';
import type { Role } from '@/auth/roles';
import { EmptyState, Loading } from '@/components/ui';
import { AppShell } from '@/layout/AppShell';
import { AuthCallbackPage } from '@/pages/AuthCallbackPage';
import { HomePage } from '@/pages/HomePage';
import { LoginPage } from '@/pages/LoginPage';
import { createQueryClient } from '@/queryClient';
import { theme } from '@/theme';

// Tách code theo màn hình: người dùng chỉ tải màn hình của vai trò mình.
const EncountersPage = lazy(() =>
  import('@/pages/clinical/EncountersPage').then((m) => ({ default: m.EncountersPage })),
);
const PatientDetailPage = lazy(() =>
  import('@/pages/clinical/PatientDetailPage').then((m) => ({ default: m.PatientDetailPage })),
);
const PatientsPage = lazy(() => import('@/pages/clinical/PatientsPage').then((m) => ({ default: m.PatientsPage })));
const BookAppointmentPage = lazy(() =>
  import('@/pages/patient/BookAppointmentPage').then((m) => ({ default: m.BookAppointmentPage })),
);
const FindDoctorPage = lazy(() =>
  import('@/pages/patient/FindDoctorPage').then((m) => ({ default: m.FindDoctorPage })),
);
const MyAppointmentsPage = lazy(() =>
  import('@/pages/patient/MyAppointmentsPage').then((m) => ({ default: m.MyAppointmentsPage })),
);
const QueuePage = lazy(() => import('@/pages/staff/QueuePage').then((m) => ({ default: m.QueuePage })));
const SchedulePage = lazy(() => import('@/pages/staff/SchedulePage').then((m) => ({ default: m.SchedulePage })));

/** Bắt buộc đăng nhập; chưa đăng nhập thì sang /login và nhớ trang định vào. */
function Protected({ children }: { readonly children: ReactNode }) {
  const { user, loading } = useAuth();
  const location = useLocation();
  if (loading) return <Loading />;
  if (!user) return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />;
  return (
    <AppShell>
      <Suspense fallback={<Loading />}>{children}</Suspense>
    </AppShell>
  );
}

function NotFound() {
  const { t } = useTranslation();
  return (
    <div className="shp-page">
      <EmptyState title="404">{t('error.notFound')}</EmptyState>
    </div>
  );
}

const route = (roles: readonly Role[], element: ReactNode) => <RequireRole roles={roles}>{element}</RequireRole>;

export function AppRoutes() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/auth/callback" element={<AuthCallbackPage />} />
      <Route
        path="/*"
        element={
          <Protected>
            <Routes>
              <Route index element={<HomePage />} />
              <Route path="doctors" element={route(['patient', 'nurse'], <FindDoctorPage />)} />
              <Route path="book" element={route(['patient'], <BookAppointmentPage />)} />
              <Route path="appointments" element={route(['patient'], <MyAppointmentsPage />)} />
              <Route path="queue" element={route(['nurse', 'doctor'], <QueuePage />)} />
              <Route path="schedule" element={route(['doctor', 'nurse', 'admin'], <SchedulePage />)} />
              <Route path="patients" element={route(['doctor', 'nurse'], <PatientsPage />)} />
              <Route path="patients/:id" element={route(['doctor', 'nurse'], <PatientDetailPage />)} />
              <Route path="encounters" element={route(['doctor', 'nurse'], <EncountersPage />)} />
              <Route path="*" element={<NotFound />} />
            </Routes>
          </Protected>
        }
      />
    </Routes>
  );
}

export function Providers(props: { readonly children: ReactNode; readonly queryClient?: QueryClient }) {
  const { i18n } = useTranslation();
  // Một QueryClient cho cả vòng đời ứng dụng (tạo lại mỗi lần render sẽ mất cache).
  const [client] = useState(() => props.queryClient ?? createQueryClient());
  return (
    <MantineProvider theme={theme}>
      <DatesProvider settings={{ locale: i18n.language, firstDayOfWeek: 1 }}>
        <Notifications position="top-right" />
        <QueryClientProvider client={client}>
          <AuthProvider>{props.children}</AuthProvider>
        </QueryClientProvider>
      </DatesProvider>
    </MantineProvider>
  );
}

export function App(props: { readonly router?: Partial<RouterProps> }) {
  return (
    <Providers>
      <BrowserRouter {...props.router}>
        <AppRoutes />
      </BrowserRouter>
    </Providers>
  );
}
