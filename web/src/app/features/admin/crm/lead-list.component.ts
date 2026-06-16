import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { debounceTime, distinctUntilChanged, map, merge, skip, Subject, switchMap } from 'rxjs';
import { PageMeta } from '../../../core/api/api-response.model';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { Company } from '../models/company.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { CrmService } from './crm.service';
import type { LeadPage } from './crm.service';
import {
  CreateLeadRequest,
  LeadDto,
  LeadSource,
} from './models/crm.model';
import { PaginatorComponent } from '../../../shared/paginator/paginator.component';

const DEFAULT_SIZE = 20;

interface LoadTrigger { page: number }

/**
 * Lead list screen. Company scope, inline create.
 * Route: /admin/crm/leads
 */
@Component({
  selector: 'app-lead-list',
  imports: [FormsModule, RouterLink, PaginatorComponent],
  templateUrl: './lead-list.component.html',
  styleUrl: './lead-list.component.scss',
})
export class LeadListComponent {
  private readonly crmService = inject(CrmService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  // ── Company context ────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── List state ─────────────────────────────────────────────────────────────
  readonly rows = signal<LeadDto[]>([]);
  readonly meta = signal<PageMeta>({ page: 0, size: DEFAULT_SIZE, totalElements: 0, totalPages: 0, hasNext: false });
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('idle');
  readonly currentPage = signal(0);

  // ── Create form ────────────────────────────────────────────────────────────
  readonly showCreateForm = signal(false);
  readonly newDisplayName = signal('');
  readonly newLeadSource = signal<LeadSource>('WEBSITE');
  readonly newCompanyName = signal('');
  readonly newContactPerson = signal('');
  readonly newPhone = signal('');
  readonly newEmail = signal('');
  readonly newNotes = signal('');
  readonly saving = signal(false);
  readonly formError = signal<string | null>(null);

  private readonly immediateTrigger$ = new Subject<LoadTrigger>();

  readonly canManage = computed(() => this.session.hasPermission('CRM.LEAD.MANAGE'));
  readonly isEmpty = computed(() => this.state() === 'idle' && this.rows().length === 0);

  readonly leadSourceOptions: Array<{ value: LeadSource; label: string }> = [
    { value: 'WEBSITE', label: 'Website' },
    { value: 'REFERRAL', label: 'Referral' },
    { value: 'WALK_IN', label: 'Walk-in' },
    { value: 'CAMPAIGN', label: 'Campaign' },
    { value: 'COLD_CALL', label: 'Cold Call' },
    { value: 'EXISTING_CUSTOMER', label: 'Existing Customer' },
    { value: 'OTHER', label: 'Other' },
  ];

  constructor() {
    const typingTrigger$ = toObservable(this.selectedCompanyId).pipe(
      skip(1),
      debounceTime(50),
      distinctUntilChanged(),
      map((): LoadTrigger => ({ page: 0 })),
    );

    merge(typingTrigger$, this.immediateTrigger$)
      .pipe(
        switchMap(({ page }) => {
          const companyId = this.selectedCompanyId();
          if (!companyId) return [];
          this.state.set('loading');
          this.currentPage.set(page);
          return this.crmService.listLeads(companyId, page, DEFAULT_SIZE);
        }),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: ({ rows, meta }: LeadPage) => {
          this.rows.set(rows);
          this.meta.set(meta);
          this.state.set('idle');
        },
        error: (err: unknown) =>
          this.state.set(err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error'),
      });

    this.loadCompanies();
  }

  private loadCompanies(): void {
    this.companyState.set('loading');
    this.organisationService.current().subscribe({
      next: (org) => {
        this.companyService.list(org.uid).subscribe({
          next: (list) => {
            this.companies.set(list);
            this.companyState.set('idle');
            if (list.length > 0) {
              this.selectedCompanyId.set(list[0].id);
              this.load(0);
            }
          },
          error: () => this.companyState.set('error'),
        });
      },
      error: () => this.companyState.set('error'),
    });
  }

  onCompanyChange(id: string): void {
    this.selectedCompanyId.set(id);
    if (id) this.load(0);
  }

  load(page: number): void {
    const companyId = this.selectedCompanyId();
    if (!companyId) return;
    this.immediateTrigger$.next({ page });
  }

  goToPage(page: number): void { this.load(page); }

  prevPage(): void { if (this.currentPage() > 0) this.load(this.currentPage() - 1); }
  nextPage(): void { if (this.meta().hasNext) this.load(this.currentPage() + 1); }

  toggleCreateForm(): void {
    this.showCreateForm.update((v) => !v);
    this.formError.set(null);
    if (!this.showCreateForm()) this.resetCreateForm();
  }

  private resetCreateForm(): void {
    this.newDisplayName.set('');
    this.newLeadSource.set('WEBSITE');
    this.newCompanyName.set('');
    this.newContactPerson.set('');
    this.newPhone.set('');
    this.newEmail.set('');
    this.newNotes.set('');
  }

  create(): void {
    const displayName = this.newDisplayName().trim();
    if (!displayName) { this.formError.set('Display name is required.'); return; }

    const company = this.companies().find((c) => c.id === this.selectedCompanyId());
    if (!company) { this.formError.set('Could not resolve selected company.'); return; }

    this.saving.set(true);
    this.formError.set(null);

    const request: CreateLeadRequest = {
      companyId: company.id,
      displayName,
      leadSource: this.newLeadSource(),
      companyName: this.newCompanyName().trim() || undefined,
      contactPerson: this.newContactPerson().trim() || undefined,
      phone: this.newPhone().trim() || undefined,
      email: this.newEmail().trim() || undefined,
      notes: this.newNotes().trim() || undefined,
    };

    this.crmService.createLead(request).subscribe({
      next: (created) => {
        this.saving.set(false);
        this.resetCreateForm();
        this.showCreateForm.set(false);
        this.alerts.success('Lead created', created.leadNumber);
        this.load(this.currentPage());
      },
      error: (err: unknown) => {
        this.formError.set(this.messageFrom(err, 'Could not create lead.'));
        this.saving.set(false);
      },
    });
  }

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}
