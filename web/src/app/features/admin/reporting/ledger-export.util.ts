import { HttpErrorResponse } from '@angular/common/http';

/**
 * Small helpers shared by the sub-ledger export controls (customer / supplier / cash statements,
 * AR ageing, VAT return, WHT register). Dates are LOCAL calendar dates as YYYY-MM-DD — never
 * toISOString(), which is UTC: between midnight and 03:00 in Dar es Salaam it still says yesterday.
 */
export function localIsoDate(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

export function todayIso(): string {
  return localIsoDate(new Date());
}

export function firstOfMonthIso(): string {
  const d = new Date();
  return localIsoDate(new Date(d.getFullYear(), d.getMonth(), 1));
}

/** A friendly, detail-free message for a failed export download. */
export function exportErrorMessage(err: unknown): string {
  if (err instanceof HttpErrorResponse) {
    if (err.status === 403) return "You don't have permission to export this.";
    if (err.status === 404) return 'Nothing was found to export. Refresh and try again.';
    if (err.status === 400) return 'Check the dates and try again.';
  }
  return 'The export could not be produced. Please try again.';
}
