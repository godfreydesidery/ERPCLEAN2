import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { SessionStore } from '../../../core/auth/session.store';
import { AgentModel, SupplierModel } from '../models/party.model';
import { formatReportAddress, ReportCompanyHeaderDto } from '../models/report-company-header.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { AgentService } from '../parties/agent.service';
import { SupplierService } from '../parties/supplier.service';
import { RoutesService } from '../routes/routes.service';
import { RouteDto } from '../routes/models/route.model';
import { UidOption, UidPickerComponent } from '../../../shared/uid-picker/uid-picker.component';
import { ExportFormat } from './models/reporting.model';
import { SalesReportDto } from './models/sales-report.model';
import { ReportingService } from './reporting.service';
import { downloadBlob } from './reporting.utils';

type LoadState = 'idle' | 'loading' | 'error' | 'forbidden';

/**
 * Sales Report screen (SAM Electronix go-live). Gated SALES.INVOICE.VIEW.
 *
 * Filter bar: required From/To date (default = current month), optional Agent / Route / Supplier
 * pickers (leave blank = all). The report itself is scoped server-side from the JWT/branch context
 * — no companyId is sent — but the agent/route/supplier picker OPTIONS are loaded locally against
 * the caller's first accessible company (same 200-cap full-fetch pattern as the standing-order
 * create screen), purely to populate the dropdowns.
 */
@Component({
  selector: 'app-sales-report',
  imports: [FormsModule, UidPickerComponent, DatePipe],
  templateUrl: './sales-report.component.html',
  styleUrl: './sales-report.component.scss',
})
export class SalesReportComponent implements OnInit {
  private readonly reportingService = inject(ReportingService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly agentService = inject(AgentService);
  private readonly routesService = inject(RoutesService);
  private readonly supplierService = inject(SupplierService);
  protected readonly session = inject(SessionStore);

  // ── Filter option lists (loaded once against the caller's first company) ────
  readonly agents = signal<AgentModel[]>([]);
  readonly routeList = signal<RouteDto[]>([]);
  readonly suppliers = signal<SupplierModel[]>([]);

  readonly agentOptions = computed<UidOption[]>(() =>
    this.agents()
      .filter((a) => a.status === 'ACTIVE')
      .map((a) => ({ uid: a.uid, label: a.displayName, hint: a.code })),
  );
  readonly routeOptions = computed<UidOption[]>(() =>
    this.routeList()
      .filter((r) => r.status === 'ACTIVE')
      .map((r) => ({ uid: r.uid, label: r.name, hint: r.code })),
  );
  readonly supplierOptions = computed<UidOption[]>(() =>
    this.suppliers()
      .filter((s) => s.status === 'ACTIVE')
      .map((s) => ({ uid: s.uid, label: s.displayName, hint: s.code })),
  );

  // ── Filter form ────────────────────────────────────────────────────────────
  readonly fromDate = signal(this.firstDayOfCurrentMonth());
  readonly toDate = signal(this.today());
  readonly agentUid = signal('');
  readonly routeUid = signal('');
  readonly supplierUid = signal('');

  // ── Report data ────────────────────────────────────────────────────────────
  readonly report = signal<SalesReportDto | null>(null);
  readonly state = signal<LoadState>('idle');
  readonly exporting = signal(false);

  readonly canView = computed(() => this.session.hasPermission('SALES.INVOICE.VIEW'));
  /** The export endpoint is gated REPORT.EXPORT server-side — distinct from the view permission. */
  readonly canExport = computed(() => this.session.hasPermission('REPORT.EXPORT'));
  readonly isEmpty = computed(() => this.state() === 'idle' && this.report() === null);

  ngOnInit(): void {
    if (this.canView()) {
      this.loadFilterOptions();
    }
  }

  private loadFilterOptions(): void {
    this.organisationService.current().subscribe({
      next: (org) => {
        this.companyService.list(org.uid).subscribe({
          next: (list) => {
            if (list.length === 0) return;
            const companyId = list[0].id;
            this.agentService.list(companyId, undefined, 0, 200).subscribe({
              next: ({ rows }) => this.agents.set(rows),
              error: () => undefined,
            });
            this.routesService.list(companyId, undefined, 0, 200).subscribe({
              next: ({ rows }) => this.routeList.set(rows),
              error: () => undefined,
            });
            this.supplierService.list(companyId, undefined, 0, 200).subscribe({
              next: ({ rows }) => this.suppliers.set(rows),
              error: () => undefined,
            });
          },
          error: () => undefined,
        });
      },
      error: () => undefined,
    });
  }

  run(): void {
    const from = String(this.fromDate() ?? '').trim();
    const to = String(this.toDate() ?? '').trim();
    if (!from || !to) return;

    this.state.set('loading');
    this.report.set(null);
    this.reportingService
      .salesReport({
        fromDate: from,
        toDate: to,
        agentUid: this.agentUid() || null,
        routeUid: this.routeUid() || null,
        supplierUid: this.supplierUid() || null,
      })
      .subscribe({
        next: (dto) => {
          this.report.set(dto);
          this.state.set('idle');
        },
        error: (err) =>
          this.state.set(
            err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error',
          ),
      });
  }

  export(format: ExportFormat): void {
    const from = String(this.fromDate() ?? '').trim();
    const to = String(this.toDate() ?? '').trim();
    if (!from || !to || this.exporting()) return;

    this.exporting.set(true);
    this.reportingService
      .exportSalesReport(
        {
          fromDate: from,
          toDate: to,
          agentUid: this.agentUid() || null,
          routeUid: this.routeUid() || null,
          supplierUid: this.supplierUid() || null,
        },
        format,
      )
      .subscribe({
        next: (blob) => {
          downloadBlob(blob, `sales-report_${from}_${to}.${format.toLowerCase()}`);
          this.exporting.set(false);
        },
        error: () => this.exporting.set(false),
      });
  }

  // ── Display helpers ────────────────────────────────────────────────────────

  /** Numeric-money guard — BigDecimal arrives as a JSON number; never call string methods on it. */
  fmtMoney(v: number | string | null | undefined): string {
    const n = +(v ?? 0);
    return Number.isFinite(n)
      ? n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
      : '0.00';
  }

  /** Quantity — 0 decimal places (task spec: "qty 0 dp"). */
  fmtQty(v: number | string | null | undefined): string {
    const n = +(v ?? 0);
    return Number.isFinite(n)
      ? n.toLocaleString('en-US', { minimumFractionDigits: 0, maximumFractionDigits: 0 })
      : '0';
  }

  addressLine(c: ReportCompanyHeaderDto): string {
    return formatReportAddress(c);
  }

  private today(): string {
    return new Date().toISOString().slice(0, 10);
  }

  private firstDayOfCurrentMonth(): string {
    const d = new Date();
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-01`;
  }
}
