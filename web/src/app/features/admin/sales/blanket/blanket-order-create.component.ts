import { HttpErrorResponse } from '@angular/common/http';
import { DecimalPipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { debounceTime, distinctUntilChanged, Subject, switchMap } from 'rxjs';
import { AlertService } from '../../../../core/feedback/alert.service';
import { SessionStore } from '../../../../core/auth/session.store';
import { Company } from '../../models/company.model';
import { Branch } from '../../models/branch.model';
import { CustomerModel } from '../../models/party.model';
import { ProductModel, UnitOfMeasureDto } from '../../models/product.model';
import { CompanyService } from '../../company/company.service';
import { OrganisationService } from '../../organisation/organisation.service';
import { BranchService } from '../../branch/branch.service';
import { CustomerService } from '../../parties/customer.service';
import { ProductService } from '../../products/product.service';
import { BlanketOrderService } from './blanket-order.service';
import {
  BlanketLineRequest,
  CreateBlanketOrderRequest,
} from './blanket-order.model';
import { CurrencySelectComponent } from '../../../../shared/currency-select/currency-select.component';

interface LineEntry {
  productId: string;
  productLabel: string;
  unitId: string;
  unitName: string;
  committedQtyBase: string;
  unitPriceAmount: string;
}

/**
 * Blanket Order create screen.
 * Route: /admin/blanket-orders/create
 * On success navigates to the detail screen.
 */
@Component({
  selector: 'app-blanket-order-create',
  imports: [FormsModule, RouterLink, DecimalPipe, CurrencySelectComponent],
  templateUrl: './blanket-order-create.component.html',
  styleUrl: './blanket-order-create.component.scss',
})
export class BlanketOrderCreateComponent {
  private readonly blanketService = inject(BlanketOrderService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly branchService = inject(BranchService);
  private readonly customerService = inject(CustomerService);
  private readonly productService = inject(ProductService);
  private readonly router = inject(Router);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  // ── Company context ──────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompany = signal<Company | null>(null);
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── Branch picker ────────────────────────────────────────────────────────
  readonly branches = signal<Branch[]>([]);
  readonly selectedBranchId = signal('');

  // ── Customer picker ──────────────────────────────────────────────────────
  readonly customerSearchQ = signal('');
  readonly customerResults = signal<CustomerModel[]>([]);
  readonly selectedCustomer = signal<{ uid: string; id: string; label: string } | null>(null);
  private readonly customerSearch$ = new Subject<string>();

  // ── Header fields ────────────────────────────────────────────────────────
  readonly currency = signal('TZS');
  readonly validFrom = signal('');
  readonly validTo = signal('');
  readonly notes = signal('');

  // ── Line builder ─────────────────────────────────────────────────────────
  readonly lines = signal<LineEntry[]>([]);

  readonly productSearchQ = signal('');
  readonly productResults = signal<ProductModel[]>([]);
  readonly selectedProduct = signal<{ uid: string; id: string; label: string } | null>(null);
  private readonly productSearch$ = new Subject<string>();

  readonly units = signal<UnitOfMeasureDto[]>([]);
  readonly selectedUnitId = signal('');
  readonly lineQty = signal('');
  readonly lineUnitPrice = signal('');
  readonly addLineError = signal<string | null>(null);

  // ── Submit ────────────────────────────────────────────────────────────────
  readonly saving = signal(false);
  readonly formError = signal<string | null>(null);

  readonly canCreate = computed(() => this.session.hasPermission('SALES.BLANKET.CREATE'));

  constructor() {
    this.customerSearch$
      .pipe(
        debounceTime(300),
        distinctUntilChanged(),
        switchMap((q) => {
          const companyId = this.selectedCompany()?.id;
          if (!companyId || !q.trim()) { this.customerResults.set([]); return []; }
          return this.customerService.list(companyId, q.trim(), 0, 10);
        }),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: ({ rows }) => this.customerResults.set(rows.filter((c) => c.status === 'ACTIVE')),
        error: () => this.customerResults.set([]),
      });

    this.productSearch$
      .pipe(
        debounceTime(300),
        distinctUntilChanged(),
        switchMap((q) => {
          const companyId = this.selectedCompany()?.id;
          if (!companyId || !q.trim()) { this.productResults.set([]); return []; }
          return this.productService.list(companyId, q.trim(), 0, 10);
        }),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: ({ rows }) => this.productResults.set(rows.filter((p) => p.status !== 'ARCHIVED')),
        error: () => this.productResults.set([]),
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
              this.selectCompany(list[0]);
            }
          },
          error: () => this.companyState.set('error'),
        });
      },
      error: () => this.companyState.set('error'),
    });
  }

  private selectCompany(c: Company): void {
    this.selectedCompany.set(c);
    this.branches.set([]);
    this.selectedBranchId.set('');
    this.branchService.list(c.uid).subscribe({
      next: (list) => {
        this.branches.set(list.filter((b) => b.status !== 'ARCHIVED'));
        if (list.length > 0) this.selectedBranchId.set(list[0].id);
      },
      error: () => this.branches.set([]),
    });
    this.loadUnits(c.id);
  }

  private loadUnits(companyId: string): void {
    this.productService.listUnits(companyId).subscribe({
      next: ({ rows }) => this.units.set(rows.filter((u) => u.status === 'ACTIVE')),
      error: () => this.units.set([]),
    });
  }

  onCompanyChange(id: string): void {
    const company = this.companies().find((c) => c.id === id);
    if (company) this.selectCompany(company);
  }

  // ── Customer picker ──────────────────────────────────────────────────────

  onCustomerSearchChange(q: string): void {
    this.customerSearchQ.set(q);
    this.selectedCustomer.set(null);
    this.customerSearch$.next(q);
  }

  selectCustomer(c: CustomerModel): void {
    this.selectedCustomer.set({ uid: c.uid, id: c.id, label: `${c.code} — ${c.displayName}` });
    this.customerResults.set([]);
    this.customerSearchQ.set(`${c.code} — ${c.displayName}`);
  }

  // ── Product picker ───────────────────────────────────────────────────────

  onProductSearchChange(q: string): void {
    this.productSearchQ.set(q);
    this.selectedProduct.set(null);
    this.productSearch$.next(q);
  }

  selectProduct(p: ProductModel): void {
    this.selectedProduct.set({ uid: p.uid, id: p.id, label: `${p.code} — ${p.name}` });
    this.productResults.set([]);
    this.productSearchQ.set(`${p.code} — ${p.name}`);
    this.selectedUnitId.set('');
  }

  // ── Lines ─────────────────────────────────────────────────────────────────

  addLine(): void {
    const product = this.selectedProduct();
    const unitId = this.selectedUnitId();
    const qty = this.lineQty().trim();
    const price = this.lineUnitPrice().trim();

    if (!product) { this.addLineError.set('Select a product.'); return; }
    if (!unitId) { this.addLineError.set('Select a unit.'); return; }
    if (!qty || isNaN(Number(qty)) || Number(qty) <= 0) {
      this.addLineError.set('Enter a committed quantity greater than zero.'); return;
    }
    if (!price || isNaN(Number(price)) || Number(price) < 0) {
      this.addLineError.set('Enter a unit price (0 or more).'); return;
    }

    const unit = this.units().find((u) => u.id === unitId);

    this.lines.update((prev) => [
      ...prev,
      {
        productId: product.id,
        productLabel: product.label,
        unitId,
        unitName: unit?.name ?? unitId,
        committedQtyBase: qty,
        unitPriceAmount: price,
      },
    ]);

    // Reset line builder
    this.selectedProduct.set(null);
    this.productSearchQ.set('');
    this.productResults.set([]);
    this.selectedUnitId.set('');
    this.lineQty.set('');
    this.lineUnitPrice.set('');
    this.addLineError.set(null);
  }

  removeLine(index: number): void {
    this.lines.update((prev) => prev.filter((_, i) => i !== index));
  }

  // ── Submit ────────────────────────────────────────────────────────────────

  submit(): void {
    const company = this.selectedCompany();
    const customer = this.selectedCustomer();
    const branchId = this.selectedBranchId();
    const curr = this.currency().trim();
    const from = this.validFrom().trim();
    const to = this.validTo().trim();

    if (!company) { this.formError.set('Select a company.'); return; }
    if (!customer) { this.formError.set('Customer is required.'); return; }
    if (!branchId) { this.formError.set('Branch is required.'); return; }
    if (!curr) { this.formError.set('Currency is required.'); return; }
    if (!from) { this.formError.set('Valid From date is required.'); return; }
    if (!to) { this.formError.set('Valid To date is required.'); return; }
    if (from > to) { this.formError.set('Valid From must be before Valid To.'); return; }
    if (this.lines().length === 0) { this.formError.set('Add at least one agreement line.'); return; }

    this.saving.set(true);
    this.formError.set(null);

    const requestLines: BlanketLineRequest[] = this.lines().map((l) => ({
      productId: l.productId,
      unitId: l.unitId,
      committedQtyBase: l.committedQtyBase,
      unitPriceAmount: l.unitPriceAmount,
    }));

    const request: CreateBlanketOrderRequest = {
      companyUid: company.uid,
      branchId,
      customerId: customer.id,
      currency: curr,
      validFrom: from,
      validTo: to,
      lines: requestLines,
      notes: this.notes().trim() || undefined,
    };

    this.blanketService.create(request).subscribe({
      next: (created) => {
        this.saving.set(false);
        this.alerts.success('Blanket order created', created.orderNumber ?? 'ACTIVE');
        this.router.navigate(['/admin/blanket-orders/uid', created.uid]);
      },
      error: (err: unknown) => {
        this.formError.set(this.messageFrom(err, 'Could not create blanket order.'));
        this.saving.set(false);
      },
    });
  }

  toStr(v: unknown): string {
    return v === null || v === undefined ? '' : String(v);
  }

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}
