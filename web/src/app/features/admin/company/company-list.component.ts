import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Company } from '../models/company.model';
import { Organisation } from '../models/organisation.model';
import { CompanyService } from './company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { AlertService } from '../../../core/feedback/alert.service';

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

  readonly organisation = signal<Organisation | null>(null);
  readonly orgState = signal<'loading' | 'ready' | 'error'>('loading');

  readonly companies = signal<Company[]>([]);
  readonly state = signal<'idle' | 'loading' | 'error'>('idle');
  readonly formError = signal<string | null>(null);

  // Create form
  readonly code = signal('');
  readonly name = signal('');
  readonly saving = signal(false);

  readonly canEdit = computed(() => this.orgState() === 'ready' && this.organisation() !== null);

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
      error: () => this.state.set('error'),
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
