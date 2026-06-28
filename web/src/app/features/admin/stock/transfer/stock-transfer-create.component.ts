import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { AlertService } from '../../../../core/feedback/alert.service';
import { SessionStore } from '../../../../core/auth/session.store';
import { CompanyService } from '../../company/company.service';
import { BranchService } from '../../branch/branch.service';
import { OrganisationService } from '../../organisation/organisation.service';
import { ProductService } from '../../products/product.service';
import { UidPickerComponent, UidOption } from '../../../../shared/uid-picker/uid-picker.component';
import { Branch } from '../../models/branch.model';
import { Company } from '../../models/company.model';
import { ProductModel } from '../../models/product.model';
import {
  CreateStockTransferRequest,
  StockLocationDto,
} from './stock-transfer.model';
import { StockTransferService } from './stock-transfer.service';

interface LineEntry {
  productUid: string;
  qty: string;
}

@Component({
  selector: 'app-stock-transfer-create',
  imports: [FormsModule, RouterLink, UidPickerComponent],
  templateUrl: './stock-transfer-create.component.html',
  styleUrl: './stock-transfer-create.component.scss',
})
export class StockTransferCreateComponent {
  private readonly transferService = inject(StockTransferService);
  private readonly companyService = inject(CompanyService);
  private readonly branchService = inject(BranchService);
  private readonly orgService = inject(OrganisationService);
  private readonly productService = inject(ProductService);
  private readonly alerts = inject(AlertService);
  private readonly router = inject(Router);
  protected readonly session = inject(SessionStore);

  // ── Context ───────────────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly selectedCompanyUid = signal('');
  readonly contextState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── Branches ──────────────────────────────────────────────────────────────────
  readonly branches = signal<Branch[]>([]);
  readonly branchOptions = computed<UidOption[]>(() =>
    this.branches().map((b) => ({ uid: b.uid, label: b.name, hint: b.code })),
  );

  // ── Source ────────────────────────────────────────────────────────────────────
  readonly sourceBranchUid = signal('');
  readonly sourceLocations = signal<StockLocationDto[]>([]);
  readonly sourceLocationOptions = computed<UidOption[]>(() =>
    this.sourceLocations().map((l) => ({ uid: l.uid, label: l.name, hint: l.code })),
  );
  readonly sourceLocationUid = signal('');

  // ── Destination ───────────────────────────────────────────────────────────────
  readonly destBranchUid = signal('');
  readonly destLocations = signal<StockLocationDto[]>([]);
  readonly destLocationOptions = computed<UidOption[]>(() =>
    this.destLocations().map((l) => ({ uid: l.uid, label: l.name, hint: l.code })),
  );
  readonly destLocationUid = signal('');

  // ── Transfer header ───────────────────────────────────────────────────────────
  readonly transferMode = signal<'INSTANT' | 'IN_TRANSIT'>('IN_TRANSIT');
  readonly transferDate = signal('');
  readonly notes = signal('');

  // ── Product options ───────────────────────────────────────────────────────────
  readonly products = signal<ProductModel[]>([]);
  readonly productOptions = computed<UidOption[]>(() =>
    this.products().map((p) => ({ uid: p.uid, label: p.name, hint: p.code })),
  );

  // ── Lines ─────────────────────────────────────────────────────────────────────
  readonly lines = signal<LineEntry[]>([{ productUid: '', qty: '' }]);

  // ── Reference-data availability ───────────────────────────────────────────────
  /** True when the branch list could not be loaded (non-fatal; user sees notice and can retry). */
  readonly branchesUnavailable = signal(false);
  /** True when the product list could not be loaded (non-fatal; user sees notice and can retry). */
  readonly productsUnavailable = signal(false);

  // ── Form state ────────────────────────────────────────────────────────────────
  readonly saving = signal(false);
  readonly formError = signal<string | null>(null);

  readonly canCreate = computed(() => this.session.hasPermission('STOCK.TRANSFER.CREATE'));

  constructor() {
    this.loadContext();
  }

  private loadContext(): void {
    this.contextState.set('loading');
    this.orgService.current().subscribe({
      next: (org) => {
        this.companyService.list(org.uid).subscribe({
          next: (companies) => {
            this.companies.set(companies);
            this.contextState.set('idle');
            if (companies.length > 0) {
              // Default to the active company from session; fall back to companies[0].
              const activeCompanyUid = this.session.user()?.activeCompanyUid ?? null;
              const co = activeCompanyUid
                ? (companies.find((c) => c.uid === activeCompanyUid) ?? companies[0])
                : companies[0];
              this.selectedCompanyId.set(co.id);
              this.selectedCompanyUid.set(co.uid);
              this.loadBranches(co.uid);
              this.loadProducts(co.id);
            }
          },
          error: () => this.contextState.set('error'),
        });
      },
      error: () => this.contextState.set('error'),
    });
  }

  private loadBranches(companyUid: string): void {
    this.branchesUnavailable.set(false);
    this.branchService.list(companyUid).subscribe({
      next: (list) => this.branches.set(list),
      error: () => { this.branches.set([]); this.branchesUnavailable.set(true); },
    });
  }

  private loadProducts(companyId: string): void {
    this.productsUnavailable.set(false);
    this.productService.list(companyId, '', 0, 200).subscribe({
      next: ({ rows }) => this.products.set(rows.filter((p) => p.status !== 'ARCHIVED')),
      error: () => { this.products.set([]); this.productsUnavailable.set(true); },
    });
  }

  onSourceBranchChange(uid: string): void {
    this.sourceBranchUid.set(uid);
    this.sourceLocationUid.set('');
    this.sourceLocations.set([]);
    if (uid) {
      this.transferService.activeLocations(uid).subscribe({
        next: (locs) => this.sourceLocations.set(locs),
        error: () => this.sourceLocations.set([]),
      });
    }
  }

  onDestBranchChange(uid: string): void {
    this.destBranchUid.set(uid);
    this.destLocationUid.set('');
    this.destLocations.set([]);
    if (uid) {
      this.transferService.activeLocations(uid).subscribe({
        next: (locs) => this.destLocations.set(locs),
        error: () => this.destLocations.set([]),
      });
    }
  }

  addLine(): void {
    this.lines.update((ls) => [...ls, { productUid: '', qty: '' }]);
  }

  removeLine(index: number): void {
    this.lines.update((ls) => ls.filter((_, i) => i !== index));
  }

  updateLineProduct(index: number, uid: string): void {
    this.lines.update((ls) => {
      const copy = [...ls];
      copy[index] = { ...copy[index], productUid: uid };
      return copy;
    });
  }

  updateLineQty(index: number, qty: string | number | null): void {
    // ngModel on type="number" emits a JS number; coerce to string so the wire
    // payload stays a string and Number(line.qty) / .trim() never crash.
    const qtyStr = qty === null || qty === undefined ? '' : String(qty);
    this.lines.update((ls) => {
      const copy = [...ls];
      copy[index] = { ...copy[index], qty: qtyStr };
      return copy;
    });
  }

  submit(): void {
    if (this.saving()) return;

    const sourceLocationUid = this.sourceLocationUid();
    const destLocationUid = this.destLocationUid();
    const transferDate = this.transferDate().trim();
    const currentLines = this.lines();

    if (!this.sourceBranchUid()) {
      this.formError.set('Select a source branch.');
      return;
    }
    if (!sourceLocationUid) {
      this.formError.set('Select a source location.');
      return;
    }
    if (!this.destBranchUid()) {
      this.formError.set('Select a destination branch.');
      return;
    }
    if (!destLocationUid) {
      this.formError.set('Select a destination location.');
      return;
    }
    if (sourceLocationUid === destLocationUid) {
      this.formError.set('Source and destination locations must be different.');
      return;
    }
    if (!transferDate) {
      this.formError.set('Transfer date is required.');
      return;
    }

    if (currentLines.length === 0) {
      this.formError.set('Add at least one line item.');
      return;
    }

    for (let i = 0; i < currentLines.length; i++) {
      const line = currentLines[i];
      if (!line.productUid) {
        this.formError.set(`Line ${i + 1}: select a product.`);
        return;
      }
      const qtyNum = Number(line.qty);
      if (!line.qty || isNaN(qtyNum) || qtyNum <= 0) {
        this.formError.set(`Line ${i + 1}: quantity must be a positive number.`);
        return;
      }
    }

    this.saving.set(true);
    this.formError.set(null);

    const request: CreateStockTransferRequest = {
      sourceLocationUid,
      destLocationUid,
      transferDate,
      transferMode: this.transferMode(),
      notes: this.notes().trim() || undefined,
      lines: currentLines.map((l) => ({ productUid: l.productUid, qty: l.qty })),
    };

    this.transferService.create(request).subscribe({
      next: (created) => {
        this.saving.set(false);
        this.alerts.success('Stock transfer created', created.transferNumber ?? 'DRAFT');
        this.router.navigate(['/admin/stock-transfers/uid', created.uid]);
      },
      error: (err: unknown) => {
        this.formError.set(this.messageFrom(err, 'Could not create stock transfer.'));
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
