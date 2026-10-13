import { HttpErrorResponse } from '@angular/common/http';
import { DecimalPipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { debounceTime, distinctUntilChanged, map, Subject, switchMap } from 'rxjs';
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
import { UidPickerComponent, UidOption, UidSearchFn } from '../../../../shared/uid-picker/uid-picker.component';

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
    // PUR-08: the newest 50 as a seed (the server lists newest first); anything older is found by
    // typing its number — the picker searches the server.
    this.purchasesService.listReceipts(companyId, undefined, 0, 50).subscribe({
      next: ({ rows }) => {
        // PUR-03: a voided receipt cannot take a return (the server refuses it too).
        this.grOptions.set(
          rows.filter((gr) => gr.status !== 'VOID').map((gr) => ({
            uid: gr.uid,
            label: gr.receiptNumber,
            hint: gr.status,
          })),
        );
      },
      error: () => {},
    });
  }

  /** PUR-08: server-side receipt-number search for the picker (voided receipts excluded). */
  readonly searchGr: UidSearchFn = (q: string) =>
    this.purchasesService.listReceipts(this.selectedCompanyId(), q, 0, 20).pipe(
      map(({ rows }) => rows
        .filter((gr) => gr.status !== 'VOID')
        .map((gr) => ({ uid: gr.uid, label: gr.receiptNumber, hint: gr.status }))),
    );

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

  // ── Unit helpers (PUR-02) ───────────────────────────────────────────────────
  // A return is entered in the RECEIPT LINE's unit (crates for a line received in crates), exactly
  // like the receipt itself; the server converts to base units with the line's own factor.

  /** Base units per one of the line's unit (qtyInBase ÷ receivedQty); 1 when unknown. */
  lineFactor(line: GoodsReceiptLineDto): number {
    const received = Number(line.receivedQty);
    const base = Number(line.qtyInBase);
    return received > 0 && base > 0 ? base / received : 1;
  }

  /** True when the line was received in a pack unit (factor ≠ 1). */
  isPackLine(line: GoodsReceiptLineDto): boolean {
    return Math.abs(this.lineFactor(line) - 1) > 1e-9;
  }

  /** What is still returnable, in the line's unit (received less confirmed returns). */
  returnableQty(line: GoodsReceiptLineDto): number {
    const base = Number(line.qtyInBase);
    const returned = Number(line.returnedQtyInBase ?? 0) || 0;
    const remainingBase = Math.max(0, base - returned);
    return this.round(remainingBase / this.lineFactor(line));
  }

  /** Preview of the entered quantity in base units, or null when nothing valid is entered. */
  baseQtyPreview(entry: ReturnLineEntry): number | null {
    const qty = Number(entry.returnedQty);
    if (!entry.returnedQty.trim() || !Number.isFinite(qty) || qty <= 0) return null;
    return this.round(qty * this.lineFactor(entry.line));
  }

  private round(n: number): number {
    return Number(n.toFixed(6));
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
      } else if (qty > this.returnableQty(entry.line) + 1e-9) {
        // Hint only — the server is authoritative (it re-checks against the base remainder).
        errors[entry.line.uid] =
          `You can return at most ${this.returnableQty(entry.line)} ${entry.line.unitName}.`;
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
