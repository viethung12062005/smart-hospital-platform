/*
 * Adapted from Medplum's AppShell (https://github.com/medplum/medplum, packages/react/src/AppShell),
 * Copyright Orangebot, Inc. and the Medplum authors, Apache-2.0 license (see web/NOTICE and web/licenses/Apache-2.0.txt).
 * Changed: removed the Medplum client dependency, use SHP's AuthProvider, role-based menu, vi/en i18n,
 * and the top bar following the SHP design system (src/styles/tokens.css).
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
      // ignore
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
