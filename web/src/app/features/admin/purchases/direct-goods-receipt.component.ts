import { DatePipe, DecimalPipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { catchError, debounceTime, distinctUntilChanged, of, Subject, switchMap } from 'rxjs';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { CurrencySelectComponent } from '../../../shared/currency-select/currency-select.component';
import { Company } from '../models/company.model';
import { SupplierModel } from '../models/party.model';
import { ProductModel, UnitOfMeasureDto } from '../models/product.model';
import {
  DirectGoodsReceiptLineRequest,
  DirectGoodsReceiptRequest,
} from '../models/purchases.model';
import { PurchaseCostSource, PurchaseCostSuggestionDto } from './purchases.service';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { ProductService } from '../products/product.service';
import { SupplierService } from '../parties/supplier.service';
import { PurchasesService } from './purchases.service';

/** A line staged on the screen, carrying the labels needed to render it back to the user. */
interface StagedLine {
  productUid: string;
  productLabel: string;
  unitUid: string;
  unitLabel: string;
  qty: string;
  unitCost: string;
  note: string;
}

/**
 * Direct Goods Receipt — record stock that arrived with NO purchase order (K3, Kilimanjaro).
 *
 * <p>The client buys from walk-in suppliers and for cash. Until this screen existed the only way to
 * get that stock onto the books from the UI was a stock adjustment, which records no supplier, no
 * document and no purchase cost — it values the movement at the current moving average (zero for a
 * brand-new product) and posts a variance hit instead of DR INVENTORY / CR GRNI. Inventory ended up
 * under-valued and cost overstated.
 *
 * <p>The storekeeper never sees a purchase order: the backend auto-raises one behind the scenes,
 * stamps it {@code DIRECT_RECEIPT}, receives it in full, and raises a RATIFICATION request in the
 * approvals inbox so a manager still reviews the spend — after the fact, which is the only point at
 * which review is possible once the goods are on the shelf.
 *
 * <p>Gated on {@code PURCHASE.RECEIVE.DIRECT} — the exact code the backend endpoint checks, so a
 * user who can open the screen can also submit from it.
 */
@Component({
  selector: 'app-direct-goods-receipt',
  imports: [FormsModule, RouterLink, DatePipe, DecimalPipe, CurrencySelectComponent],
  templateUrl: './direct-goods-receipt.component.html',
  styleUrl: './direct-goods-receipt.component.scss',
})
export class DirectGoodsReceiptComponent {
  private readonly purchasesService = inject(PurchasesService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly supplierService = inject(SupplierService);
  private readonly productService = inject(ProductService);
  private readonly router = inject(Router);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  // ── Company context ────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly selectedCompanyUid = computed(
    () => this.companies().find((c) => c.id === this.selectedCompanyId())?.uid ?? '',
  );
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── Header ─────────────────────────────────────────────────────────────────
  readonly currency = signal('TZS');
  readonly notes = signal('');

  // ── Supplier picker ────────────────────────────────────────────────────────
  readonly supplierSearchQ = signal('');
  readonly supplierResults = signal<SupplierModel[]>([]);
  readonly selectedSupplier = signal<{ uid: string; label: string } | null>(null);
  readonly supplierSearchError = signal<string | null>(null);
  private readonly supplierSearch$ = new Subject<string>();

  // ── Item entry ─────────────────────────────────────────────────────────────
  readonly productSearchQ = signal('');
  readonly productResults = signal<ProductModel[]>([]);
  readonly selectedProduct = signal<{ uid: string; label: string } | null>(null);
  readonly lineUnits = signal<UnitOfMeasureDto[]>([]);
  readonly newLineUnitUid = signal('');
  readonly newLineQty = signal('');
  readonly newLineCost = signal('');
  readonly newLineNote = signal('');
  readonly lineFormError = signal<string | null>(null);
  readonly productSearchError = signal<string | null>(null);
  private readonly productSearch$ = new Subject<string>();

  // ── Unit-cost suggestion (K-2026-08-30 #4) ─────────────────────────────────
  /** The cost the system already knows for this item, with its provenance; null when unknown. */
  readonly costSuggestion = signal<PurchaseCostSuggestionDto | null>(null);
  /** True while the cost box holds a figure WE put there, not one the storekeeper typed. */
  private readonly costPrefilled = signal(false);
  /** Guards against a slow lookup landing after a newer product/unit pick. */
  private suggestionRequest = 0;

  /**
   * True when the suggested price is in a different currency from the receipt. No conversion is
   * attempted — the storekeeper is simply told, so a figure from a foreign-currency purchase is
   * never mistaken for one in the receipt's currency.
   */
  readonly costCurrencyMismatch = computed(() => {
    const suggestion = this.costSuggestion();
    if (!suggestion) return false;
    return suggestion.currency !== this.currency();
  });

  // ── Staged lines + submit ──────────────────────────────────────────────────
  readonly stagedLines = signal<StagedLine[]>([]);
  readonly submitting = signal(false);
  readonly formError = signal<string | null>(null);

  readonly canReceiveDirect = computed(() =>
    this.session.hasPermission('PURCHASE.RECEIVE.DIRECT'),
  );

  readonly total = computed(() =>
    this.stagedLines().reduce((sum, l) => sum + Number(l.qty) * Number(l.unitCost), 0),
  );

  constructor() {
    this.supplierSearch$
      .pipe(
        debounceTime(300),
        distinctUntilChanged(),
        switchMap((q) => {
          const companyId = this.selectedCompanyId();
          if (!companyId || !q.trim()) { this.supplierResults.set([]); return []; }
          this.supplierSearchError.set(null);
          // catchError INSIDE the switchMap: an error escaping to the outer subscribe would
          // terminate the stream, leaving the search box permanently dead after one failure.
          return this.supplierService.list(companyId, q.trim(), 0, 10).pipe(
            catchError((err: unknown) => {
              this.supplierSearchError.set(this.searchMessage(err, 'suppliers'));
              return of({ rows: [] as SupplierModel[] });
            }),
          );
        }),
        takeUntilDestroyed(),
      )
      .subscribe(({ rows }) =>
        this.supplierResults.set(rows.filter((s) => s.status === 'ACTIVE')));

    this.productSearch$
      .pipe(
        debounceTime(300),
        distinctUntilChanged(),
        switchMap((q) => {
          const companyId = this.selectedCompanyId();
          if (!companyId || !q.trim()) { this.productResults.set([]); return []; }
          this.productSearchError.set(null);
          return this.productService.list(companyId, q.trim(), 0, 10).pipe(
            catchError((err: unknown) => {
              this.productSearchError.set(this.searchMessage(err, 'items'));
              return of({ rows: [] as ProductModel[] });
            }),
          );
        }),
        takeUntilDestroyed(),
      )
      .subscribe(({ rows }) =>
        this.productResults.set(rows.filter((r) => r.status !== 'ARCHIVED')));

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
            if (list.length > 0) this.selectedCompanyId.set(list[0].id);
          },
          error: () => this.companyState.set('error'),
        });
      },
      error: () => this.companyState.set('error'),
    });
  }

  /** Company scopes the supplier and product lookups, so a change invalidates everything staged. */
  onCompanyChange(id: string): void {
    this.selectedCompanyId.set(id);
    this.resetSupplier();
    this.resetItemEntry();
    this.stagedLines.set([]);
    this.formError.set(null);
  }

  // ── Supplier picker ────────────────────────────────────────────────────────

  onSupplierSearchChange(q: string): void {
    this.supplierSearchQ.set(q);
    this.selectedSupplier.set(null);
    this.supplierSearch$.next(q);
  }

  selectSupplier(s: SupplierModel): void {
    this.selectedSupplier.set({ uid: s.uid, label: `${s.code} — ${s.displayName}` });
    this.supplierResults.set([]);
    this.supplierSearchQ.set(`${s.code} — ${s.displayName}`);
    // Knowing the supplier narrows the suggestion to what THEY last charged, so an item picked
    // before the supplier gets a better answer now rather than keeping the generic one.
    const unitUid = this.newLineUnitUid();
    if (this.selectedProduct() && unitUid) this.loadCostSuggestion(unitUid);
  }

  private resetSupplier(): void {
    this.selectedSupplier.set(null);
    this.supplierSearchQ.set('');
    this.supplierResults.set([]);
  }

  // ── Item entry ─────────────────────────────────────────────────────────────

  onProductSearchChange(q: string): void {
    this.productSearchQ.set(q);
    this.selectedProduct.set(null);
    this.lineUnits.set([]);
    this.newLineUnitUid.set('');
    this.clearCostSuggestion();
    this.productSearch$.next(q);
  }

  selectProduct(product: ProductModel): void {
    this.selectedProduct.set({ uid: product.uid, label: `${product.code} — ${product.name}` });
    this.productResults.set([]);
    this.productSearchQ.set(`${product.code} — ${product.name}`);
    this.productService.listProductUnits(product.uid).subscribe({
      next: (units) => {
        this.lineUnits.set(units);
        // First returned unit is the base unit — the safe default for a counter delivery.
        if (units.length > 0) {
          this.newLineUnitUid.set(units[0].uid);
          this.loadCostSuggestion(units[0].uid);
        }
      },
      error: () => this.lineUnits.set([]),
    });
  }

  /** The unit the goods arrived in changes which price applies, so the suggestion is re-read. */
  onUnitChange(unitUid: string): void {
    this.newLineUnitUid.set(unitUid);
    this.loadCostSuggestion(unitUid);
  }

  /** A cost the storekeeper types is theirs — stop treating the box as ours to clear or replace. */
  onUnitCostChange(value: string | number | null | undefined): void {
    this.newLineCost.set(this.asStr(value));
    this.costPrefilled.set(false);
  }

  // ── Unit-cost suggestion ───────────────────────────────────────────────────

  /**
   * Fetch the cost the system already holds for this item and pre-fill the box with it — the whole
   * point of K-2026-08-30 #4 ("not having to input the cost price all the time").
   *
   * <p>Only an EMPTY box is filled, so a figure the storekeeper typed is never overwritten, and the
   * hint underneath always says where the number came from: a defaulted cost feeds the moving
   * average, so it must never look like a figure someone checked when it is not.
   */
  private loadCostSuggestion(unitUid: string): void {
    const productUid = this.selectedProduct()?.uid;
    const companyUid = this.selectedCompanyUid();
    this.costSuggestion.set(null);
    // Anything WE filled in belongs to the previous product/unit — drop it before looking the new
    // one up, so a carton price can never linger on a line that is now received per piece.
    if (this.costPrefilled()) {
      this.newLineCost.set('');
      this.costPrefilled.set(false);
    }
    if (!productUid || !unitUid || !companyUid) return;

    // The supplier is optional here: it narrows the answer to what THIS supplier last charged, but
    // items are often picked before the supplier is chosen, and the product cost still answers.
    const supplierUid = this.selectedSupplier()?.uid ?? '';

    const request = ++this.suggestionRequest;
    this.purchasesService
      .directCostSuggestion(companyUid, supplierUid, productUid, unitUid)
      .subscribe({
        next: (suggestion) => {
          if (request !== this.suggestionRequest) return;  // a newer pick already superseded this
          this.costSuggestion.set(suggestion);
          if (suggestion && this.asStr(this.newLineCost()) === '') {
            this.newLineCost.set(String(suggestion.amount));
            this.costPrefilled.set(true);
          }
        },
        error: () => {
          if (request === this.suggestionRequest) this.costSuggestion.set(null);
        },
      });
  }

  private clearCostSuggestion(): void {
    this.suggestionRequest++;
    this.costSuggestion.set(null);
    // Drop the box too when the figure in it is OURS: otherwise the old product's price sits in a
    // non-empty box, which then blocks the next suggestion from ever being applied — the line would
    // be staged at the previous item's price.
    if (this.costPrefilled()) this.newLineCost.set('');
    this.costPrefilled.set(false);
  }

  /** Friendly provenance wording for the hint under the cost box. */
  costSourceLabel(source: PurchaseCostSource): string {
    switch (source) {
      case 'LAST_QUOTE':
        return 'From the last quote';
      case 'LAST_PURCHASE':
        return 'From the last purchase';
      case 'PRODUCT_COST':
        return 'From the product cost price';
    }
  }

  /** Coerce a number-typed-input signal value to a trimmed string (ngModel on type="number"
   *  emits a number, so raw .trim() throws; the backend takes qty/cost as strings on the wire). */
  private asStr(v: string | number | null | undefined): string {
    return v === null || v === undefined ? '' : String(v).trim();
  }

  addLine(): void {
    try {
      this.stageLine();
    } catch (err) {
      // A throw here used to escape into Angular's default ErrorHandler, leaving the button looking
      // completely inert. Never let this handler fail silently again.
      console.error('Direct goods receipt — could not stage the line', err);
      this.lineFormError.set('Could not add this item. Check the quantity and unit cost, then try again.');
    }
  }

  private stageLine(): void {
    const product = this.selectedProduct();
    const unitUid = this.newLineUnitUid();
    const qtyStr = this.asStr(this.newLineQty());
    const costStr = this.asStr(this.newLineCost());
    const qty = Number(qtyStr);
    const cost = Number(costStr);
    const note = this.asStr(this.newLineNote());

    if (!product) { this.lineFormError.set('Select an item.'); return; }
    if (!unitUid) { this.lineFormError.set('Select the unit it was delivered in.'); return; }
    if (!qtyStr || !Number.isFinite(qty) || qty <= 0) {
      this.lineFormError.set('Enter a quantity greater than zero.');
      return;
    }
    if (!costStr || !Number.isFinite(cost) || cost < 0) {
      this.lineFormError.set('Enter a unit cost of zero or more.');
      return;
    }
    // Mirrors the backend rule (OQ-PURCH-04): free stock has to be explained, otherwise a slipped
    // decimal silently drags the moving average to zero.
    if (cost === 0 && !note) {
      this.lineFormError.set('A zero cost needs a note explaining why (e.g. free sample).');
      return;
    }

    const unitLabel = this.lineUnits().find((u) => u.uid === unitUid)?.name ?? '';
    this.stagedLines.update((rows) => [
      ...rows,
      {
        productUid: product.uid,
        productLabel: product.label,
        unitUid,
        unitLabel,
        qty: qtyStr,
        unitCost: costStr,
        note,
      },
    ]);
    this.resetItemEntry();
    this.formError.set(null);
  }

  removeLine(index: number): void {
    this.stagedLines.update((rows) => rows.filter((_, i) => i !== index));
  }

  private resetItemEntry(): void {
    this.selectedProduct.set(null);
    this.productSearchQ.set('');
    this.productResults.set([]);
    this.lineUnits.set([]);
    this.newLineUnitUid.set('');
    this.newLineQty.set('');
    this.newLineCost.set('');
    this.newLineNote.set('');
    this.lineFormError.set(null);
    this.clearCostSuggestion();
  }

  lineTotal(line: StagedLine): number {
    return Number(line.qty) * Number(line.unitCost);
  }

  // ── Submit ─────────────────────────────────────────────────────────────────

  submit(): void {
    const supplier = this.selectedSupplier();
    const companyUid = this.selectedCompanyUid();

    if (!companyUid) { this.formError.set('Select the company this delivery belongs to.'); return; }
    if (!supplier) { this.formError.set('Select the supplier who delivered these goods.'); return; }
    if (this.stagedLines().length === 0) { this.formError.set('Add at least one item to receive.'); return; }

    const lines: DirectGoodsReceiptLineRequest[] = this.stagedLines().map((l) => {
      const line: DirectGoodsReceiptLineRequest = {
        productUid: l.productUid,
        unitUid: l.unitUid,
        receivedQty: l.qty,
        unitCostAmount: l.unitCost,
      };
      if (l.note) line.note = l.note;
      return line;
    });

    const request: DirectGoodsReceiptRequest = {
      companyUid,
      supplierUid: supplier.uid,
      currency: this.currency().trim() || undefined,
      notes: this.notes().trim() || undefined,
      lines,
    };

    this.submitting.set(true);
    this.formError.set(null);

    this.purchasesService.receiveDirect(request).subscribe({
      next: (gr) => {
        this.submitting.set(false);
        this.alerts.success('Goods received', gr.receiptNumber);
        this.router.navigate(['/admin/goods-receipts/uid', gr.uid]);
      },
      error: (err) => {
        this.submitting.set(false);
        this.formError.set(this.messageFrom(err, 'Could not record this delivery.'));
      },
    });
  }

  /**
   * A search that fails is not a search that found nothing. A 403 here means the signed-in role is
   * missing the read permission for that master (e.g. STOREKEEPER holds PURCHASE.RECEIVE.DIRECT but
   * not SUPPLIER.VIEW) — previously that surfaced as a box that silently never returned anything.
   */
  private searchMessage(err: unknown, what: string): string {
    if (err instanceof HttpErrorResponse && err.status === 403) {
      return `You do not have permission to look up ${what}. Ask an administrator for access.`;
    }
    return `Could not search ${what}. Check your connection and try again.`;
  }

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}
