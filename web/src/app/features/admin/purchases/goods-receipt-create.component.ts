import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { debounceTime, distinctUntilChanged, Subject, switchMap } from 'rxjs';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { Company } from '../models/company.model';
import {
  CreateGoodsReceiptRequest,
  GoodsReceiptLineRequest,
  PurchaseOrderDto,
  PurchaseOrderLineDto,
} from '../models/purchases.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { PurchasesService } from './purchases.service';

interface ReceiveLineEntry {
  line: PurchaseOrderLineDto;
  /** User-editable received qty — defaults to outstanding. */
  receivedQty: string;
  /** Whether to include this line in the GR. */
  include: boolean;
}

/**
 * Goods Receipt create screen.
 * Can be reached from:
 *  - PO detail "Receive Goods" link (query param: poUid)
 *  - GR list "New Receipt" button (PO picker shown)
 *
 * Flow:
 * 1. Load the PO by uid.
 * 2. Load its lines → show only non-fully-received lines, defaulting receivedQty to outstandingQtyInBase.
 * 3. User edits receivedQty per line, unchecks lines they don't want to receive.
 * 4. Submit CreateGoodsReceiptRequest.
 * 5. 409 over-receipt surfaced inline per-line and in the form error.
 */
@Component({
  selector: 'app-goods-receipt-create',
  imports: [FormsModule, RouterLink],
  templateUrl: './goods-receipt-create.component.html',
  styleUrl: './goods-receipt-create.component.scss',
})
export class GoodsReceiptCreateComponent {
  private readonly purchasesService = inject(PurchasesService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  // ── Company context (only for PO picker when no poUid in query) ───────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── PO picker (when arriving without a poUid query param) ─────────────────
  readonly poSearchQ = signal('');
  readonly poResults = signal<PurchaseOrderDto[]>([]);
  readonly selectedPoUid = signal<string | null>(null);
  private readonly poSearch$ = new Subject<string>();

  // ── PO load state ─────────────────────────────────────────────────────────
  readonly po = signal<PurchaseOrderDto | null>(null);
  readonly poState = signal<'idle' | 'loading' | 'error'>('idle');

  // ── Lines ──────────────────────────────────────────────────────────────────
  /** Receive entries — only non-fully-received lines. */
  readonly receiveLines = signal<ReceiveLineEntry[]>([]);
  readonly linesState = signal<'idle' | 'loading' | 'error'>('idle');

  // ── Form ───────────────────────────────────────────────────────────────────
  readonly notes = signal('');
  readonly submitting = signal(false);
  readonly formError = signal<string | null>(null);
  /** Per-line error keyed by lineUid — e.g. over-receipt 409. */
  readonly lineErrors = signal<Record<string, string>>({});

  readonly canReceive = computed(() => this.session.hasPermission('PURCHASE.RECEIVE'));

  readonly hasIncludedLines = computed(() =>
    this.receiveLines().some((e) => e.include),
  );

  constructor() {
    // PO search debounce (for PO picker)
    this.poSearch$
      .pipe(
        debounceTime(300),
        distinctUntilChanged(),
        switchMap((q) => {
          const companyId = this.selectedCompanyId();
          if (!companyId || !q.trim()) { this.poResults.set([]); return []; }
          return this.purchasesService.listOrders(companyId, q.trim(), 'ORDERED', 0, 10);
        }),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: ({ rows }) => {
          // Also show PARTIALLY_RECEIVED
          this.purchasesService.listOrders(this.selectedCompanyId(), this.poSearchQ(), 'PARTIALLY_RECEIVED', 0, 10)
            .subscribe({
              next: ({ rows: pr }) => this.poResults.set([...rows, ...pr]),
              error: () => this.poResults.set(rows),
            });
        },
        error: () => this.poResults.set([]),
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
            if (list.length > 0) {
              this.selectedCompanyId.set(list[0].id);
            }
            // After companies loaded, check for poUid query param
            const poUid = this.route.snapshot.queryParamMap.get('poUid');
            if (poUid) {
              this.loadPoByUid(poUid);
            }
          },
          error: () => this.companyState.set('error'),
        });
      },
      error: () => this.companyState.set('error'),
    });
  }

  // ── PO loading ─────────────────────────────────────────────────────────────

  loadPoByUid(uid: string): void {
    this.poState.set('loading');
    this.po.set(null);
    this.receiveLines.set([]);
    this.lineErrors.set({});
    this.formError.set(null);
    this.purchasesService.getOrderByUid(uid).subscribe({
      next: (p) => {
        this.po.set(p);
        this.poState.set('idle');
        this.loadLines(uid);
      },
      error: () => this.poState.set('error'),
    });
  }

  private loadLines(poUid: string): void {
    this.linesState.set('loading');
    this.purchasesService.listOrderLines(poUid).subscribe({
      next: (lines) => {
        // Only include non-fully-received lines
        const entries: ReceiveLineEntry[] = lines
          .filter((l) => !l.fullyReceived)
          .map((l) => ({
            line: l,
            receivedQty: l.outstandingQtyInBase,
            include: true,
          }));
        this.receiveLines.set(entries);
        this.linesState.set('idle');
      },
      error: () => this.linesState.set('error'),
    });
  }

  // ── PO picker ──────────────────────────────────────────────────────────────

  onPoSearchChange(q: string): void {
    this.poSearchQ.set(q);
    this.selectedPoUid.set(null);
    this.po.set(null);
    this.receiveLines.set([]);
    this.poSearch$.next(q);
  }

  selectPo(p: PurchaseOrderDto): void {
    this.selectedPoUid.set(p.uid);
    this.poResults.set([]);
    this.poSearchQ.set((p.orderNumber ?? 'DRAFT') + ' — ' + p.supplierName);
    this.loadPoByUid(p.uid);
  }

  // ── Line editing ───────────────────────────────────────────────────────────

  updateLineQty(index: number, qty: number | string | null): void {
    // ngModel on type="number" emits a number; keep receivedQty a string (the field type +
    // the downstream .trim()/wire contract expect a string).
    const qtyStr = qty === null || qty === undefined ? '' : String(qty);
    this.receiveLines.update((entries) =>
      entries.map((e, i) => (i === index ? { ...e, receivedQty: qtyStr } : e)),
    );
  }

  toggleLineInclude(index: number, include: boolean): void {
    this.receiveLines.update((entries) =>
      entries.map((e, i) => (i === index ? { ...e, include } : e)),
    );
  }

  // ── Submit ─────────────────────────────────────────────────────────────────

  submit(): void {
    const p = this.po();
    if (!p) { this.formError.set('No purchase order selected.'); return; }

    const includedLines = this.receiveLines().filter((e) => e.include);
    if (includedLines.length === 0) {
      this.formError.set('Include at least one line to receive.');
      return;
    }

    // Validate quantities
    let valid = true;
    const errors: Record<string, string> = {};
    for (const entry of includedLines) {
      const qty = Number(entry.receivedQty);
      if (!entry.receivedQty.trim() || isNaN(qty) || qty <= 0) {
        errors[entry.line.uid] = 'Quantity must be greater than zero.';
        valid = false;
      }
    }
    this.lineErrors.set(errors);
    if (!valid) { this.formError.set('Fix the errors above before submitting.'); return; }

    this.submitting.set(true);
    this.formError.set(null);

    const lines: GoodsReceiptLineRequest[] = includedLines.map((e) => ({
      purchaseOrderLineUid: e.line.uid,
      receivedQty: e.receivedQty.trim(),
    }));

    const request: CreateGoodsReceiptRequest = {
      purchaseOrderUid: p.uid,
      notes: this.notes().trim() || undefined,
      lines,
    };

    this.purchasesService.createReceipt(request).subscribe({
      next: (gr) => {
        this.submitting.set(false);
        this.alerts.success('Goods receipt recorded', gr.receiptNumber);
        this.router.navigate(['/admin/goods-receipts/uid', gr.uid]);
      },
      error: (err) => {
        this.submitting.set(false);
        if (err instanceof HttpErrorResponse && err.status === 409) {
          this.formError.set('Over-receipt: one or more quantities exceed the outstanding amount. Reduce the quantities and try again.');
        } else {
          this.formError.set(this.messageFrom(err, 'Could not create goods receipt.'));
        }
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
