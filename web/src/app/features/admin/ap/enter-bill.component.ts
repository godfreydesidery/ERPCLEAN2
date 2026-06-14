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

/**
 * UI-only line row for the bill line editor.
 */
interface LineRow {
  description: string;
  billedQty: string;
  unitCostAmount: string;
  poLineUid: string;
}

/**
 * Enter Bill screen — AP centerpiece.
 * Gated AP.BILL.ENTER.
 *
 * Flow:
 *  1. Pick supplier (typeahead).
 *  2. Enter supplier invoice no, bill date, due date, VAT, currency, optional PO uid.
 *  3. Build line editor (description, qty, unit cost per line).
 *  4. Submit → ap.service.enterBill() → run 3-way match → render BillMatchResultDto.
 *  5. Per-line match result: MATCHED/HELD badge, price/qty variance, Accept Variance button.
 */
@Component({
  selector: 'app-enter-bill',
  imports: [FormsModule, RouterLink, UidPickerComponent],
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

  // ── Company context ────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── Supplier picker ────────────────────────────────────────────────────────
  readonly supplierSearchQ = signal('');
  readonly supplierResults = signal<SupplierModel[]>([]);
  readonly selectedSupplier = signal<{ uid: string; label: string } | null>(null);

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
      error: () => {},
    });
  }

  onPoUidChange(uid: string): void {
    this.purchaseOrderUid.set(uid);
    this.poLineOptions.set([]);
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
    this.loadPoOptions(id);
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
    this.selectedSupplier.set({ uid: s.uid, label: `${s.code} — ${s.displayName}` });
    this.supplierSearchQ.set(`${s.code} — ${s.displayName}`);
    this.supplierResults.set([]);
  }

  private resetSupplier(): void {
    this.selectedSupplier.set(null);
    this.supplierSearchQ.set('');
    this.supplierResults.set([]);
  }

  // ── Line editor ────────────────────────────────────────────────────────────

  private emptyLine(): LineRow {
    return { description: '', billedQty: '', unitCostAmount: '', poLineUid: '' };
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

  fmtMoney(v: number | string | null | undefined): string {
    const n = +(v ?? 0);
    return Number.isFinite(n) ? n.toFixed(2) : '0.00';
  }

  fmtPct(v: number | string | null | undefined): string {
    const n = +(v ?? 0);
    return Number.isFinite(n) ? n.toFixed(2) + '%' : '0.00%';
  }

  matchLineBadgeClass(line: LineMatchDto): string {
    switch (line.matchStatus) {
      case 'MATCHED': return 'text-bg-success';
      case 'HELD_PRICE_VARIANCE': return 'text-bg-danger';
      case 'HELD_QTY_VARIANCE': return 'text-bg-warning';
      case 'VARIANCE_ACCEPTED': return 'text-bg-info';
      default: return 'text-bg-secondary';
    }
  }

  isHeld(line: LineMatchDto): boolean {
    return line.matchStatus === 'HELD_PRICE_VARIANCE' || line.matchStatus === 'HELD_QTY_VARIANCE';
  }

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}
