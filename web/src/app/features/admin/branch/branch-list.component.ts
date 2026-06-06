import { Component, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Branch } from '../models/branch.model';
import { BranchService } from './branch.service';
import { AlertService } from '../../../core/feedback/alert.service';

/**
 * Lists a company's branches and manages them — create, set-default, archive. The
 * company uid comes from the route (`/admin/companies/:companyUid/branches`). Set-default is the
 * headline action: marking one default clears the previous (enforced server-side).
 */
@Component({
  selector: 'app-branch-list',
  imports: [FormsModule],
  templateUrl: './branch-list.component.html',
  styleUrl: './branch-list.component.scss',
})
export class BranchListComponent {
  private readonly branchService = inject(BranchService);
  private readonly alerts = inject(AlertService);

  /** Route input — Angular binds the `:companyUid` path param to this signal. */
  readonly companyUid = input.required<string>();

  readonly branches = signal<Branch[]>([]);
  readonly state = signal<'loading' | 'idle' | 'error'>('loading');
  readonly formError = signal<string | null>(null);

  readonly code = signal('');
  readonly name = signal('');
  readonly makeDefault = signal(false);
  readonly saving = signal(false);

  /** uid of the branch whose row action (set-default or archive) is in-flight. */
  readonly busyUid = signal<string | null>(null);

  constructor() {
    // input.required is set by the router before the constructor body runs in this config; load on init.
    queueMicrotask(() => this.load());
  }

  load(): void {
    this.state.set('loading');
    this.branchService.list(this.companyUid()).subscribe({
      next: (rows) => {
        this.branches.set(rows);
        this.state.set('idle');
      },
      error: () => this.state.set('error'),
    });
  }

  create(): void {
    if (!this.code().trim() || !this.name().trim()) {
      this.formError.set('Code and name are required.');
      return;
    }
    this.saving.set(true);
    this.formError.set(null);
    this.branchService
      .create({
        companyUid: this.companyUid(),
        code: this.code().trim(),
        name: this.name().trim(),
        makeDefault: this.makeDefault(),
      })
      .subscribe({
        next: () => {
          // Reload so a default change elsewhere is reflected consistently.
          this.code.set('');
          this.name.set('');
          this.makeDefault.set(false);
          this.saving.set(false);
          this.alerts.success('Branch created');
          this.load();
        },
        error: (err) => {
          this.formError.set(this.messageFrom(err));
          this.saving.set(false);
        },
      });
  }

  setDefault(branch: Branch): void {
    if (branch.isDefault || this.busyUid() !== null) {
      return;
    }
    this.busyUid.set(branch.uid);
    this.branchService.setDefault(branch.uid).subscribe({
      next: () => {
        this.busyUid.set(null);
        this.alerts.success('Default branch updated');
        this.load();
      },
      error: (err) => {
        this.busyUid.set(null);
        this.formError.set(this.messageFrom(err));
      },
    });
  }

  archive(branch: Branch): void {
    if (this.busyUid() !== null) {
      return;
    }
    this.busyUid.set(branch.uid);
    this.branchService.archive(branch.uid).subscribe({
      next: () => {
        this.busyUid.set(null);
        this.alerts.success('Branch archived');
        this.load();
      },
      error: (err) => {
        this.busyUid.set(null);
        this.formError.set(this.messageFrom(err));
      },
    });
  }

  private messageFrom(err: unknown): string {
    const errors = (err as { error?: { errors?: string[] } })?.error?.errors;
    return errors?.length ? errors[0] : 'Action failed. Please try again.';
  }
}
