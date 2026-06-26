import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Company } from '../models/company.model';
import { Organisation } from '../models/organisation.model';
import { CompanyService } from './company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';

/**
 * Lists companies for the deployment's organisation and creates new ones. The organisation is
 * resolved automatically by name via {@link OrganisationService} (single-org-per-deployment model) —
 * no uid typing. All states (loading / empty / error / populated) are handled, for both the
 * organisation resolve and the company list.
 */
@Component({
  selector: 'app-company-list',
  imports: [FormsModule, RouterLink],
  templateUrl: './company-list.component.html',
  styleUrl: './company-list.component.scss',
})
export class CompanyListComponent {
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly alerts = inject(AlertService);
  private readonly session = inject(SessionStore);

  readonly organisation = signal<Organisation | null>(null);
  readonly orgState = signal<'loading' | 'ready' | 'error'>('loading');

  readonly companies = signal<Company[]>([]);
  readonly state = signal<'idle' | 'loading' | 'error' | 'forbidden'>('idle');
  readonly formError = signal<string | null>(null);

  // Create form
  readonly code = signal('');
  readonly name = signal('');
  readonly saving = signal(false);

  readonly canEdit = computed(() => this.orgState() === 'ready' && this.organisation() !== null);

  /** Rename is gated on COMPANY.MANAGE (the page itself only needs COMPANY.VIEW). */
  readonly canManage = computed(() => this.session.hasPermission('COMPANY.MANAGE'));

  // Provision defaults: tracks which row's button is spinning (at most one at a time).
  readonly provisioningUid = signal<string | null>(null);

  // Inline rename: at most one row is editable at a time (keyed by uid).
  readonly editingUid = signal<string | null>(null);
  readonly editName = signal('');
  readonly savingEdit = signal(false);
  readonly editError = signal<string | null>(null);

  startEdit(c: Company): void {
    this.editError.set(null);
    this.editName.set(c.name);
    this.editingUid.set(c.uid);
  }

  provisionDefaults(c: Company): void {
    if (this.provisioningUid() !== null) return;
    // Confirm before a bulk re-provision — it re-seeds every per-company default set.
    if (!window.confirm(`Restore the default setup (tax rates, accounts, units, …) for "${c.name}"?`)) {
      return;
    }
    this.provisioningUid.set(c.uid);
    this.companyService.provisionDefaults(c.uid).subscribe({
      next: (updated) => {
        this.companies.update((list) => list.map((x) => (x.uid === updated.uid ? updated : x)));
        this.provisioningUid.set(null);
        this.alerts.success('Default setup restored', updated.name);
      },
      error: (err) => {
        this.provisioningUid.set(null);
        this.alerts.error('Could not restore defaults', this.messageFrom(err));
      },
    });
  }

  cancelEdit(): void {
    this.editingUid.set(null);
    this.editError.set(null);
  }

  saveEdit(c: Company): void {
    const name = this.editName().trim();
    if (!name) {
      this.editError.set('Name is required.');
      return;
    }
    if (name === c.name) {
      this.cancelEdit();
      return;
    }
    this.savingEdit.set(true);
    this.editError.set(null);
    // updateByUid overwrites legalName/taxId from the request, so echo the current values back —
    // a name-only edit must not blank them. timeZone is preserved server-side if blank; sent for clarity.
    this.companyService
      .update(c.uid, {
        name,
        legalName: c.legalName ?? undefined,
        taxId: c.taxId ?? undefined,
        timeZone: c.timeZone,
      })
      .subscribe({
        next: (updated) => {
          this.companies.update((list) => list.map((x) => (x.uid === updated.uid ? updated : x)));
          this.savingEdit.set(false);
          this.editingUid.set(null);
          this.alerts.success('Company updated', updated.name);
        },
        error: (err) => {
          this.editError.set(this.messageFrom(err));
          this.savingEdit.set(false);
        },
      });
  }

  constructor() {
    this.organisationService.current().subscribe({
      next: (org) => {
        this.organisation.set(org);
        this.orgState.set('ready');
        this.loadCompanies(org.uid);
      },
      error: () => this.orgState.set('error'),
    });
  }

  private loadCompanies(orgUid: string): void {
    this.state.set('loading');
    this.companyService.list(orgUid).subscribe({
      next: (rows) => {
        this.companies.set(rows);
        this.state.set('idle');
      },
      // A 403 means "you can't view companies" — show a calm panel, not a generic error
      // (the global interceptor no longer pops the red modal for 403). The route guard normally
      // keeps a permissionless user off this screen; this is the in-page backstop.
      error: (err) =>
        this.state.set(err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error'),
    });
  }

  create(): void {
    const org = this.organisation();
    if (!org || !this.code().trim() || !this.name().trim()) {
      this.formError.set('Code and name are required.');
      return;
    }
    this.saving.set(true);
    this.formError.set(null);
    this.companyService
      .create({ organisationUid: org.uid, code: this.code().trim(), name: this.name().trim() })
      .subscribe({
        next: (created) => {
          this.companies.update((list) => [...list, created]);
          this.code.set('');
          this.name.set('');
          this.saving.set(false);
          this.alerts.success('Company created', created.name);
        },
        error: (err) => {
          this.formError.set(this.messageFrom(err));
          this.saving.set(false);
        },
      });
  }

  private messageFrom(err: unknown): string {
    const errors = (err as { error?: { errors?: string[] } })?.error?.errors;
    return errors?.length ? errors[0] : 'Could not save the company.';
  }
}
