import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { Company } from '../models/company.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { FxService } from './fx.service';
import { CompanyCurrencyDto, CurrencyDto } from './models/fx.model';

/**
 * Currency-enablement admin screen (ADR-0039 D-7/D-8).
 *
 * Shows ALL global currencies in a table. For each row:
 *  - Whether the currency is currently enabled for the selected company.
 *  - Whether it is the default document currency.
 *  - Actions: Enable / Disable, Make Default (only for enabled rows).
 *
 * Gated on CURRENCY.MANAGE for write actions; CURRENCY.VIEW for reading the
 * enabled list.  Non-managers see the table read-only with actions hidden.
 *
 * Route: /admin/fx/currencies
 */
@Component({
  selector: 'app-currency-enablement-list',
  imports: [FormsModule],
  templateUrl: './currency-enablement-list.component.html',
})
export class CurrencyEnablementListComponent {
  private readonly fxService = inject(FxService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  // ── Company context ────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompany = signal<Company | null>(null);
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── Data ───────────────────────────────────────────────────────────────────
  /** Full global currency catalogue. */
  readonly allCurrencies = signal<CurrencyDto[]>([]);
  /** Active company_currency rows (enabled ones) for the selected company. */
  readonly enabledRows = signal<CompanyCurrencyDto[]>([]);
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('idle');

  // ── Action state ───────────────────────────────────────────────────────────
  /** Currency code currently being acted on (enable / disable / set-default). */
  readonly actingCode = signal<string | null>(null);
  readonly actionError = signal<string | null>(null);

  // ── Permissions ───────────────────────────────────────────────────────────
  readonly canManage = computed(() => this.session.hasPermission('CURRENCY.MANAGE'));

  // ── Derived helpers ────────────────────────────────────────────────────────
  /** Map from code → CompanyCurrencyDto for O(1) lookup in the template. */
  readonly enabledMap = computed<ReadonlyMap<string, CompanyCurrencyDto>>(() => {
    const m = new Map<string, CompanyCurrencyDto>();
    for (const row of this.enabledRows()) {
      m.set(row.currencyCode, row);
    }
    return m;
  });

  readonly isEmpty = computed(
    () => this.state() === 'idle' && this.allCurrencies().length === 0,
  );

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
              this.selectedCompany.set(list[0]);
              this.loadAll();
            }
          },
          error: () => this.companyState.set('error'),
        });
      },
      error: () => this.companyState.set('error'),
    });
  }

  onCompanyChange(uid: string): void {
    const match = this.companies().find((c) => c.uid === uid) ?? null;
    this.selectedCompany.set(match);
    this.actionError.set(null);
    if (match) this.loadAll();
  }

  /** Load the global catalogue and the company's enabled list in parallel. */
  loadAll(): void {
    const company = this.selectedCompany();
    if (!company) return;
    this.state.set('loading');
    this.actionError.set(null);

    // Load global catalogue
    this.fxService.listCurrencies().subscribe({
      next: (list) => {
        this.allCurrencies.set(list.filter((c) => c.active));
        this.loadEnabled(company.uid);
      },
      error: (err) => {
        this.state.set(
          err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error',
        );
      },
    });
  }

  private loadEnabled(companyUid: string): void {
    this.fxService.listEnabledForCompany(companyUid).subscribe({
      next: (rows) => {
        this.enabledRows.set(rows);
        this.state.set('idle');
      },
      error: (err) => {
        // 403 on the enabled-list endpoint means no CURRENCY.VIEW; treat gracefully.
        if (err instanceof HttpErrorResponse && err.status === 403) {
          this.enabledRows.set([]);
          this.state.set('idle');
        } else {
          this.state.set('error');
        }
      },
    });
  }

  // ── Actions ────────────────────────────────────────────────────────────────

  enable(code: string): void {
    const company = this.selectedCompany();
    if (!company || !this.canManage()) return;
    this.actingCode.set(code);
    this.actionError.set(null);

    this.fxService
      .enableForCompany(company.uid, { currencyCode: code, setAsDefault: false })
      .subscribe({
        next: (row) => {
          this.actingCode.set(null);
          this.enabledRows.update((rows) => {
            const idx = rows.findIndex((r) => r.currencyCode === code);
            if (idx >= 0) {
              const updated = [...rows];
              updated[idx] = row;
              return updated;
            }
            return [...rows, row];
          });
          this.alerts.success('Currency enabled', `${code} is now enabled for this company.`);
        },
        error: (err) => {
          this.actingCode.set(null);
          this.actionError.set(this.messageFrom(err, `Could not enable ${code}.`));
        },
      });
  }

  disable(code: string): void {
    const company = this.selectedCompany();
    if (!company || !this.canManage()) return;
    this.actingCode.set(code);
    this.actionError.set(null);

    this.fxService.deactivateForCompany(company.uid, code).subscribe({
      next: (row) => {
        this.actingCode.set(null);
        this.enabledRows.update((rows) => {
          const idx = rows.findIndex((r) => r.currencyCode === code);
          if (idx >= 0) {
            const updated = [...rows];
            updated[idx] = row;
            return updated;
          }
          return rows;
        });
        this.alerts.success('Currency disabled', `${code} has been disabled for this company.`);
      },
      error: (err) => {
        this.actingCode.set(null);
        this.actionError.set(this.messageFrom(err, `Could not disable ${code}.`));
      },
    });
  }

  setDefault(code: string): void {
    const company = this.selectedCompany();
    if (!company || !this.canManage()) return;
    this.actingCode.set(code);
    this.actionError.set(null);

    this.fxService.setDefaultForCompany(company.uid, code).subscribe({
      next: () => {
        this.actingCode.set(null);
        // Refresh the full enabled list so isDefault flags are accurate.
        this.loadEnabled(company.uid);
        this.alerts.success('Default set', `${code} is now the default document currency.`);
      },
      error: (err) => {
        this.actingCode.set(null);
        this.actionError.set(this.messageFrom(err, `Could not set ${code} as default.`));
      },
    });
  }

  // ── Helpers ────────────────────────────────────────────────────────────────

  isEnabled(code: string): boolean {
    const row = this.enabledMap().get(code);
    return row !== undefined && row.active;
  }

  isDefault(code: string): boolean {
    return this.enabledMap().get(code)?.isDefault === true;
  }

  isActing(code: string): boolean {
    return this.actingCode() === code;
  }

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}
