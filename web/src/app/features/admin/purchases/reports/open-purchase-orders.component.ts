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
  OpenPurchaseOrdersDto,
  OpenPurchaseOrdersFilter,
  fmtMoney,
  fmtQty,
  todayIso,
} from './models/purchase-report.model';
import { PurchaseReportPickersService } from './purchase-report-pickers.service';
import { PurchaseReportService } from './purchase-report.service';
import { serverMessage } from './purchase-report.utils';

type LoadState = 'idle' | 'loading' | 'error' | 'forbidden';

/**
 * Open Purchase Orders — placed (and therefore approved) orders still waiting for goods, line by
 * line, as at a date: ordered, received, outstanding quantity and value, and age.
 *
 * "As at" really is the past: a receipt, void or close after that date is treated as not having
 * happened yet. Orders raised automatically behind a "Receive Without Order" receipt never appear.
 *
 * Route: /admin/reports/purchases/open-orders. Gated PURCHASE.ORDER.VIEW (the purchase-order
 * screens' code); export also needs REPORT.EXPORT. Runs on open, as at today.
 */
@Component({
  selector: 'app-open-purchase-orders',
  imports: [FormsModule, DatePipe, RouterLink, UidPickerComponent],
  templateUrl: './open-purchase-orders.component.html',
  styleUrl: './purchase-reports.scss',
})
export class OpenPurchaseOrdersComponent implements OnInit {
  private readonly reports = inject(PurchaseReportService);
  protected readonly pickers = inject(PurchaseReportPickersService);
  protected readonly session = inject(SessionStore);

  protected readonly fmtMoney = fmtMoney;
  protected readonly fmtQty = fmtQty;

  readonly asOfDate = signal(todayIso());
  readonly branchUid = signal('');
  readonly supplierUid = signal('');
  readonly branchOptions = signal<UidOption[]>([]);
  readonly supplierOptions = signal<UidOption[]>([]);

  readonly report = signal<OpenPurchaseOrdersDto | null>(null);
  readonly state = signal<LoadState>('idle');
  readonly exporting = signal(false);
  readonly loadError = signal<string | null>(null);

  readonly canView = computed(() => this.session.hasPermission('PURCHASE.ORDER.VIEW'));
  readonly canExport = computed(() => this.session.hasPermission('REPORT.EXPORT'));
  readonly isEmpty = computed(() => this.state() === 'idle' && this.report() === null);
  readonly overdueCount = computed(() => this.report()?.rows.filter((r) => r.overdue).length ?? 0);

  ngOnInit(): void {
    if (!this.canView()) return;
    this.pickers.branchOptions().subscribe((o) => this.branchOptions.set(o));
    this.pickers.supplierSeed().subscribe((o) => this.supplierOptions.set(o));
    this.run();
  }

  run(): void {
    if (!this.canView()) return;
    this.state.set('loading');
    this.loadError.set(null);
    this.reports.openOrders(this.currentFilter()).subscribe({
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
    if (!this.canView() || this.exporting()) return;
    const f = this.currentFilter();
    this.exporting.set(true);
    this.reports.exportOpenOrders(f, format).subscribe({
      next: (blob) => {
        downloadBlob(blob, `open-purchase-orders_${f.asOfDate ?? 'today'}.${format.toLowerCase()}`);
        this.exporting.set(false);
      },
      error: () => this.exporting.set(false),
    });
  }

  private currentFilter(): OpenPurchaseOrdersFilter {
    return {
      // Blank means "today" — the server reads today in the company's own time zone.
      asOfDate: String(this.asOfDate() ?? '').trim() || null,
      branchUid: this.branchUid() || null,
      supplierUid: this.supplierUid() || null,
    };
  }

  addressLine(c: ReportCompanyHeaderDto): string {
    return formatReportAddress(c);
  }
}
