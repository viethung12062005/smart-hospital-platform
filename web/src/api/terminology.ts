import { load } from 'js-yaml';
// Một nguồn duy nhất với backend: đọc thẳng file hợp đồng, không chép tay danh mục mã.
import raw from '../../../contracts/terminology/code-systems.yaml?raw';

interface CodeSystemFile {
  codeSystems: Record<string, { url: string; concepts: Record<string, string> }>;
}

const file = load(raw) as CodeSystemFile;

export interface Concept {
  readonly code: string;
  readonly display: string;
}

function concepts(system: string): Concept[] {
  const cs = file.codeSystems[system];
  if (!cs) throw new Error(`Thiếu code system ${system} trong contracts/terminology/code-systems.yaml`);
  return Object.entries(cs.concepts).map(([code, display]) => ({ code: String(code), display }));
}

export function systemUrl(system: string): string {
  return file.codeSystems[system]?.url ?? '';
}

export const SPECIALTIES = concepts('specialty');
export const CANCEL_REASONS = concepts('cancel-reason');

export function specialtyName(code: string | undefined): string {
  return SPECIALTIES.find((s) => s.code === code)?.display ?? code ?? '';
}
