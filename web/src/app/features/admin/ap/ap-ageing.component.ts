import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { SessionStore } from '../../../core/auth/session.store';
import { Company } from '../models/company.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { ApSupplierAgeingRowDto } from './models/ap.model';
import { ApService } from './ap.service';
import { formatMoney } from '../../../shared/money.util';
import { ExportFormat } from '../reporting/models/reporting.model';
import { downloadBlob } from '../reporting/reporting.utils';
import { exportErrorMessage, todayIso } from '../reporting/ledger-export.util';

type LoadState = 'idle' | 'loading' | 'error' | 'forbidden';

/** One per-currency totals line under the table (amounts in different currencies never add up). */
interface CurrencyTotal {
  currency: string;
  suppliers: number;
  current: number;
  days1to30: number;
  days31to60: number;
  days61to90: number;
  days91Plus: number;
  total: number;
}

/**
 * AP Ageing — the creditors ageing across ALL suppliers (AP-11 / RPT-03 / LBO-14): one row per
 * supplier and currency, five buckets and a total, net of unapplied debit notes. Mirror of the AR
 * Ageing screen. Gated AP.VIEW (the endpoint's code); exports also need REPORT.EXPORT.
 */
@Component({
  selector: 'app-ap-ageing',
  imports: [FormsModule],
  templateUrl: './ap-ageing.component.html',
  styleUrl: './ap-ageing.component.scss',
})
export class ApAgeingComponent {
  private readonly apService = inject(ApService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  protected readonly session = inject(SessionStore);

  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  readonly asAt = signal(todayIso());
  readonly rows = signal<ApSupplierAgeingRowDto[]>([]);
  readonly state = signal<LoadState>('idle');

  readonly canView = computed(() => this.session.hasPermission('AP.VIEW'));
  /** The export endpoint also requires REPORT.EXPORT server-side — distinct from the view code. */
  readonly canExport = computed(() => this.session.hasPermission('REPORT.EXPORT'));
  readonly isEmpty = computed(() => this.state() === 'idle' && this.rows().length === 0);

  /** Totals per currency, base/first-seen order. */
  readonly totals = computed<CurrencyTotal[]>(() => {
    const byCcy = new Map<string, CurrencyTotal>();
    for (const r of this.rows()) {
      const t = byCcy.get(r.currency) ?? {
        currency: r.currency, suppliers: 0, current: 0, days1to30: 0, days31to60: 0,
        days61to90: 0, days91Plus: 0, total: 0,
      };
      t.suppliers += 1;
      t.current += +r.current;
      t.days1to30 += +r.days1to30;
      t.days31to60 += +r.days31to60;
      t.days61to90 += +r.days61to90;
      t.days91Plus += +r.days91Plus;
      t.total += +r.total;
      byCcy.set(r.currency, t);
    }
    return [...byCcy.values()];
  });

  readonly exporting = signal(false);
  readonly exportError = signal<string | null>(null);

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
              this.load();
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
    this.rows.set([]);
    if (id) this.load();
  }

  onAsAtChange(value: string): void {
    this.asAt.set(value || todayIso());
    this.load();
  }

  load(): void {
    const companyId = this.selectedCompanyId();
    if (!companyId) return;
    this.state.set('loading');
    this.apService.getSupplierAgeing(companyId, this.asAt()).subscribe({
      next: (rows) => { this.rows.set(rows); this.state.set('idle'); },
      error: (err) =>
        this.state.set(err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error'),
    });
  }

  exportAgeing(format: ExportFormat): void {
    const companyId = this.selectedCompanyId();
    if (!companyId || this.exporting()) return;
    this.exporting.set(true);
    this.exportError.set(null);
    this.apService.exportSupplierAgeing(companyId, format, this.asAt()).subscribe({
      next: (blob) => {
        downloadBlob(blob, `ap-ageing_${this.asAt()}.${format.toLowerCase()}`);
        this.exporting.set(false);
      },
      error: (err) => {
        this.exportError.set(exportErrorMessage(err));
        this.exporting.set(false);
      },
    });
  }

  readonly fmtMoney = formatMoney;
}
