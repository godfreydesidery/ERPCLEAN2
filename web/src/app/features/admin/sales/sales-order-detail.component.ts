import { HttpErrorResponse } from '@angular/common/http';
import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, computed, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { debounceTime, distinctUntilChanged, Subject, switchMap } from 'rxjs';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { ProductModel, UnitOfMeasureDto } from '../models/product.model';
import {
  AddSalesOrderLineRequest,
  CancelSalesOrderRequest,
  DeliveryDto,
  SalesOrderDto,
  SalesOrderLineDto,
} from '../models/sales-orders.model';
import { ProductService } from '../products/product.service';
import { SalesOrdersService } from './sales-orders.service';

type LoadState = 'loading' | 'idle' | 'error';

/**
 * Sales Order detail + lifecycle screen. Route: /admin/sales-orders/uid/:uid.
 * Shows ordered/reserved/fulfilled/invoiced qty per line plus backorder balance.
 * Actions:
 *   DRAFT      → Confirm (reserves stock), Cancel
 *   CONFIRMED+ → Create Delivery link, Cancel
 *   All states → read lines
 */
@Component({
  selector: 'app-sales-order-detail',
  imports: [FormsModule, RouterLink, DatePipe, DecimalPipe],
  templateUrl: './sales-order-detail.component.html',
  styleUrl: './sales-order-detail.component.scss',
})
export class SalesOrderDetailComponent {
  private readonly soService = inject(SalesOrdersService);
  private readonly productService = inject(ProductService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  readonly uid = input.required<string>();

  // ── Order header ─────────────────────────────────────────────────────────────
  readonly order = signal<SalesOrderDto | null>(null);
  readonly orderState = signal<LoadState>('loading');

  // ── Lines ─────────────────────────────────────────────────────────────────────
  readonly lines = signal<SalesOrderLineDto[]>([]);
  readonly linesState = signal<LoadState>('loading');
  readonly rowBusyLineUid = signal<string | null>(null);

  // ── Deliveries panel ──────────────────────────────────────────────────────────
  readonly deliveries = signal<DeliveryDto[]>([]);
  readonly deliveriesState = signal<LoadState>('loading');

  // ── Add-line form (DRAFT only) ────────────────────────────────────────────────
  readonly productSearchQ = signal('');
  readonly productResults = signal<ProductModel[]>([]);
  readonly selectedProduct = signal<{ uid: string; label: string } | null>(null);
  readonly lineUnits = signal<UnitOfMeasureDto[]>([]);
  readonly lineUnitsState = signal<'idle' | 'loading' | 'error'>('idle');
  readonly newLineUnitUid = signal('');
  readonly newLineQty = signal('');
  readonly newLineUnitPrice = signal('');
  readonly newLineDiscountPct = signal('');
  readonly addingLine = signal(false);
  readonly lineFormError = signal<string | null>(null);

  // ── Confirm ───────────────────────────────────────────────────────────────────
  readonly confirming = signal(false);
  readonly confirmError = signal<string | null>(null);
  readonly showConfirmDialog = signal(false);

  // ── Cancel ────────────────────────────────────────────────────────────────────
  readonly showCancelForm = signal(false);
  readonly cancelReason = signal('');
  readonly cancelling = signal(false);
  readonly cancelError = signal<string | null>(null);

  // ── Permissions ───────────────────────────────────────────────────────────────
  readonly canCreate = computed(() => this.session.hasPermission('SALES.ORDER.CREATE'));
  readonly canConfirm = computed(() => this.session.hasPermission('SALES.ORDER.CONFIRM'));
  readonly canCancel = computed(() => this.session.hasPermission('SALES.ORDER.CANCEL'));
  readonly canViewDeliveries = computed(() => this.session.hasPermission('SALES.DELIVERY.VIEW'));
  readonly canCreateDelivery = computed(() => this.session.hasPermission('SALES.DELIVERY.CREATE'));

  // ── Derived ───────────────────────────────────────────────────────────────────
  readonly isDraft = computed(() => this.order()?.status === 'DRAFT');
  readonly isConfirmed = computed(() => this.order()?.status === 'CONFIRMED');
  readonly isCancelled = computed(() => this.order()?.status === 'CANCELLED');
  readonly isClosed = computed(() => this.order()?.status === 'CLOSED');

  readonly canDeliverNow = computed(() =>
    this.canCreateDelivery() && !this.isDraft() && !this.isCancelled() && !this.isClosed(),
  );
  readonly canCancelNow = computed(() =>
    this.canCancel() && !this.isCancelled() && !this.isClosed(),
  );
  readonly canConfirmNow = computed(() =>
    this.isDraft() && this.canConfirm() && this.lines().length > 0,
  );

  readonly orderLabel = computed(() => this.order()?.orderNumber ?? 'DRAFT');

  private readonly productSearch$ = new Subject<string>();

  constructor() {
    this.productSearch$
      .pipe(
        debounceTime(300),
        distinctUntilChanged(),
        switchMap((q) => {
          const companyId = this.order()?.companyId;
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
    this.loadOrder();
    this.loadLines();
    this.loadDeliveries();
  }

  private loadOrder(): void {
    this.orderState.set('loading');
    this.soService.getOrderByUid(this.uid()).subscribe({
      next: (o) => {
        this.order.set(o);
        this.orderState.set('idle');
      },
      error: () => this.orderState.set('error'),
    });
  }

  private refetchOrder(): void {
    this.soService.getOrderByUid(this.uid()).subscribe({
      next: (o) => this.order.set(o),
      error: () => undefined,
    });
  }

  loadLines(): void {
    this.linesState.set('loading');
    this.soService.listOrderLines(this.uid()).subscribe({
      next: (rows) => { this.lines.set(rows); this.linesState.set('idle'); },
      error: () => this.linesState.set('error'),
    });
  }

  private loadDeliveries(): void {
    this.deliveriesState.set('loading');
    this.soService.listDeliveriesForOrder(this.uid()).subscribe({
      next: (rows) => { this.deliveries.set(rows); this.deliveriesState.set('idle'); },
      error: () => this.deliveriesState.set('error'),
    });
  }

  private loadUnitsForProduct(productUid: string): void {
    this.lineUnits.set([]);
    this.newLineUnitUid.set('');
    this.lineUnitsState.set('loading');
    this.productService.listProductUnits(productUid).subscribe({
      next: (units) => {
        this.lineUnits.set(units);
        this.lineUnitsState.set('idle');
        // Default-select the first returned unit (the base unit).
        if (units.length > 0) this.newLineUnitUid.set(units[0].uid);
      },
      error: () => this.lineUnitsState.set('error'),
    });
  }

  // ── Line form ──────────────────────────────────────────────────────────────────

  onProductSearchChange(q: string): void {
    this.productSearchQ.set(q);
    this.selectedProduct.set(null);
    this.newLineUnitUid.set('');
    this.lineUnits.set([]);
    this.productSearch$.next(q);
  }

  selectProduct(product: ProductModel): void {
    this.selectedProduct.set({ uid: product.uid, label: `${product.code} — ${product.name}` });
    this.productResults.set([]);
    this.productSearchQ.set(`${product.code} — ${product.name}`);
    this.loadUnitsForProduct(product.uid);
  }

  private asStr(v: unknown): string {
    return v === null || v === undefined ? '' : String(v).trim();
  }

  addLine(): void {
    const selected = this.selectedProduct();
    const unitUid = this.newLineUnitUid();
    const qty = this.asStr(this.newLineQty());

    if (!selected) { this.lineFormError.set('Select a product.'); return; }
    if (!unitUid) { this.lineFormError.set('Select a unit.'); return; }
    if (!qty || isNaN(Number(qty)) || Number(qty) <= 0) {
      this.lineFormError.set('Enter a valid quantity greater than zero.');
      return;
    }

    const price = this.asStr(this.newLineUnitPrice());
    const discPct = this.asStr(this.newLineDiscountPct());

    this.addingLine.set(true);
    this.lineFormError.set(null);

    const request: AddSalesOrderLineRequest = {
      productUid: selected.uid,
      unitUid,
      quantity: qty,
      unitPriceOverride: price || undefined,
      lineDiscountPercent: discPct || undefined,
    };

    this.soService.addOrderLine(this.uid(), request).subscribe({
      next: () => {
        this.selectedProduct.set(null);
        this.productSearchQ.set('');
        this.productResults.set([]);
        this.newLineUnitUid.set('');
        this.newLineQty.set('');
        this.newLineUnitPrice.set('');
        this.newLineDiscountPct.set('');
        this.addingLine.set(false);
        this.alerts.success('Line added');
        this.loadLines();
        this.refetchOrder();
      },
      error: (err: unknown) => {
        this.lineFormError.set(this.messageFrom(err, 'Could not add line.'));
        this.addingLine.set(false);
      },
    });
  }

  removeLine(line: SalesOrderLineDto): void {
    if (this.rowBusyLineUid() !== null) return;
    this.rowBusyLineUid.set(line.uid);
    this.soService.removeOrderLine(this.uid(), line.uid).subscribe({
      next: () => {
        this.rowBusyLineUid.set(null);
        this.alerts.success('Line removed');
        this.loadLines();
        this.refetchOrder();
      },
      error: () => this.rowBusyLineUid.set(null),
    });
  }

  // ── Confirm ────────────────────────────────────────────────────────────────────

  toggleConfirmDialog(): void {
    this.showConfirmDialog.update((v) => !v);
    this.confirmError.set(null);
  }

  confirmOrder(): void {
    if (this.confirming()) return;
    this.confirming.set(true);
    this.confirmError.set(null);
    this.soService.confirmOrder(this.uid()).subscribe({
      next: () => {
        this.confirming.set(false);
        this.showConfirmDialog.set(false);
        this.alerts.success('Order confirmed — stock reserved');
        this.loadOrder();
        this.loadLines();
      },
      error: (err: unknown) => {
        this.confirmError.set(this.messageFrom(err, 'Could not confirm order.'));
        this.confirming.set(false);
      },
    });
  }

  // ── Cancel ─────────────────────────────────────────────────────────────────────

  toggleCancelForm(): void {
    this.showCancelForm.update((v) => !v);
    this.cancelError.set(null);
    this.cancelReason.set('');
  }

  submitCancel(): void {
    if (this.cancelling()) return;
    this.cancelling.set(true);
    this.cancelError.set(null);
    const request: CancelSalesOrderRequest = { reason: this.cancelReason().trim() || undefined };
    this.soService.cancelOrder(this.uid(), request).subscribe({
      next: () => {
        this.cancelling.set(false);
        this.showCancelForm.set(false);
        this.alerts.success('Order cancelled');
        this.loadOrder();
      },
      error: (err: unknown) => {
        this.cancelError.set(this.messageFrom(err, 'Could not cancel order.'));
        this.cancelling.set(false);
      },
    });
  }

  // ── Display helpers ────────────────────────────────────────────────────────────

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}
