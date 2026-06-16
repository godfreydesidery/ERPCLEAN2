import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { Company } from '../models/company.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { FxService } from './fx.service';
import {
  CurrencyDto,
  CurrencyRateDto,
  UpsertRateRequest,
} from './models/fx.model';
import { PageMeta } from '../../../core/api/api-response.model';
import { PaginatorComponent } from '../../../shared/paginator/paginator.component';

/**
 * Currency-rate maintenance screen (ADR-0036 T5).
 * - Lists effective-dated rates for the selected company, paginated.
 * - Inline "New rate" form gated by CURRENCY.MANAGE.
 * - No edit-in-place: a correction is always a new effective-dated row.
 * Route: /admin/fx/rates
 */
@Component({
  selector: 'app-fx-rate-list',
  imports: [FormsModule, PaginatorComponent],
  templateUrl: './fx-rate-list.component.html',
  styleUrl: './fx-rate-list.component.scss',
})
export class FxRateListComponent {
  private readonly fxService = inject(FxService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  // ── Company context ────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── Currencies (for from/to pickers) ──────────────────────────────────────
  readonly currencies = signal<CurrencyDto[]>([]);

  // ── List ───────────────────────────────────────────────────────────────────
  readonly rows = signal<CurrencyRateDto[]>([]);
  readonly meta = signal<PageMeta>({ page: 0, size: 50, totalElements: 0, totalPages: 0, hasNext: false });
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('idle');
  readonly currentPage = signal(0);

  readonly isEmpty = computed(() => this.state() === 'idle' && this.rows().length === 0);

  // ── Create form ────────────────────────────────────────────────────────────
  readonly showCreateForm = signal(false);
  readonly creating = signal(false);
  readonly createError = signal<string | null>(null);
  readonly newFromCurrency = signal('');
  readonly newToCurrency = signal('');
  readonly newRate = signal('');
  readonly newEffectiveDate = signal('');
  readonly newRateType = signal('');
  readonly newSource = signal('');

  // ── Permissions ───────────────────────────────────────────────────────────
  readonly canManage = computed(() => this.session.hasPermission('CURRENCY.MANAGE'));

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
              this.loadCurrencies();
              this.load();
            }
          },
          error: () => this.companyState.set('error'),
        });
      },
      error: () => this.companyState.set('error'),
    });
  }

  private loadCurrencies(): void {
    this.fxService.listCurrencies().subscribe({
      next: (list) => this.currencies.set(list),
      error: () => { /* non-fatal: pickers remain empty */ },
    });
  }

  onCompanyChange(id: string): void {
    this.selectedCompanyId.set(id);
    this.currentPage.set(0);
    if (id) this.load();
  }

  load(page = this.currentPage()): void {
    const companyId = this.selectedCompanyId();
    if (!companyId) return;
    this.state.set('loading');
    this.fxService.listRates(companyId, page).subscribe({
      next: ({ rows, meta }) => {
        this.rows.set(rows);
        this.meta.set(meta);
        this.currentPage.set(page);
        this.state.set('idle');
      },
      error: (err) =>
        this.state.set(err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error'),
    });
  }

  goToPage(page: number): void { this.load(page); }

  prevPage(): void {
    const p = this.currentPage();
    if (p > 0) this.load(p - 1);
  }

  nextPage(): void {
    if (this.meta().hasNext) this.load(this.currentPage() + 1);
  }

  // ── Create ─────────────────────────────────────────────────────────────────

  openCreate(): void {
    this.showCreateForm.set(true);
    this.createError.set(null);
    this.newFromCurrency.set('');
    this.newToCurrency.set('');
    this.newRate.set('');
    this.newEffectiveDate.set('');
    this.newRateType.set('');
    this.newSource.set('');
  }

  cancelCreate(): void {
    this.showCreateForm.set(false);
    this.createError.set(null);
  }

  submitCreate(): void {
    const fromCurrency = String(this.newFromCurrency() ?? '').trim().toUpperCase();
    const toCurrency = String(this.newToCurrency() ?? '').trim().toUpperCase();
    const rateStr = String(this.newRate() ?? '').trim();
    const effectiveDate = String(this.newEffectiveDate() ?? '').trim();

    if (!fromCurrency || fromCurrency.length !== 3) {
      this.createError.set('From-currency must be a 3-letter ISO code.');
      return;
    }
    if (!toCurrency || toCurrency.length !== 3) {
      this.createError.set('To-currency must be a 3-letter ISO code.');
      return;
    }
    if (fromCurrency === toCurrency) {
      this.createError.set('From and To currencies must differ.');
      return;
    }
    if (!rateStr || +rateStr <= 0) {
      this.createError.set('Rate must be a positive number.');
      return;
    }
    if (!effectiveDate) {
      this.createError.set('Effective date is required.');
      return;
    }

    const request: UpsertRateRequest = {
      companyId: this.selectedCompanyId(),
      fromCurrency,
      toCurrency,
      rate: rateStr,
      effectiveDate,
      rateType: this.newRateType() || null,
      source: this.newSource() || null,
    };

    this.creating.set(true);
    this.createError.set(null);

    this.fxService.addRate(request).subscribe({
      next: (rate) => {
        this.creating.set(false);
        this.showCreateForm.set(false);
        this.alerts.success('Rate added', `${rate.fromCurrency}/${rate.toCurrency} @ ${rate.rate}`);
        this.load(0);
      },
      error: (err) => {
        this.createError.set(this.messageFrom(err, 'Could not add rate.'));
        this.creating.set(false);
      },
    });
  }

  // ── Display helpers ────────────────────────────────────────────────────────

  fmtRate(v: string | number | null | undefined): string {
    const n = +(v ?? 0);
    return Number.isFinite(n) ? n.toFixed(6) : '—';
  }

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}
