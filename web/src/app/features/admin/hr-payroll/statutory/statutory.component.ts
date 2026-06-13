import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { AlertService } from '../../../../core/feedback/alert.service';
import { SessionStore } from '../../../../core/auth/session.store';
import { Company } from '../../models/company.model';
import { CompanyService } from '../../company/company.service';
import { OrganisationService } from '../../organisation/organisation.service';
import { HrStatutoryService } from './hr-statutory.service';
import {
  BandRequest,
  CreatePayeBandSetRequest,
  CreateStatutoryRateSetRequest,
  PayeBandSetDto,
  StatutoryRateSetDto,
  StatutoryRateType,
} from '../models/hr-payroll.model';

interface BandRow {
  bandNo: number;
  lowerBound: string;
  marginalRate: string;
  cumulativeFixedTax: string;
}

/**
 * Statutory Setup screen — two sections:
 *   1. PAYE Band Sets (list + create)
 *   2. Statutory Rate Sets (list + create)
 * Route: /admin/hr/statutory
 * Permission: HR.STATUTORY.MANAGE
 */
@Component({
  selector: 'app-statutory',
  imports: [FormsModule, DecimalPipe],
  templateUrl: './statutory.component.html',
  styleUrl: './statutory.component.scss',
})
export class StatutoryComponent {
  private readonly statutoryService = inject(HrStatutoryService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  // ── Company context ──────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── PAYE Band Sets ───────────────────────────────────────────────────────
  readonly payeBandSets = signal<PayeBandSetDto[]>([]);
  readonly payeState = signal<'loading' | 'idle' | 'error' | 'forbidden'>('idle');
  readonly showPayeForm = signal(false);
  readonly pEffectiveFrom = signal('');
  readonly pTaxFreeThreshold = signal('');
  readonly pDescription = signal('');
  readonly pBands = signal<BandRow[]>([
    { bandNo: 1, lowerBound: '0', marginalRate: '0', cumulativeFixedTax: '0' },
  ]);
  readonly payeSaving = signal(false);
  readonly payeFormError = signal<string | null>(null);

  // ── Statutory Rate Sets ──────────────────────────────────────────────────
  readonly rateSets = signal<StatutoryRateSetDto[]>([]);
  readonly rateState = signal<'loading' | 'idle' | 'error' | 'forbidden'>('idle');
  readonly showRateForm = signal(false);
  readonly rRateType = signal<StatutoryRateType>('NSSF');
  readonly rEffectiveFrom = signal('');
  readonly rEmployeeRate = signal('');
  readonly rEmployerRate = signal('');
  readonly rBasis = signal('GROSS');
  readonly rCeiling = signal('');
  readonly rHeadcount = signal('');
  readonly rDescription = signal('');
  readonly rateSaving = signal(false);
  readonly rateFormError = signal<string | null>(null);

  readonly canManage = computed(() => this.session.hasPermission('HR.STATUTORY.MANAGE'));
  readonly rateTypes: StatutoryRateType[] = ['NSSF', 'WCF', 'SDL', 'HESLB'];

  constructor() {
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
              this.loadAll();
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
    if (id) this.loadAll();
  }

  private loadAll(): void {
    this.loadPayeBandSets();
    this.loadRateSets();
  }

  loadPayeBandSets(): void {
    const companyId = this.selectedCompanyId();
    if (!companyId) return;
    this.payeState.set('loading');
    this.statutoryService.listPayeBandSets(companyId).subscribe({
      next: (rows) => { this.payeBandSets.set(rows); this.payeState.set('idle'); },
      error: (err: unknown) =>
        this.payeState.set(err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error'),
    });
  }

  loadRateSets(): void {
    const companyId = this.selectedCompanyId();
    if (!companyId) return;
    this.rateState.set('loading');
    this.statutoryService.listRateSets(companyId).subscribe({
      next: (rows) => { this.rateSets.set(rows); this.rateState.set('idle'); },
      error: (err: unknown) =>
        this.rateState.set(err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error'),
    });
  }

  // ── PAYE band rows management ────────────────────────────────────────────

  addBand(): void {
    const bands = this.pBands();
    const nextNo = bands.length + 1;
    this.pBands.set([...bands, { bandNo: nextNo, lowerBound: '', marginalRate: '', cumulativeFixedTax: '' }]);
  }

  removeBand(i: number): void {
    const updated = this.pBands().filter((_, idx) => idx !== i)
      .map((b, idx) => ({ ...b, bandNo: idx + 1 }));
    this.pBands.set(updated);
  }

  updateBand(i: number, field: keyof BandRow, value: string): void {
    const updated = [...this.pBands()];
    updated[i] = { ...updated[i], [field]: field === 'bandNo' ? Number(value) : value };
    this.pBands.set(updated);
  }

  togglePayeForm(): void {
    this.showPayeForm.update((v) => !v);
    this.payeFormError.set(null);
    if (!this.showPayeForm()) this.resetPayeForm();
  }

  private resetPayeForm(): void {
    this.pEffectiveFrom.set('');
    this.pTaxFreeThreshold.set('');
    this.pDescription.set('');
    this.pBands.set([{ bandNo: 1, lowerBound: '0', marginalRate: '0', cumulativeFixedTax: '0' }]);
  }

  createPayeBandSet(): void {
    const effectiveFrom = this.pEffectiveFrom().trim();
    const threshold = this.pTaxFreeThreshold().trim();
    if (!effectiveFrom) { this.payeFormError.set('Effective date is required.'); return; }
    if (!threshold) { this.payeFormError.set('Tax-free threshold is required.'); return; }
    if (this.pBands().length === 0) { this.payeFormError.set('At least one band is required.'); return; }

    const bands: BandRequest[] = this.pBands().map((b) => ({
      bandNo: b.bandNo,
      lowerBound: b.lowerBound,
      marginalRate: b.marginalRate,
      cumulativeFixedTax: b.cumulativeFixedTax,
    }));

    this.payeSaving.set(true);
    this.payeFormError.set(null);

    // companyId is NOT in the DTO body — the backend derives it from the auth scope/context.
    const reqBody: CreatePayeBandSetRequest = {
      effectiveFrom,
      taxFreeThreshold: threshold,
      description: this.pDescription().trim() || undefined,
      bands,
    };

    this.statutoryService.createPayeBandSet(reqBody).subscribe({
      next: () => {
        this.payeSaving.set(false);
        this.resetPayeForm();
        this.showPayeForm.set(false);
        this.alerts.success('PAYE band set created');
        this.loadPayeBandSets();
      },
      error: (err: unknown) => {
        this.payeFormError.set(this.messageFrom(err, 'Could not create PAYE band set.'));
        this.payeSaving.set(false);
      },
    });
  }

  // ── Statutory Rate Sets ──────────────────────────────────────────────────

  toggleRateForm(): void {
    this.showRateForm.update((v) => !v);
    this.rateFormError.set(null);
    if (!this.showRateForm()) this.resetRateForm();
  }

  private resetRateForm(): void {
    this.rRateType.set('NSSF');
    this.rEffectiveFrom.set('');
    this.rEmployeeRate.set('');
    this.rEmployerRate.set('');
    this.rBasis.set('GROSS');
    this.rCeiling.set('');
    this.rHeadcount.set('');
    this.rDescription.set('');
  }

  createRateSet(): void {
    const effectiveFrom = this.rEffectiveFrom().trim();
    const basis = this.rBasis().trim();
    if (!effectiveFrom) { this.rateFormError.set('Effective date is required.'); return; }
    if (!basis) { this.rateFormError.set('Basis is required.'); return; }

    this.rateSaving.set(true);
    this.rateFormError.set(null);

    // companyId is NOT in the DTO body — the backend derives it from the auth scope/context.
    const req: CreateStatutoryRateSetRequest = {
      rateType: this.rRateType(),
      effectiveFrom,
      employeeRate: this.rEmployeeRate().trim() || undefined,
      employerRate: this.rEmployerRate().trim() || undefined,
      basis,
      ceilingAmount: this.rCeiling().trim() || undefined,
      headcountThreshold: this.rHeadcount().trim() ? Number(this.rHeadcount().trim()) : undefined,
      description: this.rDescription().trim() || undefined,
    };

    this.statutoryService.createRateSet(req).subscribe({
      next: () => {
        this.rateSaving.set(false);
        this.resetRateForm();
        this.showRateForm.set(false);
        this.alerts.success('Statutory rate set created');
        this.loadRateSets();
      },
      error: (err: unknown) => {
        this.rateFormError.set(this.messageFrom(err, 'Could not create rate set.'));
        this.rateSaving.set(false);
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
