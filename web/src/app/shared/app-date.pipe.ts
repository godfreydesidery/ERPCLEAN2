import { Pipe, PipeTransform } from '@angular/core';
import { formatDate, formatDateTime } from './date.util';

/**
 * `{{ inv.finalisedAt | appDate }}` → `10-Oct-2026`; `{{ x | appDate:'datetime' }}` →
 * `10-Oct-2026 06:21`. Always in the business (company) zone, never the raw ISO the API returns
 * (ADM-27). Empty/invalid input renders as an empty string, so pair it with `|| '—'` where a dash
 * is wanted.
 */
@Pipe({ name: 'appDate' })
export class AppDatePipe implements PipeTransform {
  transform(value: string | number | Date | null | undefined, mode: 'date' | 'datetime' = 'date'): string {
    return mode === 'datetime' ? formatDateTime(value) : formatDate(value);
  }
}
