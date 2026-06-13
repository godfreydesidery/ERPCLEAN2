import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { AlertService } from '../../../../core/feedback/alert.service';
import { SessionStore } from '../../../../core/auth/session.store';
import { Branch } from '../../models/branch.model';
import { Company } from '../../models/company.model';
import { ProductModel, UnitOfMeasureDto } from '../../models/product.model';
import { CustomerModel } from '../../models/party.model';
import { BranchService } from '../../branch/branch.service';
import { CompanyService } from '../../company/company.service';
import { OrganisationService } from '../../organisation/organisation.service';
import { CustomerService } from '../../parties/customer.service';
import { ProductService } from '../../products/product.service';
import { UidPickerComponent, UidOption } from '../../../../shared/uid-picker/uid-picker.component';
import {
  CreateStandingOrderLineRequest,
  CreateStandingOrderRequest,
  StandingFrequency,
} from './standing-order.model';
import { StandingOrderService } from './standing-order.service';

interface LineEntry {
  productUid: string;
  unitUid: string;
  qty: string;
  qtyBase: string;
  unitPriceAmount: string;
}

/**
 * Create standing (recurring) order screen.
 * Route: /admin/standing-orders/create
 * Guard: SALES.STANDING.CREATE
 *
 * Builds line items with product + unit pickers, resolves uid→id before submit.
 */
@Component({
  selector: 'app-standing-order-create',
  imports: [FormsModule, RouterLink, UidPickerComponent],
  templateUrl: './standing-order-create.component.html',
  styleUrl: './standing-order-create.component.scss',
})
export class StandingOrderCreateComponent {
  private readonly standingService = inject(StandingOrderService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly branchService = inject(BranchService);
  private readonly customerService = inject(CustomerService);
  private readonly productService = inject(ProductService);
  private readonly alerts = inject(AlertService);
  private readonly router = inject(Router);
  protected readonly session = inject(SessionStore);

  // ── Company context ──────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyUid = signal('');
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── Branches ─────────────────────────────────────────────────────────────────
  readonly branches = signal<Branch[]>([]);
  readonly branchOptions = computed<UidOption[]>(() =>
    this.branches().map((b) => ({ uid: b.uid, label: b.name, hint: b.code })),
  );
  readonly selectedBranchUid = signal('');

  // ── Customers ────────────────────────────────────────────────────────────────
  readonly customers = signal<CustomerModel[]>([]);
  readonly customerOptions = computed<UidOption[]>(() =>
    this.customers()
      .filter((c) => c.status === 'ACTIVE')
      .map((c) => ({ uid: c.uid, label: c.displayName, hint: c.code })),
  );
  readonly selectedCustomerUid = signal('');

  // ── Products + Units ─────────────────────────────────────────────────────────
  readonly products = signal<ProductModel[]>([]);
  readonly productOptions = computed<UidOption[]>(() =>
    this.products()
      .filter((p) => p.status !== 'ARCHIVED')
      .map((p) => ({ uid: p.uid, label: p.name, hint: p.code })),
  );
  readonly units = signal<UnitOfMeasureDto[]>([]);
  readonly unitOptions = computed<UidOption[]>(() =>
    this.units()
      .filter((u) => u.status === 'ACTIVE')
      .map((u) => ({ uid: u.uid, label: u.name, hint: u.code })),
  );

  // ── Form fields ──────────────────────────────────────────────────────────────
  readonly fCurrency = signal('TZS');
  readonly fFrequency = signal<StandingFrequency>('MONTHLY');
  readonly fStartDate = signal('');
  readonly fEndDate = signal('');
  readonly fNotes = signal('');

  readonly lines = signal<LineEntry[]>([
    { productUid: '', unitUid: '', qty: '', qtyBase: '', unitPriceAmount: '' },
  ]);

  readonly saving = signal(false);
  readonly formError = signal<string | null>(null);

  readonly canCreate = computed(() => this.session.hasPermission('SALES.STANDING.CREATE'));

  readonly frequencyOptions: Array<{ value: StandingFrequency; label: string }> = [
    { value: 'DAILY', label: 'Daily' },
    { value: 'WEEKLY', label: 'Weekly' },
    { value: 'BIWEEKLY', label: 'Bi-Weekly' },
    { value: 'MONTHLY', label: 'Monthly' },
  ];

  constructor() {
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
              this.selectedCompanyUid.set(list[0].uid);
              this.selectedCompanyId.set(list[0].id);
              this.loadDependencies(list[0].uid, list[0].id);
            }
          },
          error: () => this.companyState.set('error'),
        });
      },
      error: () => this.companyState.set('error'),
    });
  }

  private loadDependencies(companyUid: string, companyId: string): void {
    this.branchService.list(companyUid).subscribe({
      next: (list) => this.branches.set(list),
      error: () => undefined,
    });
    this.customerService.list(companyId, undefined, 0, 200).subscribe({
      next: ({ rows }) => this.customers.set(rows),
      error: () => undefined,
    });
    this.productService.list(companyId, undefined, 0, 200).subscribe({
      next: ({ rows }) => this.products.set(rows),
      error: () => undefined,
    });
    this.productService.listUnits(companyId).subscribe({
      next: ({ rows }) => this.units.set(rows),
      error: () => undefined,
    });
  }

  onCompanyChange(uid: string): void {
    const company = this.companies().find((c) => c.uid === uid);
    if (!company) return;
    this.selectedCompanyUid.set(uid);
    this.selectedCompanyId.set(company.id);
    this.selectedBranchUid.set('');
    this.selectedCustomerUid.set('');
    this.branches.set([]);
    this.customers.set([]);
    this.products.set([]);
    this.units.set([]);
    this.loadDependencies(uid, company.id);
  }

  // ── Lines ────────────────────────────────────────────────────────────────────

  addLine(): void {
    this.lines.update((ls) => [
      ...ls,
      { productUid: '', unitUid: '', qty: '', qtyBase: '', unitPriceAmount: '' },
    ]);
  }

  removeLine(idx: number): void {
    this.lines.update((ls) => ls.filter((_, i) => i !== idx));
  }

  updateLine(idx: number, patch: Partial<LineEntry>): void {
    this.lines.update((ls) =>
      ls.map((l, i) => (i === idx ? { ...l, ...patch } : l)),
    );
  }

  // ── Submit ───────────────────────────────────────────────────────────────────

  submit(): void {
    if (this.saving()) return;
    this.formError.set(null);

    const companyUid = this.selectedCompanyUid();
    if (!companyUid) { this.formError.set('Company is required.'); return; }

    const branchUid = this.selectedBranchUid();
    if (!branchUid) { this.formError.set('Branch is required.'); return; }
    const branch = this.branches().find((b) => b.uid === branchUid);
    if (!branch) { this.formError.set('Could not resolve branch.'); return; }

    const customerUid = this.selectedCustomerUid();
    if (!customerUid) { this.formError.set('Customer is required.'); return; }
    const customer = this.customers().find((c) => c.uid === customerUid);
    if (!customer) { this.formError.set('Could not resolve customer.'); return; }

    const currency = this.fCurrency().trim();
    if (!currency) { this.formError.set('Currency is required.'); return; }

    const startDate = this.fStartDate().trim();
    if (!startDate) { this.formError.set('Start date is required.'); return; }

    const lineEntries = this.lines();
    if (lineEntries.length === 0) { this.formError.set('At least one line is required.'); return; }

    const resolvedLines: CreateStandingOrderLineRequest[] = [];
    for (let i = 0; i < lineEntries.length; i++) {
      const line = lineEntries[i];
      if (!line.productUid) { this.formError.set(`Line ${i + 1}: product is required.`); return; }
      if (!line.unitUid) { this.formError.set(`Line ${i + 1}: unit is required.`); return; }

      const product = this.products().find((p) => p.uid === line.productUid);
      if (!product) { this.formError.set(`Line ${i + 1}: could not resolve product.`); return; }

      const unit = this.units().find((u) => u.uid === line.unitUid);
      if (!unit) { this.formError.set(`Line ${i + 1}: could not resolve unit.`); return; }

      const qty = line.qty.trim();
      const qtyBase = line.qtyBase.trim() || qty;
      const price = line.unitPriceAmount.trim();
      if (!qty || isNaN(Number(qty)) || Number(qty) <= 0) {
        this.formError.set(`Line ${i + 1}: enter a valid quantity.`);
        return;
      }
      if (!price || isNaN(Number(price)) || Number(price) < 0) {
        this.formError.set(`Line ${i + 1}: enter a valid unit price.`);
        return;
      }

      resolvedLines.push({
        productId: product.id,
        unitId: unit.id,
        qty,
        qtyBase,
        unitPriceAmount: price,
      });
    }

    const request: CreateStandingOrderRequest = {
      companyUid,
      branchId: branch.id,
      customerId: customer.id,
      currency,
      frequency: this.fFrequency(),
      startDate,
      endDate: this.fEndDate().trim() || undefined,
      lines: resolvedLines,
      notes: this.fNotes().trim() || undefined,
    };

    this.saving.set(true);
    this.standingService.create(request).subscribe({
      next: (created) => {
        this.saving.set(false);
        this.alerts.success('Standing order created', created.orderNumber ?? created.uid);
        void this.router.navigate(['/admin/standing-orders/uid', created.uid]);
      },
      error: (err: unknown) => {
        this.formError.set(this.messageFrom(err, 'Could not create standing order.'));
        this.saving.set(false);
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
