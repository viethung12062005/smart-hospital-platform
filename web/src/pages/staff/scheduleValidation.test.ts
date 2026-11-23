import { describe, expect, it } from 'vitest';
import {
  hasScheduleErrors,
  hasTemplateErrors,
  MAX_COMMENT_LENGTH,
  slotCountPreview,
  toTemplateInputs,
  validateScheduleForm,
  validateTemplateRows,
  type TemplateRowDraft,
} from './scheduleValidation';

function makeRow(overrides: Partial<TemplateRowDraft> = {}): TemplateRowDraft {
  return {
    key: 'r1',
    weekday: '1',
    startTime: '08:00',
    endTime: '11:30',
    slotMinutes: '15',
    validFrom: '2026-11-04',
    validTo: '',
    ...overrides,
  };
}

describe('validateScheduleForm', () => {
  it('requires practitioner and valid specialty when creating', () => {
    const errors = validateScheduleForm({ practitionerId: null, specialty: null, comment: '' }, 'create');
    expect(hasScheduleErrors(errors)).toBe(true);
    expect(errors.practitionerId?.key).toBe('schedule.errPractitionerRequired');
    expect(errors.specialty?.key).toBe('schedule.errSpecialtyRequired');
    expect(errors.comment).toBeUndefined();
  });

  it('rejects unknown specialty code and over-long comments', () => {
    const longComment = 'x'.repeat(MAX_COMMENT_LENGTH + 1);
    const errors = validateScheduleForm(
      { practitionerId: 'pr-012', specialty: 'unknown-code', comment: longComment },
      'create',
    );
    expect(errors.practitionerId).toBeUndefined();
    expect(errors.specialty?.key).toBe('schedule.errSpecialtyInvalid');
    expect(errors.comment).toEqual({ key: 'schedule.errCommentTooLong', params: { max: MAX_COMMENT_LENGTH } });
  });

  it('skips practitioner and specialty validation in edit mode and passes valid drafts', () => {
    const editOk = validateScheduleForm({ practitionerId: null, specialty: null, comment: 'Phòng 204' }, 'edit');
    expect(hasScheduleErrors(editOk)).toBe(false);

    const createOk = validateScheduleForm(
      { practitionerId: 'pr-012', specialty: 'gastroenterology', comment: 'Phòng 305' },
      'create',
    );
    expect(hasScheduleErrors(createOk)).toBe(false);
  });
});

describe('validateTemplateRows', () => {
  it('accepts divisible shift 08:00-11:30 by 15 min (14 slots) and adjacent non-overlapping shifts', () => {
    const rows = [
      makeRow({ key: 'r1', weekday: '1', startTime: '08:00', endTime: '11:30', slotMinutes: '15' }),
      makeRow({ key: 'r2', weekday: '1', startTime: '11:30', endTime: '13:30', slotMinutes: '30' }),
      makeRow({
        key: 'r3',
        weekday: '3',
        startTime: '08:00',
        endTime: '11:30',
        slotMinutes: '15',
        validTo: '2026-12-31',
      }),
    ];
    const errors = validateTemplateRows(rows);
    expect(hasTemplateErrors(errors)).toBe(false);
    expect(slotCountPreview('08:00', '11:30', '15')).toBe(14);
    expect(toTemplateInputs(rows)).toEqual([
      {
        weekday: 1,
        startTime: '08:00',
        endTime: '11:30',
        slotMinutes: 15,
        validFrom: '2026-11-04',
        validTo: null,
      },
      {
        weekday: 1,
        startTime: '11:30',
        endTime: '13:30',
        slotMinutes: 30,
        validFrom: '2026-11-04',
        validTo: null,
      },
      {
        weekday: 3,
        startTime: '08:00',
        endTime: '11:30',
        slotMinutes: 15,
        validFrom: '2026-11-04',
        validTo: '2026-12-31',
      },
    ]);
  });

  it('rejects uneven range division, end <= start, bad time/slot/weekday', () => {
    const uneven = validateTemplateRows([makeRow({ startTime: '08:00', endTime: '11:20', slotMinutes: '15' })]);
    expect(uneven['r1']?.endTime).toEqual({ key: 'schedule.errNotDivisible', params: { minutes: 15 } });
    expect(slotCountPreview('08:00', '11:20', '15')).toBeNull();

    const reversed = validateTemplateRows([makeRow({ startTime: '11:30', endTime: '08:00' })]);
    expect(reversed['r1']?.endTime?.key).toBe('schedule.errEndAfterStart');

    const invalidPrimitives = validateTemplateRows([
      makeRow({ weekday: '9', startTime: '8am', endTime: '25:00', slotMinutes: '12' }),
    ]);
    expect(invalidPrimitives['r1']?.weekday?.key).toBe('schedule.errWeekday');
    expect(invalidPrimitives['r1']?.startTime?.key).toBe('schedule.errTimeFormat');
    expect(invalidPrimitives['r1']?.endTime?.key).toBe('schedule.errTimeFormat');
    expect(invalidPrimitives['r1']?.slotMinutes?.key).toBe('schedule.errSlotMinutes');
  });

  it('rejects missing/invalid dates and validTo < validFrom', () => {
    const emptyFrom = validateTemplateRows([makeRow({ validFrom: '', validTo: '2026-11-10' })]);
    expect(emptyFrom['r1']?.validFrom?.key).toBe('schedule.errValidFromRequired');

    const badCalendar = validateTemplateRows([makeRow({ validFrom: '2026-02-30', validTo: '2026-13-01' })]);
    expect(badCalendar['r1']?.validFrom?.key).toBe('schedule.errDateInvalid');
    expect(badCalendar['r1']?.validTo?.key).toBe('schedule.errDateInvalid');

    const beforeFrom = validateTemplateRows([makeRow({ validFrom: '2026-11-10', validTo: '2026-11-05' })]);
    expect(beforeFrom['r1']?.validTo?.key).toBe('schedule.errValidToBeforeFrom');
  });

  it('marks overlapping rows on the same weekday with overlap errors', () => {
    const rows = [
      makeRow({ key: 'r1', weekday: '2', startTime: '08:00', endTime: '11:30', slotMinutes: '15' }),
      makeRow({ key: 'r2', weekday: '2', startTime: '10:00', endTime: '12:00', slotMinutes: '15' }),
    ];
    const errors = validateTemplateRows(rows);
    expect(hasTemplateErrors(errors)).toBe(true);
    expect(errors['r1']?.startTime?.key).toBe('schedule.errOverlap');
    expect(errors['r2']?.startTime?.key).toBe('schedule.errOverlap');
  });
});
