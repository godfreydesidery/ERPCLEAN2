import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { Company } from '../models/company.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { CustomerService } from '../parties/customer.service';
import { CustomerModel } from '../models/party.model';
import { CrmService } from './crm.service';
import { CreateOpportunityRequest, LeadDto, PipelineStageDto } from './models/crm.model';
import { UidPickerComponent, UidOption } from '../../../shared/uid-picker/uid-picker.component';
import { CurrencySelectComponent } from '../../../shared/currency-select/currency-select.component';

/**
 * Opportunity create screen. Route: /admin/crm/opportunities/create
 * Reads optional query params: companyId, sourceLeadUid, customerUid.
 */
@Component({
  selector: 'app-opportunity-create',
  imports: [FormsModule, RouterLink, UidPickerComponent, CurrencySelectComponent],
  templateUrl: './opportunity-create.component.html',
  styleUrl: './opportunity-create.component.scss',
})
export class OpportunityCreateComponent {
  private readonly crmService = inject(CrmService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly customerService = inject(CustomerService);
  private readonly alerts = inject(AlertService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  protected readonly session = inject(SessionStore);

  // ── Company context ────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly selectedCompanyUid = computed(() => this.companies().find((c) => c.id === this.selectedCompanyId())?.uid ?? '');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── Picker option lists ────────────────────────────────────────────────────
  readonly customers = signal<CustomerModel[]>([]);
  readonly leads = signal<LeadDto[]>([]);

  readonly customerOptions = computed<UidOption[]>(() =>
    this.customers().map((c) => ({ uid: c.uid, label: c.displayName, hint: c.code })),
  );
  readonly sourceLeadOptions = computed<UidOption[]>(() =>
    this.leads().map((l) => ({ uid: l.uid, label: l.displayName, hint: l.leadNumber })),
  );

  // ── Pipeline stages ────────────────────────────────────────────────────────
  readonly stages = signal<PipelineStageDto[]>([]);

  // ── Form fields ────────────────────────────────────────────────────────────
  readonly fTitle = signal('');
  readonly fCustomerUid = signal('');
  readonly fPipelineStageUid = signal('');
  readonly fCurrency = signal('TZS');
  readonly fEstimatedValue = signal('');
  readonly fWinProbability = signal('');
  readonly fExpectedCloseDate = signal('');
  readonly fSourceLeadUid = signal('');
  readonly fNotes = signal('');

  readonly saving = signal(false);
  readonly formError = signal<string | null>(null);

  constructor() {
    const qp = this.route.snapshot.queryParamMap;
    const initCompanyId = qp.get('companyId') ?? '';
    const initSourceLeadUid = qp.get('sourceLeadUid') ?? '';
    const initCustomerUid = qp.get('customerUid') ?? '';

    if (initSourceLeadUid) this.fSourceLeadUid.set(initSourceLeadUid);
    if (initCustomerUid) this.fCustomerUid.set(initCustomerUid);

    this.companyState.set('loading');
    this.organisationService.current().subscribe({
      next: (org) => {
        this.companyService.list(org.uid).subscribe({
          next: (list) => {
            this.companies.set(list);
            this.companyState.set('idle');
            const match = initCompanyId ? list.find((c) => c.id === initCompanyId) : null;
            const first = match ?? list[0];
            if (first) {
              this.selectedCompanyId.set(first.id);
              this.loadStages(first.id);
              this.loadPickerOptions(first.id);
            }
          },
          error: () => this.companyState.set('error'),
        });
      },
      error: () => this.companyState.set('error'),
    });
  }

  private loadStages(companyId: string): void {
    this.stages.set([]);
    this.fPipelineStageUid.set('');
    this.crmService.listPipelineStages(companyId).subscribe({
      next: (rows) => {
        const active = rows.filter((s) => s.active);
        this.stages.set(active);
        if (active.length > 0 && !this.fPipelineStageUid()) {
          this.fPipelineStageUid.set(active[0].uid);
        }
      },
      error: () => undefined,
    });
  }

  onCompanyChange(id: string): void {
    this.selectedCompanyId.set(id);
    if (id) {
      this.loadStages(id);
      this.loadPickerOptions(id);
    }
  }

  private loadPickerOptions(companyId: string): void {
    this.customerService.list(companyId, undefined, 0, 200).subscribe({
      next: ({ rows }) => this.customers.set(rows),
      error: () => undefined,
    });
    this.crmService.listLeads(companyId, 0, 200).subscribe({
      next: ({ rows }) => this.leads.set(rows.filter((l) => l.leadStatus === 'QUALIFIED')),
      error: () => undefined,
    });
  }

  create(): void {
    const title = this.fTitle().trim();
    const customerUid = this.fCustomerUid().trim();
    const stageUid = this.fPipelineStageUid();
    const currency = this.fCurrency().trim();

    if (!title) { this.formError.set('Title is required.'); return; }
    if (!customerUid) { this.formError.set('Customer UID is required.'); return; }
    if (!stageUid) { this.formError.set('Pipeline stage is required.'); return; }
    if (!currency) { this.formError.set('Currency is required.'); return; }

    const company = this.companies().find((c) => c.id === this.selectedCompanyId());
    if (!company) { this.formError.set('Could not resolve selected company.'); return; }

    this.saving.set(true);
    this.formError.set(null);

    const request: CreateOpportunityRequest = {
      companyId: company.id,
      customerUid,
      title,
      pipelineStageUid: stageUid,
      currency,
      // type="number" stores a JS number; coerce before .trim() to avoid crash.
      estimatedValueAmount: String(this.fEstimatedValue() ?? '').trim() || undefined,
      winProbability: String(this.fWinProbability() ?? '').trim() || undefined,
      expectedCloseDate: this.fExpectedCloseDate() || undefined,
      sourceLeadUid: this.fSourceLeadUid().trim() || undefined,
      notes: this.fNotes().trim() || undefined,
    };

    this.crmService.createOpportunity(request).subscribe({
      next: (created) => {
        this.saving.set(false);
        this.alerts.success('Opportunity created', created.opportunityNumber);
        this.router.navigate(['/admin/crm/opportunities/uid', created.uid]);
      },
      error: (err: unknown) => {
        this.formError.set(this.messageFrom(err, 'Could not create opportunity.'));
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
