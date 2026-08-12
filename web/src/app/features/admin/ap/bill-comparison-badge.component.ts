import { Component, computed, input } from '@angular/core';
import { BillComparisonState } from './models/ap.model';

/** How much room the caller has: a row chip, or a block on a detail screen. */
export type ComparisonVariant = 'chip' | 'detail';

interface ComparisonView {
  /** Full class string for the status chip — matches the app-wide `.status-tag` kit. */
  readonly chipClass: string;
  /** Short label shown in the chip. */
  readonly label: string;
  /**
   * Appended to the chip for screen readers, so the meaning never rides on colour or a two-word
   * label alone (WCAG 1.4.1).
   */
  readonly srHint: string;
  /** Tooltip for sighted users hovering the chip. */
  readonly title: string;
  /** Longer sentence for the detail variant. */
  readonly body: string;
}

/**
 * Deliberately, EVERY bill gets a chip — including the ones that are fine.
 *
 * <p>Showing a chip only on the bad rows would make a blank cell carry meaning, and a blank cell is
 * exactly what a rendering slip, a stale response or a field the API forgot to send also looks like.
 * The whole point of this signal is that an unchecked bill cannot hide, so the reader must be able
 * to tell "checked" from "nothing was said" at a glance.
 */
const VIEWS: Record<BillComparisonState | 'UNKNOWN', ComparisonView> = {
  ALL_LINES_COMPARED: {
    chipClass: 'status-tag status-tag--ok',
    label: 'Checked',
    srHint: ' — every line was checked against the order price and the goods received',
    title: 'Every line on this bill was checked against the purchase order price and the quantity received.',
    body: 'Every line on this bill was checked against the purchase order price and the quantity received.',
  },
  SOME_LINES_COMPARED: {
    chipClass: 'status-tag status-tag--warn',
    label: 'Partly checked',
    srHint: ' — some lines were never checked against an order or a goods receipt',
    title: 'Some lines on this bill were never checked against a purchase order and a goods receipt.',
    body:
      'Some lines on this bill were checked against the purchase order and the goods receipt, and ' +
      'some were not. Open the bill to see which, and check those against the supplier’s invoice ' +
      'yourself before you pay it.',
  },
  NO_LINES_COMPARED: {
    chipClass: 'status-tag status-tag--warn',
    label: 'Not checked',
    srHint: ' — nothing on this bill was checked against an order or a goods receipt',
    title: 'No line on this bill was checked against a purchase order or a goods receipt.',
    body:
      'Nothing on this bill was checked against a purchase order or a goods receipt — there was no ' +
      'order or receipt linked to check it against. That is normal for a service bill such as rent ' +
      'or professional fees; for a delivery of goods it is worth asking why.',
  },
  NEVER_MATCHED: {
    chipClass: 'status-tag status-tag--warn',
    label: 'No check run',
    srHint: ' — the three-way check has never been run on this bill',
    title: 'The three-way check has never been run on this bill.',
    body:
      'The three-way check has never been run on this bill. That is expected while it is still a ' +
      'draft. On a bill that has already been posted it means the amount owed was recorded without ' +
      'anyone comparing it to an order or a delivery — an opening balance does this — so check it ' +
      'against the supplier’s own statement.',
  },
  UNKNOWN: {
    chipClass: 'status-tag status-tag--neutral',
    label: 'Not reported',
    srHint: ' — this bill did not report whether it was checked',
    title: 'This bill did not report whether it was checked against a purchase order and a goods receipt.',
    body:
      'This bill did not report whether it was checked against a purchase order and a goods ' +
      'receipt. Treat it as unchecked until you have looked at it.',
  },
};

/**
 * Says, on the bill list, whether anybody actually checked this bill against a purchase order and a
 * goods receipt (UAT 2026-08-12).
 *
 * <p><b>Why it earns a column.</b> The 3-way match only binds on links the accountant chose to
 * declare, so a bill entered with no purchase-order reference comes out MATCHED and posts — the
 * control never had anything to compare. That is legitimate for rent, utilities and professional
 * fees, so the answer is not to refuse those bills; it is to stop them being indistinguishable from
 * the bills somebody checked. This is the visible half of that: no click required.
 *
 * <p>It renders a state and nothing else. It gates no action and blocks no payment.
 */
@Component({
  selector: 'app-bill-comparison-badge',
  imports: [],
  template: `
    @if (variant() === 'detail') {
      <p class="small d-flex align-items-start gap-2 mb-0">
        <span [class]="view().chipClass" [attr.title]="view().title">
          <span class="status-tag__dot" aria-hidden="true"></span>{{ view().label }}<span
            class="visually-hidden">{{ view().srHint }}</span>
        </span>
        <span class="text-muted">{{ view().body }}</span>
      </p>
    } @else {
      <span [class]="view().chipClass" [attr.title]="view().title">
        <span class="status-tag__dot" aria-hidden="true"></span>{{ view().label }}<span
          class="visually-hidden">{{ view().srHint }}</span>
      </span>
    }
  `,
})
export class BillComparisonBadgeComponent {
  /**
   * The bill's derived comparison state. A missing value falls through to "Not reported" — never to
   * "Checked", which would be the one failure mode that matters here.
   */
  readonly state = input<BillComparisonState | null | undefined>(null);
  readonly variant = input<ComparisonVariant>('chip');

  protected readonly view = computed<ComparisonView>(() => {
    const s = this.state();
    return s ? (VIEWS[s] ?? VIEWS.UNKNOWN) : VIEWS.UNKNOWN;
  });
}
