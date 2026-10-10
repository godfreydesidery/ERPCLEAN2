import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

/**
 * Why a picker's supporting list could not be loaded (ADM-28).
 *
 * The HTTP interceptor deliberately raises no modal for a 403 (it is an authorization fact, not a
 * system failure), so a picker whose lookup is refused used to fall silently empty, and the user
 * read "no tills / no price lists / no locations" as data that did not exist. A picker classifies
 * its lookup failure with {@link lookupFailure} and renders {@link LookupNoticeComponent}, which
 * says plainly that the list is not available to this user.
 */
export type LookupFailure = 'forbidden' | 'error';

/** Classifies a failed lookup: a 403 is "no access", anything else is a load error. */
export function lookupFailure(err: unknown): LookupFailure {
  return err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error';
}

/** The sentence shown when the caller may not read a picker's list. */
export function noAccessMessage(what: string): string {
  return `You don't have access to the ${what} list. Ask your administrator if you need it.`;
}

/** The sentence shown when a picker's list failed to load for any other reason. */
export function loadFailedMessage(what: string): string {
  return `The ${what} list could not be loaded. Refresh the page to try again.`;
}

/**
 * Inline notice under a picker whose list could not be loaded: "no access" for a 403, a retry
 * hint otherwise, nothing for any other state. Usage:
 *
 * ```html
 * <app-lookup-notice [state]="tillsState()" what="till" />
 * ```
 */
@Component({
  selector: 'app-lookup-notice',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (message(); as m) {
      <div class="form-text text-body-secondary" role="status" [attr.id]="noticeId() || null">
        <i class="bi bi-lock me-1" aria-hidden="true"></i>{{ m }}
      </div>
    }
  `,
})
export class LookupNoticeComponent {
  /** The picker's lookup state; only 'forbidden' and 'error' render anything. */
  readonly state = input<string | null | undefined>(null);
  /** What the list holds, singular, e.g. "till", "price list", "location". */
  readonly what = input.required<string>();
  /** Optional id so the picker can point aria-describedby at the notice. */
  readonly noticeId = input<string>('');

  readonly message = computed(() => {
    const s = this.state();
    if (s === 'forbidden') return noAccessMessage(this.what());
    if (s === 'error') return loadFailedMessage(this.what());
    return null;
  });
}
