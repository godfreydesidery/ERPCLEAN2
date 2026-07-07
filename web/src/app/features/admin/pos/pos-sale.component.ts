import { HttpErrorResponse } from '@angular/common/http';
import { DecimalPipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { debounceTime, distinctUntilChanged, Subject, switchMap } from 'rxjs';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { Company } from '../models/company.model';
import { Branch } from '../models/branch.model';
import { CustomerModel } from '../models/party.model';
import { AgentModel } from '../models/party.model';
import { ProductModel, UnitOfMeasureDto, VatStatus } from '../models/product.model';
import { TaxRateDto } from '../models/sales.model';
import { CompanyService } from '../company/company.service';
import { BranchService } from '../branch/branch.service';
import { OrganisationService } from '../organisation/organisation.service';
import { CustomerService } from '../parties/customer.service';
import { AgentService } from '../parties/agent.service';
import { ProductService } from '../products/product.service';
import { SalesService } from '../sales/sales.service';
import { UidPickerComponent, UidOption } from '../../../shared/uid-picker/uid-picker.component';
import { SalesInvoiceDto } from '../models/sales.model';
import { PosSessionDto, PosSaleLineRequest, PosSaleRequest } from './models/pos.model';
import { PosService } from './pos.service';
import { CurrencySelectComponent } from '../../../shared/currency-select/currency-select.component';

/** A single line item in the checkout basket. */
interface SaleLine {
  readonly id: string; // local key
  productUid: string;
  productId: string;
  productName: string;
  unitUid: string;
  unitId: string;
  unitName: string;
  quantity: string;
  /** System-resolved net unit price from the product's price list (server-authoritative). */
  unitPrice: string;
  lineDiscountAmount: string;
  /** VAT fraction (e.g. 0.18) resolved from the company tax rate for the product's VAT status. */
  vatRate?: string;
  /** Price-resolution state for the picked product. */
  priceState?: 'ok' | 'loading' | 'missing';
  /** Product-scoped unit options; empty until a product is selected for this line. */
  lineUnitOptions: UidOption[];
  lineUnitsLoading: boolean;
}

let _lineCounter = 0;
function nextLineId(): string { return `line-${++_lineCounter}`; }

/**
 * POS Checkout (Sell) screen.
 * Pick open session + customer + agent + currency; add line items; record tender; submit.
 * The server returns a SalesInvoiceDto on success.
 *
 * Pricing is SERVER-AUTHORITATIVE (the backend resolves each line price from the product's price
 * list and ignores any submitted unitPrice). To keep the on-screen total/change honest, this screen
 * fetches the same list price on product select and previews the VAT-inclusive gross — it never asks
 * the cashier to type a price.
 *
 * Route: /admin/pos/sell
 * Gated: POS.SALE.CREATE.
 */
@Component({
  selector: 'app-pos-sale',
  imports: [FormsModule, RouterLink, DecimalPipe, UidPickerComponent, CurrencySelectComponent],
  templateUrl: './pos-sale.component.html',
  styleUrl: './pos-sale.component.scss',
})
export class PosSaleComponent {
  private readonly posService = inject(PosService);
  private readonly companyService = inject(CompanyService);
  private readonly branchService = inject(BranchService);
  private readonly organisationService = inject(OrganisationService);
  private readonly customerService = inject(CustomerService);
  private readonly agentService = inject(AgentService);
  private readonly productService = inject(ProductService);
  private readonly salesService = inject(SalesService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  // ── Company context ────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly selectedCompanyUid = computed(() => this.companies().find((c) => c.id === this.selectedCompanyId())?.uid ?? '');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── Branch context (filters the session list to one branch) ─────────────────
  readonly branches = signal<Branch[]>([]);
  readonly selectedBranchId = signal('');
  readonly branchesError = signal(false);

  // ── Open sessions (for picker) ─────────────────────────────────────────────
  readonly openSessions = signal<PosSessionDto[]>([]);
  readonly sessionsLoaded = signal(false);
  /** Sessions narrowed to the selected branch (a session's branch is its till's branch). */
  readonly branchSessions = computed<PosSessionDto[]>(() => {
    const branchId = this.selectedBranchId();
    const all = this.openSessions();
    return branchId ? all.filter((s) => s.branchId === branchId) : all;
  });
  readonly sessionOptions = computed<UidOption[]>(() =>
    this.branchSessions().map((s) => ({ uid: s.uid, label: `Session ${s.uid.slice(-8)} (Till ${s.posTillId})` })),
  );
  /** Proactive blocker hint: options have loaded but there is no open session to sell against. */
  readonly noOpenSession = computed(() => this.sessionsLoaded() && this.sessionOptions().length === 0);

  // ── Customers (for picker) ─────────────────────────────────────────────────
  readonly customers = signal<CustomerModel[]>([]);
  readonly customerOptions = computed<UidOption[]>(() =>
    this.customers().filter((c) => c.status === 'ACTIVE').map((c) => ({ uid: c.uid, label: c.displayName, hint: c.code })),
  );

  // ── Agents (for picker) ────────────────────────────────────────────────────
  readonly agents = signal<AgentModel[]>([]);
  readonly agentsLoaded = signal(false);
  readonly agentOptions = computed<UidOption[]>(() =>
    this.agents().filter((a) => a.status === 'ACTIVE').map((a) => ({ uid: a.uid, label: a.displayName, hint: a.code })),
  );
  /** Proactive blocker hint: agents have loaded but none can be selected (common POS sale blocker). */
  readonly noAgentAvailable = computed(() => this.agentsLoaded() && this.agentOptions().length === 0);

  // ── Products (for picker) ──────────────────────────────────────────────────
  readonly products = signal<ProductModel[]>([]);
  readonly productOptions = computed<UidOption[]>(() =>
    this.products().filter((p) => p.status === 'ACTIVE' && p.sellable).map((p) => ({ uid: p.uid, label: p.name, hint: p.code })),
  );

  // ── Units accumulator (uid→id resolution for submit; populated per-line on product select) ───
  readonly units = signal<UnitOfMeasureDto[]>([]);

  // ── Tax rates (for the VAT-inclusive preview; best-effort, needs TAXRATE.VIEW) ──────────────
  readonly taxRates = signal<TaxRateDto[]>([]);

  // ── Form fields ────────────────────────────────────────────────────────────
  readonly selectedSessionUid = signal('');
  readonly selectedCustomerUid = signal('');
  readonly selectedAgentUid = signal('');
  readonly currency = signal('TZS');
  readonly tenderedAmount = signal('');
  readonly saleNotes = signal('');

  // ── Line items ─────────────────────────────────────────────────────────────
  readonly lines = signal<SaleLine[]>([]);

  // ── Derived totals ─────────────────────────────────────────────────────────
  /** Net subtotal (pre-VAT), kept for the line/subtotal display. */
  readonly subtotal = computed<number>(() =>
    this.lines().reduce((sum, l) => sum + this.lineNet(l), 0),
  );

  /** VAT-inclusive total — what the customer actually pays (matches the server gross). */
  readonly grossTotal = computed<number>(() =>
    this.lines().reduce((sum, l) => sum + this.lineGross(l), 0),
  );

  readonly vatTotal = computed<number>(() => this.grossTotal() - this.subtotal());

  readonly change = computed<number>(() => {
    const tendered = +this.tenderedAmount() || 0;
    return tendered - this.grossTotal();
  });

  /** True once a tax rate is known, so the preview can be labelled "incl. VAT" honestly. */
  readonly vatKnown = computed(() => this.taxRates().length > 0);

  // ── Submit state ───────────────────────────────────────────────────────────
  readonly submitting = signal(false);
  readonly formError = signal<string | null>(null);
  readonly savedInvoice = signal<SalesInvoiceDto | null>(null);

  // ── Permission ─────────────────────────────────────────────────────────────
  readonly canSell = computed(() => this.session.hasPermission('POS.SALE.CREATE'));
  /** The agent picker needs AGENT.VIEW; without it, skip the fetch (it would 403) and show the hint. */
  readonly canViewAgents = computed(() => this.session.hasPermission('AGENT.VIEW'));

  // ── Debounced search subjects ──────────────────────────────────────────────
  private readonly customerSearch$ = new Subject<string>();
  private readonly agentSearch$ = new Subject<string>();
  private readonly productSearch$ = new Subject<string>();

  constructor() {
    this.customerSearch$
      .pipe(
        debounceTime(300),
        distinctUntilChanged(),
        switchMap((q) => {
          const cId = this.selectedCompanyId();
          if (!cId) return [];
          return this.customerService.list(cId, q.trim() || undefined, 0, 50);
        }),
        takeUntilDestroyed(),
      )
      .subscribe({ next: ({ rows }) => this.customers.set(rows), error: () => {} });

    this.agentSearch$
      .pipe(
        debounceTime(300),
        distinctUntilChanged(),
        switchMap((q) => {
          const cId = this.selectedCompanyId();
          if (!cId) return [];
          return this.agentService.list(cId, q.trim() || undefined, 0, 50);
        }),
        takeUntilDestroyed(),
      )
      .subscribe({ next: ({ rows }) => { this.agents.set(rows); this.agentsLoaded.set(true); }, error: () => this.agentsLoaded.set(true) });

    this.productSearch$
      .pipe(
        debounceTime(300),
        distinctUntilChanged(),
        switchMap((q) => {
          const cId = this.selectedCompanyId();
          if (!cId) return [];
          return this.productService.list(cId, q.trim() || undefined, 0, 100);
        }),
        takeUntilDestroyed(),
      )
      .subscribe({ next: ({ rows }) => this.products.set(rows), error: () => {} });

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
              this.loadBranches(list[0].uid);
              this.loadOptions(list[0].id);
            }
          },
          error: () => this.companyState.set('error'),
        });
      },
      error: () => this.companyState.set('error'),
    });
  }

  private loadOptions(companyId: string): void {
    // Load open sessions
    this.sessionsLoaded.set(false);
    // Only OPEN sessions matter for checkout — filter server-side so a fresh session isn't
    // buried beyond page 0 when a company has many historical (closed/reconciled) sessions.
    this.posService.listSessions(companyId, 0, 50, 'OPEN').subscribe({
      next: ({ rows }) => { this.openSessions.set(rows.filter((s) => s.status === 'OPEN')); this.sessionsLoaded.set(true); },
      error: () => this.sessionsLoaded.set(true),
    });
    // Seed customer/agent/product options with empty query
    this.customerSearch$.next('');
    if (this.canViewAgents()) {
      this.agentsLoaded.set(false);
      this.agentSearch$.next('');
    } else {
      // No AGENT.VIEW: skip the agents fetch (it would 403 and leave a raw error) — leave the picker
      // empty; the "no agent available" hint explains the blocker calmly, like a company with none.
      this.agents.set([]);
      this.agentsLoaded.set(true);
    }
    this.productSearch$.next('');
    // Units are loaded per-product when a product is selected on a line (listProductUnits).
    // Load tax rates for the VAT-inclusive preview (best-effort — needs TAXRATE.VIEW).
    this.salesService.listTaxRates(companyId).subscribe({
      next: (rows) => this.taxRates.set(rows),
      error: () => this.taxRates.set([]),
    });
  }

  onCompanyChange(id: string): void {
    this.selectedCompanyId.set(id);
    this.selectedSessionUid.set('');
    this.selectedCustomerUid.set('');
    this.selectedAgentUid.set('');
    this.lines.set([]);
    if (id) {
      const uid = this.companies().find((c) => c.id === id)?.uid;
      if (uid) this.loadBranches(uid);
      this.loadOptions(id);
    }
  }

  /** Load this company's active branches and default the filter to the active/first branch. */
  private loadBranches(companyUid: string): void {
    this.branchesError.set(false);
    this.branchService.list(companyUid).subscribe({
      next: (list) => {
        const active = list.filter((b) => b.status === 'ACTIVE');
        this.branches.set(active);
        const activeBranchUid = this.session.activeBranchUid();
        const chosen = active.find((b) => b.uid === activeBranchUid) ?? active[0];
        this.selectedBranchId.set(chosen?.id ?? '');
      },
      error: () => {
        // Non-fatal: without BRANCH.VIEW the picker is hidden and every open session is shown.
        this.branches.set([]);
        this.selectedBranchId.set('');
        this.branchesError.set(true);
      },
    });
  }

  /** Switch the branch whose open sessions are offered; clear a now out-of-branch session. */
  onBranchChange(branchId: string): void {
    this.selectedBranchId.set(branchId);
    this.selectedSessionUid.set('');
  }

  onProductSearch(q: string): void { this.productSearch$.next(q); }
  onCustomerSearch(q: string): void { this.customerSearch$.next(q); }
  onAgentSearch(q: string): void { if (this.canViewAgents()) this.agentSearch$.next(q); }

  // ── Line management ────────────────────────────────────────────────────────

  addLine(): void {
    this.lines.update((ls) => [
      ...ls,
      { id: nextLineId(), productUid: '', productId: '', productName: '', unitUid: '', unitId: '', unitName: '',
        quantity: '1', unitPrice: '0.00', lineDiscountAmount: '0.00', vatRate: '0', priceState: 'ok',
        lineUnitOptions: [], lineUnitsLoading: false },
    ]);
  }

  removeLine(lineId: string): void {
    this.lines.update((ls) => ls.filter((l) => l.id !== lineId));
  }

  onLineProductChange(lineId: string, productUid: string): void {
    const product = this.products().find((p) => p.uid === productUid);
    const vatRate = product ? this.vatRateFor(product.vatStatus) : 0;
    // Reset line fields and start loading product-scoped units.
    this.lines.update((ls) =>
      ls.map((l) =>
        l.id === lineId
          ? { ...l, productUid, productId: product?.id ?? '', productName: product?.name ?? '',
              unitUid: '', unitId: '', unitName: '',
              vatRate: String(vatRate), unitPrice: '0.00', priceState: product ? 'loading' : 'ok',
              lineUnitOptions: [], lineUnitsLoading: !!product }
          : l,
      ),
    );
    if (product) {
      // Load product-scoped units (base unit first, then active pack units).
      this.productService.listProductUnits(product.uid).subscribe({
        next: (units) => {
          // Accumulate into units signal so onLineUnitChange can resolve uid→id.
          const existing = this.units();
          units.forEach((u) => {
            if (!existing.some((e) => e.uid === u.uid)) {
              this.units.update((arr) => [...arr, u]);
            }
          });
          const opts = units.map((u) => ({ uid: u.uid, label: u.name, hint: u.code }));
          const baseUnit = units.find((u) => u.uid === product.baseUnitUid) ?? units[0];
          this.lines.update((ls) =>
            ls.map((l) =>
              l.id === lineId
                ? { ...l, lineUnitOptions: opts, lineUnitsLoading: false,
                    unitUid: baseUnit?.uid ?? '', unitId: baseUnit?.id ?? '',
                    unitName: baseUnit?.name ?? product.baseUnitName ?? '' }
                : l,
            ),
          );
        },
        error: () => {
          this.lines.update((ls) =>
            ls.map((l) => l.id === lineId ? { ...l, lineUnitsLoading: false } : l),
          );
        },
      });
      // Auto-fetch the server-authoritative list price (mirrors the backend's first-price-for-company rule).
      this.fetchLinePrice(lineId, product.uid);
    }
  }

  /** Pulls the product's price-list price and populates the (read-only) line price. */
  private fetchLinePrice(lineId: string, productUid: string): void {
    const companyId = this.selectedCompanyId();
    this.productService.listPrices(productUid).subscribe({
      next: (priceRows) => {
        const row = priceRows.find((p) => p.companyId === companyId) ?? priceRows[0];
        const amount = row?.price?.amount;
        this.lines.update((ls) =>
          ls.map((l) => l.id === lineId
            ? { ...l, unitPrice: amount ?? '0.00', priceState: amount != null ? 'ok' : 'missing' }
            : l),
        );
      },
      error: () => {
        this.lines.update((ls) =>
          ls.map((l) => l.id === lineId ? { ...l, priceState: 'missing' } : l),
        );
      },
    });
  }

  onLineUnitChange(lineId: string, unitUid: string): void {
    const unit = this.units().find((u) => u.uid === unitUid);
    this.lines.update((ls) =>
      ls.map((l) =>
        l.id === lineId ? { ...l, unitUid, unitId: unit?.id ?? '', unitName: unit?.name ?? '' } : l,
      ),
    );
  }

  onLineFieldChange(lineId: string, field: 'quantity' | 'lineDiscountAmount', value: number | string | null): void {
    // type="number" emits a JS number; coerce so SaleLine string fields never hold a raw number.
    const str = value === null || value === undefined ? '' : String(value);
    this.lines.update((ls) =>
      ls.map((l) => l.id === lineId ? { ...l, [field]: str } : l),
    );
  }

  /**
   * Resolve the VAT fraction for a product's VAT status from the company tax rates.
   * The backend stores TaxRate.rate as a FRACTION with a DB CHECK (0 ≤ rate < 1) —
   * e.g. 0.18 = 18% — so it is used verbatim. A value outside [0, 1) is an invalid
   * row and is treated as no VAT (0).
   */
  private vatRateFor(vatStatus: VatStatus): number {
    const tr = this.taxRates().find((t) => t.vatStatus === vatStatus);
    if (!tr) return 0;
    const raw = +tr.rate || 0;
    return raw >= 0 && raw < 1 ? raw : 0;
  }

  /** Net amount for a line: qty × unit price − discount. */
  private lineNet(l: SaleLine): number {
    return (+l.quantity || 0) * (+l.unitPrice || 0) - (+l.lineDiscountAmount || 0);
  }

  /** Gross (VAT-inclusive) amount for a line. */
  lineGross(l: SaleLine): number {
    return this.lineNet(l) * (1 + (+(l.vatRate ?? '0') || 0));
  }

  // ── Submit ─────────────────────────────────────────────────────────────────

  /** Returns an error message for a single sale line, or null if valid. */
  private validateSaleLine(l: SaleLine): string | null {
    if (!l.productId) return 'Select a product for every line.';
    if (!l.unitId) return 'Select a unit for every line.';
    if (l.priceState === 'missing') return `"${l.productName}" has no price set. Set a price for it before selling.`;
    if (!l.quantity || +l.quantity <= 0) return 'Quantity must be positive for every line.';
    return null;
  }

  /** Returns a validation error message, or null if the form is valid. */
  private validateSaleForm(sessionUid: string, customerUid: string, agentUid: string, curr: string, tendered: string): string | null {
    if (!sessionUid) return 'Select an open session before completing the sale.';
    if (!customerUid) return 'Select a customer before completing the sale.';
    if (!agentUid) return 'Select a sales agent before completing the sale.';
    if (!curr) return 'Currency is required.';
    if (this.lines().length === 0) return 'Add at least one line item.';
    for (const l of this.lines()) {
      const lineErr = this.validateSaleLine(l);
      if (lineErr) return lineErr;
    }
    if (!tendered || Number.isNaN(+tendered) || +tendered < 0) return 'Tendered amount must be a valid non-negative number.';
    if (+tendered < this.grossTotal()) return 'Tendered amount is less than the total.';
    return null;
  }

  submit(): void {
    const sessionUid = this.selectedSessionUid().trim();
    const customerUid = this.selectedCustomerUid().trim();
    const agentUid = this.selectedAgentUid().trim();
    const curr = this.currency().trim();
    // A `type="number"` input stores a number (or null) in the signal via NumberValueAccessor,
    // so coerce to string before trimming — `.trim()` on a number throws (DEFECT-POS-TENDER).
    const tendered = String(this.tenderedAmount() ?? '').trim();

    const validationError = this.validateSaleForm(sessionUid, customerUid, agentUid, curr, tendered);
    if (validationError) { this.formError.set(validationError); return; }

    // Resolve customerId from uid
    const customer = this.customers().find((c) => c.uid === customerUid);
    if (!customer) { this.formError.set('Could not resolve customer. Re-select from the list.'); return; }

    // Resolve agentId from uid (required)
    const agent = this.agents().find((a) => a.uid === agentUid);
    if (!agent) { this.formError.set('Could not resolve agent. Re-select from the list.'); return; }

    const saleLines: PosSaleLineRequest[] = this.lines().map((l) => ({
      productId: l.productId,
      unitId: l.unitId,
      // type="number" emits a JS number; coerce before sending so the payload is always a string.
      quantity: String(l.quantity ?? ''),
      unitPrice: l.unitPrice,
      lineDiscountAmount: +l.lineDiscountAmount > 0 ? String(l.lineDiscountAmount) : undefined,
    }));

    const request: PosSaleRequest = {
      sessionUid,
      customerId: customer.id,
      agentId: agent.id,
      currency: curr,
      lines: saleLines,
      tenderedAmount: tendered,
      notes: this.saleNotes().trim() || undefined,
    };

    this.submitting.set(true);
    this.formError.set(null);

    this.posService.processSale(request).subscribe({
      next: (invoice) => {
        this.submitting.set(false);
        this.savedInvoice.set(invoice);
        this.alerts.success('Sale recorded', invoice.invoiceNumber ?? undefined);
      },
      error: (err: unknown) => {
        this.formError.set(this.messageFrom(err, 'Could not process sale.'));
        this.submitting.set(false);
      },
    });
  }

  resetSale(): void {
    this.savedInvoice.set(null);
    this.lines.set([]);
    this.selectedSessionUid.set('');
    this.selectedCustomerUid.set('');
    this.selectedAgentUid.set('');
    this.tenderedAmount.set('');
    this.saleNotes.set('');
    this.formError.set(null);
    // Refresh open sessions
    const cId = this.selectedCompanyId();
    if (cId) {
      this.sessionsLoaded.set(false);
      this.posService.listSessions(cId, 0, 50, 'OPEN').subscribe({
        next: ({ rows }) => { this.openSessions.set(rows.filter((s) => s.status === 'OPEN')); this.sessionsLoaded.set(true); },
        error: () => this.sessionsLoaded.set(true),
      });
    }
  }

  lineSubtotal(l: SaleLine): number {
    return this.lineNet(l);
  }

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}
