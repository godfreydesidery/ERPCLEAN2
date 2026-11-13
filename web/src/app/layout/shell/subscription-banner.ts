import { DatePipe } from '@angular/common';
import {
  SubscriptionSummary,
  daysBetween,
  localToday,
} from '../../features/admin/platform/subscription.model';

/** How close to a due date the info banner starts (ADR-0069 D-6: "from 7 days out"). */
export const BANNER_LEAD_DAYS = 7;

export interface SubscriptionBanner {
  severity: 'info' | 'warning' | 'error';
  message: string;
}

/** 'yyyy-MM-dd' to a readable date, e.g. "14 Nov 2026". Date-only strings parse as local dates. */
function readable(iso: string): string {
  return new DatePipe('en-US').transform(iso, 'd MMM y') ?? iso;
}

/**
 * The banner a tenant user sees for their subscription, or null when there is nothing to say
 * (unrestricted, suspended, or still far from the date). `today` is the browser's local date.
 * The wording is the owner's (ADR-0069).
 */
export function subscriptionBanner(
  sub: SubscriptionSummary | null,
  today: string = localToday(),
): SubscriptionBanner | null {
  if (!sub) return null;
  switch (sub.stage) {
    case 'TRIAL':
      return sub.dueDate && daysBetween(today, sub.dueDate) <= BANNER_LEAD_DAYS
        ? { severity: 'info', message: `Trial ends on ${readable(sub.dueDate)}.` }
        : null;
    case 'ACTIVE':
      return sub.dueDate && daysBetween(today, sub.dueDate) <= BANNER_LEAD_DAYS
        ? { severity: 'info', message: `Subscription due on ${readable(sub.dueDate)}.` }
        : null;
    case 'GRACE':
      return {
        severity: 'warning',
        message: sub.readOnlyFrom
          ? `Payment overdue. The system becomes read-only on ${readable(sub.readOnlyFrom)}.`
          : 'Payment overdue. The system will become read-only soon.',
      };
    case 'READ_ONLY':
      return {
        severity: 'error',
        message:
          'Your subscription is overdue, so the system is read-only. ' +
          (sub.suspendedFrom ? `It will be suspended on ${readable(sub.suspendedFrom)}. ` : '') +
          'Payment restores full access.',
      };
    default:
      return null;
  }
}
