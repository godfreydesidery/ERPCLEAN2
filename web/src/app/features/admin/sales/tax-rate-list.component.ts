import { HttpErrorResponse } from '@angular/common/http';
import { DecimalPipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { Company } from '../models/company.model';
import { TaxRateDto, UpdateTaxRateRequest, VatStatus } from '../models/sales.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { SalesService } from './sales.service';

/**
 * Tax Rate admin screen. Lists the 3 VAT bands for the selected company.
 * Supports inline rate edit (PUT).
 * Gated by TAXRATE.VIEW; management actions require TAXRATE.MANAGE.
 * Mirrors units-of-measure-list.component exactly.
 */
@Component({
  selector: 'app-tax-rate-list',
  imports: [FormsModule, DecimalPipe],
  templateUrl: './tax-rate-list.component.html',
  styleUrl: './tax-rate-list.component.scss',
})
export class TaxRateListComponent {
  private readonly salesService = inject(SalesService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  // ── Company context ────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── List state ─────────────────────────────────────────────────────────────
  readonly rows = signal<TaxRateDto[]>([]);
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('idle');

  // ── Inline edit ────────────────────────────────────────────────────────────
  readonly editingUid = signal<string | null>(null);
  readonly editRate = signal('');
  readonly editSaving = signal(false);
  readonly editError = signal<string | null>(null);

  readonly canManage = computed(() => this.session.hasPermission('TAXRATE.MANAGE'));
  readonly isEmpty = computed(() => this.state() === 'idle' && this.rows().length === 0);

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
              this.load();
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
    if (id) this.load();
  }

  load(): void {
    const companyId = this.selectedCompanyId();
    if (!companyId) return;
    this.state.set('loading');
    this.salesService.listTaxRates(companyId).subscribe({
      next: (rows) => {
        this.rows.set(rows);
        this.state.set('idle');
      },
      error: (err) =>
        this.state.set(err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error'),
    });
  }

  // ── Inline edit ────────────────────────────────────────────────────────────

  /** Convert the stored fraction (e.g. "0.1800") to a display percentage number (18). */
  ratePercent(fraction: string): number {
    return (+fraction || 0) * 100;
  }

  startEdit(tr: TaxRateDto): void {
    this.editingUid.set(tr.uid);
    // The field is a percentage; prefill from the stored fraction (0.18 → "18").
    this.editRate.set(String(Number(this.ratePercent(tr.rate).toFixed(4))));
    this.editError.set(null);
  }

  cancelEdit(): void {
    this.editingUid.set(null);
    this.editError.set(null);
  }

  saveEdit(tr: TaxRateDto): void {
    const entered = this.editRate().trim();
    const pct = Number(entered);
    if (!entered || Number.isNaN(pct)) {
      this.editError.set('Enter the rate as a percentage (e.g. 18).');
      return;
    }
    // Backend stores a fraction in [0, 0.9999]; a percentage maps to [0, 99.99].
    if (pct < 0 || pct > 99.99) {
      this.editError.set('Rate must be between 0 and 99.99%.');
      return;
    }
    this.editSaving.set(true);
    this.editError.set(null);
    const request: UpdateTaxRateRequest = { rate: (pct / 100).toFixed(4) };
    this.salesService.updateTaxRate(tr.uid, request).subscribe({
      next: (updated) => {
        this.editSaving.set(false);
        this.editingUid.set(null);
        this.rows.update((rows) => rows.map((r) => (r.uid === updated.uid ? updated : r)));
        this.alerts.success('Tax rate updated');
      },
      error: (err) => {
        this.editError.set(this.messageFrom(err, 'Could not update tax rate.'));
        this.editSaving.set(false);
      },
    });
  }

  // ── Display helpers ────────────────────────────────────────────────────────

  vatStatusLabel(status: VatStatus): string {
    if (status === 'STANDARD') return 'Standard';
    if (status === 'ZERO_RATED') return 'Zero Rated';
    return 'Exempt';
  }

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}
