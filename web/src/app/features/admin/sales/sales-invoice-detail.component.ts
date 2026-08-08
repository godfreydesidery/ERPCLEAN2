import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, DestroyRef, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { debounceTime, distinctUntilChanged, Subject, switchMap } from 'rxjs';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { blobErrorMessage } from '../../../core/api/blob-error';
import { ProductModel } from '../models/product.model';
import {
  AddInvoiceLineRequest,
  AddPaymentRequest,
  FiscalReceiptDto,
  SalesInvoiceDto,
  SalesInvoiceLineDto,
  SalesInvoicePaymentDto,
  TenderType,
  VoidInvoiceRequest,
} from '../models/sales.model';
import { UnitOfMeasureDto } from '../models/product.model';
import { ProductService } from '../products/product.service';
import { DocumentsService } from '../documents/documents.service';
import { SalesService } from './sales.service';
import {
  ManagerApproval,
  ManagerApprovalDialogComponent,
} from '../../../shared/manager-approval/manager-approval-dialog.component';
import { DISCOUNT_OVERRIDE_PERMISSION, DiscountPolicyService } from './discount-policy.service';

type LoadState = 'loading' | 'idle' | 'error';

/**
 * Sales invoice detail + actions screen. Route: /admin/sales-invoices/uid/:uid.
 * Header shows status + number + customer/agent + totals.
 * Lines panel: add/remove lines (DRAFT only, SALES.INVOICE.CREATE); override a line's unit
 * price (DRAFT only, SALES.INVOICE.OVERRIDE, FR-SALES-08/BR-SALES-09) — a price >10x or <0.1x
 * the line's list price asks for a window.confirm before applying (soft fat-finger guard, never
 * a hard block: any positive price is allowed once confirmed); shows a rounding note when
 * requestedQuantity differs from the billed quantity (weighed-goods scale-step rounding).
 * Payments panel: add/remove payments (DRAFT only, SALES.INVOICE.SETTLE).
 * Finalize button: DRAFT + lines exist (SALES.INVOICE.CREATE).
 * Void button: any non-void status (SALES.INVOICE.VOID).
 *
 * All amounts displayed from server DTO fields — do NOT compute client-side.
 */
@Component({
  selector: 'app-sales-invoice-detail',
  imports: [FormsModule, RouterLink, ManagerApprovalDialogComponent],
  templateUrl: './sales-invoice-detail.component.html',
  styleUrl: './sales-invoice-detail.component.scss',
})
export class SalesInvoiceDetailComponent {
  private readonly salesService = inject(SalesService);
  private readonly productService = inject(ProductService);
  private readonly documentsService = inject(DocumentsService);
  private readonly discountPolicy = inject(DiscountPolicyService);
  private readonly alerts = inject(AlertService);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly session = inject(SessionStore);

  /** Route input bound via withComponentInputBinding. */
  readonly uid = input.required<string>();

  // ── Invoice header ─────────────────────────────────────────────────────────
  readonly invoice = signal<SalesInvoiceDto | null>(null);
  readonly invoiceState = signal<LoadState>('loading');

  // ── Lines ──────────────────────────────────────────────────────────────────
  readonly lines = signal<SalesInvoiceLineDto[]>([]);
  readonly linesState = signal<LoadState>('loading');
  readonly rowBusyLineUid = signal<string | null>(null);

  // ── Line price override (FR-SALES-08, BR-SALES-09 — DRAFT only, SALES.INVOICE.OVERRIDE) ────
  readonly overridingLineUid = signal<string | null>(null);
  readonly overridePriceInput = signal('');
  readonly overridePriceSaving = signal(false);
  readonly overridePriceError = signal<string | null>(null);

  // ── Add-line form ──────────────────────────────────────────────────────────
  /** Free-text search query for the product picker. */
  readonly productSearchQ = signal('');
  /** Results from the debounced product search. */
  readonly productResults = signal<ProductModel[]>([]);
  /** The product selected from productResults. */
  readonly selectedProduct = signal<{ uid: string; label: string } | null>(null);
  /** Units available for the selected product's company. */
  readonly lineUnits = signal<UnitOfMeasureDto[]>([]);
  readonly lineUnitsState = signal<'idle' | 'loading' | 'error'>('idle');
  readonly newLineUnitUid = signal('');
  readonly newLineQty = signal('');
  readonly newLineDiscountAmount = signal('');
  readonly newLineDiscountPercent = signal('');
  readonly addingLine = signal(false);
  readonly lineFormError = signal<string | null>(null);

  // ── Manager-authorised discount (K7) ───────────────────────────────────────
  // This screen carries the same ungated discount boxes as the till, and the server applies the
  // same policy to both (a POS sale is built out of these very add-line calls). Until now a
  // wholesale or credit invoice that tripped the ceiling was a dead end here: the refusal appeared
  // with no way forward, and the step-up existed only at the POS screen — so the answer to "the
  // system won't take my discount" was "go and ring it on a till", which is not an answer.
  //
  // The affordance appears only AFTER the server has refused, because this screen cannot know the
  // company's ceiling (the policy endpoint is gated on SALES.SETTINGS.MANAGE, which a salesperson
  // does not hold). Whether to offer it is decided on the refusal's machine-readable code, never on
  // its wording.

  /** True once the server refused an add-line in a way a supervisor could rescue. */
  readonly discountNeedsApproval = signal(false);
  /** Open state of the step-up prompt. */
  readonly approvalPromptOpen = signal(false);
  /** The approving supervisor, once they have signed. Sent with the next add-line attempt. */
  readonly discountAuthorisedByUid = signal<string | null>(null);
  readonly discountAuthorisedByName = signal<string | null>(null);
  /** Seeded permission a supervisor must hold to wave a discount through. */
  protected readonly discountOverridePermission = DISCOUNT_OVERRIDE_PERMISSION;

  /** True when the line being added carries a discount at all — nothing to approve otherwise. */
  readonly newLineHasDiscount = computed(
    () =>
      Number(this.newLineDiscountAmount().trim() || '0') > 0 ||
      Number(this.newLineDiscountPercent().trim() || '0') > 0,
  );

  /** Show the "Ask a supervisor" button only where it could actually help. */
  readonly canRequestDiscountApproval = computed(
    () =>
      this.discountNeedsApproval() &&
      this.newLineHasDiscount() &&
      this.discountAuthorisedByUid() === null,
  );

  /** One plain sentence naming what the supervisor is being asked to approve. */
  readonly approvalReason = computed(() => {
    const product = this.selectedProduct()?.label ?? 'this line';
    const amount = this.newLineDiscountAmount().trim();
    const percent = this.newLineDiscountPercent().trim();
    const size = percent ? `${percent}%` : amount ? amount : 'A discount';
    return `A discount of ${size} on ${product} needs a supervisor's approval.`;
  });

  private readonly productSearch$ = new Subject<string>();

  // ── Payments ───────────────────────────────────────────────────────────────
  readonly payments = signal<SalesInvoicePaymentDto[]>([]);
  readonly paymentsState = signal<LoadState>('loading');
  readonly rowBusyPaymentUid = signal<string | null>(null);

  // ── Add-payment form ───────────────────────────────────────────────────────
  readonly newPaymentTender = signal<TenderType>('CASH');
  readonly newPaymentAmount = signal('');
  readonly newPaymentReference = signal('');
  readonly addingPayment = signal(false);
  readonly paymentFormError = signal<string | null>(null);

  // ── Finalise ───────────────────────────────────────────────────────────────
  readonly finalising = signal(false);
  readonly finaliseError = signal<string | null>(null);

  // ── Void ───────────────────────────────────────────────────────────────────
  readonly showVoidForm = signal(false);
  readonly voidReason = signal('');
  readonly voiding = signal(false);
  readonly voidError = signal<string | null>(null);

  // ── Print PDF ────────────────────────────────────────────────────────────
  readonly printing = signal(false);
  readonly printError = signal<string | null>(null);

  // ── Fiscal receipt (D-6: EFD, ADR-0049) ─────────────────────────────────────
  readonly fiscalReceipt = signal<FiscalReceiptDto | null>(null);
  readonly fiscalReceiptState = signal<LoadState>('idle');
  readonly issuingFiscal = signal(false);

  // ── Permissions ────────────────────────────────────────────────────────────
  readonly canCreate = computed(() => this.session.hasPermission('SALES.INVOICE.CREATE'));
  readonly canSettle = computed(() => this.session.hasPermission('SALES.INVOICE.SETTLE'));
  readonly canVoid = computed(() => this.session.hasPermission('SALES.INVOICE.VOID'));
  readonly canPrint = computed(() => this.session.hasPermission('DOCUMENT.RENDER'));
  readonly canViewFiscal = computed(() => this.session.hasPermission('FISCAL.VIEW'));
  readonly canManageFiscal = computed(() => this.session.hasPermission('FISCAL.MANAGE'));
  readonly canOverridePrice = computed(() => this.session.hasPermission('SALES.INVOICE.OVERRIDE'));

  // ── Derived state ──────────────────────────────────────────────────────────
  readonly isDraft = computed(() => this.invoice()?.status === 'DRAFT');
  readonly isFinalised = computed(() => this.invoice()?.status === 'FINALISED');
  readonly isVoid = computed(() => this.invoice()?.status === 'VOID');

  readonly invoiceLabel = computed(() => {
    const inv = this.invoice();
    if (!inv) return '';
    return inv.invoiceNumber ?? 'DRAFT';
  });

  readonly hasLines = computed(() => this.lines().length > 0);

  /** ISSUED is terminal (never re-fiscalised) and VOID is blocked server-side (409) — no button. */
  readonly canShowFiscalIssue = computed(() => {
    const status = this.fiscalReceipt()?.status;
    return this.canManageFiscal() && status !== 'ISSUED' && status !== 'VOID';
  });

  /** A row already exists (any status) → this is a retry, not a first attempt. */
  readonly fiscalIssueLabel = computed(() =>
    this.fiscalReceipt() ? 'Retry EFD receipt' : 'Issue EFD receipt',
  );

  /** Running paid total from server-returned payment amounts. */
  readonly totalPaid = computed(() =>
    this.payments()
      .reduce((sum, p) => sum + parseFloat(p.amount || '0'), 0)
      .toFixed(2),
  );

  /** Remaining balance: grossTotal - totalPaid. Server is authoritative; this is display-only. */
  readonly remaining = computed(() => {
    const gross = parseFloat(this.invoice()?.grossTotalAmount || '0');
    const paid = parseFloat(this.totalPaid());
    return (gross - paid).toFixed(2);
  });

  readonly canFinalise = computed(() =>
    this.isDraft() && this.hasLines() && this.canCreate(),
  );

  constructor() {
    // Debounced product search for the line picker.
    this.productSearch$
      .pipe(
        debounceTime(300),
        distinctUntilChanged(),
        switchMap((q) => {
          const companyId = this.invoice()?.companyId;
          if (!companyId || !q.trim()) {
            this.productResults.set([]);
            return [];
          }
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
    this.loadInvoice();
    this.loadLines();
    this.loadPayments();
  }

  // ── Invoice ────────────────────────────────────────────────────────────────

  private loadInvoice(): void {
    this.invoiceState.set('loading');
    this.salesService.getByUid(this.uid()).subscribe({
      next: (inv) => {
        this.invoice.set(inv);
        this.invoiceState.set('idle');
        if (inv.status === 'FINALISED' && this.canViewFiscal()) this.loadFiscalReceipt();
      },
      error: () => this.invoiceState.set('error'),
    });
  }

  private refetchInvoice(): void {
    this.salesService.getByUid(this.uid()).subscribe({
      next: (inv) => this.invoice.set(inv),
      error: () => undefined,
    });
  }

  // ── Lines ──────────────────────────────────────────────────────────────────

  loadLines(): void {
    this.linesState.set('loading');
    this.salesService.listLines(this.uid()).subscribe({
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
    this.clearDiscountApproval();
  }

  selectProduct(product: ProductModel): void {
    this.selectedProduct.set({ uid: product.uid, label: `${product.code} — ${product.name}` });
    this.productResults.set([]);
    this.productSearchQ.set(`${product.code} — ${product.name}`);
    this.loadUnitsForProduct(product.uid);
    this.clearDiscountApproval();
  }

  /**
   * Called whenever the quantity or either discount box changes.
   *
   * A supervisor approved a specific discount on a specific line. Letting that approval survive an
   * edit would mean a signature given for 10% off silently covering 60% off — so any change to what
   * was approved throws the approval away and the salesperson must ask again.
   */
  clearDiscountApproval(): void {
    if (this.discountAuthorisedByUid() !== null) {
      this.discountAuthorisedByUid.set(null);
      this.discountAuthorisedByName.set(null);
    }
  }

  addLine(): void {
    const selected = this.selectedProduct();
    const unitUid = this.newLineUnitUid();
    const qty = this.newLineQty().trim();

    if (!selected) {
      this.lineFormError.set('Select a product.');
      return;
    }
    if (!unitUid) {
      this.lineFormError.set('Select a unit.');
      return;
    }
    if (!qty || isNaN(Number(qty)) || Number(qty) <= 0) {
      this.lineFormError.set('Enter a valid quantity greater than zero.');
      return;
    }

    this.addingLine.set(true);
    this.lineFormError.set(null);

    const request: AddInvoiceLineRequest = {
      productUid: selected.uid,
      unitUid,
      quantity: qty,
      lineDiscountAmount: this.newLineDiscountAmount().trim() || undefined,
      lineDiscountPercent: this.newLineDiscountPercent().trim() || undefined,
      // K7: only ever sent when a supervisor actually signed for THIS discount. The server
      // re-resolves the uid and requires that user to be active and to genuinely hold the override
      // in this invoice's company, so sending it is a pointer to an approval, never the approval.
      discountAuthorisedByUid: this.discountAuthorisedByUid() ?? undefined,
    };

    this.salesService.addLine(this.uid(), request).subscribe({
      next: () => {
        this.selectedProduct.set(null);
        this.productSearchQ.set('');
        this.productResults.set([]);
        this.newLineUnitUid.set('');
        this.newLineQty.set('');
        this.newLineDiscountAmount.set('');
        this.newLineDiscountPercent.set('');
        this.discountNeedsApproval.set(false);
        this.discountAuthorisedByUid.set(null);
        this.discountAuthorisedByName.set(null);
        this.addingLine.set(false);
        this.alerts.success('Line added');
        this.loadLines();
        this.refetchInvoice();
      },
      error: (err) => {
        this.lineFormError.set(this.messageFrom(err, 'Could not add line.'));
        // Offer the step-up when the server's refusal is one a supervisor could sign off. Decided on
        // the refusal's machine-readable code where the endpoint sends one, and otherwise on the
        // HTTP status — never on the message text, which is written for the human and gets reworded.
        // A rejected approval clears the stale authoriser so the next attempt asks again rather than
        // resending a signature the server has already turned down.
        if (this.discountPolicy.approvalMayRescue(err)) {
          this.discountAuthorisedByUid.set(null);
          this.discountAuthorisedByName.set(null);
          this.discountNeedsApproval.set(true);
        }
        this.addingLine.set(false);
      },
    });
  }

  // ── Manager-authorised discount (K7) ───────────────────────────────────────

  requestDiscountApproval(): void {
    this.approvalPromptOpen.set(true);
  }

  cancelDiscountApproval(): void {
    this.approvalPromptOpen.set(false);
  }

  /**
   * A supervisor signed. Stamp the approval on the pending line and send it straight away — making
   * them press "Add Line" again after a manager has already walked over and typed their password is
   * one step too many at a counter with a customer waiting.
   */
  onDiscountApprovalGranted(approval: ManagerApproval): void {
    this.discountAuthorisedByUid.set(approval.authoriserUid);
    this.discountAuthorisedByName.set(approval.authoriserName);
    this.approvalPromptOpen.set(false);
    this.lineFormError.set(null);
    this.addLine();
  }

  removeLine(line: SalesInvoiceLineDto): void {
    if (this.rowBusyLineUid() !== null) return;
    this.rowBusyLineUid.set(line.uid);
    this.salesService.removeLine(this.uid(), line.uid).subscribe({
      next: () => {
        this.rowBusyLineUid.set(null);
        this.alerts.success('Line removed');
        this.loadLines();
        this.refetchInvoice();
      },
      error: () => this.rowBusyLineUid.set(null),
    });
  }

  // ── Line price override (FR-SALES-08, BR-SALES-09) ──────────────────────────

  startOverridePrice(line: SalesInvoiceLineDto): void {
    this.overridingLineUid.set(line.uid);
    this.overridePriceInput.set(line.unitPriceAmount);
    this.overridePriceError.set(null);
  }

  cancelOverridePrice(): void {
    this.overridingLineUid.set(null);
    this.overridePriceError.set(null);
  }

  saveOverridePrice(line: SalesInvoiceLineDto): void {
    const entered = this.overridePriceInput().trim();
    const price = Number(entered);
    if (!entered || Number.isNaN(price) || price <= 0) {
      this.overridePriceError.set('Enter a valid unit price greater than zero.');
      return;
    }

    // Soft fat-finger guard (persona re-test, Sabina): never blocks a manager override — any
    // positive price is allowed — but a price wildly off the list price needs a confirm click.
    // Cancelling leaves the editor open with the entered value untouched.
    if (!this.confirmIfFarFromListPrice(line, price)) return;

    if (this.overridePriceSaving()) return;
    this.overridePriceSaving.set(true);
    this.overridePriceError.set(null);

    this.salesService.overrideLinePrice(this.uid(), line.uid, price).subscribe({
      next: (updated) => {
        this.overridePriceSaving.set(false);
        this.overridingLineUid.set(null);
        this.lines.update((rows) => rows.map((r) => (r.uid === updated.uid ? updated : r)));
        this.alerts.success('Line price overridden');
        this.refetchInvoice();
      },
      error: (err) => {
        this.overridePriceError.set(this.messageFrom(err, 'Could not override the price.'));
        this.overridePriceSaving.set(false);
      },
    });
  }

  /**
   * True when no confirmation is needed (list price unknown/zero, or the entered price is within
   * ~0.1x–10x of it) or the user confirmed a wildly-off price; false when they cancelled — the
   * caller must not proceed. Uses window.confirm (existing app precedent: mass-price-change /
   * company restore-defaults) rather than a bespoke dialog.
   */
  private confirmIfFarFromListPrice(line: SalesInvoiceLineDto, price: number): boolean {
    const listPrice = Number(line.listPriceAmount);
    if (!(listPrice > 0)) return true;
    const ratio = price / listPrice;
    if (ratio <= 10 && ratio >= 0.1) return true;
    const multiple = ratio >= 10 ? Math.round(ratio) : Math.round(ratio * 100) / 100;
    return window.confirm(
      `This is about ${multiple}× the list price (${line.listPriceAmount}). Apply anyway?`,
    );
  }

  /**
   * True when the caller entered a different quantity than what was billed — a weighed-goods
   * scale-step rounding event (FR-SALES-08). requestedQuantity is set on every add/update-line
   * call, so compare numerically (it may be null only for lines predating the field).
   */
  quantityWasRounded(line: SalesInvoiceLineDto): boolean {
    return line.requestedQuantity != null && Number(line.requestedQuantity) !== Number(line.quantity);
  }

  // ── Payments ───────────────────────────────────────────────────────────────

  loadPayments(): void {
    this.paymentsState.set('loading');
    this.salesService.listPayments(this.uid()).subscribe({
      next: (rows) => {
        this.payments.set(rows);
        this.paymentsState.set('idle');
      },
      error: () => this.paymentsState.set('error'),
    });
  }

  addPayment(): void {
    const amount = this.newPaymentAmount().trim();
    const currency = this.invoice()?.currency ?? 'TZS';

    if (!amount || isNaN(Number(amount)) || Number(amount) <= 0) {
      this.paymentFormError.set('Enter a valid amount greater than zero.');
      return;
    }

    this.addingPayment.set(true);
    this.paymentFormError.set(null);

    const request: AddPaymentRequest = {
      tenderType: this.newPaymentTender(),
      amount,
      currency,
      reference: this.newPaymentReference().trim() || undefined,
    };

    this.salesService.addPayment(this.uid(), request).subscribe({
      next: () => {
        this.newPaymentTender.set('CASH');
        this.newPaymentAmount.set('');
        this.newPaymentReference.set('');
        this.addingPayment.set(false);
        this.alerts.success('Payment recorded');
        this.loadPayments();
        this.refetchInvoice();
      },
      error: (err) => {
        this.paymentFormError.set(this.messageFrom(err, 'Could not record payment.'));
        this.addingPayment.set(false);
      },
    });
  }

  removePayment(payment: SalesInvoicePaymentDto): void {
    if (this.rowBusyPaymentUid() !== null) return;
    this.rowBusyPaymentUid.set(payment.uid);
    this.salesService.removePayment(this.uid(), payment.uid).subscribe({
      next: () => {
        this.rowBusyPaymentUid.set(null);
        this.alerts.success('Payment removed');
        this.loadPayments();
        this.refetchInvoice();
      },
      error: () => this.rowBusyPaymentUid.set(null),
    });
  }

  // ── Finalise ───────────────────────────────────────────────────────────────

  finalise(): void {
    if (this.finalising()) return;
    this.finalising.set(true);
    this.finaliseError.set(null);
    this.salesService.finalise(this.uid()).subscribe({
      next: () => {
        this.finalising.set(false);
        this.alerts.success('Invoice finalised');
        this.loadInvoice();
      },
      error: (err) => {
        this.finaliseError.set(this.messageFrom(err, 'Could not finalise invoice.'));
        this.finalising.set(false);
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
    if (!reason) {
      this.voidError.set('A void reason is required.');
      return;
    }
    if (this.voiding()) return;
    this.voiding.set(true);
    this.voidError.set(null);

    const request: VoidInvoiceRequest = { reason };
    this.salesService.void(this.uid(), request).subscribe({
      next: () => {
        this.voiding.set(false);
        this.showVoidForm.set(false);
        this.alerts.success('Invoice voided');
        this.loadInvoice();
      },
      error: (err) => {
        this.voidError.set(this.messageFrom(err, 'Could not void invoice.'));
        this.voiding.set(false);
      },
    });
  }

  // ── Print PDF ────────────────────────────────────────────────────────────

  /**
   * Render the invoice as a PDF and trigger a browser download.
   * Mirrors the documents-module pattern (DocumentsService.renderBlob →
   * triggerBlobDownload). Hidden for DRAFT invoices in the template — the backend
   * only renders FINALISED or VOID invoices (an unfinalised invoice has no
   * numbers to hand over).
   */
  printPdf(): void {
    if (this.printing()) return;
    this.printing.set(true);
    this.printError.set(null);
    this.documentsService
      .renderBlob('INVOICE', this.uid())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (blob) => {
          this.printing.set(false);
          const name = `invoice-${this.invoice()?.invoiceNumber ?? this.uid()}.pdf`;
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

  // ── Fiscal receipt (D-6: EFD, ADR-0049) ─────────────────────────────────────

  /** Loads the invoice's fiscal receipt; absence (backend 404) resolves to `null`, not an error. */
  loadFiscalReceipt(): void {
    this.fiscalReceiptState.set('loading');
    this.salesService.getFiscalReceipt(this.uid()).subscribe({
      next: (receipt) => {
        this.fiscalReceipt.set(receipt);
        this.fiscalReceiptState.set('idle');
      },
      error: () => this.fiscalReceiptState.set('error'),
    });
  }

  /**
   * Issue or retry the invoice's fiscal receipt — a single idempotent endpoint (ADR-0049 §4):
   * no prior row issues one, a FAILED/NOT_CONFIGURED/PENDING row retries in place, an ISSUED
   * row is a no-op that just re-returns itself.
   */
  issueFiscalReceipt(): void {
    if (this.issuingFiscal()) return;
    this.issuingFiscal.set(true);
    this.salesService.issueFiscalReceipt(this.uid()).subscribe({
      next: (receipt) => {
        this.fiscalReceipt.set(receipt);
        this.fiscalReceiptState.set('idle');
        this.issuingFiscal.set(false);
        // The endpoint returns 200 for every outcome — only ISSUED is a success. FAILED /
        // NOT_CONFIGURED (and any non-issued state) did not produce a receipt, so surface them as an
        // error the user must acknowledge, not an auto-dismissing green toast.
        const message = this.fiscalOutcomeMessage(receipt);
        if (receipt.status === 'ISSUED') {
          this.alerts.success(message);
        } else {
          this.alerts.error(message);
        }
      },
      error: (err) => {
        this.issuingFiscal.set(false);
        this.alerts.error('Could not issue the fiscal receipt.', this.messageFrom(err, ''));
      },
    });
  }

  /** Friendly, non-technical echo of the outcome status — surfaced via the alert service. */
  private fiscalOutcomeMessage(receipt: FiscalReceiptDto): string {
    switch (receipt.status) {
      case 'ISSUED':
        return `Fiscal receipt issued — number ${receipt.fiscalNumber}`;
      case 'NOT_CONFIGURED':
        return 'No EFD device is configured for this company yet.';
      case 'FAILED':
        return receipt.errorDetail ?? 'The fiscal receipt attempt failed. You can retry.';
      default:
        return 'Fiscal receipt status updated.';
    }
  }

  // ── Display helpers ────────────────────────────────────────────────────────

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
