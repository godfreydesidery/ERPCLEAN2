/**
 * Business dates and printed timestamps (owner ruling 2026-10-10: "save UTC in the db but display
 * in timezone").
 *
 * The API stores and returns instants in UTC (`2026-10-10T03:21:19Z`). People must never see that
 * raw string, and a form's "today" must be the shop's today — `new Date().toISOString().slice(0,10)`
 * is the UTC date, which between 00:00 and 03:00 in Dar es Salaam is still yesterday (ADM-26).
 *
 * Everything here works in the business time zone: the company's zone when the shell knows it
 * ({@link setBusinessTimeZone}), otherwise Africa/Dar_es_Salaam — the same fallback the server uses.
 */

export const DEFAULT_BUSINESS_TIME_ZONE = 'Africa/Dar_es_Salaam';

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const DATE_ONLY = /^(\d{4})-(\d{2})-(\d{2})$/;

let zone = DEFAULT_BUSINESS_TIME_ZONE;

/** True when the runtime knows `tz` as an IANA zone. */
function isValidZone(tz: string): boolean {
  try {
    new Intl.DateTimeFormat('en-GB', { timeZone: tz });
    return true;
  } catch {
    return false;
  }
}

/** Sets the business zone (the company's); blank or unknown falls back to Africa/Dar_es_Salaam. */
export function setBusinessTimeZone(tz: string | null | undefined): void {
  const t = tz?.trim();
  zone = t && isValidZone(t) ? t : DEFAULT_BUSINESS_TIME_ZONE;
}

/** The zone dates are shown and defaulted in. */
export function businessTimeZone(): string {
  return zone;
}

interface Parts {
  y: string;
  m: string;
  d: string;
  hh: string;
  mm: string;
}

function partsInZone(at: Date, tz: string): Parts {
  const fmt = new Intl.DateTimeFormat('en-GB', {
    timeZone: tz,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
  });
  const p: Record<string, string> = {};
  for (const part of fmt.formatToParts(at)) {
    p[part.type] = part.value;
  }
  return { y: p['year'], m: p['month'], d: p['day'], hh: p['hour'], mm: p['minute'] };
}

/** Today's date in the business zone as YYYY-MM-DD — the default for every date field and filter. */
export function todayLocal(now: Date = new Date()): string {
  const p = partsInZone(now, zone);
  return `${p.y}-${p.m}-${p.d}`;
}

/** The first day of the current business-zone month as YYYY-MM-DD. */
export function firstOfMonthLocal(now: Date = new Date()): string {
  return `${todayLocal(now).slice(0, 8)}01`;
}

/**
 * YYYY-MM-DD of a Date built from LOCAL calendar fields (`new Date(y, m, 1)`, `setDate(...)`).
 * Never `toISOString()` for these: local midnight in UTC+3 is 21:00 the day before in UTC.
 */
export function localIsoDate(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

type DateInput = string | number | Date | null | undefined;

/** A plain calendar date (`2026-10-10`) is formatted as-is; anything else is an instant. */
function toInstant(value: DateInput): Date | null {
  if (value === null || value === undefined || value === '') return null;
  const d = value instanceof Date ? value : new Date(value);
  return Number.isNaN(d.getTime()) ? null : d;
}

/** `10-Oct-2026` — a calendar date stays as is, an instant is read in the business zone. */
export function formatDate(value: DateInput): string {
  if (typeof value === 'string') {
    const m = DATE_ONLY.exec(value.trim());
    if (m) return `${m[3]}-${MONTHS[Number(m[2]) - 1]}-${m[1]}`;
  }
  const at = toInstant(value);
  if (!at) return typeof value === 'string' ? value : '';
  const p = partsInZone(at, zone);
  return `${p.d}-${MONTHS[Number(p.m) - 1]}-${p.y}`;
}

/** `10-Oct-2026 06:21` in the business zone; a bare calendar date prints without a time. */
export function formatDateTime(value: DateInput): string {
  if (typeof value === 'string' && DATE_ONLY.test(value.trim())) return formatDate(value);
  const at = toInstant(value);
  if (!at) return typeof value === 'string' ? value : '';
  const p = partsInZone(at, zone);
  return `${p.d}-${MONTHS[Number(p.m) - 1]}-${p.y} ${p.hh}:${p.mm}`;
}
