import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { Company } from '../models/company.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { StockService } from '../stock/stock.service';
import { StockOnHandDto } from '../models/stock.model';
import { OpeningValuationResultDto, StockValuationReportDto, StockValuationRowDto } from './models/inventory-valuation.model';
import { InventoryValuationService } from './inventory-valuation.service';

/**
 * Unvalued-row picker shown in the select dropdown.
 */
interface UnvaluedRow {
  stockOnHandUid: string;
  productCode: string;
  productName: string;
  quantity: string;
  label: string;
}

type LoadState = 'idle' | 'loading' | 'error' | 'forbidden';

/**
 * Opening Valuation screen (FR-INV-06, ADR-0020 D-5b).
 * Gated INVENTORY.OPENING.SET.
 *
 * Sets the one-time opening unit cost for a product that has on-hand quantity but no cost.
 * The backend posts DR INVENTORY (1300) / CR OPENING_BALANCE_EQUITY (3100).
 *
 * Flow:
 *   1. Load company list → auto-select first company.
 *   2. Load valuation report → extract unvalued rows (avgCost null/0).
 *   3. Cross-reference with stock on-hand list to get stockOnHandUid.
 *   4. User picks a row + enters openingCost → POST /api/v1/stock/valuation/opening.
 *
 * "Once per product" affordance: rows disappear from the list after valuation.
 * Already-valued rejection (409) surfaced as an inline error message.
 */
@Component({
  selector: 'app-opening-valuation',
  imports: [FormsModule, RouterLink],
  templateUrl: './opening-valuation.component.html',
  styleUrl: './opening-valuation.component.scss',
})
export class OpeningValuationComponent implements OnInit {
  private readonly valuationService = inject(InventoryValuationService);
  private readonly stockService = inject(StockService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  // ── Company context ────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── Unvalued rows (loaded from report + on-hand cross-ref) ─────────────────
  readonly unvaluedRows = signal<UnvaluedRow[]>([]);
  readonly loadState = signal<LoadState>('idle');

  // ── Form fields ────────────────────────────────────────────────────────────
  readonly selectedStockOnHandUid = signal('');
  readonly openingCostStr = signal('');

  // ── Submit state ───────────────────────────────────────────────────────────
  readonly saving = signal(false);
  readonly formError = signal<string | null>(null);
  readonly lastResult = signal<OpeningValuationResultDto | null>(null);

  // ── Permissions ────────────────────────────────────────────────────────────
  readonly canSet = computed(() => this.session.hasPermission('INVENTORY.OPENING.SET'));

  readonly submitDisabled = computed(() =>
    !this.selectedStockOnHandUid() ||
    !String(this.openingCostStr() ?? '').trim() ||
    +(String(this.openingCostStr() ?? '').trim()) < 0 ||
    isNaN(+(String(this.openingCostStr() ?? '').trim())) ||
    this.saving() ||
    this.loadState() === 'loading',
  );

  readonly hasUnvalued = computed(() => this.unvaluedRows().length > 0);

  ngOnInit(): void {
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
              this.loadUnvaluedRows();
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
    this.resetForm();
    this.loadUnvaluedRows();
  }

  /**
   * Load unvalued rows by fetching the valuation report (rows where avgCost is null/zero)
   * and then fetching the on-hand list for the selected company to get stockOnHandUid.
   * Cross-references by productId (Long id).
   */
  private loadUnvaluedRows(): void {
    const companyId = this.selectedCompanyId();
    if (!companyId) return;

    this.loadState.set('loading');
    this.unvaluedRows.set([]);

    this.valuationService.report().subscribe({
      next: (report: StockValuationReportDto) => {
        const unvaluedInReport = report.rows.filter(
          (r: StockValuationRowDto) => r.avgCost === null || +(r.avgCost ?? 0) === 0,
        );

        if (unvaluedInReport.length === 0) {
          this.unvaluedRows.set([]);
          this.loadState.set('idle');
          return;
        }

        // Cross-reference with on-hand list to get stockOnHandUid (= StockOnHandDto.uid).
        // Fetch a broad page; product IDs are matched by productId (Long id as string).
        this.stockService.listOnHand(companyId, undefined, undefined, 0, 500).subscribe({
          next: ({ rows: onHandRows }) => {
            const productIdToOnHandUid = new Map<string, string>(
              onHandRows.map((oh: StockOnHandDto) => [oh.productId, oh.uid]),
            );

            const resolved: UnvaluedRow[] = unvaluedInReport
              .map((r: StockValuationRowDto) => {
                const stockOnHandUid = productIdToOnHandUid.get(r.productId);
                if (!stockOnHandUid) return null;
                return {
                  stockOnHandUid,
                  productCode: r.productCode,
                  productName: r.productName,
                  quantity: String(+(r.quantity ?? 0)),
                  label: `${r.productCode} — ${r.productName} (qty: ${+(r.quantity ?? 0)})`,
                };
              })
              .filter((r): r is UnvaluedRow => r !== null);

            this.unvaluedRows.set(resolved);
            this.loadState.set('idle');
          },
          error: () => this.loadState.set('error'),
        });
      },
      error: (err) =>
        this.loadState.set(
          err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error',
        ),
    });
  }

  submit(): void {
    if (this.submitDisabled()) return;

    const uid = this.selectedStockOnHandUid().trim();
    const costStr = String(this.openingCostStr() ?? '').trim();

    if (!uid) { this.formError.set('Select a product to value.'); return; }
    if (!costStr || isNaN(+costStr) || +costStr < 0) {
      this.formError.set('Enter a valid opening unit cost (0 or greater).');
      return;
    }

    this.saving.set(true);
    this.formError.set(null);

    this.valuationService
      .setOpening({ stockOnHandUid: uid, openingCost: costStr })
      .subscribe({
        next: (result) => {
          this.saving.set(false);
          this.lastResult.set(result);
          this.alerts.success(
            'Opening valuation set',
            `${this.fmtMoney(result.openingValue)} ${result.currency}`,
          );
          // Remove the just-valued row from the list
          this.unvaluedRows.update((rows) => rows.filter((r) => r.stockOnHandUid !== uid));
          this.resetForm();
        },
        error: (err) => {
          this.formError.set(this.messageFrom(err, 'Could not set opening valuation.'));
          this.saving.set(false);
        },
      });
  }

  private resetForm(): void {
    this.selectedStockOnHandUid.set('');
    this.openingCostStr.set('');
    this.formError.set(null);
  }

  // ── Display helpers ────────────────────────────────────────────────────────

  /** Numeric-money guard — BigDecimal arrives as JSON number. */
  fmtMoney(v: number | string | null | undefined): string {
    const n = +(v ?? 0);
    return Number.isFinite(n)
      ? n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
      : '0.00';
  }

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      // 409 = already valued
      if (err.status === 409) {
        return 'This product already has an opening valuation set (one-time operation per on-hand row).';
      }
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}
