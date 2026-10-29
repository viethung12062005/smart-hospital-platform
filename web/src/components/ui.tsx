import { Alert, Center, Loader, Text } from '@mantine/core';
import { IconAlertTriangle } from '@tabler/icons-react';
import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { ApiError, NetworkError } from '@/api/client';

/** Tiêu đề trang theo hệ thiết kế: tiêu đề, dòng phụ, nút thao tác bên phải. */
export function PageHeader(props: { readonly title: string; readonly sub?: string; readonly actions?: ReactNode }) {
  return (
    <div className="shp-pagehead">
      <div>
        <h1>{props.title}</h1>
        {props.sub && <div className="sub">{props.sub}</div>}
      </div>
      {props.actions && <div className="acts">{props.actions}</div>}
    </div>
  );
}

export function Card(props: {
  readonly title?: ReactNode;
  readonly count?: ReactNode;
  readonly actions?: ReactNode;
  readonly flush?: boolean;
  readonly children: ReactNode;
}) {
  return (
    <section className="shp-card">
      {(props.title || props.actions) && (
        <div className="shp-card-h">
          <h3>{props.title}</h3>
          {props.count !== undefined && <span className="cnt">{props.count}</span>}
          {props.actions && <div className="acts">{props.actions}</div>}
        </div>
      )}
      <div className={props.flush ? undefined : 'shp-card-b'}>{props.children}</div>
    </section>
  );
}

const CHIP: Record<string, string> = {
  booked: 'info',
  arrived: 'violet',
  fulfilled: 'ok',
  finished: 'ok',
  cancelled: 'muted',
  noshow: 'err',
  planned: 'info',
  'in-progress': 'warn',
  free: 'teal',
  busy: 'info',
  'busy-unavailable': 'muted',
};

/** Chip trạng thái FHIR (Appointment, Encounter, Slot) với nhãn dịch vi/en. */
export function StatusChip({ status }: { readonly status: string | undefined }) {
  const { t } = useTranslation();
  if (!status) return null;
  return (
    <span className={`shp-chip shp-chip--${CHIP[status] ?? 'muted'}`}>
      <span className="dot" />
      {t(`status.${status}`, { defaultValue: status })}
    </span>
  );
}

export function KpiTile(props: {
  readonly value: ReactNode;
  readonly label: string;
  readonly active?: boolean;
  readonly onClick?: () => void;
}) {
  const content = (
    <>
      <div className="n">{props.value}</div>
      <div className="l">{props.label}</div>
      <div className="bar" />
    </>
  );
  return props.onClick ? (
    <button
      type="button"
      className={`shp-kpi ${props.active ? 'is-on' : ''}`}
      onClick={props.onClick}
      aria-pressed={props.active}
    >
      {content}
    </button>
  ) : (
    <div className="shp-kpi">{content}</div>
  );
}

export function EmptyState(props: { readonly title: string; readonly children?: ReactNode }) {
  return (
    <div className="shp-empty">
      <div className="big">{props.title}</div>
      {props.children}
    </div>
  );
}

export function Loading() {
  const { t } = useTranslation();
  return (
    <Center p="xl" aria-busy="true">
      <Loader size="sm" />
      <Text ml="sm" c="dimmed" size="sm">
        {t('common.loading')}
      </Text>
    </Center>
  );
}

/** Hiển thị lỗi API: thông điệp máy chủ (đã đúng ngôn ngữ) và mã truy vết để báo lỗi. */
export function ProblemAlert({ error }: { readonly error: unknown }) {
  const { t } = useTranslation();
  if (!error) return null;
  const message =
    error instanceof ApiError ? error.message : error instanceof NetworkError ? t('error.network') : t('error.title');
  return (
    <Alert color="red" icon={<IconAlertTriangle size={18} />} title={t('error.title')} mb="md">
      {message}
      {error instanceof ApiError && error.traceId && (
        <Text size="xs" c="dimmed" mt={4}>
          {t('common.errorTrace', { id: error.traceId })}
        </Text>
      )}
    </Alert>
  );
}
