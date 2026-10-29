import { Button, Center, Paper, SimpleGrid, Stack, Text, Title, UnstyledButton } from '@mantine/core';
import { IconLogin } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '@/auth/useAuth';
import { ROLES } from '@/auth/roles';
import { MOCK_USERS } from '@/auth/session';

export function LoginPage() {
  const { t } = useTranslation();
  const { mode, signIn, signInAs } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const returnTo = (location.state as { from?: string } | null)?.from ?? '/';

  return (
    <Center mih="100vh" bg="var(--shp-bg)" p="md">
      <Paper withBorder radius="lg" p="xl" w={mode === 'mock' ? 640 : 420} shadow="sm">
        <Stack gap="xs" mb="lg">
          <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
            <span
              style={{
                width: 34,
                height: 34,
                borderRadius: 9,
                background: 'var(--shp-teal)',
                color: '#fff',
                display: 'grid',
                placeItems: 'center',
                fontWeight: 700,
              }}
            >
              {t('app.short')}
            </span>
            <Title order={3}>{t('app.name')}</Title>
          </div>
          <Text c="dimmed" size="sm">
            {mode === 'mock' ? t('auth.mockHint') : t('auth.signInHint')}
          </Text>
        </Stack>

        {mode === 'mock' ? (
          <SimpleGrid cols={{ base: 1, sm: 2 }} spacing="sm">
            {ROLES.map((role) => (
              <UnstyledButton
                key={role}
                className="shp-kpi"
                onClick={() => {
                  signInAs(role);
                  navigate(returnTo, { replace: true });
                }}
              >
                <Text fw={600}>{t(`role.${role}`)}</Text>
                <Text size="xs" c="dimmed">
                  {MOCK_USERS[role].name}
                </Text>
              </UnstyledButton>
            ))}
          </SimpleGrid>
        ) : (
          <Button fullWidth size="md" leftSection={<IconLogin size={18} />} onClick={() => void signIn(returnTo)}>
            {t('auth.signIn')}
          </Button>
        )}
      </Paper>
    </Center>
  );
}
