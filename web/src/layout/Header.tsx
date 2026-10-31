/*
 * Chuyển thể từ Header của Medplum AppShell (Apache-2.0, xem web/NOTICE).
 * Đã sửa: bỏ ô tìm resource kiểu Medplum, thêm đổi ngôn ngữ, menu người dùng có vai trò và đăng xuất.
 */
import {
  Avatar,
  Badge,
  Burger,
  Group,
  AppShell as MantineAppShell,
  Menu,
  SegmentedControl,
  Text,
  UnstyledButton,
} from '@mantine/core';
import { IconChevronDown, IconLogout } from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { useAuth } from '@/auth/useAuth';
import { currentLang, setLanguage, type Lang } from '@/i18n';
import classes from './AppShell.module.css';

function initials(name: string): string {
  const parts = name
    .replace(/^(BS\.|ĐD\.)\s*/u, '')
    .split(/\s+/)
    .filter(Boolean);
  return ((parts.at(-2)?.[0] ?? '') + (parts.at(-1)?.[0] ?? '')).toUpperCase();
}

export function Header(props: { readonly navbarOpen: boolean; readonly navbarToggle: () => void }) {
  const { t } = useTranslation();
  const { user, signOut, mode } = useAuth();
  const [opened, setOpened] = useState(false);
  const [lang, setLang] = useState<Lang>(currentLang());

  return (
    <MantineAppShell.Header className={classes.header}>
      <Burger opened={props.navbarOpen} onClick={props.navbarToggle} size="sm" aria-label="menu" />
      <UnstyledButton component={Link} to="/" className={classes.brand}>
        <span className={classes.mark}>{t('app.short')}</span>
        <span>{t('app.name')}</span>
      </UnstyledButton>
      {mode === 'mock' && (
        <Badge color="orange" variant="light" size="sm" styles={{ label: { color: '#8a3300' } }}>
          mock
        </Badge>
      )}
      <div style={{ flex: 1 }} />
      <SegmentedControl
        size="xs"
        value={lang}
        aria-label={t('common.language')}
        onChange={(v) => {
          setLanguage(v as Lang);
          setLang(v as Lang);
        }}
        data={[
          { label: 'VI', value: 'vi' },
          { label: 'EN', value: 'en' },
        ]}
      />
      {user && (
        <Menu width={240} shadow="lg" position="bottom-end" opened={opened} onChange={setOpened}>
          <Menu.Target>
            <UnstyledButton className={`${classes.user} ${opened ? classes.userActive : ''}`}>
              <Group gap={8}>
                <Avatar size={28} radius="xl" color="shp" variant="filled">
                  {initials(user.name)}
                </Avatar>
                <span className={classes.userName}>{user.name}</span>
                <IconChevronDown size={12} stroke={1.5} />
              </Group>
            </UnstyledButton>
          </Menu.Target>
          <Menu.Dropdown>
            <Menu.Label>
              <Text size="sm" fw={600} c="dark">
                {user.name}
              </Text>
              <Group gap={4} mt={4}>
                {user.roles.map((r) => (
                  <Badge key={r} size="xs" variant="light">
                    {t(`role.${r}`)}
                  </Badge>
                ))}
              </Group>
            </Menu.Label>
            <Menu.Divider />
            <Menu.Item leftSection={<IconLogout size={16} />} onClick={() => void signOut()}>
              {t('auth.signOut')}
            </Menu.Item>
          </Menu.Dropdown>
        </Menu>
      )}
    </MantineAppShell.Header>
  );
}
