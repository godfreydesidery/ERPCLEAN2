import { HttpErrorResponse } from '@angular/common/http';
import { DatePipe } from '@angular/common';
import { Component, computed, DestroyRef, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { debounceTime, distinctUntilChanged, Subject, switchMap } from 'rxjs';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { blobErrorMessage } from '../../../core/api/blob-error';
import { ProductModel, UnitOfMeasureDto } from '../models/product.model';
import {
  AddPurchaseOrderLineRequest,
  PoApprovalStatus,
  PurchaseOrderDto,
  PurchaseOrderLineDto,
  PurchaseSettingsDto,
  VoidPurchaseOrderRequest,
} from '../models/purchases.model';
import { ProductService } from '../products/product.service';
import { DocumentsService } from '../documents/documents.service';
import { PurchasesService } from './purchases.service';
import { PurchaseSettingsService } from './settings/purchase-settings.service';

type LoadState = 'loading' | 'idle' | 'error';

/**
 * Purchase Order detail + lifecycle screen. Route: /admin/purchase-orders/uid/:uid.
 * Header: status, order number, supplier, totals.
 * Lines panel (DRAFT only): add/edit/remove lines — product + unit picker, qty, unit cost.
 * Actions:
 *   DRAFT  → Submit for Approval (when approval gate enabled + total >= threshold),
 *             Place (PURCHASE.ORDER.CREATE — only when APPROVED or no approval required),
 *             Void (PURCHASE.ORDER.VOID)
 *   ORDERED / PARTIALLY_RECEIVED → Receive (link to GR create), Close, Void
 *   RECEIVED → Close, Void
 *   CLOSED / VOID → read-only
 * Received / outstanding qty shown per line from server DTO fields — no client computation.
 *
 * Approval status note: the backend PurchaseOrderDto record does not yet include approvalStatus
 * in its serialised output. The component therefore fetches PurchaseSettings to decide whether to
 * show the Submit-for-Approval button, and tracks the current session's approval state via a local
 * signal (localApprovalStatus) so the PENDING banner persists while the user stays on the page.
 * If the DTO later gains the field, po().approvalStatus takes precedence via effectiveApprovalStatus.
 */
@Component({
  selector: 'app-purchase-order-detail',
  imports: [FormsModule, RouterLink, DatePipe],
  templateUrl: './purchase-order-detail.component.html',
  styleUrl: './purchase-order-detail.component.scss',
})
export class PurchaseOrderDetailComponent {
  private readonly purchasesService = inject(PurchasesService);
  private readonly purchaseSettingsService = inject(PurchaseSettingsService);
  private readonly productService = inject(ProductService);
  private readonly documentsService = inject(DocumentsService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);
  private readonly destroyRef = inject(DestroyRef);

  /** Route input via withComponentInputBinding. */
  readonly uid = input.required<string>();

  // ── PO header ──────────────────────────────────────────────────────────────
  readonly po = signal<PurchaseOrderDto | null>(null);
  readonly poState = signal<LoadState>('loading');

  // ── Purchase Settings (approval gate) ──────────────────────────────────────
  /** Null = not yet loaded or unavailable; loaded once on init. */
  readonly purchaseSettings = signal<PurchaseSettingsDto | null>(null);

  /**
   * Local approval-status mirror. Seeded from po().approvalStatus if the backend ever emits it;
   * otherwise updated when the user submits for approval in this session.
   */
  readonly localApprovalStatus = signal<PoApprovalStatus | null>(null);

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

  // ── Submit for Approval ────────────────────────────────────────────────────
  readonly submitting = signal(false);
  readonly submitError = signal<string | null>(null);

  // ── Close ──────────────────────────────────────────────────────────────────
  readonly closing = signal(false);
  readonly closeError = signal<string | null>(null);

  // ── Void ──────────────────────────────────────────────────────────────────
  readonly showVoidForm = signal(false);
  readonly voidReason = signal('');
  readonly voiding = signal(false);
  readonly voidError = signal<string | null>(null);

  // ── Print PDF ────────────────────────────────────────────────────────────
  readonly printing = signal(false);
  readonly printError = signal<string | null>(null);

  // ── Permissions ────────────────────────────────────────────────────────────
  readonly canCreate = computed(() => this.session.hasPermission('PURCHASE.ORDER.CREATE'));
  readonly canVoid = computed(() => this.session.hasPermission('PURCHASE.ORDER.VOID'));
  readonly canReceive = computed(() => this.session.hasPermission('PURCHASE.RECEIVE'));
  readonly canPrint = computed(() => this.session.hasPermission('DOCUMENT.RENDER'));

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

  /**
   * The effective approval status: prefer the field from the PO DTO (if the backend ever emits
   * it), otherwise fall back to the locally tracked signal that reflects this session's action.
   */
  readonly effectiveApprovalStatus = computed<PoApprovalStatus | null>(() => {
    const fromDto = this.po()?.approvalStatus;
    if (fromDto) return fromDto;
    return this.localApprovalStatus();
  });

  /**
   * True when the approval gate is enabled and the PO's current total meets or exceeds the
   * threshold. BigDecimal comes as a JSON string from the wire — coerce with Number() before
   * comparing. If settings are unavailable, returns false (fail-safe: don't block "Place").
   */
  readonly requiresApproval = computed(() => {
    const settings = this.purchaseSettings();
    const po = this.po();
    if (!settings || !po) return false;
    if (!settings.poApprovalEnabled) return false;
    const threshold = Number(settings.poApprovalThresholdAmount);
    const total = Number(po.orderTotalAmount);
    return Number.isFinite(total) && Number.isFinite(threshold) && total >= threshold;
  });

  /**
   * DRAFT POs show "Submit for Approval" when the gate requires it AND the order isn't already
   * pending or approved.
   */
  readonly showSubmitForApproval = computed(() => {
    if (!this.isDraft() || !this.canCreate()) return false;
    if (!this.requiresApproval()) return false;
    const status = this.effectiveApprovalStatus();
    return status !== 'PENDING' && status !== 'APPROVED';
  });

  /**
   * "Place Order" is available on DRAFT when: no approval is required, OR approval has been
   * granted (APPROVED). Hidden while PENDING or REJECTED.
   */
  readonly canPlace = computed(() => {
    if (!this.isDraft() || !this.hasLines() || !this.canCreate()) return false;
    if (!this.requiresApproval()) return true;
    return this.effectiveApprovalStatus() === 'APPROVED';
  });

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
    this.loadSettings();
  }

  // ── PO ─────────────────────────────────────────────────────────────────────

  private loadPo(): void {
    this.poState.set('loading');
    this.purchasesService.getOrderByUid(this.uid()).subscribe({
      next: (po) => {
        this.po.set(po);
        this.poState.set('idle');
        // Seed the local approval status from the DTO field when the backend provides it.
        if (po.approvalStatus) {
          this.localApprovalStatus.set(po.approvalStatus);
        }
      },
      error: () => this.poState.set('error'),
    });
  }

  private refetchPo(): void {
    this.purchasesService.getOrderByUid(this.uid()).subscribe({
      next: (po) => {
        this.po.set(po);
        if (po.approvalStatus) {
          this.localApprovalStatus.set(po.approvalStatus);
        }
      },
      error: () => undefined,
    });
  }

  // ── Settings (approval gate) ───────────────────────────────────────────────

  private loadSettings(): void {
    const companyUid = this.session.user()?.activeCompanyUid;
    if (!companyUid) return;
    this.purchaseSettingsService.getByCompany(companyUid).subscribe({
      next: (s) => this.purchaseSettings.set(s),
      // Non-critical: if settings fail to load, approval logic falls back to "not required"
      // (fail-safe) — the user can still place the order; backend enforces the gate regardless.
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
    if (!qty || Number.isNaN(Number(qty)) || Number(qty) <= 0) {
      this.lineFormError.set('Enter a valid quantity greater than zero.');
      return;
    }
    if (!cost || Number.isNaN(Number(cost)) || Number(cost) < 0) {
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

  // ── Submit for Approval ────────────────────────────────────────────────────

  submitForApproval(): void {
    if (this.submitting()) return;
    this.submitting.set(true);
    this.submitError.set(null);
    this.purchasesService.submitForApproval(this.uid()).subscribe({
      next: () => {
        this.submitting.set(false);
        this.alerts.success('Order submitted for approval');
        // The backend response DTO does not yet carry approvalStatus; track locally.
        this.localApprovalStatus.set('PENDING');
        this.refetchPo();
      },
      error: (err) => {
        this.submitError.set(this.messageFrom(err, 'Could not submit order for approval.'));
        this.submitting.set(false);
      },
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

  // ── Print PDF ────────────────────────────────────────────────────────────

  /**
   * Render the purchase order as a PDF and trigger a browser download.
   * Mirrors the documents-module pattern (DocumentsService.renderBlob →
   * triggerBlobDownload). Hidden for DRAFT orders in the template so an
   * unconfirmed order is never shared with a supplier.
   */
  printPdf(): void {
    if (this.printing()) return;
    this.printing.set(true);
    this.printError.set(null);
    this.documentsService
      .renderBlob('PURCHASE_ORDER', this.uid())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (blob) => {
          this.printing.set(false);
          const name = `purchase-order-${this.po()?.orderNumber ?? this.uid()}.pdf`;
          triggerBlobDownload(blob, name);
        },
        error: (err) => {
          this.printing.set(false);
          // The error body is a Blob (responseType: 'blob'), so read the friendly server message
          // out of it; falls back to a generic line if it isn't a JSON error envelope.
          void blobErrorMessage(err, 'Could not generate the PDF.').then((m) => this.printError.set(m));
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

/** Create an object URL for a blob and click a synthetic anchor to download it. */
function triggerBlobDownload(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  anchor.click();
  URL.revokeObjectURL(url);
}
