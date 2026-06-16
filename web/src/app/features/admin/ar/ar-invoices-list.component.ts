import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { Subject, switchMap } from 'rxjs';
import { PageMeta } from '../../../core/api/api-response.model';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { Company } from '../models/company.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { CustomerModel } from '../models/party.model';
import { CustomerService } from '../parties/customer.service';
import { ArInvoiceDto, WriteOffRequest, RaiseCreditNoteRequest } from './models/ar.model';
import { ArService } from './ar.service';
import { debounceTime, distinctUntilChanged, Subject as RxSubject } from 'rxjs';
import { PaginatorComponent } from '../../../shared/paginator/paginator.component';

const DEFAULT_SIZE = 20;

interface LoadTrigger {
  companyId: string;
  customerUid: string;
  status: string;
  page: number;
}

/**
 * Paged list of AR open items (invoices) scoped to the active company.
 * Provides customer filter, status filter, write-off and credit-note actions.
 * Gated AR.VIEW.
 */
@Component({
  selector: 'app-ar-invoices-list',
  imports: [FormsModule, PaginatorComponent],
  templateUrl: './ar-invoices-list.component.html',
  styleUrl: './ar-invoices-list.component.scss',
})
export class ArInvoicesListComponent {
  private readonly arService = inject(ArService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly customerService = inject(CustomerService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  // ── Company context ────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── List state ─────────────────────────────────────────────────────────────
  readonly rows = signal<ArInvoiceDto[]>([]);
  readonly meta = signal<PageMeta>({ page: 0, size: DEFAULT_SIZE, totalElements: 0, totalPages: 0, hasNext: false });
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('idle');
  readonly currentPage = signal(0);

  // ── Filters ────────────────────────────────────────────────────────────────
  readonly statusFilter = signal('');

  // ── Customer lookup map (id → "code — displayName") ─────────────────────
  readonly customerMap = signal<Map<string, string>>(new Map());

  // ── Customer picker (filter) ───────────────────────────────────────────────
  readonly customerFilterQ = signal('');
  readonly customerFilterResults = signal<CustomerModel[]>([]);
  readonly selectedFilterCustomer = signal<{ uid: string; label: string } | null>(null);

  // ── Write-off modal ────────────────────────────────────────────────────────
  readonly writeOffInvoice = signal<ArInvoiceDto | null>(null);
  readonly writeOffDate = signal('');
  readonly writeOffReason = signal('');
  readonly writingOff = signal(false);
  readonly writeOffError = signal<string | null>(null);

  // ── Credit note modal ──────────────────────────────────────────────────────
  readonly creditNoteInvoice = signal<ArInvoiceDto | null>(null);
  readonly creditNoteDate = signal('');
  readonly creditNoteNet = signal('');
  readonly creditNoteVat = signal('0');
  readonly creditNoteCurrency = signal('TZS');
  readonly creditNoteReason = signal('');
  readonly raisingCreditNote = signal(false);
  readonly creditNoteError = signal<string | null>(null);

  // ── Permissions ────────────────────────────────────────────────────────────
  readonly canView = computed(() => this.session.hasPermission('AR.VIEW'));
  readonly canWriteOff = computed(() => this.session.hasPermission('AR.WRITEOFF'));
  readonly canCreditNote = computed(() => this.session.hasPermission('AR.CREDITNOTE'));

  readonly isEmpty = computed(() => this.state() === 'idle' && this.rows().length === 0);

  private readonly loadTrigger$ = new Subject<LoadTrigger>();
  private readonly customerFilterSearch$ = new RxSubject<string>();

  constructor() {
    this.loadTrigger$
      .pipe(
        switchMap(({ companyId, customerUid, status, page }) => {
          if (!companyId) return [];
          this.state.set('loading');
          this.currentPage.set(page);
          return this.arService.listInvoices(
            companyId,
            customerUid || undefined,
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

    this.customerFilterSearch$
      .pipe(
        debounceTime(300),
        distinctUntilChanged(),
        switchMap((q) => {
          const companyId = this.selectedCompanyId();
          if (!companyId || !q.trim()) {
            this.customerFilterResults.set([]);
            return [];
          }
          return this.customerService.list(companyId, q.trim(), 0, 10);
        }),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: ({ rows }) => this.customerFilterResults.set(rows.filter((c) => c.status === 'ACTIVE')),
        error: () => this.customerFilterResults.set([]),
      });

    // Default write-off date to today
    this.writeOffDate.set(new Date().toISOString().slice(0, 10));
    this.creditNoteDate.set(new Date().toISOString().slice(0, 10));

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
              this.loadCustomerMap(list[0].id);
            }
          },
          error: () => this.companyState.set('error'),
        });
      },
      error: () => this.companyState.set('error'),
    });
  }

  private loadCustomerMap(companyId: string): void {
    this.customerService.list(companyId, undefined, 0, 200).subscribe({
      next: ({ rows }) => {
        const m = new Map<string, string>();
        rows.forEach((c) => m.set(String(c.id), `${c.code} — ${c.displayName}`));
        this.customerMap.set(m);
      },
      error: () => {},
    });
  }

  onCompanyChange(id: string): void {
    this.selectedCompanyId.set(id);
    this.clearCustomerFilter();
    if (id) {
      this.load(0);
      this.loadCustomerMap(id);
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
      customerUid: this.selectedFilterCustomer()?.uid ?? '',
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

  // ── Customer filter picker ─────────────────────────────────────────────────

  onCustomerFilterChange(q: string): void {
    this.customerFilterQ.set(q);
    if (!q.trim()) {
      this.selectedFilterCustomer.set(null);
      this.customerFilterResults.set([]);
      this.load(0);
    }
    this.customerFilterSearch$.next(q);
  }

  selectFilterCustomer(c: CustomerModel): void {
    this.selectedFilterCustomer.set({ uid: c.uid, label: `${c.code} — ${c.displayName}` });
    this.customerFilterQ.set(`${c.code} — ${c.displayName}`);
    this.customerFilterResults.set([]);
    this.load(0);
  }

  clearCustomerFilter(): void {
    this.selectedFilterCustomer.set(null);
    this.customerFilterQ.set('');
    this.customerFilterResults.set([]);
    this.load(0);
  }

  // ── Write-off ──────────────────────────────────────────────────────────────

  openWriteOff(inv: ArInvoiceDto): void {
    this.writeOffInvoice.set(inv);
    this.writeOffError.set(null);
    this.writeOffReason.set('');
    this.writeOffDate.set(new Date().toISOString().slice(0, 10));
  }

  closeWriteOff(): void {
    this.writeOffInvoice.set(null);
    this.writeOffError.set(null);
  }

  submitWriteOff(): void {
    const inv = this.writeOffInvoice();
    if (!inv) return;
    const reason = String(this.writeOffReason() ?? '').trim();
    const date = String(this.writeOffDate() ?? '').trim();
    if (!reason) { this.writeOffError.set('Reason is required.'); return; }
    if (!date) { this.writeOffError.set('Write-off date is required.'); return; }

    this.writingOff.set(true);
    this.writeOffError.set(null);

    const request: WriteOffRequest = {
      arInvoiceUid: inv.uid,
      writeOffDate: date,
      reason,
    };

    this.arService.writeOff(request).subscribe({
      next: () => {
        this.writingOff.set(false);
        this.closeWriteOff();
        this.alerts.success('Invoice written off', inv.documentNo);
        this.load(this.currentPage());
      },
      error: (err) => {
        this.writeOffError.set(this.messageFrom(err, 'Could not write off invoice.'));
        this.writingOff.set(false);
      },
    });
  }

  // ── Credit note ────────────────────────────────────────────────────────────

  openCreditNote(inv: ArInvoiceDto): void {
    this.creditNoteInvoice.set(inv);
    this.creditNoteError.set(null);
    this.creditNoteNet.set('');
    this.creditNoteVat.set('0');
    this.creditNoteCurrency.set(String(inv.currency ?? 'TZS'));
    this.creditNoteReason.set('');
    this.creditNoteDate.set(new Date().toISOString().slice(0, 10));
  }

  closeCreditNote(): void {
    this.creditNoteInvoice.set(null);
    this.creditNoteError.set(null);
  }

  submitCreditNote(): void {
    const inv = this.creditNoteInvoice();
    if (!inv) return;
    const net = String(this.creditNoteNet() ?? '').trim();
    const vat = String(this.creditNoteVat() ?? '').trim();
    const reason = String(this.creditNoteReason() ?? '').trim();
    const date = String(this.creditNoteDate() ?? '').trim();
    const currency = String(this.creditNoteCurrency() ?? '').trim();

    if (!net || isNaN(+net) || +net <= 0) { this.creditNoteError.set('Enter a valid net amount.'); return; }
    if (!reason) { this.creditNoteError.set('Reason is required.'); return; }
    if (!date) { this.creditNoteError.set('Note date is required.'); return; }

    const company = this.companies().find((c) => c.id === this.selectedCompanyId());
    if (!company) { this.creditNoteError.set('Could not resolve company.'); return; }

    this.raisingCreditNote.set(true);
    this.creditNoteError.set(null);

    const request: RaiseCreditNoteRequest = {
      companyUid: company.uid,
      customerUid: String(inv.customerId),
      arInvoiceUid: inv.uid,
      noteDate: date,
      netAmount: net,
      vatAmount: vat || '0',
      currency,
      reason,
    };

    this.arService.raiseCreditNote(request).subscribe({
      next: () => {
        this.raisingCreditNote.set(false);
        this.closeCreditNote();
        this.alerts.success('Credit note raised', inv.documentNo);
        this.load(this.currentPage());
      },
      error: (err) => {
        this.creditNoteError.set(this.messageFrom(err, 'Could not raise credit note.'));
        this.raisingCreditNote.set(false);
      },
    });
  }

  // ── Display helpers ────────────────────────────────────────────────────────

  customerDisplay(customerId: string): string {
    return this.customerMap().get(String(customerId)) ?? String(customerId);
  }

  /** Coerce money to display string — handles both number and string on the wire. */
  fmtMoney(v: number | string | null | undefined): string {
    const n = +(v ?? 0);
    return Number.isFinite(n) ? n.toFixed(2) : '0.00';
  }

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}
