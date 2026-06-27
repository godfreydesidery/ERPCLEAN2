import { HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { AlertService } from '../../../../core/feedback/alert.service';

import { SessionStore } from '../../../../core/auth/session.store';
import { Company } from '../../models/company.model';
import { ProductModel, UnitOfMeasureDto } from '../../models/product.model';
import { CompanyService } from '../../company/company.service';
import { OrganisationService } from '../../organisation/organisation.service';
import { ProductService } from '../../products/product.service';
import { UidPickerComponent, UidOption } from '../../../../shared/uid-picker/uid-picker.component';
import { CreatePurchaseRequisitionRequest, RequisitionLineRequest } from './purchase-requisition.model';
import { PurchaseRequisitionService } from './purchase-requisition.service';

/** A single line item in the creation form */
interface LineEntry {
  productUid: string;
  unitUid: string;
  requestedQty: string;
  estimatedUnitCost: string;
  note: string;
  /** Product-scoped unit options; empty until a product is selected for this line. */
  lineUnitOptions: UidOption[];
  lineUnitsLoading: boolean;
}

function blankLine(): LineEntry {
  return { productUid: '', unitUid: '', requestedQty: '', estimatedUnitCost: '', note: '', lineUnitOptions: [], lineUnitsLoading: false };
}

@Component({
  selector: 'app-requisition-create',
  imports: [FormsModule, RouterLink, UidPickerComponent],
  templateUrl: './requisition-create.component.html',
  styleUrl: './requisition-create.component.scss',
})
export class RequisitionCreateComponent {
  private readonly reqService = inject(PurchaseRequisitionService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly productService = inject(ProductService);
  private readonly router = inject(Router);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);
  private readonly destroyRef = inject(DestroyRef);

  // ── Company context ────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyUid = signal('');
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── Product + unit pickers ─────────────────────────────────────────────────
  readonly products = signal<ProductModel[]>([]);
  /**
   * Accumulates all UoMs fetched via per-line listProductUnits calls so submit()
   * can resolve uid → id without re-fetching.
   */
  private readonly loadedUnits = signal<UnitOfMeasureDto[]>([]);
  readonly pickerState = signal<'loading' | 'idle' | 'error'>('idle');

  readonly productOptions = computed<UidOption[]>(() =>
    this.products().map((p) => ({ uid: p.uid, label: p.name, hint: p.code })),
  );

  // ── Form fields ────────────────────────────────────────────────────────────
  readonly requiredByDate = signal('');
  readonly costCentreCode = signal('');
  readonly notes = signal('');
  readonly lines = signal<LineEntry[]>([blankLine()]);

  readonly saving = signal(false);
  readonly formError = signal<string | null>(null);

  readonly canCreate = computed(() => this.session.hasPermission('PURCHASE.REQUISITION.CREATE'));

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
              this.loadPickerData(list[0].id);
            }
          },
          error: () => this.companyState.set('error'),
        });
      },
      error: () => this.companyState.set('error'),
    });
  }

  private loadPickerData(companyId: string): void {
    this.pickerState.set('loading');
    this.productService.list(companyId, undefined, 0, 200)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: ({ rows }) => {
          this.products.set(rows);
          this.pickerState.set('idle');
        },
        error: () => this.pickerState.set('error'),
      });
  }

  onCompanyChange(id: string): void {
    const company = this.companies().find((c) => c.id === id);
    if (!company) return;
    this.selectedCompanyId.set(company.id);
    this.selectedCompanyUid.set(company.uid);
    this.loadPickerData(company.id);
  }

  // ── Line management ────────────────────────────────────────────────────────

  addLine(): void {
    this.lines.update((l) => [...l, blankLine()]);
  }

  removeLine(index: number): void {
    this.lines.update((l) => l.filter((_, i) => i !== index));
  }

  updateLine(index: number, patch: Partial<LineEntry>): void {
    this.lines.update((l) =>
      l.map((entry, i) => (i === index ? { ...entry, ...patch } : entry)),
    );
  }

  onLineProductChange(index: number, productUid: string): void {
    // Clear unit selection for this line and start loading product-scoped units.
    this.updateLine(index, { productUid, unitUid: '', lineUnitOptions: [], lineUnitsLoading: true });
    if (!productUid) return;
    this.productService.listProductUnits(productUid).subscribe({
      next: (units) => {
        // Accumulate into loadedUnits for uid→id resolution at submit time.
        const existing = this.loadedUnits();
        units.forEach((u) => {
          if (!existing.some((e) => e.uid === u.uid)) {
            this.loadedUnits.update((arr) => [...arr, u]);
          }
        });
        const opts = units.map((u) => ({ uid: u.uid, label: u.name, hint: u.code }));
        const defaultUid = units[0]?.uid ?? '';
        this.updateLine(index, { lineUnitOptions: opts, lineUnitsLoading: false, unitUid: defaultUid });
      },
      error: () => this.updateLine(index, { lineUnitsLoading: false }),
    });
  }

  // ── Submit ─────────────────────────────────────────────────────────────────

  submit(): void {
    const companyUid = this.selectedCompanyUid();
    if (!companyUid) { this.formError.set('Company is required.'); return; }

    const lineEntries = this.lines();
    if (lineEntries.length === 0) {
      this.formError.set('At least one line is required.');
      return;
    }

    // Validate each line
    for (let i = 0; i < lineEntries.length; i++) {
      const l = lineEntries[i];
      if (!l.productUid) { this.formError.set(`Line ${i + 1}: product is required.`); return; }
      if (!l.unitUid) { this.formError.set(`Line ${i + 1}: unit is required.`); return; }
      const qty = Number(l.requestedQty);
      if (!l.requestedQty.trim() || isNaN(qty) || qty <= 0) {
        this.formError.set(`Line ${i + 1}: quantity must be greater than zero.`);
        return;
      }
    }

    // Resolve uid → id for products and units
    const productMap = new Map(this.products().map((p) => [p.uid, p.id]));
    const unitMap = new Map(this.loadedUnits().map((u) => [u.uid, u.id]));

    const resolvedLines: RequisitionLineRequest[] = [];
    for (let i = 0; i < lineEntries.length; i++) {
      const l = lineEntries[i];
      const productId = productMap.get(l.productUid);
      const unitId = unitMap.get(l.unitUid);
      if (!productId) { this.formError.set(`Line ${i + 1}: product not found.`); return; }
      if (!unitId) { this.formError.set(`Line ${i + 1}: unit not found.`); return; }
      resolvedLines.push({
        productId,
        unitId,
        requestedQty: l.requestedQty.trim(),
        estimatedUnitCost: l.estimatedUnitCost.trim() || undefined,
        note: l.note.trim() || undefined,
      });
    }

    const request: CreatePurchaseRequisitionRequest = {
      companyUid,
      requiredByDate: this.requiredByDate().trim() || undefined,
      costCentreCode: this.costCentreCode().trim() || undefined,
      notes: this.notes().trim() || undefined,
      lines: resolvedLines,
    };

    this.saving.set(true);
    this.formError.set(null);

    this.reqService.create(request).subscribe({
      next: (created) => {
        this.saving.set(false);
        this.alerts.success('Purchase requisition created', created.requisitionNumber ?? undefined);
        this.router.navigate(['/admin/purchase-requisitions/uid', created.uid]);
      },
      error: (err) => {
        this.formError.set(this.messageFrom(err, 'Could not create purchase requisition.'));
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
