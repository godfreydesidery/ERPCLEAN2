import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { SessionStore } from '../../../core/auth/session.store';
import { Company } from '../models/company.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { ChangesInEquityDto, EquityMovementRowDto, ExportFormat } from './models/reporting.model';
import { ReportingService } from './reporting.service';
import { downloadBlob } from './reporting.utils';
import { todayLocal } from '../../../shared/date.util';

type LoadState = 'idle' | 'loading' | 'error' | 'forbidden';

/**
 * Statement of Changes in Equity — how each part of the owners' equity moved over a period:
 * opening (the Balance Sheet the day before), profit, opening balances, capital put in, drawings
 * and dividends taken out, transfers between equity lines (the year-end close), and closing (the
 * Balance Sheet at the period end). Every figure comes from the server; the screen only lays it
 * out. A component whose movements do not add up to its closing figure is flagged, never hidden.
 *
 * Route: /admin/reporting/changes-in-equity. Gated REPORT.BS.VIEW (its closing column IS the
 * Balance Sheet's equity); export additionally REPORT.EXPORT. Company-wide only.
 */
@Component({
  selector: 'app-changes-in-equity',
  imports: [FormsModule],
  templateUrl: './changes-in-equity.component.html',
  styleUrl: './changes-in-equity.component.scss',
})
export class ChangesInEquityComponent implements OnInit {
  private readonly reportingService = inject(ReportingService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  protected readonly session = inject(SessionStore);

  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  readonly fromDate = signal(this.firstDayOfYear());
  readonly toDate = signal(this.today());

  readonly statement = signal<ChangesInEquityDto | null>(null);
  readonly state = signal<LoadState>('idle');
  readonly exporting = signal(false);

  readonly canView = computed(() => this.session.hasPermission('REPORT.BS.VIEW'));
  readonly canExport = computed(() => this.session.hasPermission('REPORT.EXPORT'));
  readonly isEmpty = computed(() => this.state() === 'idle' && this.statement() === null);

  readonly datesValid = computed(() => {
    const from = String(this.fromDate() ?? '').trim();
    const to = String(this.toDate() ?? '').trim();
    return !!from && !!to && from <= to;
  });

  ngOnInit(): void {
    this.organisationService.current().subscribe({
      next: (org) =>
        this.companyService.list(org.uid).subscribe({
          next: (list) => {
            this.companies.set(list);
            this.companyState.set('idle');
            if (list.length > 0) this.selectedCompanyId.set(list[0].id);
          },
          error: () => this.companyState.set('error'),
        }),
      error: () => this.companyState.set('error'),
    });
  }

  onCompanyChange(id: string): void {
    this.selectedCompanyId.set(id);
    this.statement.set(null);
  }

  run(): void {
    const companyId = this.selectedCompanyId();
    if (!this.canView() || !companyId || !this.datesValid()) return;
    this.state.set('loading');
    this.statement.set(null);
    this.reportingService
      .changesInEquity(companyId, this.from(), this.to())
      .subscribe({
        next: (dto) => {
          this.statement.set(dto);
          this.state.set('idle');
        },
        error: (err: unknown) =>
          this.state.set(
            err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error',
          ),
      });
  }

  export(format: ExportFormat): void {
    const companyId = this.selectedCompanyId();
    if (!this.canView() || !this.canExport() || !companyId || !this.datesValid() || this.exporting()) return;
    this.exporting.set(true);
    this.reportingService
      .exportChangesInEquity(companyId, this.from(), this.to(), format)
      .subscribe({
        next: (blob) => {
          downloadBlob(
            blob,
            `changes-in-equity_${this.from()}_${this.to()}.${format.toLowerCase()}`,
          );
          this.exporting.set(false);
        },
        error: () => this.exporting.set(false),
      });
  }

  componentLabel(r: EquityMovementRowDto): string {
    return r.accountCode ? `${r.accountCode} ${r.component}` : r.component;
  }

  /** Numeric-money guard: BigDecimal arrives as a JSON number. */
  fmtMoney(v: number | string | null | undefined): string {
    const n = +(v ?? 0);
    return Number.isFinite(n)
      ? n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
      : '0.00';
  }

  /** A zero movement reads as a dash so the real movements stand out; opening/closing never do. */
  fmtMovement(v: number | string | null | undefined): string {
    return +(v ?? 0) === 0 ? '—' : this.fmtMoney(v);
  }

  private from(): string {
    return String(this.fromDate() ?? '').trim();
  }

  private to(): string {
    return String(this.toDate() ?? '').trim();
  }

  private today(): string {
    return todayLocal();
  }

  private firstDayOfYear(): string {
    return `${new Date().getFullYear()}-01-01`;
  }
}
