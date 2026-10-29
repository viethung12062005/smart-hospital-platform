import type { CodeableConcept, HumanName, Reference } from '@medplum/fhirtypes';
import dayjs from 'dayjs';

/** "Nguyễn Thị Lan": tên hiển thị theo thứ tự Việt Nam (họ trước), có tiền tố (BS., ĐD.) nếu có. */
export function formatName(names: HumanName[] | undefined): string {
  const n = names?.[0];
  if (!n) return '';
  if (n.text) return n.text;
  return [...(n.prefix ?? []), n.family, ...(n.given ?? [])].filter(Boolean).join(' ');
}

export function refId(ref: Reference | string | undefined): string | undefined {
  const r = typeof ref === 'string' ? ref : ref?.reference;
  return r?.split('/')[1];
}

export function conceptText(c: CodeableConcept | undefined): string {
  return c?.text ?? c?.coding?.[0]?.display ?? c?.coding?.[0]?.code ?? '';
}

export function ageFrom(birthDate: string | undefined): number | undefined {
  return birthDate ? dayjs().diff(dayjs(birthDate), 'year') : undefined;
}

export function formatDateTime(value: string | undefined): string {
  return value ? dayjs(value).format('HH:mm · DD/MM/YYYY') : '';
}

export function formatDate(value: string | undefined): string {
  return value ? dayjs(value).format('DD/MM/YYYY') : '';
}

export function formatTime(value: string | undefined): string {
  return value ? dayjs(value).format('HH:mm') : '';
}
