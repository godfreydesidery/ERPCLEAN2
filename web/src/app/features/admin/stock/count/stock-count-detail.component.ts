import { HttpErrorResponse } from '@angular/common/http';
import { DatePipe, DecimalPipe, NgClass } from '@angular/common';
import { Component, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AlertService } from '../../../../core/feedback/alert.service';
import { SessionStore } from '../../../../core/auth/session.store';
import { StockCountDto, StockCountLineDto } from './stock-count.model';
import { StockCountService } from './stock-count.service';
import { StockUnitOption, StockUnitOptionsService, packBreakdown, toBaseQty, unitFactor } from '../stock-units';

/** Counts up to this many distinct products load their pack sizes up front; larger ones on focus. */
const EAGER_UNIT_PRODUCTS = 50;

/**
 * Stock Count detail page. Route: /admin/stock-counts/uid/:uid.
 *
 * States and actions per status:
 *  COUNTING  → editable countedQty per line (Enter action), Cancel
 *  COUNTING  → Post action (posting date) after at least one qty entered
 *  POSTED    → read-only; shows variance per line + GL entry uid
 *  CANCELLED → read-only
 */
@Component({
  selector: 'app-stock-count-detail',
  imports: [FormsModule, RouterLink, DatePipe, DecimalPipe, NgClass],
  templateUrl: './stock-count-detail.component.html',
  styleUrl: './stock-count-detail.component.scss',
})
export class StockCountDetailComponent {
  private readonly countService = inject(StockCountService);
  private readonly alerts = inject(AlertService);
  private readonly unitOptions = inject(StockUnitOptionsService);
  protected readonly session = inject(SessionStore);

  readonly uid = input.required<string>();

  // ── Entity ────────────────────────────────────────────────────────────────────
  readonly count = signal<StockCountDto | null>(null);
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('loading');

  // ── Enter-count form state ────────────────────────────────────────────────────
  /** Map lineId → user-typed counted qty (string from number input) */
  readonly editedQtys = signal<Record<string, string>>({});
  /** Map lineId → reason code */
  readonly editedReasons = signal<Record<string, string>>({});
  /** STK-08: map lineId → unit the counted qty is typed in ('' = base unit). */
  readonly editedUnits = signal<Record<string, string>>({});
  /** productUid → its units (base first, then pack sizes), once loaded. */
  readonly unitsByProduct = signal<Record<string, StockUnitOption[]>>({});
  readonly entering = signal(false);
  readonly enterError = signal<string | null>(null);

  // ── Post form ─────────────────────────────────────────────────────────────────
  readonly showPostForm = signal(false);
  readonly postingDate = signal(new Date().toISOString().substring(0, 10));
  readonly posting = signal(false);
  readonly postError = signal<string | null>(null);

  // ── Cancel ────────────────────────────────────────────────────────────────────
  readonly cancelling = signal(false);
  readonly cancelError = signal<string | null>(null);

  // ── Permissions ───────────────────────────────────────────────────────────────
  readonly canCreate = computed(() => this.session.hasPermission('STOCK.COUNT.CREATE'));
  readonly canPost   = computed(() => this.session.hasPermission('STOCK.COUNT.POST'));

  // ── Derived ───────────────────────────────────────────────────────────────────
  readonly isCounting  = computed(() => this.count()?.status === 'COUNTING');
  readonly isPosted    = computed(() => this.count()?.status === 'POSTED');
  readonly isCancelled = computed(() => this.count()?.status === 'CANCELLED');

  constructor() {
    queueMicrotask(() => this.init());
  }

  private init(): void {
    this.state.set('loading');
    this.countService.getByUid(this.uid()).subscribe({
      next: (c) => {
        this.count.set(c);
        this.state.set('idle');
        this.initEditedQtys(c);
        const products = [...new Set(c.lines.map((l) => l.productUid).filter((u): u is string => !!u))];
        if (products.length <= EAGER_UNIT_PRODUCTS) products.forEach((u) => this.loadUnits(u));
      },
      error: (err) => {
        this.state.set(err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error');
      },
    });
  }

  private initEditedQtys(c: StockCountDto): void {
    const qtys: Record<string, string> = {};
    const reasons: Record<string, string> = {};
    for (const line of c.lines) {
      qtys[line.id] = line.countedQty ?? '';
      reasons[line.id] = line.reasonCode ?? '';
    }
    this.editedQtys.set(qtys);
    this.editedReasons.set(reasons);
    // Saved quantities come back in base units, so every line starts again in the base unit.
    this.editedUnits.set({});
  }

  /** Loads a product's pack sizes once (cached app-wide); called eagerly or on focus. */
  loadUnits(productUid: string | null | undefined): void {
    if (!productUid || this.unitsByProduct()[productUid]) return;
    this.unitOptions.load(productUid).subscribe({
      next: (units) => this.unitsByProduct.update((m) => ({ ...m, [productUid]: units })),
      error: () => undefined,
    });
  }

  unitsFor(line: StockCountLineDto): StockUnitOption[] {
    return (line.productUid && this.unitsByProduct()[line.productUid]) || [];
  }

  onUnitChange(lineId: string, unitUid: string): void {
    this.editedUnits.update((m) => ({ ...m, [lineId]: unitUid }));
  }

  /** "= 48 Pieces" under a quantity typed in a pack size. */
  basePreview(line: StockCountLineDto): string {
    const units = this.unitsFor(line);
    const factor = unitFactor(units, this.editedUnits()[line.id] ?? '');
    const raw = this.editedQtys()[line.id];
    const n = Number(raw);
    if (factor === 1 || raw === '' || raw === undefined || !Number.isFinite(n)) return '';
    return `= ${toBaseQty(n, factor)} ${units[0]?.name ?? line.unitName ?? ''}`.trim();
  }

  /** "4 CTN + 7 PCS" for a base quantity on this line, when the product has a pack size. */
  packs(line: StockCountLineDto, baseQty: string | null): string {
    return packBreakdown(baseQty, this.unitsFor(line));
  }

  onQtyChange(lineId: string, val: unknown): void {
    this.editedQtys.update((m) => ({ ...m, [lineId]: val === null || val === undefined ? '' : String(val) }));
  }

  onReasonChange(lineId: string, val: string): void {
    this.editedReasons.update((m) => ({ ...m, [lineId]: val }));
  }

  // ── Enter counted quantities ──────────────────────────────────────────────────

  enterCount(): void {
    this.enterError.set(null);
    const qtys = this.editedQtys();
    const reasons = this.editedReasons();
    const units = this.editedUnits();
    const lines = (this.count()?.lines ?? [])
      .filter((l) => {
        const v = qtys[l.id];
        return v !== '' && v !== null && v !== undefined && !isNaN(Number(v));
      })
      .map((l) => ({
        lineId: l.id,
        countedQty: String(qtys[l.id]).trim(),
        reasonCode: reasons[l.id]?.trim() || undefined,
        // Omitted for the base unit; the server converts a pack size to base.
        ...(units[l.id] ? { unitUid: units[l.id] } : {}),
      }));

    if (lines.length === 0) {
      this.enterError.set('Enter at least one counted quantity.');
      return;
    }

    this.entering.set(true);
    this.countService.enterCount(this.uid(), { lines }).subscribe({
      next: (updated) => {
        this.count.set(updated);
        this.initEditedQtys(updated);
        this.entering.set(false);
        this.alerts.success('Counted quantities saved');
      },
      error: (err) => {
        this.enterError.set(this.messageFrom(err, 'Could not save counted quantities.'));
        this.entering.set(false);
      },
    });
  }

  // ── Post ──────────────────────────────────────────────────────────────────────

  togglePostForm(): void {
    this.showPostForm.update((v) => !v);
    this.postError.set(null);
  }

  submitPost(): void {
    this.postError.set(null);
    if (!this.postingDate()) { this.postError.set('Posting date is required.'); return; }

    this.posting.set(true);
    this.countService.post(this.uid(), this.postingDate()).subscribe({
      next: (updated) => {
        this.count.set(updated);
        this.initEditedQtys(updated);
        this.posting.set(false);
        this.showPostForm.set(false);
        this.alerts.success('Stock count posted', 'Variance journal created.');
      },
      error: (err) => {
        this.postError.set(this.messageFrom(err, 'Could not post stock count.'));
        this.posting.set(false);
      },
    });
  }

  // ── Cancel ────────────────────────────────────────────────────────────────────

  cancelCount(): void {
    this.cancelError.set(null);
    this.cancelling.set(true);
    this.countService.cancel(this.uid()).subscribe({
      next: () => {
        // Reload to get CANCELLED status
        this.countService.getByUid(this.uid()).subscribe({
          next: (updated) => {
            this.count.set(updated);
            this.cancelling.set(false);
            this.alerts.success('Stock count cancelled');
          },
          error: () => { this.cancelling.set(false); },
        });
      },
      error: (err) => {
        this.cancelError.set(this.messageFrom(err, 'Could not cancel stock count.'));
        this.cancelling.set(false);
      },
    });
  }

  // ── Display helpers ───────────────────────────────────────────────────────────

  varianceClass(varianceQty: string | null): string {
    if (!varianceQty) return '';
    const n = Number(varianceQty);
    if (n > 0) return 'text-success';
    if (n < 0) return 'text-danger';
    return 'text-muted';
  }

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}
