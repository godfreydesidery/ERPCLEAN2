import { DecimalPipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { debounceTime, distinctUntilChanged, Subject, switchMap } from 'rxjs';
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
  imports: [FormsModule, RouterLink, DecimalPipe, CurrencySelectComponent],
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
  private readonly productSearch$ = new Subject<string>();

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
          return this.supplierService.list(companyId, q.trim(), 0, 10);
        }),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: ({ rows }) => this.supplierResults.set(rows.filter((s) => s.status === 'ACTIVE')),
        error: () => this.supplierResults.set([]),
      });

    this.productSearch$
      .pipe(
        debounceTime(300),
        distinctUntilChanged(),
        switchMap((q) => {
          const companyId = this.selectedCompanyId();
          if (!companyId || !q.trim()) { this.productResults.set([]); return []; }
          return this.productService.list(companyId, q.trim(), 0, 10);
        }),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: ({ rows }) => this.productResults.set(rows.filter((r) => r.status !== 'ARCHIVED')),
        error: () => this.productResults.set([]),
      });

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
        if (units.length > 0) this.newLineUnitUid.set(units[0].uid);
      },
      error: () => this.lineUnits.set([]),
    });
  }

  addLine(): void {
    const product = this.selectedProduct();
    const unitUid = this.newLineUnitUid();
    const qty = Number(this.newLineQty());
    const cost = Number(this.newLineCost());
    const note = this.newLineNote().trim();

    if (!product) { this.lineFormError.set('Select an item.'); return; }
    if (!unitUid) { this.lineFormError.set('Select the unit it was delivered in.'); return; }
    if (!this.newLineQty().trim() || !Number.isFinite(qty) || qty <= 0) {
      this.lineFormError.set('Enter a quantity greater than zero.');
      return;
    }
    if (!this.newLineCost().trim() || !Number.isFinite(cost) || cost < 0) {
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
        qty: this.newLineQty().trim(),
        unitCost: this.newLineCost().trim(),
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

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}
