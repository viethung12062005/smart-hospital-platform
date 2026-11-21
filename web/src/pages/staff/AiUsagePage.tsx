import { Alert, Progress, SegmentedControl, Text, TextInput } from '@mantine/core';
import { IconAlertTriangle } from '@tabler/icons-react';
import { useQuery } from '@tanstack/react-query';
import dayjs from 'dayjs';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { BUDGET_ALERT_AT, DAILY_TOKEN_LIMIT, getUsage, type UsageGroup } from '@/api/ai';
import { Card, EmptyState, KpiTile, Loading, PageHeader, ProblemAlert } from '@/components/ui';

const GROUPS: readonly UsageGroup[] = ['task', 'model', 'day'];
const nf = (n: number) => n.toLocaleString('en-US');
const usd = (n: number, digits = 4) => `$${n.toFixed(digits)}`;

/** AI usage and cost (US-052 area, AI10, NFR-C2): tokens and cost by task, model or day, and the daily budget at 80 %. */
export function AiUsagePage() {
  const { t } = useTranslation();
  const today = dayjs().format('YYYY-MM-DD');
  const [from, setFrom] = useState(() => dayjs().subtract(30, 'day').format('YYYY-MM-DD'));
  const [to, setTo] = useState(today);
  const [groupBy, setGroupBy] = useState<UsageGroup>('task');

  const usage = useQuery({
    queryKey: ['ai', 'usage', from, to, groupBy],
    queryFn: () => getUsage({ from, to, groupBy }),
  });
  const todayRow = useQuery({
    queryKey: ['ai', 'usage', 'today', today],
    queryFn: () => getUsage({ from: today, to: today, groupBy: 'day' }),
    refetchInterval: 60_000,
  });

  const rows = usage.data ?? [];
  const total = rows.reduce(
    (a, r) => ({
      requests: a.requests + r.requests,
      cost: a.cost + r.costUsd,
      fallbacks: a.fallbacks + r.fallbackRate * r.requests,
    }),
    { requests: 0, cost: 0, fallbacks: 0 },
  );
  const used = (todayRow.data ?? []).reduce((a, r) => a + r.inputTokens + r.outputTokens, 0);
  const ratio = used / DAILY_TOKEN_LIMIT;
  const alert = ratio >= BUDGET_ALERT_AT;

  return (
    <div className="shp-page">
      <PageHeader title={t('usage.title')} sub={t('usage.sub')} />
      <ProblemAlert error={usage.error ?? todayRow.error} />

      <Card title={t('usage.budget')}>
        {todayRow.isLoading ? (
          <Loading />
        ) : (
          <>
            {alert && (
              <Alert
                color="red"
                styles={{ label: { color: '#8a1c1c' } }}
                icon={<IconAlertTriangle size={18} />}
                title={t('usage.budgetAlert', { percent: Math.round(BUDGET_ALERT_AT * 100) })}
                mb="sm"
              >
                {t('usage.budgetAlertHint', { percent: Math.round(BUDGET_ALERT_AT * 100) })}
              </Alert>
            )}
            <Progress
              value={Math.min(ratio * 100, 100)}
              color={alert ? 'orange' : 'teal'}
              size="lg"
              aria-label={t('usage.budget')}
            />
            <Text size="sm" mt="xs">
              {t('usage.budgetUsed', {
                used: nf(used),
                limit: nf(DAILY_TOKEN_LIMIT),
                percent: (ratio * 100).toFixed(1),
              })}
            </Text>
            <Text size="xs" c="dimmed">
              {t('usage.budgetNote')}
            </Text>
          </>
        )}
      </Card>

      <div className="shp-kpis" style={{ marginTop: 16 }}>
        <KpiTile value={nf(total.requests)} label={t('usage.requests')} />
        <KpiTile value={usd(total.cost, 2)} label={t('usage.cost')} />
        <KpiTile value={total.requests ? usd(total.cost / total.requests) : '-'} label={t('usage.avgCost')} />
        <KpiTile
          value={total.requests ? `${((total.fallbacks / total.requests) * 100).toFixed(1)}%` : '-'}
          label={t('usage.fallbackRate')}
        />
      </div>

      <Card
        title={t('usage.table')}
        flush
        actions={
          <SegmentedControl
            size="xs"
            aria-label={t('usage.groupBy')}
            value={groupBy}
            onChange={(v) => setGroupBy(v as UsageGroup)}
            data={GROUPS.map((g) => ({ value: g, label: t(`usage.by.${g}`) }))}
          />
        }
      >
        <div style={{ display: 'flex', gap: 12, padding: 12, flexWrap: 'wrap' }}>
          <TextInput
            type="date"
            size="xs"
            label={t('usage.from')}
            value={from}
            max={to}
            onChange={(e) => setFrom(e.currentTarget.value)}
          />
          <TextInput
            type="date"
            size="xs"
            label={t('usage.to')}
            value={to}
            min={from}
            onChange={(e) => setTo(e.currentTarget.value)}
          />
        </div>
        {usage.isLoading ? (
          <Loading />
        ) : rows.length === 0 ? (
          <EmptyState title={t('common.noData')} />
        ) : (
          <table className="shp-tbl">
            <thead>
              <tr>
                <th>{t(`usage.by.${groupBy}`)}</th>
                <th>{t('usage.requests')}</th>
                <th>{t('usage.inputTokens')}</th>
                <th>{t('usage.outputTokens')}</th>
                <th>{t('usage.cost')}</th>
                <th>{t('usage.fallbackRate')}</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.key}>
                  <td className="strong">{r.key}</td>
                  <td>{nf(r.requests)}</td>
                  <td>{nf(r.inputTokens)}</td>
                  <td>{nf(r.outputTokens)}</td>
                  <td>{usd(r.costUsd)}</td>
                  <td>{(r.fallbackRate * 100).toFixed(1)}%</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>
    </div>
  );
}
