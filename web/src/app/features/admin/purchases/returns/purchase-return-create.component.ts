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
import {
  CreatePurchaseReturnRequest,
  GoodsReceiptDto,
  GoodsReceiptLineDto,
} from '../../models/purchases.model';
import { CompanyService } from '../../company/company.service';
import { OrganisationService } from '../../organisation/organisation.service';
import { PurchasesService } from '../purchases.service';
import { PurchaseReturnService } from './purchase-return.service';
import { UidPickerComponent, UidOption } from '../../../../shared/uid-picker/uid-picker.component';

interface ReturnLineEntry {
  line: GoodsReceiptLineDto;
  returnedQty: string;
  include: boolean;
}

@Component({
  selector: 'app-purchase-return-create',
  imports: [FormsModule, RouterLink, DecimalPipe, UidPickerComponent],
  templateUrl: './purchase-return-create.component.html',
  styleUrl: './purchase-return-create.component.scss',
})
export class PurchaseReturnCreateComponent {
  private readonly returnService = inject(PurchaseReturnService);
  private readonly purchasesService = inject(PurchasesService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly router = inject(Router);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── GR picker ──────────────────────────────────────────────────────────────
  readonly grOptions = signal<UidOption[]>([]);
  readonly selectedGrUid = signal('');
  readonly grState = signal<'idle' | 'loading' | 'error'>('idle');

  // ── Loaded GR ─────────────────────────────────────────────────────────────
  readonly gr = signal<GoodsReceiptDto | null>(null);
  readonly returnLines = signal<ReturnLineEntry[]>([]);

  // ── Form ───────────────────────────────────────────────────────────────────
  readonly reason = signal('');
  readonly submitting = signal(false);
  readonly formError = signal<string | null>(null);
  readonly lineErrors = signal<Record<string, string>>({});

  readonly canCreate = computed(() => this.session.hasPermission('PURCHASE.RETURN.CREATE'));
  readonly hasIncludedLines = computed(() => this.returnLines().some((e) => e.include));

  private readonly grSearch$ = new Subject<string>();

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
              this.selectedCompanyId.set(list[0].id);
              this.loadGrOptions();
            }
          },
          error: () => this.companyState.set('error'),
        });
      },
      error: () => this.companyState.set('error'),
    });
  }

  private loadGrOptions(): void {
    const companyId = this.selectedCompanyId();
    if (!companyId) return;
    this.purchasesService.listReceipts(companyId, undefined, 0, 100).subscribe({
      next: ({ rows }) => {
        this.grOptions.set(
          rows.map((gr) => ({
            uid: gr.uid,
            label: gr.receiptNumber,
            hint: gr.status,
          })),
        );
      },
      error: () => {},
    });
  }

  onGrPick(uid: string): void {
    this.selectedGrUid.set(uid);
    this.gr.set(null);
    this.returnLines.set([]);
    this.lineErrors.set({});
    if (!uid) return;
    this.grState.set('loading');
    this.purchasesService.getReceiptByUid(uid).subscribe({
      next: (gr) => {
        this.gr.set(gr);
        this.grState.set('idle');
        const entries: ReturnLineEntry[] = (gr.lines ?? []).map((l) => ({
          line: l,
          returnedQty: '',
          include: false,
        }));
        this.returnLines.set(entries);
      },
      error: () => this.grState.set('error'),
    });
  }

  onCompanyChange(id: string): void {
    this.selectedCompanyId.set(id);
    this.selectedGrUid.set('');
    this.gr.set(null);
    this.returnLines.set([]);
    if (id) this.loadGrOptions();
  }

  updateLineQty(index: number, qty: unknown): void {
    this.returnLines.update((entries) =>
      entries.map((e, i) => (i === index ? { ...e, returnedQty: String(qty ?? '') } : e)),
    );
  }

  toggleLineInclude(index: number, include: boolean): void {
    this.returnLines.update((entries) =>
      entries.map((e, i) => (i === index ? { ...e, include } : e)),
    );
  }

  submit(): void {
    if (!this.canCreate()) return;
    const grUid = this.selectedGrUid();
    if (!grUid) { this.formError.set('Select a goods receipt.'); return; }
    const reason = this.reason().trim();
    if (!reason) { this.formError.set('Return reason is required.'); return; }

    const included = this.returnLines().filter((e) => e.include);
    if (included.length === 0) { this.formError.set('Select at least one line to return.'); return; }

    const errors: Record<string, string> = {};
    let valid = true;
    for (const entry of included) {
      const qty = Number(entry.returnedQty);
      if (!entry.returnedQty.trim() || isNaN(qty) || qty <= 0) {
        errors[entry.line.uid] = 'Quantity must be greater than zero.';
        valid = false;
      }
    }
    this.lineErrors.set(errors);
    if (!valid) { this.formError.set('Fix the errors above.'); return; }

    const company = this.companies().find((c) => c.id === this.selectedCompanyId());
    if (!company) { this.formError.set('Could not resolve company.'); return; }

    this.submitting.set(true);
    this.formError.set(null);

    const request: CreatePurchaseReturnRequest = {
      companyUid: company.uid,
      goodsReceiptUid: grUid,
      reason,
      lines: included.map((e) => ({
        goodsReceiptLineUid: e.line.uid,
        returnedQty: e.returnedQty.trim(),
      })),
    };

    this.returnService.create(request).subscribe({
      next: (created) => {
        this.submitting.set(false);
        this.alerts.success('Purchase return created', created.returnNumber);
        this.router.navigate(['/admin/purchase-returns/uid', created.uid]);
      },
      error: (err) => {
        this.formError.set(this.messageFrom(err, 'Could not create purchase return.'));
        this.submitting.set(false);
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
