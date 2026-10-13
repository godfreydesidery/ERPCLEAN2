import { afterEach, describe, expect, it } from 'vitest';
import { AppDatePipe } from './app-date.pipe';
import {
  businessTimeZone,
  DEFAULT_BUSINESS_TIME_ZONE,
  firstOfMonthLocal,
  formatDate,
  formatDateTime,
  localIsoDate,
  setBusinessTimeZone,
  todayLocal,
} from './date.util';

describe('date.util (owner ruling 2026-10-10: UTC at rest, business zone on screen)', () => {
  afterEach(() => setBusinessTimeZone(null));

  it('defaults to Africa/Dar_es_Salaam and rejects unknown zones', () => {
    expect(businessTimeZone()).toBe(DEFAULT_BUSINESS_TIME_ZONE);
    setBusinessTimeZone('Mars/Olympus');
    expect(businessTimeZone()).toBe('Africa/Dar_es_Salaam');
    setBusinessTimeZone('Africa/Nairobi');
    expect(businessTimeZone()).toBe('Africa/Nairobi');
  });

  it('todayLocal is the EAT date even while UTC is still yesterday (ADM-26)', () => {
    // 00:30 EAT on 1 Nov 2026 == 21:30 UTC on 31 Oct.
    const halfPastMidnightEat = new Date('2026-10-31T21:30:00Z');
    expect(halfPastMidnightEat.toISOString().slice(0, 10)).toBe('2026-10-31');
    expect(todayLocal(halfPastMidnightEat)).toBe('2026-11-01');
    expect(firstOfMonthLocal(halfPastMidnightEat)).toBe('2026-11-01');
  });

  it('formats an instant as dd-MMM-yyyy / dd-MMM-yyyy HH:mm in the business zone (ADM-27)', () => {
    expect(formatDate('2026-10-10T03:21:19.624913Z')).toBe('10-Oct-2026');
    expect(formatDateTime('2026-10-10T03:21:19.624913Z')).toBe('10-Oct-2026 06:21');
    expect(formatDateTime('2026-10-31T21:30:00Z')).toBe('01-Nov-2026 00:30');
  });

  it('leaves a calendar date on its own day and handles empties', () => {
    expect(formatDate('2026-09-01')).toBe('01-Sep-2026');
    expect(formatDateTime('2026-09-01')).toBe('01-Sep-2026');
    expect(formatDate(null)).toBe('');
    expect(formatDateTime(undefined)).toBe('');
    expect(formatDate('not a date')).toBe('not a date');
  });

  it('localIsoDate keeps a locally-built date on its own day', () => {
    expect(localIsoDate(new Date(2026, 9, 1))).toBe('2026-10-01');
  });

  it('appDate pipe delegates to the formatters', () => {
    const pipe = new AppDatePipe();
    expect(pipe.transform('2026-10-10T03:21:19Z')).toBe('10-Oct-2026');
    expect(pipe.transform('2026-10-10T03:21:19Z', 'datetime')).toBe('10-Oct-2026 06:21');
  });
});
