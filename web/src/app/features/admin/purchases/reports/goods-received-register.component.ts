import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { PageMeta } from '../../../../core/api/api-response.model';
import { SessionStore } from '../../../../core/auth/session.store';
import { PaginatorComponent } from '../../../../shared/paginator/paginator.component';
import { UidOption, UidPickerComponent } from '../../../../shared/uid-picker/uid-picker.component';
import { formatReportAddress, ReportCompanyHeaderDto } from '../../models/report-company-header.model';
import { ExportFormat } from '../../reporting/models/reporting.model';
import { downloadBlob } from '../../reporting/reporting.utils';
import {
  GoodsReceivedRegisterDto,
  GoodsReceivedRegisterFilter,
  firstOfMonthIso,
  fmtDay,
  fmtMoney,
  fmtQty,
  todayIso,
} from './models/purchase-report.model';
import { PurchaseReportPickersService } from './purchase-report-pickers.service';
import { PurchaseReportService } from './purchase-report.service';
import { serverMessage } from './purchase-report.utils';

type LoadState = 'idle' | 'loading' | 'error' | 'forbidden';

/**
 * Goods Received Register — every goods-receipt line over a period, with direct receipts.
 *
 * A receipt voided in the period appears again as a negative VOID line on the day it was voided
 * (the Stock Movement report's treatment), so a printed month never changes afterwards. Values are
 * the receipt's own cost, excluding VAT — a receipt stores no VAT.
 *
 * Route: /admin/reports/purchases/goods-received. Gated PURCHASE.GOODS_RECEIPT.VIEW (the same code
 * as the goods-receipt screens, which already show these costs); export also needs REPORT.EXPORT.
 */
@Component({
  selector: 'app-goods-received-register',
  imports: [FormsModule, DatePipe, RouterLink, UidPickerComponent, PaginatorComponent],
  templateUrl: './goods-received-register.component.html',
  styleUrl: './purchase-reports.scss',
})
export class GoodsReceivedRegisterComponent implements OnInit {
  private readonly reports = inject(PurchaseReportService);
  protected readonly pickers = inject(PurchaseReportPickersService);
  protected readonly session = inject(SessionStore);

  private static readonly PAGE_SIZE = 50;

  protected readonly fmtMoney = fmtMoney;
  protected readonly fmtQty = fmtQty;
  protected readonly fmtDay = fmtDay;

  readonly fromDate = signal(firstOfMonthIso());
  readonly toDate = signal(todayIso());
  readonly branchUid = signal('');
  readonly supplierUid = signal('');
  readonly productUid = signal('');

  readonly branchOptions = signal<UidOption[]>([]);
  readonly supplierOptions = signal<UidOption[]>([]);
  readonly productOptions = signal<UidOption[]>([]);

  readonly report = signal<GoodsReceivedRegisterDto | null>(null);
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

  readonly meta = computed<PageMeta | null>(() => {
    const r = this.report();
    if (!r) return null;
    return {
      page: r.page,
      size: r.size,
      totalElements: r.totalElements,
      totalPages: r.totalPages,
      hasNext: r.page < r.totalPages - 1,
    };
  });

  ngOnInit(): void {
    if (!this.canView()) return;
    this.pickers.branchOptions().subscribe((o) => this.branchOptions.set(o));
    this.pickers.supplierSeed().subscribe((o) => this.supplierOptions.set(o));
    this.pickers.productSeed().subscribe((o) => this.productOptions.set(o));
  }

  run(): void {
    this.load(0);
  }

  goToPage(page: number): void {
    this.load(page);
  }

  private load(page: number): void {
    if (!this.canView() || !this.datesValid()) return;
    this.state.set('loading');
    this.loadError.set(null);
    this.reports
      .goodsReceived(this.currentFilter(), page, GoodsReceivedRegisterComponent.PAGE_SIZE)
      .subscribe({
        next: (dto) => {
          this.report.set(dto);
          this.state.set('idle');
        },
        error: (err: unknown) => {
          this.report.set(null);
          this.loadError.set(serverMessage(err));
          this.state.set(
            err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error',
          );
        },
      });
  }

  export(format: ExportFormat): void {
    if (!this.canView() || !this.datesValid() || this.exporting()) return;
    const f = this.currentFilter();
    this.exporting.set(true);
    this.reports.exportGoodsReceived(f, format).subscribe({
      next: (blob) => {
        downloadBlob(blob, `goods-received_${f.fromDate}_${f.toDate}.${format.toLowerCase()}`);
        this.exporting.set(false);
      },
      error: () => this.exporting.set(false),
    });
  }

  private currentFilter(): GoodsReceivedRegisterFilter {
    return {
      fromDate: String(this.fromDate() ?? '').trim(),
      toDate: String(this.toDate() ?? '').trim(),
      branchUid: this.branchUid() || null,
      supplierUid: this.supplierUid() || null,
      productUid: this.productUid() || null,
    };
  }

  addressLine(c: ReportCompanyHeaderDto): string {
    return formatReportAddress(c);
  }
}

