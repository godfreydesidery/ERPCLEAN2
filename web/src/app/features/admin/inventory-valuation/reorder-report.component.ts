import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Observable, map, of } from 'rxjs';
import { SessionStore } from '../../../core/auth/session.store';
import { UidOption, UidPickerComponent } from '../../../shared/uid-picker/uid-picker.component';
import { formatReportAddress, ReportCompanyHeaderDto } from '../models/report-company-header.model';
import { SupplierService } from '../parties/supplier.service';
import { ExportFormat } from '../reporting/models/reporting.model';
import {
  ReportFilterOptionsService,
  fmtMoneyOrDash,
  fmtQuantity,
  serverMessage,
  todayIso,
} from '../reporting/report-filter-options.service';
import { downloadBlob } from '../reporting/reporting.utils';
import { ReorderReportDto, ReorderReportFilter } from './models/reorder-report.model';
import { StockReorderAgeingService } from './stock-reorder-ageing.service';

type LoadState = 'idle' | 'loading' | 'error' | 'forbidden';

/** How many suppliers one search returns — a search, not a listing. */
const SUPPLIER_SEARCH_SIZE = 20;

/**
 * Reorder Report — items at or below their reorder level, with the shortfall, a suggested order
 * quantity and the preferred supplier. Filter to one supplier and it is that supplier's order list.
 *
 * Cost is a hidden COLUMN, not a refused screen: the storekeeper who reorders holds STOCK.VIEW but
 * not necessarily INVENTORY.VALUATION.VIEW. The server says which it did (`costVisible`); when
 * false the cost columns are left out entirely rather than shown blank, because blank reads as
 * "no cost on record".
 *
 * The supplier picker searches the server as you type (suppliers can run past any preload).
 *
 * Route: /admin/reports/reorder. Gated STOCK.VIEW; export additionally REPORT.EXPORT.
 */
@Component({
  selector: 'app-reorder-report',
  imports: [FormsModule, DatePipe, UidPickerComponent],
  templateUrl: './reorder-report.component.html',
  styleUrl: './reorder-report.component.scss',
})
export class ReorderReportComponent implements OnInit {
  private readonly api = inject(StockReorderAgeingService);
  private readonly options = inject(ReportFilterOptionsService);
  private readonly supplierService = inject(SupplierService);
  protected readonly session = inject(SessionStore);

  protected readonly fmtAmount = fmtMoneyOrDash;
  protected readonly fmtQty = fmtQuantity;

  readonly branchUid = signal('');
  readonly supplierUid = signal('');
  readonly branchOptions = signal<UidOption[]>([]);
  private readonly companyId = signal('');

  readonly report = signal<ReorderReportDto | null>(null);
  readonly state = signal<LoadState>('idle');
  readonly exporting = signal(false);
  readonly loadError = signal<string | null>(null);

  readonly canView = computed(() => this.session.hasPermission('STOCK.VIEW'));
  readonly canExport = computed(() => this.session.hasPermission('REPORT.EXPORT'));
  readonly isEmpty = computed(() => this.state() === 'idle' && this.report() === null);
  readonly costVisible = computed(() => this.report()?.costVisible === true);
  readonly columnCount = computed(() => (this.costVisible() ? 10 : 8));

  /** Server-side supplier lookup (arrow property so `this` survives the template binding). */
  readonly searchSuppliers = (q: string): Observable<readonly UidOption[]> => {
    if (!this.companyId()) return of([]);
    return this.supplierService.list(this.companyId(), q, 0, SUPPLIER_SEARCH_SIZE).pipe(
      map(({ rows }) =>
        rows
          .filter((s) => s.status === 'ACTIVE')
          .map((s) => ({ uid: s.uid, label: s.displayName, hint: s.code })),
      ),
    );
  };

  ngOnInit(): void {
    if (!this.canView()) return;
    const company$ = this.options.company();
    company$.subscribe((c) => this.companyId.set(c?.id ?? ''));
    this.options.branchOptions(company$).subscribe((opts) => this.branchOptions.set(opts));
    this.run();
  }

  run(): void {
    this.state.set('loading');
    this.loadError.set(null);
    this.api.reorder(this.currentFilter()).subscribe({
      next: (dto) => {
        this.report.set(dto);
        this.state.set('idle');
      },
      error: (err: unknown) => {
        this.report.set(null);
        const forbidden = err instanceof HttpErrorResponse && err.status === 403;
        this.loadError.set(
          serverMessage(err, forbidden ? '' : 'Could not build the Reorder Report. Please try again.'),
        );
        this.state.set(forbidden ? 'forbidden' : 'error');
      },
    });
  }

  export(format: ExportFormat): void {
    if (this.exporting()) return;
    this.exporting.set(true);
    this.api.exportReorder(this.currentFilter(), format).subscribe({
      next: (blob) => {
        downloadBlob(blob, `reorder_${todayIso()}.${format.toLowerCase()}`);
        this.exporting.set(false);
      },
      error: () => this.exporting.set(false),
    });
  }

  private currentFilter(): ReorderReportFilter {
    return { branchUid: this.branchUid() || null, supplierUid: this.supplierUid() || null };
  }

  addressLine(c: ReportCompanyHeaderDto): string {
    return formatReportAddress(c);
  }
}
