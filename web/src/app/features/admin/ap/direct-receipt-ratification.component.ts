import { Component, computed, input } from '@angular/core';
import { DirectReceiptRatificationState } from './models/ap.model';

/** How much room the caller has: a row chip, or a block on a detail screen. */
export type RatificationVariant = 'chip' | 'detail';

/**
 * True when the state is worth showing at all. NOT_APPLICABLE (an ordinary purchase) and a
 * missing value both render nothing. Single source of truth — callers that need to reserve
 * layout for the notice use this rather than re-testing the enum.
 */
export function hasRatificationNotice(
  state: DirectReceiptRatificationState | null | undefined,
): state is Exclude<DirectReceiptRatificationState, 'NOT_APPLICABLE'> {
  return !!state && state !== 'NOT_APPLICABLE';
}

interface RatificationView {
  /** Full class string for the status chip — matches the app-wide `.status-tag` kit. */
  readonly chipClass: string;
  /** Short label shown in the chip. */
  readonly label: string;
  /**
   * Appended to the chip for screen readers so the state is never carried by colour or a
   * two-word label alone.
   */
  readonly srHint: string;
  /** Tooltip for sighted users hovering the chip. */
  readonly title: string;
  /** Bootstrap alert modifier for the detail block; empty when the state renders quietly. */
  readonly alertClass: string;
  readonly icon: string;
  readonly heading: string;
  readonly body: string;
  /** True for states that need no alarm — rendered as a quiet line instead of an alert. */
  readonly quiet: boolean;
}

/**
 * Copy is deliberately written from the AP clerk's side of the screen: what happened, what it
 * stops them doing, and who unblocks it. Ordinary bills (NOT_APPLICABLE) render nothing at all.
 */
const VIEWS: Record<Exclude<DirectReceiptRatificationState, 'NOT_APPLICABLE'>, RatificationView> = {
  AWAITING_RATIFICATION: {
    chipClass: 'status-tag status-tag--warn',
    label: 'Awaiting ratification',
    srHint: ' — a manager must confirm this delivery before the bill can be paid',
    title: 'Goods received without a purchase order. A manager must confirm the delivery before this bill can be paid.',
    alertClass: 'alert-warning',
    icon: 'bi-hourglass-split',
    heading: 'Waiting for a manager to confirm this delivery',
    body:
      'These goods arrived without a purchase order, so a manager has to confirm the delivery ' +
      'before the bill can be paid. Enter and match the bill as normal — but payment will be ' +
      'refused until that confirmation comes through. If it is holding you up, ask the approver ' +
      'to clear it from their approvals inbox.',
    quiet: false,
  },
  RATIFICATION_REFUSED: {
    chipClass: 'status-tag status-tag--danger',
    label: 'Ratification refused',
    srHint: ' — a manager refused this delivery, so this bill cannot be paid',
    title: 'A manager refused this delivery. This bill cannot be paid.',
    alertClass: 'alert-danger',
    icon: 'bi-x-octagon-fill',
    heading: 'A manager refused this delivery',
    body:
      'These goods arrived without a purchase order and a manager has refused to confirm the ' +
      'delivery, so this bill will not be paid. Do not promise the supplier a payment date. ' +
      'Take it back to the manager who refused it, or raise a debit note if the delivery itself ' +
      'was wrong.',
    quiet: false,
  },
  RATIFIED: {
    chipClass: 'status-tag status-tag--ok',
    label: 'Ratified',
    srHint: ' — a manager confirmed this delivery; the bill pays as normal',
    title: 'A manager confirmed this delivery. This bill pays as normal.',
    alertClass: '',
    icon: 'bi-check-circle',
    heading: 'Delivery confirmed',
    body: 'Goods received without a purchase order. A manager has confirmed the delivery, so this bill pays as normal.',
    quiet: true,
  },
};

/**
 * Surfaces the post-hoc ratification state of a direct goods receipt on a supplier bill
 * (K3 follow-up).
 *
 * <p>The backend derives {@code SupplierBillDto.directReceiptRatification} at read time and blocks
 * payment release while it is AWAITING_RATIFICATION or RATIFICATION_REFUSED. Without this badge the
 * clerk only learns that at payment time, as a refusal; with it they see it the moment the bill is
 * entered.
 *
 * <p>NOT_APPLICABLE (and a missing value) render nothing — the overwhelming majority of bills are
 * ordinary purchases and must not gain visual noise.
 */
@Component({
  selector: 'app-direct-receipt-ratification',
  imports: [],
  // Collapse the host too, so a caller's spacing utilities cannot leave a gap on ordinary bills.
  host: { '[class.d-none]': '!view()' },
  template: `
    @if (view(); as v) {
      @if (variant() === 'detail') {
        @if (v.quiet) {
          <p class="small text-muted d-flex align-items-center gap-2 mb-0">
            <span [class]="v.chipClass" [attr.title]="v.title">
              <span class="status-tag__dot" aria-hidden="true"></span>{{ v.label }}<span
                class="visually-hidden">{{ v.srHint }}</span>
            </span>
            <span>{{ v.body }}</span>
          </p>
        } @else {
          <div [class]="'alert d-flex gap-2 mb-0 ' + v.alertClass" role="alert">
            <i [class]="'bi flex-shrink-0 ' + v.icon" aria-hidden="true"></i>
            <div>
              <strong>{{ v.heading }}</strong>
              <div class="small">{{ v.body }}</div>
            </div>
          </div>
        }
      } @else {
        <span [class]="v.chipClass" [attr.title]="v.title">
          <span class="status-tag__dot" aria-hidden="true"></span>{{ v.label }}<span
            class="visually-hidden">{{ v.srHint }}</span>
        </span>
      }
    }
  `,
})
export class DirectReceiptRatificationComponent {
  /** Derived bill field; null/undefined and NOT_APPLICABLE both render nothing. */
  readonly state = input<DirectReceiptRatificationState | null | undefined>(null);
  readonly variant = input<RatificationVariant>('chip');

  protected readonly view = computed<RatificationView | null>(() => {
    const s = this.state();
    return hasRatificationNotice(s) ? VIEWS[s] : null;
  });
}
