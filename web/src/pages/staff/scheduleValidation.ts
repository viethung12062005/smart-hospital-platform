import type { ScheduleTemplateInput } from '@/api/scheduling';
import { SPECIALTIES } from '@/api/terminology';

export const ALLOWED_SLOT_MINUTES = [10, 15, 20, 30] as const;
export const WEEKDAYS = [1, 2, 3, 4, 5, 6, 7] as const;
export const MAX_COMMENT_LENGTH = 500;

export interface FieldError {
  readonly key: string;
  readonly params?: Record<string, string | number>;
}

export interface ScheduleFormDraft {
  readonly practitionerId: string | null;
  readonly specialty: string | null;
  readonly comment: string;
}

export interface ScheduleFormErrors {
  practitionerId?: FieldError;
  specialty?: FieldError;
  comment?: FieldError;
}

/** Validate S1/S4 Schedule form inputs and return per-field error descriptors. */
export function validateScheduleForm(draft: ScheduleFormDraft, mode: 'create' | 'edit'): ScheduleFormErrors {
  const errors: ScheduleFormErrors = {};
  if (mode === 'create') {
    if (!draft.practitionerId?.trim()) {
      errors.practitionerId = { key: 'schedule.errPractitionerRequired' };
    }
    if (!draft.specialty?.trim()) {
      errors.specialty = { key: 'schedule.errSpecialtyRequired' };
    } else if (!SPECIALTIES.some((s) => s.code === draft.specialty)) {
      errors.specialty = { key: 'schedule.errSpecialtyInvalid' };
    }
  }
  if (draft.comment.trim().length > MAX_COMMENT_LENGTH) {
    errors.comment = { key: 'schedule.errCommentTooLong', params: { max: MAX_COMMENT_LENGTH } };
  }
  return errors;
}

export function hasScheduleErrors(errors: ScheduleFormErrors): boolean {
  return Boolean(errors.practitionerId || errors.specialty || errors.comment);
}

export interface TemplateRowDraft {
  readonly key: string;
  readonly weekday: string;
  readonly startTime: string;
  readonly endTime: string;
  readonly slotMinutes: string;
  readonly validFrom: string;
  readonly validTo: string;
}

export interface TemplateRowErrors {
  weekday?: FieldError;
  startTime?: FieldError;
  endTime?: FieldError;
  slotMinutes?: FieldError;
  validFrom?: FieldError;
  validTo?: FieldError;
}

const TIME_RE = /^(?:[01]\d|2[0-3]):[0-5]\d$/;

function parseMinutes(raw: string): number | null {
  const trimmed = raw.trim();
  if (!TIME_RE.test(trimmed)) return null;
  const parts = trimmed.split(':');
  return Number(parts[0]) * 60 + Number(parts[1]);
}

function isValidIsoDate(raw: string): boolean {
  const trimmed = raw.trim();
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(trimmed);
  if (!match) return false;
  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  if (month < 1 || month > 12 || day < 1 || day > 31) return false;
  const utc = new Date(Date.UTC(year, month - 1, day));
  return utc.getUTCFullYear() === year && utc.getUTCMonth() === month - 1 && utc.getUTCDate() === day;
}

/** Validate S5 weekly template rows per ScheduleService rules (HH:mm, slotMinutes, divisibility, dates, no same-weekday overlap). */
export function validateTemplateRows(rows: readonly TemplateRowDraft[]): Record<string, TemplateRowErrors> {
  const errors: Record<string, TemplateRowErrors> = {};
  const validIntervals: { key: string; weekday: number; start: number; end: number }[] = [];

  for (const row of rows) {
    const rowErr: TemplateRowErrors = {};
    const weekdayNum = Number(row.weekday);
    const weekdayValid = Number.isInteger(weekdayNum) && weekdayNum >= 1 && weekdayNum <= 7;
    if (!weekdayValid) {
      rowErr.weekday = { key: 'schedule.errWeekday' };
    }

    const slotNum = Number(row.slotMinutes);
    const slotValid = ALLOWED_SLOT_MINUTES.includes(slotNum as (typeof ALLOWED_SLOT_MINUTES)[number]);
    if (!slotValid) {
      rowErr.slotMinutes = { key: 'schedule.errSlotMinutes' };
    }

    const startMin = parseMinutes(row.startTime);
    if (startMin === null) {
      rowErr.startTime = { key: 'schedule.errTimeFormat' };
    }

    const endMin = parseMinutes(row.endTime);
    if (endMin === null) {
      rowErr.endTime = { key: 'schedule.errTimeFormat' };
    }

    if (startMin !== null && endMin !== null) {
      if (startMin >= endMin) {
        rowErr.endTime = { key: 'schedule.errEndAfterStart' };
      } else if (slotValid && (endMin - startMin) % slotNum !== 0) {
        rowErr.endTime = { key: 'schedule.errNotDivisible', params: { minutes: slotNum } };
      } else if (weekdayValid) {
        validIntervals.push({ key: row.key, weekday: weekdayNum, start: startMin, end: endMin });
      }
    }

    const from = row.validFrom.trim();
    const fromValid = isValidIsoDate(from);
    if (!from) {
      rowErr.validFrom = { key: 'schedule.errValidFromRequired' };
    } else if (!fromValid) {
      rowErr.validFrom = { key: 'schedule.errDateInvalid' };
    }

    const to = row.validTo.trim();
    if (to) {
      if (!isValidIsoDate(to)) {
        rowErr.validTo = { key: 'schedule.errDateInvalid' };
      } else if (fromValid && to < from) {
        rowErr.validTo = { key: 'schedule.errValidToBeforeFrom' };
      }
    }

    errors[row.key] = rowErr;
  }

  for (let i = 0; i < validIntervals.length; i++) {
    const a = validIntervals[i];
    if (!a) continue;
    for (let j = i + 1; j < validIntervals.length; j++) {
      const b = validIntervals[j];
      if (!b) continue;
      if (a.weekday === b.weekday && a.start < b.end && b.start < a.end) {
        markOverlap(errors[a.key]);
        markOverlap(errors[b.key]);
      }
    }
  }

  return errors;
}

function markOverlap(target: TemplateRowErrors | undefined): void {
  if (!target) return;
  if (!target.startTime) {
    target.startTime = { key: 'schedule.errOverlap' };
  } else if (!target.endTime) {
    target.endTime = { key: 'schedule.errOverlap' };
  }
}

export function hasTemplateErrors(errorsByKey: Record<string, TemplateRowErrors>): boolean {
  return Object.values(errorsByKey).some((e) =>
    Boolean(e.weekday || e.startTime || e.endTime || e.slotMinutes || e.validFrom || e.validTo),
  );
}

/** Return the number of slots a shift produces when startTime, endTime and slotMinutes are valid and divisible. */
export function slotCountPreview(startTime: string, endTime: string, slotMinutes: string): number | null {
  const startMin = parseMinutes(startTime);
  const endMin = parseMinutes(endTime);
  const step = Number(slotMinutes);
  if (
    startMin === null ||
    endMin === null ||
    !ALLOWED_SLOT_MINUTES.includes(step as (typeof ALLOWED_SLOT_MINUTES)[number])
  ) {
    return null;
  }
  const diff = endMin - startMin;
  if (diff <= 0 || diff % step !== 0) return null;
  return diff / step;
}

export function toTemplateInputs(rows: readonly TemplateRowDraft[]): ScheduleTemplateInput[] {
  return rows.map((r) => ({
    weekday: Number(r.weekday),
    startTime: r.startTime.trim(),
    endTime: r.endTime.trim(),
    slotMinutes: Number(r.slotMinutes),
    validFrom: r.validFrom.trim(),
    validTo: r.validTo.trim() ? r.validTo.trim() : null,
  }));
}
