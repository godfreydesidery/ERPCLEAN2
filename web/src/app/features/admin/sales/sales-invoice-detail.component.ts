import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { debounceTime, distinctUntilChanged, Subject, switchMap } from 'rxjs';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { ProductModel } from '../models/product.model';
import {
  AddInvoiceLineRequest,
  AddPaymentRequest,
  SalesInvoiceDto,
  SalesInvoiceLineDto,
  SalesInvoicePaymentDto,
  TenderType,
  VoidInvoiceRequest,
} from '../models/sales.model';
import { UnitOfMeasureDto } from '../models/product.model';
import { ProductService } from '../products/product.service';
import { SalesService } from './sales.service';

type LoadState = 'loading' | 'idle' | 'error';

/**
 * Sales invoice detail + actions screen. Route: /admin/sales-invoices/uid/:uid.
 * Header shows status + number + customer/agent + totals.
 * Lines panel: add/remove lines (DRAFT only, SALES.INVOICE.CREATE).
 * Payments panel: add/remove payments (DRAFT only, SALES.INVOICE.SETTLE).
 * Finalize button: DRAFT + lines exist (SALES.INVOICE.CREATE).
 * Void button: any non-void status (SALES.INVOICE.VOID).
 *
 * All amounts displayed from server DTO fields — do NOT compute client-side.
 */
@Component({
  selector: 'app-sales-invoice-detail',
  imports: [FormsModule, RouterLink],
  templateUrl: './sales-invoice-detail.component.html',
  styleUrl: './sales-invoice-detail.component.scss',
})
export class SalesInvoiceDetailComponent {
  private readonly salesService = inject(SalesService);
  private readonly productService = inject(ProductService);
  private readonly alerts = inject(AlertService);
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

  // ── Permissions ────────────────────────────────────────────────────────────
  readonly canCreate = computed(() => this.session.hasPermission('SALES.INVOICE.CREATE'));
  readonly canSettle = computed(() => this.session.hasPermission('SALES.INVOICE.SETTLE'));
  readonly canVoid = computed(() => this.session.hasPermission('SALES.INVOICE.VOID'));

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
        // Load units for the add-line form once we know the company.
        this.loadUnitsForCompany(inv.companyId);
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
        this.addingLine.set(false);
        this.alerts.success('Line added');
        this.loadLines();
        this.refetchInvoice();
      },
      error: (err) => {
        this.lineFormError.set(this.messageFrom(err, 'Could not add line.'));
        this.addingLine.set(false);
      },
    });
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

  // ── Display helpers ────────────────────────────────────────────────────────

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}
