import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { Company } from '../models/company.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { WhtRegisterDto } from './models/tax.model';
import { TaxService } from './tax.service';
import { todayLocal } from '../../../shared/date.util';
import { CashbankService } from '../cashbank/cashbank.service';
import { CashAccountOptionDto } from '../cashbank/models/cashbank.model';
import { ExportFormat } from '../reporting/models/reporting.model';
import { downloadBlob } from '../reporting/reporting.utils';
import { exportErrorMessage } from '../reporting/ledger-export.util';

type WhtPeriod = { year: number; month: number } | { periodStart: string; periodEnd: string };

/**
 * WHT Register screen (FR-WHT-04 / US-VAT-06).
 * Gated WHT.VIEW.
 *
 * Period selector: year+month OR explicit date range.
 * Two groups: WHT Payable to TRA (WHT_ON_PAYMENT) and WHT Receivable (WHT_ON_RECEIPT).
 * Rows: certificate number, party, source ref, taxable base, WHT amount, date.
 * Group totals at the bottom of each section.
 */
@Component({
  selector: 'app-wht-register',
  imports: [FormsModule],
  templateUrl: './wht-register.component.html',
  styleUrl: './wht-register.component.scss',
})
export class WhtRegisterComponent {
  private readonly taxService = inject(TaxService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly _alerts = inject(AlertService);
  private readonly cashbankService = inject(CashbankService);
  protected readonly session = inject(SessionStore);

  // ── Company context ────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── Period selector ────────────────────────────────────────────────────────
  /** 'month' = year+month; 'range' = explicit dates */
  readonly periodMode = signal<'month' | 'range'>('month');
  readonly periodYear = signal(new Date().getFullYear());
  readonly periodMonth = signal(new Date().getMonth() + 1);
  readonly periodStart = signal('');
  readonly periodEnd = signal('');

  // ── Register ───────────────────────────────────────────────────────────────
  readonly register = signal<WhtRegisterDto | null>(null);
  readonly state = signal<'idle' | 'loading' | 'error' | 'forbidden'>('idle');

  // ── Permissions ───────────────────────────────────────────────────────────
  readonly canView = computed(() => this.session.hasPermission('WHT.VIEW'));
  /** The export endpoint also requires REPORT.EXPORT server-side — distinct from the view code. */
  readonly canExport = computed(() => this.session.hasPermission('REPORT.EXPORT'));

  // ── Export ─────────────────────────────────────────────────────────────────
  /** The company + period the register on screen was loaded for, so the export matches it. */
  private readonly loadedFor = signal<{ companyId: string; period: WhtPeriod } | null>(null);
  readonly exporting = signal(false);
  readonly exportError = signal<string | null>(null);

  readonly months = [
    { value: 1, label: 'January' }, { value: 2, label: 'February' }, { value: 3, label: 'March' },
    { value: 4, label: 'April' }, { value: 5, label: 'May' }, { value: 6, label: 'June' },
    { value: 7, label: 'July' }, { value: 8, label: 'August' }, { value: 9, label: 'September' },
    { value: 10, label: 'October' }, { value: 11, label: 'November' }, { value: 12, label: 'December' },
  ];

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
    this.register.set(null);
  }

  load(): void {
    const companyId = this.selectedCompanyId();
    if (!companyId) return;

    this.state.set('loading');
    this.register.set(null);

    const period: WhtPeriod = this.periodMode() === 'month'
      ? { year: this.periodYear(), month: this.periodMonth() }
      : {
          periodStart: String(this.periodStart() ?? '').trim(),
          periodEnd: String(this.periodEnd() ?? '').trim(),
        };
    const obs = 'year' in period
      ? this.taxService.getWhtRegisterByMonth(companyId, period.year, period.month)
      : this.taxService.getWhtRegisterByRange(companyId, period.periodStart, period.periodEnd);

    obs.subscribe({
      next: (reg) => {
        this.register.set(reg);
        this.loadedFor.set({ companyId, period });
        this.exportError.set(null);
        this.state.set('idle');
      },
      error: (err) =>
        this.state.set(err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error'),
    });
  }

  /** Download the register exactly as loaded on screen (same company, same period). */
  exportRegister(format: ExportFormat): void {
    const loaded = this.loadedFor();
    if (!loaded || this.exporting()) return;
    this.exporting.set(true);
    this.exportError.set(null);
    this.taxService.exportWhtRegister(loaded.companyId, loaded.period, format).subscribe({
      next: (blob) => {
        const reg = this.register();
        const suffix = reg ? `${reg.periodStart}_${reg.periodEnd}` : 'period';
        downloadBlob(blob, `wht-register_${suffix}.${format.toLowerCase()}`);
        this.exporting.set(false);
      },
      error: (err) => {
        this.exportError.set(exportErrorMessage(err));
        this.exporting.set(false);
      },
    });
  }

  // ── Record payment to TRA (ACC-07) ─────────────────────────────────────────
  /** Remitting / paying WHT is WHT.REMIT. */
  readonly canPay = computed(() => this.session.hasPermission('WHT.REMIT'));
  private readonly unpaidRows = computed(() =>
    (this.register()?.payableRows ?? []).filter((r) => !r.remitted));
  readonly unpaidCount = computed(() => this.unpaidRows().length);
  readonly unpaidTotal = computed(() =>
    this.unpaidRows().reduce((sum, r) => sum + +(r.whtAmount ?? 0), 0));
  readonly showPayForm = signal(false);
  readonly paying = signal(false);
  readonly payError = signal<string | null>(null);
  readonly cashAccounts = signal<CashAccountOptionDto[]>([]);
  readonly payAccountUid = signal('');
  readonly payDate = signal(todayLocal());
  readonly payRef = signal('');

  openPayForm(): void {
    const loaded = this.loadedFor();
    if (!loaded) return;
    this.showPayForm.set(true);
    this.payError.set(null);
    this.payRef.set('');
    this.cashbankService.listAccountOptions(loaded.companyId).subscribe({
      next: (list) => {
        this.cashAccounts.set(list ?? []);
        const preferred = list.find((a) => a.accountType === 'BANK' && a.isDefault)
          ?? list.find((a) => a.isDefault);
        if (preferred && !this.payAccountUid()) this.payAccountUid.set(preferred.uid);
      },
      error: () => this.payError.set('Could not load the cash and bank accounts.'),
    });
  }

  submitPayment(): void {
    const loaded = this.loadedFor();
    const reg = this.register();
    if (!loaded || !reg) return;
    const ref = String(this.payRef() ?? '').trim();
    if (!this.payAccountUid()) { this.payError.set('Choose the account the payment was made from.'); return; }
    if (!this.payDate()) { this.payError.set('Payment date is required.'); return; }
    if (!ref) { this.payError.set('Enter the TRA payment reference.'); return; }

    this.paying.set(true);
    this.payError.set(null);
    this.taxService.payWhtPeriod({
      companyId: loaded.companyId,
      periodStart: reg.periodStart,
      periodEnd: reg.periodEnd,
      cashBankAccountUid: this.payAccountUid(),
      paymentDate: this.payDate(),
      remittanceRef: ref,
    }).subscribe({
      next: (res) => {
        this.paying.set(false);
        this.showPayForm.set(false);
        this._alerts.success('WHT payment recorded',
          `${res.certificatesRemitted} certificate(s), ${this.fmtMoney(res.amountPaid)}`);
        this.load();
      },
      error: (err) => {
        this.paying.set(false);
        const errors = err instanceof HttpErrorResponse
          ? (err.error as { errors?: string[] })?.errors : undefined;
        this.payError.set(errors?.[0] ?? 'Could not record the payment.');
      },
    });
  }

  // ── Display helpers ────────────────────────────────────────────────────────

  fmtMoney(v: number | string | null | undefined): string {
    const n = +(v ?? 0);
    return Number.isFinite(n) ? n.toFixed(2) : '0.00';
  }
}
