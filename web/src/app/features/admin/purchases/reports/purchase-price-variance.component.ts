import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { SessionStore } from '../../../../core/auth/session.store';
import { UidOption, UidPickerComponent } from '../../../../shared/uid-picker/uid-picker.component';
import { formatReportAddress, ReportCompanyHeaderDto } from '../../models/report-company-header.model';
import { ExportFormat } from '../../reporting/models/reporting.model';
import { downloadBlob } from '../../reporting/reporting.utils';
import {
  PeriodBranchSupplierFilter,
  PurchasePriceVarianceDto,
  firstOfMonthIso,
  fmtDay,
  fmtMoney,
  fmtPct,
  fmtQty,
  todayIso,
} from './models/purchase-report.model';
import { PurchaseReportPickersService } from './purchase-report-pickers.service';
import { PurchaseReportService } from './purchase-report.service';
import { serverMessage } from './purchase-report.utils';

type LoadState = 'idle' | 'loading' | 'error' | 'forbidden';

/**
 * Purchase Price Variance — received lines whose receipt cost, or the price on the supplier bill
 * claimed against them, differs from the purchase-order price.
 *
 * A placed order is frozen and a receipt takes its cost from the order, so the receipt column is
 * normally zero; the bill column is where real variance shows. Bill prices need AP.VIEW — without
 * it the server withholds them (`billsShown: false`) and the screen says so.
 *
 * Route: /admin/reports/purchases/price-variance. Gated PURCHASE.ORDER.VIEW AND
 * PURCHASE.GOODS_RECEIPT.VIEW; export also needs REPORT.EXPORT.
 */
@Component({
  selector: 'app-purchase-price-variance',
  imports: [FormsModule, DatePipe, RouterLink, UidPickerComponent],
  templateUrl: './purchase-price-variance.component.html',
  styleUrl: './purchase-reports.scss',
})
export class PurchasePriceVarianceComponent implements OnInit {
  private readonly reports = inject(PurchaseReportService);
  protected readonly pickers = inject(PurchaseReportPickersService);
  protected readonly session = inject(SessionStore);

  protected readonly fmtMoney = fmtMoney;
  protected readonly fmtQty = fmtQty;
  protected readonly fmtPct = fmtPct;
  protected readonly fmtDay = fmtDay;

  readonly fromDate = signal(firstOfMonthIso());
  readonly toDate = signal(todayIso());
  readonly branchUid = signal('');
  readonly supplierUid = signal('');
  readonly branchOptions = signal<UidOption[]>([]);
  readonly supplierOptions = signal<UidOption[]>([]);

  readonly report = signal<PurchasePriceVarianceDto | null>(null);
  readonly state = signal<LoadState>('idle');
  readonly exporting = signal(false);
  readonly loadError = signal<string | null>(null);

  readonly canView = computed(
    () =>
      this.session.hasPermission('PURCHASE.ORDER.VIEW') &&
      this.session.hasPermission('PURCHASE.GOODS_RECEIPT.VIEW'),
  );
  readonly canExport = computed(() => this.session.hasPermission('REPORT.EXPORT'));
  readonly isEmpty = computed(() => this.state() === 'idle' && this.report() === null);
  readonly datesValid = computed(() => {
    const from = String(this.fromDate() ?? '').trim();
    const to = String(this.toDate() ?? '').trim();
    return !!from && !!to && from <= to;
  });
  readonly columnCount = computed(() => (this.report()?.billsShown ? 13 : 9));

  ngOnInit(): void {
    if (!this.canView()) return;
    this.pickers.branchOptions().subscribe((o) => this.branchOptions.set(o));
    this.pickers.supplierSeed().subscribe((o) => this.supplierOptions.set(o));
  }

  run(): void {
    if (!this.canView() || !this.datesValid()) return;
    this.state.set('loading');
    this.loadError.set(null);
    this.reports.priceVariance(this.currentFilter()).subscribe({
      next: (dto) => {
        this.report.set(dto);
        this.state.set('idle');
      },
      error: (err: unknown) => {
        this.report.set(null);
        this.loadError.set(serverMessage(err));
        this.state.set(err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error');
      },
    });
  }

  export(format: ExportFormat): void {
    if (!this.canView() || !this.datesValid() || this.exporting()) return;
    const f = this.currentFilter();
    this.exporting.set(true);
    this.reports.exportPriceVariance(f, format).subscribe({
      next: (blob) => {
        downloadBlob(blob, `price-variance_${f.fromDate}_${f.toDate}.${format.toLowerCase()}`);
        this.exporting.set(false);
      },
      error: () => this.exporting.set(false),
    });
  }

  /** Positive = paid more than ordered: shown in the danger tone. */
  isOver(v: number | null | undefined): boolean {
    return v !== null && v !== undefined && +v > 0;
  }

  private currentFilter(): PeriodBranchSupplierFilter {
    return {
      fromDate: String(this.fromDate() ?? '').trim(),
      toDate: String(this.toDate() ?? '').trim(),
      branchUid: this.branchUid() || null,
      supplierUid: this.supplierUid() || null,
    };
  }

  addressLine(c: ReportCompanyHeaderDto): string {
    return formatReportAddress(c);
  }
}
