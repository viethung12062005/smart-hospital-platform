/*
 * Chuyển thể từ AppShell của Medplum (https://github.com/medplum/medplum, packages/react/src/AppShell),
 * Copyright Orangebot, Inc. và các tác giả Medplum, giấy phép Apache-2.0 (xem web/NOTICE và web/licenses/Apache-2.0.txt).
 * Đã sửa: bỏ phụ thuộc client Medplum, dùng AuthProvider của SHP, menu theo vai trò, i18n vi/en,
 * thanh trên theo hệ thiết kế SHP (src/styles/tokens.css).
 */
import { AppShell as MantineAppShell } from '@mantine/core';
import { useState, type ReactNode } from 'react';
import { useLocation } from 'react-router-dom';
import { useAuth } from '@/auth/useAuth';
import { ErrorBoundary } from '@/components/ErrorBoundary';
import classes from './AppShell.module.css';
import { Header } from './Header';
import { menusFor } from './menus';
import { Navbar } from './Navbar';

const NAVBAR_KEY = 'shp.navbarOpen';

function readNavbarOpen(): boolean {
  try {
    return localStorage.getItem(NAVBAR_KEY) !== 'false';
  } catch {
    return true;
  }
}

export function AppShell({ children }: { readonly children: ReactNode }) {
  const [navbarOpen, setNavbarOpen] = useState(readNavbarOpen);
  const { user } = useAuth();
  const location = useLocation();

  function setOpen(open: boolean): void {
    try {
      localStorage.setItem(NAVBAR_KEY, String(open));
    } catch {
      // bỏ qua
    }
    setNavbarOpen(open);
  }

  return (
    <MantineAppShell
      header={{ height: 52 }}
      navbar={{ width: 240, breakpoint: 'sm', collapsed: { desktop: !navbarOpen, mobile: !navbarOpen } }}
      padding={0}
    >
      <Header navbarOpen={navbarOpen} navbarToggle={() => setOpen(!navbarOpen)} />
      <Navbar
        pathname={location.pathname}
        menus={menusFor(user?.roles ?? [])}
        closeNavbar={() => {
          if (window.innerWidth < 768) setOpen(false);
        }}
      />
      <MantineAppShell.Main className={classes.main}>
        <ErrorBoundary key={location.pathname}>{children}</ErrorBoundary>
      </MantineAppShell.Main>
    </MantineAppShell>
  );
}
