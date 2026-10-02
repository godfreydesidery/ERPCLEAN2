import { describe, it, expect } from 'vitest';
import { HttpErrorResponse } from '@angular/common/http';
import { exportErrorMessage, firstOfMonthIso, localIsoDate } from './ledger-export.util';

describe('ledger-export.util', () => {
  it('localIsoDate formats the LOCAL calendar date, zero-padded', () => {
    // 00:30 local on 1 Sep — toISOString() would say 31 Aug in Dar es Salaam (UTC+3).
    expect(localIsoDate(new Date(2026, 8, 1, 0, 30))).toBe('2026-09-01');
    expect(localIsoDate(new Date(2026, 11, 31, 23, 59))).toBe('2026-12-31');
  });

  it('firstOfMonthIso ends in -01', () => {
    expect(firstOfMonthIso()).toMatch(/^\d{4}-\d{2}-01$/);
  });

  it('exportErrorMessage is friendly and carries no server detail', () => {
    const forbidden = new HttpErrorResponse({ status: 403, error: 'stack trace here' });
    expect(exportErrorMessage(forbidden)).toBe("You don't have permission to export this.");
    expect(exportErrorMessage(new HttpErrorResponse({ status: 400 }))).toBe('Check the dates and try again.');
    expect(exportErrorMessage(new HttpErrorResponse({ status: 500, error: 'SQL boom' })))
      .toBe('The export could not be produced. Please try again.');
    expect(exportErrorMessage(new Error('x'))).toBe('The export could not be produced. Please try again.');
  });
});
