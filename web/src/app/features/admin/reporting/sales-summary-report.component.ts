import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { SessionStore } from '../../../core/auth/session.store';
import { UidOption, UidPickerComponent } from '../../../shared/uid-picker/uid-picker.component';
import { formatReportAddress, ReportCompanyHeaderDto } from '../models/report-company-header.model';
import { ExportFormat } from './models/reporting.model';
import {
  SALES_SUMMARY_GROUPINGS,
  SalesSummaryFilter,
  SalesSummaryGroupBy,
  SalesSummaryReportDto,
} from './models/sales-summary.model';
import {
  ReportFilterOptionsService,
  firstOfMonthIso,
  fmtMoneyOrDash,
  fmtPercentOrDash,
  fmtQuantity,
  serverMessage,
  todayIso,
} from './report-filter-options.service';
import { downloadBlob } from './reporting.utils';
import { SalesSummaryReportsService } from './sales-summary-reports.service';

type LoadState = 'idle' | 'loading' | 'error' | 'forbidden';

/**
 * Sales Summary — finalised sales over a period, one row per customer, agent, route, branch, day
 * or cashier, with cost of sales and margin. One screen behind five questions people ask by name:
 * sales by customer, agent performance, sales by route, daily sales, cashier sales.
 *
 * A dash in cost / margin means UNKNOWN (stock sold before it was ever costed), never zero — 0.00
 * there would report the whole sale as profit. The partial-total banner says how many groups were
 * left out of the margin foot.
 *
 * `?groupBy=AGENT` (etc.) preselects the grouping, so a link can open straight onto one question.
 *
 * Route: /admin/reports/sales-summary. Gated SALES.INVOICE.VIEW; export additionally REPORT.EXPORT.
 */
@Component({
  selector: 'app-sales-summary-report',
  imports: [FormsModule, DatePipe, UidPickerComponent],
  templateUrl: './sales-summary-report.component.html',
  styleUrl: './sales-summary-report.component.scss',
})
export class SalesSummaryReportComponent implements OnInit {
  private readonly api = inject(SalesSummaryReportsService);
  private readonly options = inject(ReportFilterOptionsService);
  private readonly route = inject(ActivatedRoute);
  protected readonly session = inject(SessionStore);

  protected readonly groupings = SALES_SUMMARY_GROUPINGS;
  protected readonly fmtAmount = fmtMoneyOrDash;
  protected readonly fmtQty = fmtQuantity;
  protected readonly fmtPct = fmtPercentOrDash;

  readonly fromDate = signal(firstOfMonthIso());
  readonly toDate = signal(todayIso());
  readonly groupBy = signal<SalesSummaryGroupBy>('CUSTOMER');
  readonly branchUid = signal('');
  readonly branchOptions = signal<UidOption[]>([]);

  readonly report = signal<SalesSummaryReportDto | null>(null);
  readonly state = signal<LoadState>('idle');
  readonly exporting = signal(false);
  readonly loadError = signal<string | null>(null);

  readonly canView = computed(() => this.session.hasPermission('SALES.INVOICE.VIEW'));
  readonly canExport = computed(() => this.session.hasPermission('REPORT.EXPORT'));
  readonly isEmpty = computed(() => this.state() === 'idle' && this.report() === null);

  readonly datesValid = computed(() => {
    const from = String(this.fromDate() ?? '').trim();
    const to = String(this.toDate() ?? '').trim();
    return !!from && !!to && from <= to;
  });

  /** Column heading for the group — follows the report on screen, not the unsent selector. */
  readonly groupColumn = computed(() => {
    const by = this.report()?.groupBy ?? this.groupBy();
    return this.groupings.find((g) => g.value === by)?.column ?? 'Group';
  });

  /** True when the margin foot leaves some groups out — it must say so, or it overstates. */
  readonly costIncomplete = computed(() => (this.report()?.totals.groupsWithUnknownCost ?? 0) > 0);

  ngOnInit(): void {
    const requested = this.route.snapshot.queryParamMap.get('groupBy');
    if (requested && this.groupings.some((g) => g.value === requested)) {
      this.groupBy.set(requested as SalesSummaryGroupBy);
    }
    if (this.canView()) {
      this.options.branchOptions().subscribe((opts) => this.branchOptions.set(opts));
      this.run();
    }
  }

  run(): void {
    if (!this.datesValid()) return;
    this.state.set('loading');
    this.loadError.set(null);
    this.api.salesSummary(this.currentFilter()).subscribe({
      next: (dto) => {
        this.report.set(dto);
        this.state.set('idle');
      },
      error: (err: unknown) => {
        this.report.set(null);
        const forbidden = err instanceof HttpErrorResponse && err.status === 403;
        this.loadError.set(
          serverMessage(err, forbidden ? '' : 'Could not build the Sales Summary. Please try again.'),
        );
        this.state.set(forbidden ? 'forbidden' : 'error');
      },
    });
  }

  export(format: ExportFormat): void {
    if (!this.datesValid() || this.exporting()) return;
    const f = this.currentFilter();
    this.exporting.set(true);
    this.api.exportSalesSummary(f, format).subscribe({
      next: (blob) => {
        downloadBlob(
          blob,
          `sales-summary-${f.groupBy.toLowerCase()}_${f.fromDate}_${f.toDate}.${format.toLowerCase()}`,
        );
        this.exporting.set(false);
      },
      error: () => this.exporting.set(false),
    });
  }

  /** One builder, so run() and export() can never disagree about what was asked for. */
  private currentFilter(): SalesSummaryFilter {
    return {
      fromDate: String(this.fromDate() ?? '').trim(),
      toDate: String(this.toDate() ?? '').trim(),
      groupBy: this.groupBy(),
      branchUid: this.branchUid() || null,
    };
  }

  addressLine(c: ReportCompanyHeaderDto): string {
    return formatReportAddress(c);
  }
}
