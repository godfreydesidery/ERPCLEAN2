import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { SessionStore } from '../../../core/auth/session.store';
import { UidOption, UidPickerComponent } from '../../../shared/uid-picker/uid-picker.component';
import { formatReportAddress, ReportCompanyHeaderDto } from '../models/report-company-header.model';
import { PaymentSummaryFilter, PaymentSummaryReportDto } from './models/payment-summary.model';
import { ExportFormat } from './models/reporting.model';
import {
  ReportFilterOptionsService,
  fmtMoneyOrDash,
  serverMessage,
  todayIso,
} from './report-filter-options.service';
import { downloadBlob } from './reporting.utils';
import { SalesSummaryReportsService } from './sales-summary-reports.service';

type LoadState = 'idle' | 'loading' | 'error' | 'forbidden';

/**
 * Daily cash-up / Payment Summary — money taken for finalised sales, per day, cashier and payment
 * method (cash, mobile money, card, cheque), with a total per currency.
 *
 * Opens on TODAY, because the question is usually "what did we take today". Every amount is net of
 * change handed back — the same rule the till's X/Z-read uses — so the cash column is what the
 * drawers should hold for those sales (before float and payouts, which this report does not cover).
 *
 * The cashier picker is filled from the report itself (everyone who took a payment in the period):
 * the full user list needs USER.VIEW, which a cash-up reader should not need.
 *
 * Route: /admin/reports/payment-summary. Gated POS.CASHUP.VIEW (managers); export
 * additionally REPORT.EXPORT.
 */
@Component({
  selector: 'app-payment-summary-report',
  imports: [FormsModule, DatePipe, UidPickerComponent],
  templateUrl: './payment-summary-report.component.html',
  styleUrl: './payment-summary-report.component.scss',
})
export class PaymentSummaryReportComponent implements OnInit {
  private readonly api = inject(SalesSummaryReportsService);
  private readonly options = inject(ReportFilterOptionsService);
  protected readonly session = inject(SessionStore);

  protected readonly fmtAmount = fmtMoneyOrDash;

  readonly fromDate = signal(todayIso());
  readonly toDate = signal(todayIso());
  readonly branchUid = signal('');
  readonly cashierUid = signal('');
  readonly branchOptions = signal<UidOption[]>([]);
  readonly cashierOptions = signal<UidOption[]>([]);

  readonly report = signal<PaymentSummaryReportDto | null>(null);
  readonly state = signal<LoadState>('idle');
  readonly exporting = signal(false);
  readonly loadError = signal<string | null>(null);

  readonly canView = computed(() => this.session.hasPermission('POS.CASHUP.VIEW'));
  readonly canExport = computed(() => this.session.hasPermission('REPORT.EXPORT'));
  readonly isEmpty = computed(() => this.state() === 'idle' && this.report() === null);

  readonly datesValid = computed(() => {
    const from = String(this.fromDate() ?? '').trim();
    const to = String(this.toDate() ?? '').trim();
    return !!from && !!to && from <= to;
  });

  ngOnInit(): void {
    if (this.canView()) {
      this.options.branchOptions().subscribe((opts) => this.branchOptions.set(opts));
      this.run();
    }
  }

  run(): void {
    if (!this.datesValid()) return;
    this.state.set('loading');
    this.loadError.set(null);
    this.api.paymentSummary(this.currentFilter()).subscribe({
      next: (dto) => {
        this.report.set(dto);
        this.cashierOptions.set(dto.cashiers.map((c) => ({ uid: c.uid, label: c.name })));
        this.state.set('idle');
      },
      error: (err: unknown) => {
        this.report.set(null);
        const forbidden = err instanceof HttpErrorResponse && err.status === 403;
        this.loadError.set(
          serverMessage(err, forbidden ? '' : 'Could not build the Payment Summary. Please try again.'),
        );
        this.state.set(forbidden ? 'forbidden' : 'error');
      },
    });
  }

  export(format: ExportFormat): void {
    if (!this.datesValid() || this.exporting()) return;
    const f = this.currentFilter();
    this.exporting.set(true);
    this.api.exportPaymentSummary(f, format).subscribe({
      next: (blob) => {
        downloadBlob(blob, `payment-summary_${f.fromDate}_${f.toDate}.${format.toLowerCase()}`);
        this.exporting.set(false);
      },
      error: () => this.exporting.set(false),
    });
  }

  private currentFilter(): PaymentSummaryFilter {
    return {
      fromDate: String(this.fromDate() ?? '').trim(),
      toDate: String(this.toDate() ?? '').trim(),
      branchUid: this.branchUid() || null,
      cashierUid: this.cashierUid() || null,
    };
  }

  addressLine(c: ReportCompanyHeaderDto): string {
    return formatReportAddress(c);
  }
}
