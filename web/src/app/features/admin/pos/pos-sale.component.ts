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
import { CustomerModel } from '../models/party.model';
import { AgentModel } from '../models/party.model';
import { ProductModel, UnitOfMeasureDto } from '../models/product.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { CustomerService } from '../parties/customer.service';
import { AgentService } from '../parties/agent.service';
import { ProductService } from '../products/product.service';
import { UidPickerComponent, UidOption } from '../../../shared/uid-picker/uid-picker.component';
import { SalesInvoiceDto } from '../models/sales.model';
import { PosSessionDto, PosSaleLineRequest, PosSaleRequest } from './models/pos.model';
import { PosService } from './pos.service';

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
  unitPrice: string;
  lineDiscountAmount: string;
}

let _lineCounter = 0;
function nextLineId(): string { return `line-${++_lineCounter}`; }

/**
 * POS Checkout (Sell) screen.
 * Pick open session + customer + agent + currency; add line items; record tender; submit.
 * The server returns a SalesInvoiceDto on success.
 * Route: /admin/pos/sell
 * Gated: POS.SALE.CREATE.
 */
@Component({
  selector: 'app-pos-sale',
  imports: [FormsModule, RouterLink, DecimalPipe, UidPickerComponent],
  templateUrl: './pos-sale.component.html',
  styleUrl: './pos-sale.component.scss',
})
export class PosSaleComponent {
  private readonly posService = inject(PosService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly customerService = inject(CustomerService);
  private readonly agentService = inject(AgentService);
  private readonly productService = inject(ProductService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  // ── Company context ────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── Open sessions (for picker) ─────────────────────────────────────────────
  readonly openSessions = signal<PosSessionDto[]>([]);
  readonly sessionOptions = computed<UidOption[]>(() =>
    this.openSessions().map((s) => ({ uid: s.uid, label: `Session ${s.uid.slice(-8)} (Till ${s.posTillId})` })),
  );

  // ── Customers (for picker) ─────────────────────────────────────────────────
  readonly customers = signal<CustomerModel[]>([]);
  readonly customerOptions = computed<UidOption[]>(() =>
    this.customers().filter((c) => c.status === 'ACTIVE').map((c) => ({ uid: c.uid, label: c.displayName, hint: c.code })),
  );

  // ── Agents (for picker) ────────────────────────────────────────────────────
  readonly agents = signal<AgentModel[]>([]);
  readonly agentOptions = computed<UidOption[]>(() =>
    this.agents().filter((a) => a.status === 'ACTIVE').map((a) => ({ uid: a.uid, label: a.displayName, hint: a.code })),
  );

  // ── Products (for picker) ──────────────────────────────────────────────────
  readonly products = signal<ProductModel[]>([]);
  readonly productOptions = computed<UidOption[]>(() =>
    this.products().filter((p) => p.status === 'ACTIVE' && p.sellable).map((p) => ({ uid: p.uid, label: p.name, hint: p.code })),
  );

  // ── Units (for picker) ────────────────────────────────────────────────────
  readonly units = signal<UnitOfMeasureDto[]>([]);
  readonly unitOptions = computed<UidOption[]>(() =>
    this.units().filter((u) => u.status === 'ACTIVE').map((u) => ({ uid: u.uid, label: u.name, hint: u.code })),
  );

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
  readonly subtotal = computed<number>(() =>
    this.lines().reduce((sum, l) => {
      const qty = +l.quantity || 0;
      const price = +l.unitPrice || 0;
      const disc = +l.lineDiscountAmount || 0;
      return sum + (qty * price - disc);
    }, 0),
  );

  readonly change = computed<number>(() => {
    const tendered = +this.tenderedAmount() || 0;
    return tendered - this.subtotal();
  });

  // ── Submit state ───────────────────────────────────────────────────────────
  readonly submitting = signal(false);
  readonly formError = signal<string | null>(null);
  readonly savedInvoice = signal<SalesInvoiceDto | null>(null);

  // ── Permission ─────────────────────────────────────────────────────────────
  readonly canSell = computed(() => this.session.hasPermission('POS.SALE.CREATE'));

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
      .subscribe({ next: ({ rows }) => this.agents.set(rows), error: () => {} });

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
    this.posService.listSessions(companyId, 0, 50).subscribe({
      next: ({ rows }) => this.openSessions.set(rows.filter((s) => s.status === 'OPEN')),
      error: () => {},
    });
    // Seed customer/agent/product options with empty query
    this.customerSearch$.next('');
    this.agentSearch$.next('');
    this.productSearch$.next('');
    // Load units
    this.productService.listUnits(companyId, undefined, 0, 200).subscribe({
      next: ({ rows }) => this.units.set(rows),
      error: () => {},
    });
  }

  onCompanyChange(id: string): void {
    this.selectedCompanyId.set(id);
    this.selectedSessionUid.set('');
    this.selectedCustomerUid.set('');
    this.selectedAgentUid.set('');
    this.lines.set([]);
    if (id) this.loadOptions(id);
  }

  onProductSearch(q: string): void { this.productSearch$.next(q); }
  onCustomerSearch(q: string): void { this.customerSearch$.next(q); }
  onAgentSearch(q: string): void { this.agentSearch$.next(q); }

  // ── Line management ────────────────────────────────────────────────────────

  addLine(): void {
    this.lines.update((ls) => [
      ...ls,
      { id: nextLineId(), productUid: '', productId: '', productName: '', unitUid: '', unitId: '', unitName: '', quantity: '1', unitPrice: '0.00', lineDiscountAmount: '0.00' },
    ]);
  }

  removeLine(lineId: string): void {
    this.lines.update((ls) => ls.filter((l) => l.id !== lineId));
  }

  onLineProductChange(lineId: string, productUid: string): void {
    const product = this.products().find((p) => p.uid === productUid);
    this.lines.update((ls) =>
      ls.map((l) =>
        l.id === lineId
          ? { ...l, productUid, productId: product?.id ?? '', productName: product?.name ?? '',
              unitUid: product?.baseUnitUid ?? '', unitId: '', unitName: product?.baseUnitName ?? '' }
          : l,
      ),
    );
    // Resolve the base unit id
    if (product?.baseUnitUid) {
      const unit = this.units().find((u) => u.uid === product.baseUnitUid);
      if (unit) {
        this.lines.update((ls) =>
          ls.map((l) => l.id === lineId ? { ...l, unitId: unit.id, unitName: unit.name } : l),
        );
      }
    }
  }

  onLineUnitChange(lineId: string, unitUid: string): void {
    const unit = this.units().find((u) => u.uid === unitUid);
    this.lines.update((ls) =>
      ls.map((l) =>
        l.id === lineId ? { ...l, unitUid, unitId: unit?.id ?? '', unitName: unit?.name ?? '' } : l,
      ),
    );
  }

  onLineFieldChange(lineId: string, field: 'quantity' | 'unitPrice' | 'lineDiscountAmount', value: string): void {
    this.lines.update((ls) =>
      ls.map((l) => l.id === lineId ? { ...l, [field]: value } : l),
    );
  }

  // ── Submit ─────────────────────────────────────────────────────────────────

  /** Returns an error message for a single sale line, or null if valid. */
  private validateSaleLine(l: SaleLine): string | null {
    if (!l.productId) return 'Select a product for every line.';
    if (!l.unitId) return 'Select a unit for every line.';
    if (!l.quantity || +l.quantity <= 0) return 'Quantity must be positive for every line.';
    if (!l.unitPrice || +l.unitPrice < 0) return 'Unit price must be non-negative for every line.';
    return null;
  }

  /** Returns a validation error message, or null if the form is valid. */
  private validateSaleForm(sessionUid: string, customerUid: string, agentUid: string, curr: string, tendered: string): string | null {
    if (!sessionUid) return 'Session is required.';
    if (!customerUid) return 'Customer is required.';
    if (!agentUid) return 'Agent is required.';
    if (!curr) return 'Currency is required.';
    if (this.lines().length === 0) return 'Add at least one line item.';
    for (const l of this.lines()) {
      const lineErr = this.validateSaleLine(l);
      if (lineErr) return lineErr;
    }
    if (!tendered || Number.isNaN(+tendered) || +tendered < 0) return 'Tendered amount must be a valid non-negative number.';
    if (+tendered < this.subtotal()) return 'Tendered amount is less than the total.';
    return null;
  }

  submit(): void {
    const sessionUid = this.selectedSessionUid().trim();
    const customerUid = this.selectedCustomerUid().trim();
    const agentUid = this.selectedAgentUid().trim();
    const curr = this.currency().trim();
    const tendered = this.tenderedAmount().trim();

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
      quantity: l.quantity,
      unitPrice: l.unitPrice,
      lineDiscountAmount: +l.lineDiscountAmount > 0 ? l.lineDiscountAmount : undefined,
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
        this.alerts.success('Sale recorded', invoice.invoiceNumber ?? invoice.uid);
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
      this.posService.listSessions(cId, 0, 50).subscribe({
        next: ({ rows }) => this.openSessions.set(rows.filter((s) => s.status === 'OPEN')),
        error: () => {},
      });
    }
  }

  lineSubtotal(l: SaleLine): number {
    return (+l.quantity || 0) * (+l.unitPrice || 0) - (+l.lineDiscountAmount || 0);
  }

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}
