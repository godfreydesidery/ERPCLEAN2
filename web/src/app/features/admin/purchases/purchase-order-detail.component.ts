import { HttpErrorResponse } from '@angular/common/http';
import { DatePipe } from '@angular/common';
import { Component, computed, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { debounceTime, distinctUntilChanged, Subject, switchMap } from 'rxjs';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { ProductModel, UnitOfMeasureDto } from '../models/product.model';
import {
  AddPurchaseOrderLineRequest,
  PurchaseOrderDto,
  PurchaseOrderLineDto,
  PurchaseOrderStatus,
  VoidPurchaseOrderRequest,
} from '../models/purchases.model';
import { ProductService } from '../products/product.service';
import { PurchasesService } from './purchases.service';

type LoadState = 'loading' | 'idle' | 'error';

/**
 * Purchase Order detail + lifecycle screen. Route: /admin/purchase-orders/uid/:uid.
 * Header: status, order number, supplier, totals.
 * Lines panel (DRAFT only): add/edit/remove lines — product + unit picker, qty, unit cost.
 * Actions:
 *   DRAFT  → Place (PURCHASE.ORDER.CREATE), Void (PURCHASE.ORDER.VOID)
 *   ORDERED / PARTIALLY_RECEIVED → Receive (link to GR create), Close, Void
 *   RECEIVED → Close, Void
 *   CLOSED / VOID → read-only
 * Received / outstanding qty shown per line from server DTO fields — no client computation.
 */
@Component({
  selector: 'app-purchase-order-detail',
  imports: [FormsModule, RouterLink, DatePipe],
  templateUrl: './purchase-order-detail.component.html',
  styleUrl: './purchase-order-detail.component.scss',
})
export class PurchaseOrderDetailComponent {
  private readonly purchasesService = inject(PurchasesService);
  private readonly productService = inject(ProductService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  /** Route input via withComponentInputBinding. */
  readonly uid = input.required<string>();

  // ── PO header ──────────────────────────────────────────────────────────────
  readonly po = signal<PurchaseOrderDto | null>(null);
  readonly poState = signal<LoadState>('loading');

  // ── Lines ──────────────────────────────────────────────────────────────────
  readonly lines = signal<PurchaseOrderLineDto[]>([]);
  readonly linesState = signal<LoadState>('loading');
  readonly rowBusyLineUid = signal<string | null>(null);

  // ── Add-line form ──────────────────────────────────────────────────────────
  readonly productSearchQ = signal('');
  readonly productResults = signal<ProductModel[]>([]);
  readonly selectedProduct = signal<{ uid: string; label: string } | null>(null);
  readonly lineUnits = signal<UnitOfMeasureDto[]>([]);
  readonly lineUnitsState = signal<'idle' | 'loading' | 'error'>('idle');
  readonly newLineUnitUid = signal('');
  readonly newLineQty = signal('');
  readonly newLineUnitCost = signal('');
  readonly newLineNote = signal('');
  readonly addingLine = signal(false);
  readonly lineFormError = signal<string | null>(null);

  private readonly productSearch$ = new Subject<string>();

  // ── Place ──────────────────────────────────────────────────────────────────
  readonly placing = signal(false);
  readonly placeError = signal<string | null>(null);

  // ── Close ──────────────────────────────────────────────────────────────────
  readonly closing = signal(false);
  readonly closeError = signal<string | null>(null);

  // ── Void ──────────────────────────────────────────────────────────────────
  readonly showVoidForm = signal(false);
  readonly voidReason = signal('');
  readonly voiding = signal(false);
  readonly voidError = signal<string | null>(null);

  // ── Permissions ────────────────────────────────────────────────────────────
  readonly canCreate = computed(() => this.session.hasPermission('PURCHASE.ORDER.CREATE'));
  readonly canVoid = computed(() => this.session.hasPermission('PURCHASE.ORDER.VOID'));
  readonly canReceive = computed(() => this.session.hasPermission('PURCHASE.RECEIVE'));

  // ── Derived state ──────────────────────────────────────────────────────────
  readonly isDraft = computed(() => this.po()?.status === 'DRAFT');
  readonly isOrdered = computed(() => this.po()?.status === 'ORDERED');
  readonly isPartiallyReceived = computed(() => this.po()?.status === 'PARTIALLY_RECEIVED');
  readonly isReceived = computed(() => this.po()?.status === 'RECEIVED');
  readonly isClosed = computed(() => this.po()?.status === 'CLOSED');
  readonly isVoid = computed(() => this.po()?.status === 'VOID');

  readonly isReceivable = computed(() =>
    this.isOrdered() || this.isPartiallyReceived() || this.isReceived(),
  );
  readonly isCloseable = computed(() =>
    this.isOrdered() || this.isPartiallyReceived() || this.isReceived(),
  );
  readonly isVoidable = computed(() => !this.isClosed() && !this.isVoid());

  readonly orderLabel = computed(() => {
    const p = this.po();
    if (!p) return '';
    return p.orderNumber ?? 'DRAFT';
  });

  readonly hasLines = computed(() => this.lines().length > 0);

  readonly canPlace = computed(() =>
    this.isDraft() && this.hasLines() && this.canCreate(),
  );

  constructor() {
    this.productSearch$
      .pipe(
        debounceTime(300),
        distinctUntilChanged(),
        switchMap((q) => {
          const companyId = this.po()?.companyId;
          if (!companyId || !q.trim()) { this.productResults.set([]); return []; }
          return this.productService.list(companyId, q.trim(), 0, 10);
        }),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: ({ rows }) => this.productResults.set(rows.filter((r) => r.status !== 'ARCHIVED')),
        error: () => this.productResults.set([]),
      });

    queueMicrotask(() => this.init());
  }

  private init(): void {
    this.loadPo();
    this.loadLines();
  }

  // ── PO ─────────────────────────────────────────────────────────────────────

  private loadPo(): void {
    this.poState.set('loading');
    this.purchasesService.getOrderByUid(this.uid()).subscribe({
      next: (po) => {
        this.po.set(po);
        this.poState.set('idle');
        this.loadUnitsForCompany(po.companyId);
      },
      error: () => this.poState.set('error'),
    });
  }

  private refetchPo(): void {
    this.purchasesService.getOrderByUid(this.uid()).subscribe({
      next: (po) => this.po.set(po),
      error: () => undefined,
    });
  }

  // ── Lines ──────────────────────────────────────────────────────────────────

  loadLines(): void {
    this.linesState.set('loading');
    this.purchasesService.listOrderLines(this.uid()).subscribe({
      next: (rows) => {
        this.lines.set(rows);
        this.linesState.set('idle');
      },
      error: () => this.linesState.set('error'),
    });
  }

  private loadUnitsForCompany(companyId: string): void {
    this.lineUnitsState.set('loading');
    this.productService.listUnits(companyId).subscribe({
      next: ({ rows }) => {
        this.lineUnits.set(rows.filter((u) => u.status === 'ACTIVE'));
        this.lineUnitsState.set('idle');
      },
      error: () => this.lineUnitsState.set('error'),
    });
  }

  onProductSearchChange(q: string): void {
    this.productSearchQ.set(q);
    this.selectedProduct.set(null);
    this.newLineUnitUid.set('');
    this.productSearch$.next(q);
  }

  selectProduct(product: ProductModel): void {
    this.selectedProduct.set({ uid: product.uid, label: `${product.code} — ${product.name}` });
    this.productResults.set([]);
    this.productSearchQ.set(`${product.code} — ${product.name}`);
  }

  /** Coerce a number-typed-input signal value to a trimmed string (ngModel on type="number"
   *  emits a number, so raw .trim() throws; the backend takes qty/cost as strings on the wire). */
  private asStr(v: unknown): string {
    return v === null || v === undefined ? '' : String(v).trim();
  }

  addLine(): void {
    const selected = this.selectedProduct();
    const unitUid = this.newLineUnitUid();
    const qty = this.asStr(this.newLineQty());
    const cost = this.asStr(this.newLineUnitCost());

    if (!selected) { this.lineFormError.set('Select a product.'); return; }
    if (!unitUid) { this.lineFormError.set('Select a unit.'); return; }
    if (!qty || isNaN(Number(qty)) || Number(qty) <= 0) {
      this.lineFormError.set('Enter a valid quantity greater than zero.');
      return;
    }
    if (!cost || isNaN(Number(cost)) || Number(cost) < 0) {
      this.lineFormError.set('Enter a valid unit cost (zero or positive).');
      return;
    }

    this.addingLine.set(true);
    this.lineFormError.set(null);

    const request: AddPurchaseOrderLineRequest = {
      productUid: selected.uid,
      unitUid,
      orderedQty: qty,
      unitCostAmount: cost,
      note: this.newLineNote().trim() || undefined,
    };

    this.purchasesService.addLine(this.uid(), request).subscribe({
      next: () => {
        this.selectedProduct.set(null);
        this.productSearchQ.set('');
        this.productResults.set([]);
        this.newLineUnitUid.set('');
        this.newLineQty.set('');
        this.newLineUnitCost.set('');
        this.newLineNote.set('');
        this.addingLine.set(false);
        this.alerts.success('Line added');
        this.loadLines();
        this.refetchPo();
      },
      error: (err) => {
        this.lineFormError.set(this.messageFrom(err, 'Could not add line.'));
        this.addingLine.set(false);
      },
    });
  }

  removeLine(line: PurchaseOrderLineDto): void {
    if (this.rowBusyLineUid() !== null) return;
    this.rowBusyLineUid.set(line.uid);
    this.purchasesService.removeLine(this.uid(), line.uid).subscribe({
      next: () => {
        this.rowBusyLineUid.set(null);
        this.alerts.success('Line removed');
        this.loadLines();
        this.refetchPo();
      },
      error: () => this.rowBusyLineUid.set(null),
    });
  }

  // ── Place ──────────────────────────────────────────────────────────────────

  place(): void {
    if (this.placing()) return;
    this.placing.set(true);
    this.placeError.set(null);
    this.purchasesService.placeOrder(this.uid()).subscribe({
      next: () => {
        this.placing.set(false);
        this.alerts.success('Order placed');
        this.loadPo();
      },
      error: (err) => {
        this.placeError.set(this.messageFrom(err, 'Could not place order.'));
        this.placing.set(false);
      },
    });
  }

  // ── Close ──────────────────────────────────────────────────────────────────

  close(): void {
    if (this.closing()) return;
    this.closing.set(true);
    this.closeError.set(null);
    this.purchasesService.closeOrder(this.uid()).subscribe({
      next: () => {
        this.closing.set(false);
        this.alerts.success('Order closed');
        this.loadPo();
      },
      error: (err) => {
        this.closeError.set(this.messageFrom(err, 'Could not close order.'));
        this.closing.set(false);
      },
    });
  }

  // ── Void ───────────────────────────────────────────────────────────────────

  toggleVoidForm(): void {
    this.showVoidForm.update((v) => !v);
    this.voidError.set(null);
    this.voidReason.set('');
  }

  submitVoid(): void {
    const reason = this.voidReason().trim();
    if (!reason) { this.voidError.set('A void reason is required.'); return; }
    if (this.voiding()) return;
    this.voiding.set(true);
    this.voidError.set(null);
    const request: VoidPurchaseOrderRequest = { reason };
    this.purchasesService.voidOrder(this.uid(), request).subscribe({
      next: () => {
        this.voiding.set(false);
        this.showVoidForm.set(false);
        this.alerts.success('Order voided');
        this.loadPo();
      },
      error: (err) => {
        this.voidError.set(this.messageFrom(err, 'Could not void order.'));
        this.voiding.set(false);
      },
    });
  }

  // ── Display helpers ────────────────────────────────────────────────────────

  statusBadgeClass(status: PurchaseOrderStatus): string {
    switch (status) {
      case 'ORDERED': return 'text-bg-primary';
      case 'PARTIALLY_RECEIVED': return 'text-bg-info';
      case 'RECEIVED': return 'text-bg-success';
      case 'CLOSED': return 'text-bg-secondary';
      case 'VOID': return 'text-bg-secondary';
      default: return 'text-bg-warning'; // DRAFT
    }
  }

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}
