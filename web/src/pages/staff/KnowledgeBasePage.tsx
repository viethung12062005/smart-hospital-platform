import { Alert, Button, Checkbox, FileInput, Group, Modal, Select, Stack, Text, TextInput } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconFileUpload, IconUpload } from '@tabler/icons-react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import dayjs from 'dayjs';
import { useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import {
  approveKbDocument,
  KB_ACCEPT,
  KB_MAX_BYTES,
  listKbDocuments,
  replaceKbFile,
  retireKbDocument,
  uploadKbDocument,
  type KbDocument,
  type KbStatus,
} from '@/api/ai';
import { ApiError } from '@/api/client';
import { SPECIALTIES, specialtyName } from '@/api/terminology';
import { useAuth } from '@/auth/useAuth';
import { Card, EmptyState, KpiTile, Loading, PageHeader, ProblemAlert } from '@/components/ui';

const STATUSES: readonly KbStatus[] = ['draft', 'approved', 'indexed', 'retired'];
const CHIP: Record<KbStatus, string> = { draft: 'info', approved: 'violet', indexed: 'ok', retired: 'muted' };
const HOSPITAL_WIDE = 'hospital-wide';
const FILE_PATTERN = /\.(pdf|docx|md)$/i;

function KbStatusChip({ status }: { readonly status: KbStatus }) {
  const { t } = useTranslation();
  return (
    <span className={`shp-chip shp-chip--${CHIP[status]}`}>
      <span className="dot" />
      {t(`kb.status.${status}`)}
    </span>
  );
}

function departmentName(code: string, hospitalWide: string): string {
  return code === HOSPITAL_WIDE ? hospitalWide : specialtyName(code);
}

/** Why a chosen file is refused before upload (the service re-checks the type from the file content). */
function fileProblem(file: File | null, t: (k: string) => string): string | undefined {
  if (!file) return undefined;
  if (!FILE_PATTERN.test(file.name)) return t('kb.fileType');
  if (file.size > KB_MAX_BYTES) return t('kb.fileTooLarge');
  if (file.size === 0) return t('kb.fileEmpty');
  return undefined;
}

/**
 * Knowledge base management (US-052, AI9). Admins upload, replace and remove documents; admins and doctors (for their own
 * department, enforced by the service) approve a draft, which then goes to indexing.
 */
export function KnowledgeBasePage() {
  const { t } = useTranslation();
  const { user } = useAuth();
  const queryClient = useQueryClient();
  const isAdmin = !!user?.roles.includes('admin');
  const [filter, setFilter] = useState<KbStatus | undefined>();
  const [uploading, setUploading] = useState(false);
  const [replacing, setReplacing] = useState<KbDocument | undefined>();
  const [removing, setRemoving] = useState<KbDocument | undefined>();

  const docs = useQuery({
    queryKey: ['ai', 'kb'],
    queryFn: () => listKbDocuments(),
    // Indexing runs in the background after approval: keep refreshing until it ends.
    refetchInterval: (q) => (q.state.data?.some((d) => d.status === 'approved' && !d.indexError) ? 4000 : false),
  });
  const refresh = () => queryClient.invalidateQueries({ queryKey: ['ai', 'kb'] });
  const fail = (e: unknown) =>
    notifications.show({ color: 'red', message: e instanceof ApiError ? e.message : t('error.title') });

  const approve = useMutation({
    mutationFn: (d: KbDocument) => approveKbDocument(d.id),
    onSuccess: (d) => {
      notifications.show({ color: 'teal', message: t('kb.approved', { title: d.title }) });
      void refresh();
    },
    onError: fail,
  });
  const retire = useMutation({
    mutationFn: (d: KbDocument) => retireKbDocument(d.id),
    onSuccess: (d) => {
      notifications.show({ color: 'teal', message: t('kb.removed', { title: d.title }) });
      setRemoving(undefined);
      void refresh();
    },
    onError: (e) => {
      setRemoving(undefined);
      fail(e);
    },
  });

  const all = docs.data ?? [];
  const rows = all.filter((d) => !filter || d.status === filter);

  return (
    <div className="shp-page">
      <PageHeader
        title={t('kb.title')}
        sub={t('kb.sub')}
        actions={
          isAdmin && (
            <Button leftSection={<IconUpload size={16} />} onClick={() => setUploading(true)}>
              {t('kb.upload')}
            </Button>
          )
        }
      />
      <div className="shp-kpis">
        <KpiTile value={all.length} label={t('common.all')} active={!filter} onClick={() => setFilter(undefined)} />
        {STATUSES.map((s) => (
          <KpiTile
            key={s}
            value={all.filter((d) => d.status === s).length}
            label={t(`kb.status.${s}`)}
            active={filter === s}
            onClick={() => setFilter(filter === s ? undefined : s)}
          />
        ))}
      </div>
      <ProblemAlert error={docs.error} />
      <Card title={t('kb.documents')} count={rows.length} flush>
        {docs.isLoading ? (
          <Loading />
        ) : rows.length === 0 ? (
          <EmptyState title={t('kb.empty')} />
        ) : (
          <table className="shp-tbl">
            <thead>
              <tr>
                <th>{t('kb.docTitle')}</th>
                <th>{t('kb.department')}</th>
                <th>{t('kb.version')}</th>
                <th>{t('appt.status')}</th>
                <th>{t('kb.chunks')}</th>
                <th>{t('kb.updated')}</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {rows.map((d) => (
                <tr key={d.id}>
                  <td>
                    <div className="strong">{d.title}</div>
                    <Text size="xs" c="dimmed">
                      {d.source}
                    </Text>
                    {d.indexError && (
                      <Text size="xs" c="red.8" role="alert">
                        {t('kb.indexError', { error: d.indexError })}
                      </Text>
                    )}
                  </td>
                  <td>{departmentName(d.department, t('kb.hospitalWide'))}</td>
                  <td>v{d.version}</td>
                  <td>
                    <KbStatusChip status={d.status} />
                  </td>
                  <td>{d.status === 'indexed' ? (d.chunkCount ?? '-') : '-'}</td>
                  <td>{d.updatedAt ? dayjs(d.updatedAt).format('DD/MM/YYYY HH:mm') : '-'}</td>
                  <td className="tight">
                    <Group gap={4} wrap="nowrap">
                      {d.status === 'draft' && (
                        <Button
                          size="xs"
                          loading={approve.isPending && approve.variables?.id === d.id}
                          aria-label={t('kb.approveDoc', { title: d.title })}
                          onClick={() => approve.mutate(d)}
                        >
                          {t('kb.approve')}
                        </Button>
                      )}
                      {isAdmin && d.status !== 'retired' && (
                        <>
                          <Button
                            size="xs"
                            variant="default"
                            aria-label={t('kb.replaceDoc', { title: d.title })}
                            onClick={() => setReplacing(d)}
                          >
                            {t('kb.replace')}
                          </Button>
                          <Button
                            size="xs"
                            variant="default"
                            color="red"
                            aria-label={t('kb.removeDoc', { title: d.title })}
                            onClick={() => setRemoving(d)}
                          >
                            {t('kb.remove')}
                          </Button>
                        </>
                      )}
                    </Group>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>

      <UploadModal
        opened={uploading}
        onClose={() => setUploading(false)}
        onDone={(d) => {
          notifications.show({ color: 'teal', message: t('kb.uploaded', { title: d.title }) });
          setUploading(false);
          void refresh();
        }}
      />
      <ReplaceModal
        doc={replacing}
        onClose={() => setReplacing(undefined)}
        onDone={(d) => {
          notifications.show({ color: 'teal', message: t('kb.replaced', { title: d.title, version: d.version }) });
          setReplacing(undefined);
          void refresh();
        }}
      />
      <Modal opened={!!removing} onClose={() => setRemoving(undefined)} title={t('kb.removeTitle')}>
        <Text size="sm" mb="md">
          {t('kb.removeConfirm', { title: removing?.title })}
        </Text>
        <Group justify="flex-end">
          <Button variant="default" onClick={() => setRemoving(undefined)}>
            {t('common.cancel')}
          </Button>
          <Button color="red" loading={retire.isPending} onClick={() => removing && retire.mutate(removing)}>
            {t('kb.remove')}
          </Button>
        </Group>
      </Modal>
    </div>
  );
}

function UploadModal(props: {
  readonly opened: boolean;
  readonly onClose: () => void;
  readonly onDone: (d: KbDocument) => void;
}) {
  const { t } = useTranslation();
  const [title, setTitle] = useState('');
  const [source, setSource] = useState('');
  const [department, setDepartment] = useState<string | null>(HOSPITAL_WIDE);
  const [roles, setRoles] = useState<string[]>(['doctor', 'nurse']);
  const [file, setFile] = useState<File | null>(null);

  const upload = useMutation({
    mutationFn: uploadKbDocument,
    onSuccess: (d) => {
      setTitle('');
      setSource('');
      setFile(null);
      props.onDone(d);
    },
  });
  const problem = fileProblem(file, t);
  const valid =
    title.trim().length >= 3 && source.trim().length >= 3 && !!department && roles.length > 0 && !!file && !problem;

  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (!valid || !file || !department) return;
    upload.mutate({ title: title.trim(), source: source.trim(), department, allowedRoles: roles, file });
  };

  return (
    <Modal opened={props.opened} onClose={props.onClose} title={t('kb.upload')} size="lg">
      <form onSubmit={submit}>
        <Stack gap="sm">
          <ProblemAlert error={upload.error} />
          <TextInput
            label={t('kb.docTitle')}
            required
            minLength={3}
            maxLength={200}
            value={title}
            onChange={(e) => setTitle(e.currentTarget.value)}
          />
          <TextInput
            label={t('kb.source')}
            required
            minLength={3}
            maxLength={300}
            value={source}
            onChange={(e) => setSource(e.currentTarget.value)}
          />
          <Select
            label={t('kb.department')}
            required
            allowDeselect={false}
            data={[
              { value: HOSPITAL_WIDE, label: t('kb.hospitalWide') },
              ...SPECIALTIES.map((s) => ({ value: s.code, label: s.display })),
            ]}
            value={department}
            onChange={setDepartment}
          />
          <Checkbox.Group label={t('kb.allowedRoles')} required value={roles} onChange={setRoles}>
            <Group mt={4}>
              <Checkbox value="doctor" label={t('role.doctor')} />
              <Checkbox value="nurse" label={t('role.nurse')} />
            </Group>
          </Checkbox.Group>
          <FileInput
            label={t('kb.file')}
            description={t('kb.fileHint')}
            required
            accept={KB_ACCEPT}
            leftSection={<IconFileUpload size={16} />}
            clearable
            value={file}
            onChange={setFile}
            error={problem}
          />
          <Group justify="flex-end">
            <Button variant="default" onClick={props.onClose}>
              {t('common.cancel')}
            </Button>
            <Button type="submit" disabled={!valid} loading={upload.isPending}>
              {t('kb.upload')}
            </Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  );
}

function ReplaceModal(props: {
  readonly doc: KbDocument | undefined;
  readonly onClose: () => void;
  readonly onDone: (d: KbDocument) => void;
}) {
  const { t } = useTranslation();
  const [file, setFile] = useState<File | null>(null);
  const replace = useMutation({
    mutationFn: (f: File) => replaceKbFile(props.doc!.id, f),
    onSuccess: (d) => {
      setFile(null);
      props.onDone(d);
    },
  });
  const problem = fileProblem(file, t);
  return (
    <Modal opened={!!props.doc} onClose={props.onClose} title={t('kb.replaceTitle')} size="lg">
      <Stack gap="sm">
        <ProblemAlert error={replace.error} />
        <Alert color="blue" variant="light">
          {t('kb.replaceHint', { title: props.doc?.title })}
        </Alert>
        <FileInput
          label={t('kb.file')}
          description={t('kb.fileHint')}
          accept={KB_ACCEPT}
          leftSection={<IconFileUpload size={16} />}
          clearable
          value={file}
          onChange={setFile}
          error={problem}
        />
        <Group justify="flex-end">
          <Button variant="default" onClick={props.onClose}>
            {t('common.cancel')}
          </Button>
          <Button
            disabled={!file || !!problem}
            loading={replace.isPending}
            onClick={() => file && replace.mutate(file)}
          >
            {t('kb.replace')}
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}
