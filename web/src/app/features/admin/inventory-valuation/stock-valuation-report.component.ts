import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { SessionStore } from '../../../core/auth/session.store';
import { ExportFormat } from '../reporting/models/reporting.model';
import { downloadBlob } from '../reporting/reporting.utils';
import { StockValuationReportDto } from './models/inventory-valuation.model';
import { InventoryValuationService } from './inventory-valuation.service';

type LoadState = 'idle' | 'loading' | 'error' | 'forbidden';

/**
 * Stock Valuation Report screen (FR-INV-07, ADR-0020 D-6).
 * Gated INVENTORY.VALUATION.VIEW.
 *
 * Shows per-product rows: quantity, avg cost, inventory value.
 * Total row below the table.
 * Reconciliation bar: Σ on_hand_value vs GL 1300 (Inventory) account balance.
 *   - ties=true  → green "Reconciled to GL" badge.
 *   - ties=false → red finance-grade alarm.
 *
 * The backend scopes the report to the caller's company context (JWT principal).
 * No companyId param needed from the UI.
 */
@Component({
  selector: 'app-stock-valuation-report',
  imports: [RouterLink],
  templateUrl: './stock-valuation-report.component.html',
  styleUrl: './stock-valuation-report.component.scss',
})
export class StockValuationReportComponent implements OnInit {
  private readonly valuationService = inject(InventoryValuationService);
  protected readonly session = inject(SessionStore);

  // ── State ──────────────────────────────────────────────────────────────────
  readonly report = signal<StockValuationReportDto | null>(null);
  readonly state = signal<LoadState>('idle');
  readonly exporting = signal(false);

  // ── Permissions ────────────────────────────────────────────────────────────
  readonly canView = computed(() => this.session.hasPermission('INVENTORY.VALUATION.VIEW'));
  /** The export endpoint is gated REPORT.EXPORT server-side — distinct from the view permission. */
  readonly canExport = computed(() => this.session.hasPermission('REPORT.EXPORT'));
  readonly canSetOpening = computed(() => this.session.hasPermission('INVENTORY.OPENING.SET'));

  readonly isEmpty = computed(() => this.state() === 'idle' && this.report() === null);

  ngOnInit(): void {
    if (this.canView()) {
      this.load();
    }
  }

  load(): void {
    this.state.set('loading');
    this.report.set(null);
    this.valuationService.report().subscribe({
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
    if (this.exporting()) return;
    this.exporting.set(true);
    this.valuationService.export(format).subscribe({
      next: (blob) => {
        downloadBlob(blob, `stock-valuation_${this.today()}.${format.toLowerCase()}`);
        this.exporting.set(false);
      },
      error: () => this.exporting.set(false),
    });
  }

  // ── Display helpers ────────────────────────────────────────────────────────

  /** Numeric-money guard — BigDecimal arrives as JSON number; never call string methods on it. */
  fmtMoney(v: number | string | null | undefined): string {
    const n = +(v ?? 0);
    return Number.isFinite(n)
      ? n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
      : '0.00';
  }

  /** Coerce a qty/cost to display number. */
  fmtQty(v: number | string | null | undefined): string {
    const n = +(v ?? 0);
    return Number.isFinite(n)
      ? n.toLocaleString('en-US', { minimumFractionDigits: 0, maximumFractionDigits: 4 })
      : '0';
  }

  /** True when a row has no avg cost (quantity-only, unvalued). */
  isUnvalued(v: number | string | null | undefined): boolean {
    return v === null || v === undefined || +(v) === 0;
  }

  private today(): string {
    return new Date().toISOString().slice(0, 10);
  }
}
