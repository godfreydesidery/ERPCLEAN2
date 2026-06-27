import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { debounceTime, distinctUntilChanged, Subject, switchMap } from 'rxjs';
import { AlertService } from '../../../../core/feedback/alert.service';
import { SessionStore } from '../../../../core/auth/session.store';
import { Company } from '../../models/company.model';
import { ProductModel, UnitOfMeasureDto } from '../../models/product.model';
import { SupplierModel } from '../../models/party.model';
import { CompanyService } from '../../company/company.service';
import { OrganisationService } from '../../organisation/organisation.service';
import { ProductService } from '../../products/product.service';
import { SupplierService } from '../../parties/supplier.service';
import { CreateRfqRequest } from './models/rfq.model';
import { RfqService } from './rfq.service';
import { UidOption, UidPickerComponent } from '../../../../shared/uid-picker/uid-picker.component';

/** One editable line on the create form. */
interface RfqLineEntry {
  /** uid of the picked product (drives the picker) */
  productUid: string;
  /** resolved id string (FK for request body) */
  productId: string;
  /** uid of the picked unit */
  unitUid: string;
  /** resolved id string (FK for request body) */
  unitId: string;
  quantity: string;
  /** Product-scoped unit options; empty until a product is selected. */
  lineUnitOptions: UidOption[];
  lineUnitsLoading: boolean;
}

/**
 * RFQ create screen.
 * - Company selector → loads products + units + suppliers for that company.
 * - Dynamic line items: product picker + unit picker + qty.
 * - Multi-supplier invite: add/remove pattern (each supplier selected via UidPicker).
 */
@Component({
  selector: 'app-rfq-create',
  imports: [FormsModule, RouterLink, UidPickerComponent],
  templateUrl: './rfq-create.component.html',
  styleUrl: './rfq-create.component.scss',
})
export class RfqCreateComponent {
  private readonly rfqService = inject(RfqService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly productService = inject(ProductService);
  private readonly supplierService = inject(SupplierService);
  private readonly router = inject(Router);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  // ── Company context ────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly selectedCompanyUid = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── Options for pickers ───────────────────────────────────────────────────
  readonly productOptions = signal<UidOption[]>([]);
  readonly supplierOptions = signal<UidOption[]>([]);

  /** Raw models for id resolution. */
  private productMap = new Map<string, string>(); // uid → id
  private unitMap = new Map<string, string>();    // uid → id (still populated for backwards compat)

  // ── Lines ──────────────────────────────────────────────────────────────────
  readonly lines = signal<RfqLineEntry[]>([this.blankLine()]);

  // ── Invited suppliers ─────────────────────────────────────────────────────
  /** uids of invited suppliers. */
  readonly invitedSupplierUids = signal<string[]>([]);
  /** Uid currently being added (drives the picker). */
  readonly pendingSupplierUid = signal('');

  // ── Header fields ──────────────────────────────────────────────────────────
  readonly responseDueDate = signal('');
  readonly notes = signal('');

  // ── Form state ─────────────────────────────────────────────────────────────
  readonly saving = signal(false);
  readonly formError = signal<string | null>(null);

  readonly canCreate = computed(() => this.session.hasPermission('PURCHASE.RFQ.MANAGE'));

  /** Supplier options not yet invited (filter out already-added ones). */
  readonly availableSupplierOptions = computed<UidOption[]>(() => {
    const added = new Set(this.invitedSupplierUids());
    return this.supplierOptions().filter((o) => !added.has(o.uid));
  });

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
              this.selectCompany(list[0].id, list[0].uid);
            }
          },
          error: () => this.companyState.set('error'),
        });
      },
      error: () => this.companyState.set('error'),
    });
  }

  onCompanyChange(id: string): void {
    const company = this.companies().find((c) => c.id === id);
    if (company) this.selectCompany(company.id, company.uid);
  }

  private selectCompany(id: string, uid: string): void {
    this.selectedCompanyId.set(id);
    this.selectedCompanyUid.set(uid);
    this.loadProductsAndUnits(id);
    this.loadSuppliers(id);
  }

  private loadProductsAndUnits(companyId: string): void {
    this.productService.list(companyId, undefined, 0, 200).subscribe({
      next: ({ rows }) => {
        this.productMap = new Map(rows.map((p) => [p.uid, p.id]));
        this.productOptions.set(rows.map((p) => ({ uid: p.uid, label: p.name, hint: p.code })));
      },
      error: () => {},
    });
    // unitMap is still populated so we can resolve unit id from uid after
    // listProductUnits returns (which gives uid but not id directly).
    this.productService.listUnits(companyId, undefined, 0, 200).subscribe({
      next: ({ rows }) => {
        this.unitMap = new Map(rows.map((u) => [u.uid, u.id]));
      },
      error: () => {},
    });
  }

  private loadSuppliers(companyId: string): void {
    this.supplierService.list(companyId, undefined, 0, 200).subscribe({
      next: ({ rows }) => {
        this.supplierOptions.set(
          rows.filter((s) => s.status === 'ACTIVE').map((s) => ({
            uid: s.uid,
            label: s.displayName,
            hint: s.code,
          })),
        );
      },
      error: () => {},
    });
  }

  // ── Line management ────────────────────────────────────────────────────────

  private blankLine(): RfqLineEntry {
    return {
      productUid: '', productId: '',
      unitUid: '', unitId: '',
      quantity: '',
      lineUnitOptions: [],
      lineUnitsLoading: false,
    };
  }

  addLine(): void {
    this.lines.update((ls) => [...ls, this.blankLine()]);
  }

  removeLine(index: number): void {
    this.lines.update((ls) => ls.filter((_, i) => i !== index));
  }

  onProductPicked(index: number, uid: string): void {
    const id = this.productMap.get(uid) ?? '';
    // Reset the unit selection and mark loading for this line.
    this.lines.update((ls) =>
      ls.map((l, i) =>
        i === index
          ? { ...l, productUid: uid, productId: id, unitUid: '', unitId: '', lineUnitOptions: [], lineUnitsLoading: true }
          : l,
      ),
    );
    if (!uid) return;
    this.productService.listProductUnits(uid).subscribe({
      next: (units) => {
        const opts = units.map((u) => ({ uid: u.uid, label: u.name, hint: u.code }));
        // Populate per-line unit map entries for id resolution.
        units.forEach((u) => this.unitMap.set(u.uid, u.id));
        // Default-select the first unit (base unit).
        const defaultUid = units[0]?.uid ?? '';
        const defaultId = units[0]?.id ?? '';
        this.lines.update((ls) =>
          ls.map((l, i) =>
            i === index
              ? { ...l, lineUnitOptions: opts, lineUnitsLoading: false, unitUid: defaultUid, unitId: defaultId }
              : l,
          ),
        );
      },
      error: () => {
        this.lines.update((ls) =>
          ls.map((l, i) => (i === index ? { ...l, lineUnitsLoading: false } : l)),
        );
      },
    });
  }

  onUnitPicked(index: number, uid: string): void {
    const id = this.unitMap.get(uid) ?? '';
    this.lines.update((ls) =>
      ls.map((l, i) => (i === index ? { ...l, unitUid: uid, unitId: id } : l)),
    );
  }

  onQtyChange(index: number, qty: unknown): void {
    this.lines.update((ls) =>
      ls.map((l, i) => (i === index ? { ...l, quantity: String(qty ?? '') } : l)),
    );
  }

  // ── Supplier invite ────────────────────────────────────────────────────────

  addSupplier(): void {
    const uid = this.pendingSupplierUid();
    if (!uid || this.invitedSupplierUids().includes(uid)) return;
    this.invitedSupplierUids.update((arr) => [...arr, uid]);
    this.pendingSupplierUid.set('');
  }

  removeSupplier(uid: string): void {
    this.invitedSupplierUids.update((arr) => arr.filter((u) => u !== uid));
  }

  supplierLabel(uid: string): string {
    const opt = this.supplierOptions().find((o) => o.uid === uid);
    return opt ? `${opt.hint} — ${opt.label}` : uid;
  }

  // ── Submit ─────────────────────────────────────────────────────────────────

  submit(): void {
    this.formError.set(null);

    const companyUid = this.selectedCompanyUid();
    if (!companyUid) { this.formError.set('Select a company.'); return; }

    const ls = this.lines();
    if (ls.length === 0) { this.formError.set('Add at least one line.'); return; }

    for (let i = 0; i < ls.length; i++) {
      const l = ls[i];
      if (!l.productUid) { this.formError.set(`Line ${i + 1}: select a product.`); return; }
      if (!l.unitUid) { this.formError.set(`Line ${i + 1}: select a unit.`); return; }
      const qty = Number(l.quantity);
      if (!l.quantity.trim() || isNaN(qty) || qty <= 0) {
        this.formError.set(`Line ${i + 1}: quantity must be greater than zero.`); return;
      }
    }

    const supplierUids = this.invitedSupplierUids();
    if (supplierUids.length === 0) { this.formError.set('Invite at least one supplier.'); return; }

    this.saving.set(true);

    const request: CreateRfqRequest = {
      companyUid,
      responseDueDate: this.responseDueDate().trim() || undefined,
      notes: this.notes().trim() || undefined,
      supplierUids,
      lines: ls.map((l) => ({
        productId: l.productId,
        unitId: l.unitId,
        quantity: l.quantity.trim(),
      })),
    };

    this.rfqService.createRfq(request).subscribe({
      next: (created) => {
        this.saving.set(false);
        this.alerts.success('RFQ created', created.rfqNumber);
        this.router.navigate(['/admin/rfqs/uid', created.uid]);
      },
      error: (err) => {
        this.formError.set(this.messageFrom(err, 'Could not create RFQ.'));
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
