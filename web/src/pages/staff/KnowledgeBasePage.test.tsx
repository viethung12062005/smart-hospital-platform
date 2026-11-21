import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { server } from '@/mocks/server';
import { beforeEach, describe, expect, it } from 'vitest';
import { resetMockDb } from '@/mocks/handlers';
import { renderApp } from '@/test/render';

beforeEach(() => resetMockDb());

const fileInput = (root: HTMLElement) => root.querySelector<HTMLInputElement>('input[type="file"]')!;
const row = async (title: string) => (await screen.findByText(title)).closest('tr')!;

describe('knowledge base (administrator)', () => {
  it('lists the documents with their lifecycle status and an indexing error', async () => {
    renderApp('/knowledge', 'admin');

    const indexed = await row('Phác đồ tăng huyết áp 2026');
    expect(within(indexed).getByText('Đã lập chỉ mục')).toBeInTheDocument();
    expect(within(indexed).getByText('42')).toBeInTheDocument();
    expect(within(await row('Quy trình vệ sinh tay')).getByText('Bản nháp')).toBeInTheDocument();
    expect(within(await row('Quy trình cũ về truyền máu')).getByText('Đã gỡ')).toBeInTheDocument();
    expect(
      within(await row('Phác đồ đái tháo đường típ 2')).getByText(/Lập chỉ mục lỗi: EmbeddingUnavailable/),
    ).toBeInTheDocument();
  });

  it('uploads a document, which is created as a draft', async () => {
    const user = userEvent.setup();
    renderApp('/knowledge', 'admin');
    await user.click(await screen.findByRole('button', { name: 'Tải tài liệu lên' }));
    const dialog = await screen.findByRole('dialog');

    await user.type(within(dialog).getByLabelText(/Tiêu đề/), 'Quy trình tiếp nhận cấp cứu');
    await user.type(within(dialog).getByLabelText(/Nguồn/), 'Quy chế bệnh viện');
    await user.upload(fileInput(dialog), new File(['# Quy trinh'], 'quy-trinh.md', { type: 'text/markdown' }));
    await user.click(within(dialog).getByRole('button', { name: 'Tải tài liệu lên' }));

    expect(await screen.findByText('Đã tải lên "Quy trình tiếp nhận cấp cứu" (bản nháp)')).toBeInTheDocument();
    expect(within(await row('Quy trình tiếp nhận cấp cứu')).getByText('Bản nháp')).toBeInTheDocument();
  });

  it('refuses an unsupported file type before uploading', async () => {
    const user = userEvent.setup({ applyAccept: false });
    renderApp('/knowledge', 'admin');
    await user.click(await screen.findByRole('button', { name: 'Tải tài liệu lên' }));
    const dialog = await screen.findByRole('dialog');

    await user.type(within(dialog).getByLabelText(/Tiêu đề/), 'Tài liệu thử');
    await user.type(within(dialog).getByLabelText(/Nguồn/), 'Nguồn thử');
    await user.upload(fileInput(dialog), new File(['x'], 'macro.exe', { type: 'application/octet-stream' }));

    expect(await within(dialog).findByText('Chỉ nhận tệp PDF, DOCX hoặc Markdown (.md).')).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: 'Tải tài liệu lên' })).toBeDisabled();
  });

  it('shows the service error inside the upload dialog', async () => {
    server.use(
      http.post('/api/v1/ai/kb/documents', () =>
        HttpResponse.json({ code: 'file-too-large', detail: 'Tệp quá lớn.' }, { status: 413 }),
      ),
    );
    const user = userEvent.setup();
    renderApp('/knowledge', 'admin');
    await user.click(await screen.findByRole('button', { name: 'Tải tài liệu lên' }));
    const dialog = await screen.findByRole('dialog');
    await user.type(within(dialog).getByLabelText(/Tiêu đề/), 'Tài liệu lớn');
    await user.type(within(dialog).getByLabelText(/Nguồn/), 'Nguồn thử');
    await user.upload(fileInput(dialog), new File(['x'], 'lon.pdf', { type: 'application/pdf' }));
    await user.click(within(dialog).getByRole('button', { name: 'Tải tài liệu lên' }));

    expect(await within(dialog).findByText('Tệp quá lớn.')).toBeInTheDocument();
  });

  it('approves a draft, which is then indexed', async () => {
    const user = userEvent.setup();
    renderApp('/knowledge', 'admin');
    await user.click(await screen.findByRole('button', { name: 'Duyệt tài liệu Quy trình vệ sinh tay' }));

    expect(await screen.findByText('Đã duyệt "Quy trình vệ sinh tay", đang lập chỉ mục')).toBeInTheDocument();
    await waitFor(async () =>
      expect(within(await row('Quy trình vệ sinh tay')).getByText('Đã lập chỉ mục')).toBeInTheDocument(),
    );
  });

  it('replaces the file: new version, back to draft', async () => {
    const user = userEvent.setup();
    renderApp('/knowledge', 'admin');
    await user.click(await screen.findByRole('button', { name: 'Thay tệp của Phác đồ tăng huyết áp 2026' }));
    const dialog = await screen.findByRole('dialog');
    await user.upload(fileInput(dialog), new File(['%PDF-1.7'], 'v2.pdf', { type: 'application/pdf' }));
    await user.click(within(dialog).getByRole('button', { name: 'Thay tệp' }));

    expect(await screen.findByText(/Đã thay tệp "Phác đồ tăng huyết áp 2026" \(phiên bản 2\)/)).toBeInTheDocument();
    const r = await row('Phác đồ tăng huyết áp 2026');
    expect(within(r).getByText('v2')).toBeInTheDocument();
    expect(within(r).getByText('Bản nháp')).toBeInTheDocument();
  });

  it('removes a document after confirmation; the record stays as retired', async () => {
    const user = userEvent.setup();
    renderApp('/knowledge', 'admin');
    await user.click(await screen.findByRole('button', { name: 'Gỡ tài liệu Quy trình xử trí cấp cứu tim mạch' }));
    const dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getByRole('button', { name: 'Gỡ' }));

    expect(await screen.findByText('Đã gỡ "Quy trình xử trí cấp cứu tim mạch"')).toBeInTheDocument();
    await waitFor(async () =>
      expect(within(await row('Quy trình xử trí cấp cứu tim mạch')).getByText('Đã gỡ')).toBeInTheDocument(),
    );
    expect(
      within(await row('Quy trình xử trí cấp cứu tim mạch')).queryByRole('button', { name: /Thay tệp/ }),
    ).toBeNull();
  });
});

describe('knowledge base (doctor)', () => {
  it('can approve a document of their own department but cannot upload, replace or remove', async () => {
    const user = userEvent.setup();
    renderApp('/knowledge', 'doctor');
    await row('Phác đồ suy tim 2026');
    expect(screen.queryByRole('button', { name: 'Tải tài liệu lên' })).toBeNull();
    expect(screen.queryByRole('button', { name: /Thay tệp/ })).toBeNull();
    expect(screen.queryByRole('button', { name: /Gỡ tài liệu/ })).toBeNull();
    // Documents of other departments are not listed.
    expect(screen.queryByText('Hướng dẫn tiêm chủng cho trẻ')).toBeNull();

    await user.click(screen.getByRole('button', { name: 'Duyệt tài liệu Phác đồ suy tim 2026' }));
    expect(await screen.findByText('Đã duyệt "Phác đồ suy tim 2026", đang lập chỉ mục')).toBeInTheDocument();
  });

  it('shows the service refusal when approving a hospital-wide document', async () => {
    const user = userEvent.setup();
    renderApp('/knowledge', 'doctor');
    await user.click(await screen.findByRole('button', { name: 'Duyệt tài liệu Quy trình vệ sinh tay' }));

    expect(await screen.findByText('Bạn không có quyền thực hiện thao tác này.')).toBeInTheDocument();
  });
});

describe('knowledge base access', () => {
  it('is not available to a nurse', async () => {
    renderApp('/knowledge', 'nurse');
    expect(await screen.findByText('Bạn không có quyền xem trang này.')).toBeInTheDocument();
  });
});
