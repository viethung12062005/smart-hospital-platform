import dayjs from 'dayjs';
import timezone from 'dayjs/plugin/timezone';
import utc from 'dayjs/plugin/utc';

dayjs.extend(utc);
dayjs.extend(timezone);

/** Clinic times are shown in Vietnam time, whatever the browser's own zone is. */
export const VN_TIMEZONE = 'Asia/Ho_Chi_Minh';

export function vnTime(value: string | undefined): string {
  return value ? dayjs(value).tz(VN_TIMEZONE).format('HH:mm') : '';
}

/** `YYYY-MM-DD` of the instant as seen in Vietnam. */
export function vnDay(value: string | undefined): string {
  return value ? dayjs(value).tz(VN_TIMEZONE).format('YYYY-MM-DD') : '';
}

/** Long label of a `YYYY-MM-DD` day, for a group heading. */
export function vnDayLabel(day: string): string {
  return dayjs(day).format('dddd, DD/MM/YYYY');
}
