import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Subject, switchMap } from 'rxjs';
import { debounceTime, distinctUntilChanged } from 'rxjs';
import { PageMeta } from '../../../core/api/api-response.model';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { Company } from '../models/company.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { SupplierModel } from '../models/party.model';
import { SupplierService } from '../parties/supplier.service';
import {
  SupplierBillDto,
  RaiseDebitNoteRequest,
} from './models/ap.model';
import { ApService } from './ap.service';
import { DirectReceiptRatificationComponent } from './direct-receipt-ratification.component';
import { PaginatorComponent } from '../../../shared/paginator/paginator.component';
import { formatMoney } from '../../../shared/money.util';

const DEFAULT_SIZE = 20;

interface LoadTrigger {
  companyId: string;
  supplierUid: string;
  status: string;
  page: number;
}

/**
 * Paged list of supplier bills.
 * Company + supplier + status filter; debit-note action gated AP.DEBITNOTE.
 * Row navigation to bill detail (AP.BILL.MATCH / AP.PAYMENT.RUN) and enter bill.
 * Gated AP.VIEW.
 */
@Component({
  selector: 'app-supplier-bills-list',
  imports: [FormsModule, RouterLink, PaginatorComponent, DirectReceiptRatificationComponent],
  templateUrl: './supplier-bills-list.component.html',
  styleUrl: './supplier-bills-list.component.scss',
})
export class SupplierBillsListComponent {
  private readonly apService = inject(ApService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly supplierService = inject(SupplierService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  // ── Company context ────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── List state ─────────────────────────────────────────────────────────────
  readonly rows = signal<SupplierBillDto[]>([]);
  readonly meta = signal<PageMeta>({ page: 0, size: DEFAULT_SIZE, totalElements: 0, totalPages: 0, hasNext: false });
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('idle');
  readonly currentPage = signal(0);

  // ── Filters ────────────────────────────────────────────────────────────────
  readonly statusFilter = signal('');

  // ── Supplier lookup map (id → "code — displayName") ──────────────────────
  readonly supplierMap = signal<Map<string, string>>(new Map());

  // ── Supplier picker (filter) ───────────────────────────────────────────────
  readonly supplierFilterQ = signal('');
  readonly supplierFilterResults = signal<SupplierModel[]>([]);
  readonly selectedFilterSupplier = signal<{ uid: string; label: string } | null>(null);

  // ── Debit note modal ───────────────────────────────────────────────────────
  readonly debitNoteBill = signal<SupplierBillDto | null>(null);
  readonly debitNoteDate = signal('');
  readonly debitNoteNet = signal('');
  readonly debitNoteVat = signal('0');
  readonly debitNoteReason = signal('');
  readonly raisingDebitNote = signal(false);
  readonly debitNoteError = signal<string | null>(null);

  // ── Permissions ────────────────────────────────────────────────────────────
  readonly canView = computed(() => this.session.hasPermission('AP.VIEW'));
  readonly canEnterBill = computed(() => this.session.hasPermission('AP.BILL.ENTER'));
  readonly canMatch = computed(() => this.session.hasPermission('AP.BILL.MATCH'));
  readonly canPay = computed(() => this.session.hasPermission('AP.PAYMENT.RUN'));
  readonly canDebitNote = computed(() => this.session.hasPermission('AP.DEBITNOTE'));

  readonly isEmpty = computed(() => this.state() === 'idle' && this.rows().length === 0);

  private readonly loadTrigger$ = new Subject<LoadTrigger>();
  private readonly supplierFilterSearch$ = new Subject<string>();

  constructor() {
    this.loadTrigger$
      .pipe(
        switchMap(({ companyId, supplierUid, status, page }) => {
          if (!companyId) return [];
          this.state.set('loading');
          this.currentPage.set(page);
          return this.apService.listBills(
            companyId,
            supplierUid || undefined,
            status || undefined,
            page,
            DEFAULT_SIZE,
          );
        }),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: ({ rows, meta }) => {
          this.rows.set(rows);
          this.meta.set(meta);
          this.state.set('idle');
        },
        error: (err) =>
          this.state.set(err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error'),
      });

    this.supplierFilterSearch$
      .pipe(
        debounceTime(300),
        distinctUntilChanged(),
        switchMap((q) => {
          const companyId = this.selectedCompanyId();
          if (!companyId || !q.trim()) {
            this.supplierFilterResults.set([]);
            return [];
          }
          return this.supplierService.list(companyId, q.trim(), 0, 10);
        }),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: ({ rows }) => this.supplierFilterResults.set(rows.filter((s) => s.status === 'ACTIVE')),
        error: () => this.supplierFilterResults.set([]),
      });

    this.debitNoteDate.set(new Date().toISOString().slice(0, 10));
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
              this.load(0);
              this.loadSupplierMap(list[0].id);
            }
          },
          error: () => this.companyState.set('error'),
        });
      },
      error: () => this.companyState.set('error'),
    });
  }

  private loadSupplierMap(companyId: string): void {
    this.supplierService.list(companyId, undefined, 0, 200).subscribe({
      next: ({ rows }) => {
        const m = new Map<string, string>();
        rows.forEach((s) => m.set(String(s.id), `${s.code} — ${s.displayName}`));
        this.supplierMap.set(m);
      },
      error: () => {},
    });
  }

  onCompanyChange(id: string): void {
    this.selectedCompanyId.set(id);
    this.clearSupplierFilter();
    if (id) {
      this.load(0);
      this.loadSupplierMap(id);
    }
  }

  onStatusChange(status: string): void {
    this.statusFilter.set(status);
    this.load(0);
  }

  load(page: number): void {
    const companyId = this.selectedCompanyId();
    if (!companyId) return;
    this.loadTrigger$.next({
      companyId,
      supplierUid: this.selectedFilterSupplier()?.uid ?? '',
      status: this.statusFilter(),
      page,
    });
  }

  goToPage(page: number): void { this.load(page); }

  prevPage(): void {
    const p = this.currentPage();
    if (p > 0) this.load(p - 1);
  }

  nextPage(): void {
    if (this.meta().hasNext) this.load(this.currentPage() + 1);
  }

  // ── Supplier filter picker ─────────────────────────────────────────────────

  onSupplierFilterChange(q: string): void {
    this.supplierFilterQ.set(q);
    if (!q.trim()) {
      this.selectedFilterSupplier.set(null);
      this.supplierFilterResults.set([]);
      this.load(0);
    }
    this.supplierFilterSearch$.next(q);
  }

  selectFilterSupplier(s: SupplierModel): void {
    this.selectedFilterSupplier.set({ uid: s.uid, label: `${s.code} — ${s.displayName}` });
    this.supplierFilterQ.set(`${s.code} — ${s.displayName}`);
    this.supplierFilterResults.set([]);
    this.load(0);
  }

  clearSupplierFilter(): void {
    this.selectedFilterSupplier.set(null);
    this.supplierFilterQ.set('');
    this.supplierFilterResults.set([]);
    this.load(0);
  }

  // ── Debit note modal ───────────────────────────────────────────────────────

  openDebitNote(bill: SupplierBillDto): void {
    this.debitNoteBill.set(bill);
    this.debitNoteError.set(null);
    this.debitNoteNet.set('');
    this.debitNoteVat.set('0');
    this.debitNoteReason.set('');
    this.debitNoteDate.set(new Date().toISOString().slice(0, 10));
  }

  closeDebitNote(): void {
    this.debitNoteBill.set(null);
    this.debitNoteError.set(null);
  }

  submitDebitNote(): void {
    const bill = this.debitNoteBill();
    if (!bill) return;
    const net = String(this.debitNoteNet() ?? '').trim();
    const vat = String(this.debitNoteVat() ?? '').trim();
    const reason = String(this.debitNoteReason() ?? '').trim();
    const date = String(this.debitNoteDate() ?? '').trim();

    if (!net || isNaN(+net) || +net <= 0) { this.debitNoteError.set('Enter a valid net amount.'); return; }
    if (!reason) { this.debitNoteError.set('Reason is required.'); return; }
    if (!date) { this.debitNoteError.set('Note date is required.'); return; }

    const company = this.companies().find((c) => c.id === this.selectedCompanyId());
    if (!company) { this.debitNoteError.set('Could not resolve company.'); return; }

    this.raisingDebitNote.set(true);
    this.debitNoteError.set(null);

    const request: RaiseDebitNoteRequest = {
      companyUid: company.uid,
      supplierUid: String(bill.supplierId),
      supplierBillUid: bill.uid,
      noteDate: date,
      netAmount: net,
      vatAmount: vat || '0',
      reason,
    };

    this.apService.raiseDebitNote(request).subscribe({
      next: (note) => {
        this.raisingDebitNote.set(false);
        this.closeDebitNote();
        this.alerts.success('Debit note raised', note.debitNoteNumber);
        this.load(this.currentPage());
      },
      error: (err) => {
        this.debitNoteError.set(this.messageFrom(err, 'Could not raise debit note.'));
        this.raisingDebitNote.set(false);
      },
    });
  }

  // ── Display helpers ────────────────────────────────────────────────────────

  supplierDisplay(supplierId: string): string {
    return this.supplierMap().get(String(supplierId)) ?? String(supplierId);
  }

  canPayBill(bill: SupplierBillDto): boolean {
    return bill.status === 'MATCHED' || bill.status === 'APPROVED' || bill.status === 'PARTIALLY_PAID';
  }

  canMatchBill(bill: SupplierBillDto): boolean {
    return bill.status === 'DRAFT' || bill.status === 'HELD';
  }

  /** Coerce + format money with thousand separators (shared util). */
  readonly fmtMoney = formatMoney;

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}
