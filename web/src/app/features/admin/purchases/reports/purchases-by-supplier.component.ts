import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { SessionStore } from '../../../../core/auth/session.store';
import { UidOption, UidPickerComponent } from '../../../../shared/uid-picker/uid-picker.component';
import { formatReportAddress, ReportCompanyHeaderDto } from '../../models/report-company-header.model';
import { ExportFormat } from '../../reporting/models/reporting.model';
import { downloadBlob } from '../../reporting/reporting.utils';
import {
  PeriodBranchFilter,
  PurchasesBySupplierDto,
  firstOfMonthIso,
  fmtMoney,
  todayIso,
} from './models/purchase-report.model';
import { PurchaseReportPickersService } from './purchase-report-pickers.service';
import { PurchaseReportService } from './purchase-report.service';
import { serverMessage } from './purchase-report.utils';

type LoadState = 'idle' | 'loading' | 'error' | 'forbidden';

/**
 * Purchases by Supplier — receipts, value received, returns and net purchases per supplier over a
 * period; plus billed and still-unpaid amounts for a user who may see supplier bills.
 *
 * The Returns / Net columns appear only with PURCHASE.RETURN.VIEW and the Billed / Unpaid columns
 * only with AP.VIEW — the server decides and says so (`returnsShown` / `billsShown`); the screen
 * never shows a withheld column as zero.
 *
 * Route: /admin/reports/purchases/by-supplier. Gated PURCHASE.GOODS_RECEIPT.VIEW; export also
 * needs REPORT.EXPORT.
 */
@Component({
  selector: 'app-purchases-by-supplier',
  imports: [FormsModule, DatePipe, UidPickerComponent],
  templateUrl: './purchases-by-supplier.component.html',
  styleUrl: './purchase-reports.scss',
})
export class PurchasesBySupplierComponent implements OnInit {
  private readonly reports = inject(PurchaseReportService);
  private readonly pickers = inject(PurchaseReportPickersService);
  protected readonly session = inject(SessionStore);

  protected readonly fmtMoney = fmtMoney;

  readonly fromDate = signal(firstOfMonthIso());
  readonly toDate = signal(todayIso());
  readonly branchUid = signal('');
  readonly branchOptions = signal<UidOption[]>([]);

  readonly report = signal<PurchasesBySupplierDto | null>(null);
  readonly state = signal<LoadState>('idle');
  readonly exporting = signal(false);
  readonly loadError = signal<string | null>(null);

  readonly canView = computed(() => this.session.hasPermission('PURCHASE.GOODS_RECEIPT.VIEW'));
  readonly canExport = computed(() => this.session.hasPermission('REPORT.EXPORT'));
  readonly isEmpty = computed(() => this.state() === 'idle' && this.report() === null);
  readonly datesValid = computed(() => {
    const from = String(this.fromDate() ?? '').trim();
    const to = String(this.toDate() ?? '').trim();
    return !!from && !!to && from <= to;
  });

  /** Columns in the table, so the empty row and the foot span exactly what is rendered. */
  readonly columnCount = computed(() => {
    const r = this.report();
    return 4 + (r?.returnsShown ? 2 : 0) + (r?.billsShown ? 2 : 0);
  });

  ngOnInit(): void {
    if (!this.canView()) return;
    this.pickers.branchOptions().subscribe((o) => this.branchOptions.set(o));
  }

  run(): void {
    if (!this.canView() || !this.datesValid()) return;
    this.state.set('loading');
    this.loadError.set(null);
    this.reports.bySupplier(this.currentFilter()).subscribe({
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
    this.reports.exportBySupplier(f, format).subscribe({
      next: (blob) => {
        downloadBlob(blob, `purchases-by-supplier_${f.fromDate}_${f.toDate}.${format.toLowerCase()}`);
        this.exporting.set(false);
      },
      error: () => this.exporting.set(false),
    });
  }

  private currentFilter(): PeriodBranchFilter {
    return {
      fromDate: String(this.fromDate() ?? '').trim(),
      toDate: String(this.toDate() ?? '').trim(),
      branchUid: this.branchUid() || null,
    };
  }

  addressLine(c: ReportCompanyHeaderDto): string {
    return formatReportAddress(c);
  }
}
