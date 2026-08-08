import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  inject,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AuthService } from '../../core/auth/auth.service';
import { AuthorityVerificationDto } from '../../core/auth/auth.model';

/** What the caller gets back when a supervisor successfully authorises the action. */
export interface ManagerApproval {
  /** The approving user's uid — put this on the business request. */
  readonly authoriserUid: string;
  /** Display name, for "Approved by …" on screen. Falls back to the username. */
  readonly authoriserName: string;
}

/**
 * Manager step-up ("supervisor override") prompt.
 *
 * A supervisor walks to the operator's device, types their OWN username and password, and this
 * calls `POST /api/v1/auth/verify-authority`. That endpoint issues no token and rotates nothing —
 * the operator stays logged in, mid-sale, with the same session before and after. On success the
 * caller receives the approving user's uid to attach to the request it was about to send.
 *
 * Two deliberate behaviours:
 *  - A refusal arrives as HTTP **200** with `authorised: false`, so the dialog STAYS OPEN and shows
 *    the server's own friendly message. A mistyped password must not unwind the basket.
 *  - The password is held in a local signal, cleared on every close, and never logged or echoed.
 *
 * Accessibility: a real `role="dialog"` with `aria-modal`, labelled by its heading, focus moved to
 * the username field on open, Escape cancels, and the error region is `role="alert"`.
 *
 * Usage:
 *   @if (approvalOpen()) {
 *     <app-manager-approval-dialog
 *        permissionCode="SALES.INVOICE.OVERRIDE"
 *        [reason]="'A 60% discount on Sugar 1kg needs a supervisor.'"
 *        (approved)="onApproved($event)" (cancelled)="approvalOpen.set(false)" />
 *   }
 */
@Component({
  selector: 'app-manager-approval-dialog',
  standalone: true,
  imports: [FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './manager-approval-dialog.component.html',
  styleUrl: './manager-approval-dialog.component.scss',
})
export class ManagerApprovalDialogComponent {
  private readonly auth = inject(AuthService);

  /** The seeded permission code the supervisor must hold (e.g. SALES.INVOICE.OVERRIDE). */
  readonly permissionCode = input.required<string>();
  /** One plain sentence saying what is being approved — shown above the fields. */
  readonly reason = input<string>('');
  /** Heading text; override when the action is not a discount. */
  readonly title = input<string>('Supervisor approval needed');

  readonly approved = output<ManagerApproval>();
  readonly cancelled = output<void>();

  readonly username = signal('');
  readonly password = signal('');
  readonly checking = signal(false);
  readonly errorMessage = signal<string | null>(null);

  private readonly usernameField = viewChild<ElementRef<HTMLInputElement>>('usernameField');

  constructor() {
    // Move focus into the dialog once it is on the page, so a keyboard user is not left behind it.
    afterNextRender(() => this.usernameField()?.nativeElement.focus());
  }

  submit(): void {
    if (this.checking()) return;
    const username = this.username().trim();
    const password = this.password();
    if (!username || !password) {
      this.errorMessage.set('Enter the supervisor’s username and password.');
      return;
    }

    this.checking.set(true);
    this.errorMessage.set(null);
    this.auth
      .verifyAuthority({ username, password, permissionCode: this.permissionCode() })
      .subscribe({
        next: (result: AuthorityVerificationDto) => {
          this.checking.set(false);
          if (!result.authorised || !result.authoriserUid) {
            // 200 + authorised:false is the normal refusal path — stay open, let them retype.
            this.password.set('');
            this.errorMessage.set(result.message || 'That approval was not accepted.');
            this.usernameField()?.nativeElement.focus();
            return;
          }
          this.password.set('');
          this.approved.emit({
            authoriserUid: result.authoriserUid,
            authoriserName: result.authoriserName || result.authoriserUsername || 'Supervisor',
          });
        },
        error: () => {
          this.checking.set(false);
          this.password.set('');
          this.errorMessage.set('Could not check that approval right now. Please try again.');
        },
      });
  }

  cancel(): void {
    this.password.set('');
    this.cancelled.emit();
  }
}
