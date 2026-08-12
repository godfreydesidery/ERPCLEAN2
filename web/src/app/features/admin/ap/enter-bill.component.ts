import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { debounceTime, distinctUntilChanged, Subject, switchMap } from 'rxjs';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { Company } from '../models/company.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { SupplierModel } from '../models/party.model';
import { SupplierService } from '../parties/supplier.service';
import { PurchasesService } from '../purchases/purchases.service';
import {
  BillLineRequest,
  BillMatchResultDto,
  EnterBillRequest,
  LineMatchDto,
  SupplierBillDto,
} from './models/ap.model';
import { ApService } from './ap.service';
import { UidOption, UidPickerComponent } from '../../../shared/uid-picker/uid-picker.component';
import { CurrencySelectComponent } from '../../../shared/currency-select/currency-select.component';

/**
 * UI-only line row for the bill line editor.
 *
 * `grLineUid` is the goods-receipt line this bill line is claimed against. Without it the 3-way
 * match cannot check the quantity billed against the quantity received, so the backend holds the
 * line — and until this field existed the accountant had no way to satisfy that hold except the
 * audited "accept variance" override.
 */
interface LineRow {
  description: string;
  billedQty: string;
  unitCostAmount: string;
  poLineUid: string;
  grLineUid: string;
}

/**
 * Enter Bill screen — AP centerpiece.
 * Gated AP.BILL.ENTER.
 *
 * Flow:
 *  1. Pick supplier (typeahead).
 *  2. Enter supplier invoice no, bill date, due date, VAT, currency, optional PO uid.
 *  3. Build line editor (description, qty, unit cost, order line and goods receipt line per line).
 *  4. Submit → ap.service.enterBill() → run 3-way match → render BillMatchResultDto.
 *  5. Per-line match result: human status + the backend's plain-English note, the figures that were
 *     actually compared (and "—" for the ones that were not), and the override button.
 *
 * <p><b>Why the receipt picker exists.</b> The match holds a line whose goods receipt cannot be
 * found and tells the accountant to attach it. This screen had no way to do that, so the only exit
 * left was "accept variance" — an audited override on every single bill, which is how override
 * normalisation starts. Attaching the receipt has to be possible here, at entry, using the numbers
 * printed on the delivery note.
 */
@Component({
  selector: 'app-enter-bill',
  imports: [FormsModule, RouterLink, UidPickerComponent, CurrencySelectComponent],
  templateUrl: './enter-bill.component.html',
  styleUrl: './enter-bill.component.scss',
})
export class EnterBillComponent {
  private readonly apService = inject(ApService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly supplierService = inject(SupplierService);
  private readonly purchasesService = inject(PurchasesService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  // ── PO picker ─────────────────────────────────────────────────────────────
  readonly poOptions = signal<UidOption[]>([]);
  /** PO line options, loaded when a PO is selected. */
  readonly poLineOptions = signal<UidOption[]>([]);
  /** True when the PO list could not be loaded (non-fatal; PO matching is optional). */
  readonly poListUnavailable = signal(false);

  // ── Goods receipt line picker ─────────────────────────────────────────────
  /**
   * Receipt lines the accountant can attach to a bill line, labelled with the things printed on the
   * delivery note in their hand — GRN number, product, quantity received. Never a raw uid: the UAT
   * accountant could not tell that the field wanted an internal id rather than the GRN number.
   * Loaded from the goods-receipts read endpoint the storekeeper screens already use
   * (PURCHASE.GOODS_RECEIPT.VIEW).
   */
  readonly grLineOptions = signal<UidOption[]>([]);
  readonly grLookupState = signal<'idle' | 'loading' | 'ready' | 'unavailable'>('idle');

  // ── Company context ────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly selectedCompanyUid = computed(() => this.companies().find((c) => c.id === this.selectedCompanyId())?.uid ?? '');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── Supplier picker ────────────────────────────────────────────────────────
  readonly supplierSearchQ = signal('');
  readonly supplierResults = signal<SupplierModel[]>([]);
  /** `id` is kept alongside the uid so goods receipts (which carry supplierId) can be narrowed. */
  readonly selectedSupplier = signal<{ id: string; uid: string; label: string } | null>(null);

  // ── Bill header ────────────────────────────────────────────────────────────
  readonly supplierInvoiceNo = signal('');
  readonly purchaseOrderUid = signal('');
  readonly billDate = signal('');
  readonly dueDate = signal('');
  readonly vatAmount = signal('0');
  readonly currency = signal('TZS');

  // ── Line editor ────────────────────────────────────────────────────────────
  readonly lines = signal<LineRow[]>([this.emptyLine()]);

  // ── Submit / match state ──────────────────────────────────────────────────
  readonly submitting = signal(false);
  readonly formError = signal<string | null>(null);
  readonly savedBill = signal<SupplierBillDto | null>(null);
  readonly matchResult = signal<BillMatchResultDto | null>(null);
  readonly matchState = signal<'idle' | 'running' | 'done' | 'error'>('idle');
  readonly acceptingLine = signal<string | null>(null); // billLineUid being accepted

  // ── Permissions ────────────────────────────────────────────────────────────
  readonly canEnter = computed(() => this.session.hasPermission('AP.BILL.ENTER'));

  readonly submitDisabled = computed(() =>
    !this.selectedSupplier() ||
    !String(this.supplierInvoiceNo() ?? '').trim() ||
    !String(this.billDate() ?? '').trim() ||
    !String(this.dueDate() ?? '').trim() ||
    !this.selectedCompanyId() ||
    this.lines().length === 0 ||
    this.submitting(),
  );

  private readonly supplierSearch$ = new Subject<string>();

  constructor() {
    this.billDate.set(new Date().toISOString().slice(0, 10));
    this.dueDate.set(new Date().toISOString().slice(0, 10));

    this.supplierSearch$
      .pipe(
        debounceTime(300),
        distinctUntilChanged(),
        switchMap((q) => {
          const companyId = this.selectedCompanyId();
          if (!companyId || !q.trim()) {
            this.supplierResults.set([]);
            return [];
          }
          return this.supplierService.list(companyId, q.trim(), 0, 10);
        }),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: ({ rows }) => this.supplierResults.set(rows.filter((s) => s.status === 'ACTIVE')),
        error: () => this.supplierResults.set([]),
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
              this.loadPoOptions(list[0].id);
            }
          },
          error: () => this.companyState.set('error'),
        });
      },
      error: () => this.companyState.set('error'),
    });
  }

  private loadPoOptions(companyId: string): void {
    this.poListUnavailable.set(false);
    this.purchasesService.listOrders(companyId, undefined, 'ORDERED', 0, 200).subscribe({
      next: ({ rows }) => {
        this.poOptions.set(
          rows.map((po) => ({
            uid: po.uid,
            label: po.orderNumber ?? '(draft PO)',
            hint: po.supplierName,
          })),
        );
      },
      error: () => { this.poOptions.set([]); this.poListUnavailable.set(true); },
    });
  }

  onPoUidChange(uid: string): void {
    this.purchaseOrderUid.set(uid);
    this.poLineOptions.set([]);
    // A PO line uid picked under the previous order belongs to that order — carrying it over would
    // silently submit a link the match cannot resolve.
    this.clearLineLinks({ po: true, gr: false });
    // Narrow (or re-widen) the receipt list to this order.
    this.loadGrLineOptions();
    if (!uid) return;
    this.purchasesService.listOrderLines(uid).subscribe({
      next: (lines) => {
        this.poLineOptions.set(
          lines.map((l) => ({
            uid: l.uid,
            label: `${l.productCode} — ${l.productName}`,
            hint: `L${l.lineNo} qty ${l.orderedQty}`,
          })),
        );
      },
      error: () => {},
    });
  }

  onCompanyChange(id: string): void {
    this.selectedCompanyId.set(id);
    this.resetSupplier();
    this.poListUnavailable.set(false);
    this.loadPoOptions(id);
  }

  // ── Goods receipt line picker ──────────────────────────────────────────────

  /**
   * Load the supplier's received goods-receipt lines so they can be attached to a bill line.
   *
   * <p>The list endpoint returns each receipt with its lines, so one call is enough. Narrowing is
   * done here rather than server-side (the endpoint filters by company + free-text only):
   * RECEIVED receipts, this supplier, and — when an order is selected — that order. If the order
   * filter would leave nothing to choose from, the supplier's wider list is kept instead: an empty
   * picker is exactly the dead end this screen is meant to remove.
   */
  private loadGrLineOptions(): void {
    const companyId = this.selectedCompanyId();
    const supplier = this.selectedSupplier();
    if (!companyId || !supplier) {
      this.grLineOptions.set([]);
      this.grLookupState.set('idle');
      return;
    }
    this.grLookupState.set('loading');
    this.purchasesService.listReceipts(companyId, undefined, 0, 100).subscribe({
      next: ({ rows }) => {
        const poUid = String(this.purchaseOrderUid() ?? '').trim();
        const forSupplier = rows.filter(
          (gr) =>
            gr.status === 'RECEIVED' &&
            (!supplier.id || String(gr.supplierId ?? '') === String(supplier.id)),
        );
        const forOrder = poUid
          ? forSupplier.filter((gr) => gr.purchaseOrderUid === poUid)
          : forSupplier;
        const chosen = forOrder.length > 0 ? forOrder : forSupplier;

        const options: UidOption[] = [];
        for (const gr of chosen) {
          for (const l of gr.lines ?? []) {
            options.push({
              uid: l.uid,
              label: `${gr.receiptNumber} — ${l.productName}`,
              hint: `${l.productCode} · received ${this.fmtQty(l.receivedQty)} ${l.unitName}`,
            });
          }
        }
        this.grLineOptions.set(options);
        this.grLookupState.set('ready');
      },
      error: () => {
        this.grLineOptions.set([]);
        this.grLookupState.set('unavailable');
      },
    });
  }

  /** Drop stale line links after the order or the supplier they belong to has changed. */
  private clearLineLinks(what: { po: boolean; gr: boolean }): void {
    this.lines.update((rows) =>
      rows.map((r) => ({
        ...r,
        poLineUid: what.po ? '' : r.poLineUid,
        grLineUid: what.gr ? '' : r.grLineUid,
      })),
    );
  }

  // ── Supplier picker ────────────────────────────────────────────────────────

  onSupplierSearchChange(q: string): void {
    this.supplierSearchQ.set(q);
    if (!q.trim()) {
      this.selectedSupplier.set(null);
      this.supplierResults.set([]);
      return;
    }
    this.selectedSupplier.set(null);
    this.supplierSearch$.next(q);
  }

  selectSupplier(s: SupplierModel): void {
    this.selectedSupplier.set({ id: s.id, uid: s.uid, label: `${s.code} — ${s.displayName}` });
    this.supplierSearchQ.set(`${s.code} — ${s.displayName}`);
    this.supplierResults.set([]);
    // Receipt lines belong to a supplier — anything already picked is now the wrong supplier's.
    this.clearLineLinks({ po: false, gr: true });
    this.loadGrLineOptions();
  }

  private resetSupplier(): void {
    this.selectedSupplier.set(null);
    this.supplierSearchQ.set('');
    this.supplierResults.set([]);
    this.grLineOptions.set([]);
    this.grLookupState.set('idle');
    this.clearLineLinks({ po: false, gr: true });
  }

  // ── Line editor ────────────────────────────────────────────────────────────

  private emptyLine(): LineRow {
    return { description: '', billedQty: '', unitCostAmount: '', poLineUid: '', grLineUid: '' };
  }

  addLine(): void {
    this.lines.update((rows) => [...rows, this.emptyLine()]);
  }

  removeLine(index: number): void {
    this.lines.update((rows) => rows.filter((_, i) => i !== index));
  }

  updateLine(index: number, field: keyof LineRow, value: string): void {
    this.lines.update((rows) =>
      rows.map((r, i) => (i === index ? { ...r, [field]: value } : r)),
    );
  }

  lineNetAmount(line: LineRow): number {
    const qty = +(String(line.billedQty ?? '').trim() || '0');
    const cost = +(String(line.unitCostAmount ?? '').trim() || '0');
    return Number.isFinite(qty * cost) ? qty * cost : 0;
  }

  get totalNet(): number {
    return this.lines().reduce((sum, l) => sum + this.lineNetAmount(l), 0);
  }

  /**
   * True when this line will go in with its quantity unchecked: the bill is tied to a purchase (an
   * order on the header, or an order line on the row) but no goods receipt line is attached, so the
   * match will hold it. Flagged here, while it is still cheap to fix — once the bill is saved the
   * only remaining exit is the audited override.
   *
   * <p>A genuine service charge has no order anywhere and is deliberately not flagged.
   */
  receiptMissing(line: LineRow): boolean {
    const purchaseLinked =
      !!String(this.purchaseOrderUid() ?? '').trim() || !!String(line.poLineUid ?? '').trim();
    return purchaseLinked && !String(line.grLineUid ?? '').trim();
  }

  /** How many lines would be held for a missing receipt if the bill were submitted now. */
  readonly linesMissingReceipt = computed(
    () => this.lines().filter((l) => this.receiptMissing(l)).length,
  );

  // ── Submit ─────────────────────────────────────────────────────────────────

  submit(): void {
    if (this.submitDisabled()) return;

    const company = this.companies().find((c) => c.id === this.selectedCompanyId());
    if (!company) { this.formError.set('Could not resolve company.'); return; }

    const supplier = this.selectedSupplier();
    if (!supplier) { this.formError.set('Supplier is required.'); return; }

    const invNo = String(this.supplierInvoiceNo() ?? '').trim();
    const bDate = String(this.billDate() ?? '').trim();
    const dDate = String(this.dueDate() ?? '').trim();
    const vat = String(this.vatAmount() ?? '').trim() || '0';
    const curr = String(this.currency() ?? '').trim();
    const poUid = String(this.purchaseOrderUid() ?? '').trim();

    if (!invNo) { this.formError.set('Supplier invoice no. is required.'); return; }
    if (!bDate) { this.formError.set('Bill date is required.'); return; }
    if (!dDate) { this.formError.set('Due date is required.'); return; }

    const lineRequests: BillLineRequest[] = this.lines()
      .filter((l) => String(l.description ?? '').trim())
      .map((l) => ({
        description: String(l.description ?? '').trim(),
        billedQty: String(+(String(l.billedQty ?? '').trim() || '0')),
        unitCostAmount: String(+(String(l.unitCostAmount ?? '').trim() || '0')),
        poLineUid: String(l.poLineUid ?? '').trim() || null,
        grLineUid: String(l.grLineUid ?? '').trim() || null,
      }));

    if (lineRequests.length === 0) { this.formError.set('At least one bill line is required.'); return; }

    const request: EnterBillRequest = {
      companyUid: company.uid,
      supplierUid: supplier.uid,
      supplierInvoiceNo: invNo,
      purchaseOrderUid: poUid || null,
      billDate: bDate,
      dueDate: dDate,
      vatAmount: vat,
      currency: curr,
      lines: lineRequests,
    };

    this.submitting.set(true);
    this.formError.set(null);
    this.matchResult.set(null);
    this.matchState.set('idle');

    this.apService.enterBill(request).subscribe({
      next: (bill) => {
        this.submitting.set(false);
        this.savedBill.set(bill);
        this.alerts.success('Bill entered', bill.billNumber);
        this.runMatch(bill.uid);
      },
      error: (err) => {
        this.formError.set(this.messageFrom(err, 'Could not enter bill.'));
        this.submitting.set(false);
      },
    });
  }

  // ── 3-way match ───────────────────────────────────────────────────────────

  private runMatch(billUid: string): void {
    this.matchState.set('running');
    this.apService.runMatch(billUid).subscribe({
      next: (result) => {
        this.matchResult.set(result);
        this.matchState.set('done');
      },
      error: () => this.matchState.set('error'),
    });
  }

  acceptVariance(lineUid: string): void {
    const bill = this.savedBill();
    if (!bill) return;
    this.acceptingLine.set(lineUid);
    this.apService.acceptVariance(bill.uid, { billLineUid: lineUid }).subscribe({
      next: (result) => {
        this.matchResult.set(result);
        this.acceptingLine.set(null);
        this.alerts.success('Variance accepted');
      },
      error: (err) => {
        this.alerts.error?.('Could not accept variance', this.messageFrom(err, ''));
        this.acceptingLine.set(null);
      },
    });
  }

  // ── Display helpers ────────────────────────────────────────────────────────

  /** Shown in place of a figure the 3-way match never computed. Must not read as a number. */
  static readonly NOT_CHECKED = '—';

  /**
   * True when the backend sent no value for this figure, i.e. the comparison did not run.
   *
   * <p>This is the last inch of the fail-closed fix. `+(null ?? 0)` used to turn "never checked"
   * into `0.00`, which is precisely the reading that let a 149,999,985 bill look reconciled. A
   * genuine 0 is a *good* result and still renders as `0.00`.
   */
  isNotChecked(v: number | string | null | undefined): boolean {
    if (v === null || v === undefined) return true;
    if (typeof v === 'string' && v.trim() === '') return true;
    return !Number.isFinite(+v);
  }

  /**
   * True only when the figure was actually computed AND differs from zero — i.e. when highlighting
   * it as a difference is truthful. A null must never light up as a variance, nor be arithmetic'd.
   */
  nonZero(v: number | string | null | undefined): boolean {
    if (this.isNotChecked(v)) return false;
    return +(v as number | string) !== 0;
  }

  fmtMoney(v: number | string | null | undefined): string {
    if (this.isNotChecked(v)) return EnterBillComponent.NOT_CHECKED;
    return (+(v as number | string)).toFixed(2);
  }

  fmtPct(v: number | string | null | undefined): string {
    if (this.isNotChecked(v)) return EnterBillComponent.NOT_CHECKED;
    return (+(v as number | string)).toFixed(2) + '%';
  }

  /** Quantity for a picker label: plain, no forced decimals ("12", not "12.00"). */
  private fmtQty(v: number | string | null | undefined): string {
    const n = +(v ?? 0);
    if (!Number.isFinite(n)) return '0';
    return String(Math.round(n * 1000) / 1000);
  }

  isHeld(line: LineMatchDto): boolean {
    return line.matchStatus === 'HELD_PRICE_VARIANCE' || line.matchStatus === 'HELD_QTY_VARIANCE';
  }

  /** True when the backend states outright that the 3-way control did not run on this line. */
  notCompared(line: LineMatchDto): boolean {
    return line.comparisonPerformed === false;
  }

  /**
   * The human status shown to the accountant. NEVER the raw enum: `HELD_PRICE_VARIANCE` is both
   * jargon and, since the fail-closed fix reuses that status for a missing goods receipt, a lie —
   * there may be no price variance at all.
   */
  statusLabel(line: LineMatchDto): string {
    if (line.matchStatus === 'VARIANCE_ACCEPTED') return 'Variance accepted';
    if (this.notCompared(line)) {
      return line.matchStatus === 'MATCHED' ? 'Accepted — not checked' : 'On hold — not checked';
    }
    switch (line.matchStatus) {
      case 'MATCHED':
        return 'Matched';
      case 'HELD_PRICE_VARIANCE':
        return 'On hold — price differs';
      case 'HELD_QTY_VARIANCE':
        return 'On hold — quantity differs';
      default:
        return 'On hold';
    }
  }

  /**
   * The explanation under the line. The backend writes it in plain English with the next step; we
   * only supply a fallback for the clean case, where it sends nothing because there is nothing to
   * explain.
   */
  lineNote(line: LineMatchDto): string {
    const note = String(line.matchNote ?? '').trim();
    if (note) return note;
    if (line.matchStatus === 'VARIANCE_ACCEPTED') {
      return 'The difference on this line was accepted by a reviewer, and that decision is recorded.';
    }
    return 'The price and the quantity billed both agree with the order and the goods receipt.';
  }

  /** Held lines the control never actually ran on — these need a link, not an override. */
  readonly notCheckedHolds = computed(
    () => (this.matchResult()?.lineResults ?? []).filter((l) => this.isHeld(l) && this.notCompared(l)).length,
  );

  /**
   * The override button's wording follows what actually happened. Calling it "Accept variance" on a
   * line where nothing was compared invites the habit of clicking it on every bill.
   */
  acceptLabel(line: LineMatchDto): string {
    return this.notCompared(line) ? 'Post without checking' : 'Accept variance';
  }

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}
