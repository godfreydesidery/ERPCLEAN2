import { HttpErrorResponse } from '@angular/common/http';
import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, computed, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { debounceTime, distinctUntilChanged, Subject, switchMap } from 'rxjs';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { ProductModel, UnitOfMeasureDto } from '../models/product.model';
import {
  AddQuotationLineRequest,
  QuotationDto,
  QuotationLineDto,
} from '../models/sales-orders.model';
import { ProductService } from '../products/product.service';
import { SalesOrdersService } from './sales-orders.service';

type LoadState = 'loading' | 'idle' | 'error';

/**
 * Quotation detail + lifecycle screen. Route: /admin/quotations/uid/:uid.
 * Actions: DRAFT → Send, Accept (converts to SO, navigates), Reject.
 * Lines panel (DRAFT only): add / remove lines.
 */
@Component({
  selector: 'app-quotation-detail',
  imports: [FormsModule, RouterLink, DatePipe, DecimalPipe],
  templateUrl: './quotation-detail.component.html',
  styleUrl: './quotation-detail.component.scss',
})
export class QuotationDetailComponent {
  private readonly soService = inject(SalesOrdersService);
  private readonly productService = inject(ProductService);
  private readonly alerts = inject(AlertService);
  private readonly router = inject(Router);
  protected readonly session = inject(SessionStore);

  readonly uid = input.required<string>();

  // ── Quote header ────────────────────────────────────────────────────────────
  readonly quote = signal<QuotationDto | null>(null);
  readonly quoteState = signal<LoadState>('loading');

  // ── Lines ───────────────────────────────────────────────────────────────────
  readonly lines = signal<QuotationLineDto[]>([]);
  readonly linesState = signal<LoadState>('loading');
  readonly rowBusyLineUid = signal<string | null>(null);

  // ── Add-line form ────────────────────────────────────────────────────────────
  readonly productSearchQ = signal('');
  readonly productResults = signal<ProductModel[]>([]);
  readonly selectedProduct = signal<{ uid: string; label: string } | null>(null);
  readonly lineUnits = signal<UnitOfMeasureDto[]>([]);
  readonly lineUnitsState = signal<'idle' | 'loading' | 'error'>('idle');
  readonly newLineUnitUid = signal('');
  readonly newLineQty = signal('');
  readonly newLineUnitPrice = signal('');
  readonly newLineDiscountPct = signal('');
  /** The product's price-list (Co.) price, fetched on product select; override is optional. */
  readonly resolvedPrice = signal<string | null>(null);
  readonly priceState = signal<'idle' | 'loading' | 'ok' | 'missing'>('idle');
  readonly addingLine = signal(false);
  readonly lineFormError = signal<string | null>(null);

  // ── Send ─────────────────────────────────────────────────────────────────────
  readonly sending = signal(false);
  readonly sendError = signal<string | null>(null);

  // ── Accept ───────────────────────────────────────────────────────────────────
  readonly accepting = signal(false);
  readonly acceptError = signal<string | null>(null);

  // ── Reject ───────────────────────────────────────────────────────────────────
  readonly rejecting = signal(false);
  readonly rejectError = signal<string | null>(null);

  // ── Permissions ──────────────────────────────────────────────────────────────
  readonly canCreate = computed(() => this.session.hasPermission('SALES.QUOTE.CREATE'));
  readonly canSend = computed(() => this.session.hasPermission('SALES.QUOTE.SEND'));
  readonly canAccept = computed(() => this.session.hasPermission('SALES.QUOTE.ACCEPT'));

  // ── Derived ──────────────────────────────────────────────────────────────────
  readonly isDraft = computed(() => this.quote()?.status === 'DRAFT');
  readonly isSent = computed(() => this.quote()?.status === 'SENT');
  readonly isAccepted = computed(() => this.quote()?.status === 'ACCEPTED');
  readonly isRejected = computed(() => this.quote()?.status === 'REJECTED');
  readonly isEditable = computed(() => this.isDraft());
  readonly canSendNow = computed(() => this.isDraft() && this.canSend() && this.lines().length > 0);
  readonly canAcceptNow = computed(() => this.isSent() && this.canAccept());
  readonly canRejectNow = computed(() => this.isSent() && this.canAccept());

  readonly quoteLabel = computed(() => this.quote()?.quoteNumber ?? 'DRAFT');

  private readonly productSearch$ = new Subject<string>();

  constructor() {
    this.productSearch$
      .pipe(
        debounceTime(300),
        distinctUntilChanged(),
        switchMap((q) => {
          const companyId = this.quote()?.companyId;
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
    this.loadQuote();
    this.loadLines();
  }

  private loadQuote(): void {
    this.quoteState.set('loading');
    this.soService.getQuotationByUid(this.uid()).subscribe({
      next: (q) => {
        this.quote.set(q);
        this.quoteState.set('idle');
        this.loadUnitsForCompany(q.companyId);
      },
      error: () => this.quoteState.set('error'),
    });
  }

  private refetchQuote(): void {
    this.soService.getQuotationByUid(this.uid()).subscribe({
      next: (q) => this.quote.set(q),
      error: () => undefined,
    });
  }

  loadLines(): void {
    this.linesState.set('loading');
    this.soService.listQuotationLines(this.uid()).subscribe({
      next: (rows) => { this.lines.set(rows); this.linesState.set('idle'); },
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
    this.resolvedPrice.set(null);
    this.priceState.set('idle');
    this.productSearch$.next(q);
  }

  selectProduct(product: ProductModel): void {
    this.selectedProduct.set({ uid: product.uid, label: `${product.code} — ${product.name}` });
    this.productResults.set([]);
    this.productSearchQ.set(`${product.code} — ${product.name}`);
    this.fetchLinePrice(product.uid);
  }

  /** Fetch the product's price-list price for this company so the Co. price is visible before submit. */
  private fetchLinePrice(productUid: string): void {
    const companyId = this.quote()?.companyId;
    this.priceState.set('loading');
    this.resolvedPrice.set(null);
    this.productService.listPrices(productUid).subscribe({
      next: (rows) => {
        const row = rows.find((p) => p.companyId === companyId) ?? rows[0];
        const amount = row?.price?.amount ?? null;
        this.resolvedPrice.set(amount);
        this.priceState.set(amount != null ? 'ok' : 'missing');
      },
      error: () => { this.resolvedPrice.set(null); this.priceState.set('missing'); },
    });
  }

  private asStr(v: unknown): string {
    return v === null || v === undefined ? '' : String(v).trim();
  }

  addLine(): void {
    const selected = this.selectedProduct();
    const unitUid = this.newLineUnitUid();
    const qty = this.asStr(this.newLineQty());
    const price = this.asStr(this.newLineUnitPrice());

    if (!selected) { this.lineFormError.set('Select a product.'); return; }
    if (!unitUid) { this.lineFormError.set('Select a unit.'); return; }
    if (!qty || isNaN(Number(qty)) || Number(qty) <= 0) {
      this.lineFormError.set('Enter a valid quantity greater than zero.');
      return;
    }

    const discPct = this.asStr(this.newLineDiscountPct());

    this.addingLine.set(true);
    this.lineFormError.set(null);

    const request: AddQuotationLineRequest = {
      productUid: selected.uid,
      unitUid,
      quantity: qty,
      unitPriceOverride: price || undefined,
      lineDiscountPercent: discPct || undefined,
    };

    this.soService.addQuotationLine(this.uid(), request).subscribe({
      next: () => {
        this.selectedProduct.set(null);
        this.productSearchQ.set('');
        this.productResults.set([]);
        this.newLineUnitUid.set('');
        this.newLineQty.set('');
        this.newLineUnitPrice.set('');
        this.newLineDiscountPct.set('');
        this.resolvedPrice.set(null);
        this.priceState.set('idle');
        this.addingLine.set(false);
        this.alerts.success('Line added');
        this.loadLines();
        this.refetchQuote();
      },
      error: (err: unknown) => {
        this.lineFormError.set(this.messageFrom(err, 'Could not add line.'));
        this.addingLine.set(false);
      },
    });
  }

  removeLine(line: QuotationLineDto): void {
    if (this.rowBusyLineUid() !== null) return;
    this.rowBusyLineUid.set(line.uid);
    this.soService.removeQuotationLine(this.uid(), line.uid).subscribe({
      next: () => {
        this.rowBusyLineUid.set(null);
        this.alerts.success('Line removed');
        this.loadLines();
        this.refetchQuote();
      },
      error: () => this.rowBusyLineUid.set(null),
    });
  }

  // ── Send ─────────────────────────────────────────────────────────────────────

  send(): void {
    if (this.sending()) return;
    this.sending.set(true);
    this.sendError.set(null);
    this.soService.sendQuotation(this.uid()).subscribe({
      next: () => {
        this.sending.set(false);
        this.alerts.success('Quotation sent');
        this.loadQuote();
      },
      error: (err: unknown) => {
        this.sendError.set(this.messageFrom(err, 'Could not send quotation.'));
        this.sending.set(false);
      },
    });
  }

  // ── Accept (converts to SO) ───────────────────────────────────────────────────

  accept(): void {
    if (this.accepting()) return;
    this.accepting.set(true);
    this.acceptError.set(null);
    this.soService.acceptQuotation(this.uid()).subscribe({
      next: (so) => {
        this.accepting.set(false);
        this.alerts.success('Quotation accepted — Sales Order created', so.orderNumber ?? '');
        this.router.navigateByUrl(`/admin/sales-orders/uid/${so.uid}`);
      },
      error: (err: unknown) => {
        this.acceptError.set(this.messageFrom(err, 'Could not accept quotation.'));
        this.accepting.set(false);
      },
    });
  }

  // ── Reject ────────────────────────────────────────────────────────────────────

  reject(): void {
    if (this.rejecting()) return;
    this.rejecting.set(true);
    this.rejectError.set(null);
    this.soService.rejectQuotation(this.uid()).subscribe({
      next: () => {
        this.rejecting.set(false);
        this.alerts.success('Quotation rejected');
        this.loadQuote();
      },
      error: (err: unknown) => {
        this.rejectError.set(this.messageFrom(err, 'Could not reject quotation.'));
        this.rejecting.set(false);
      },
    });
  }

  // ── Display helpers ───────────────────────────────────────────────────────────

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}
