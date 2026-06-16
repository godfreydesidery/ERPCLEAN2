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
import {
  CashBankAccountDto,
  ChequeDto,
  RegisterChequeRequest,
} from './models/cashbank.model';
import { CashbankService } from './cashbank.service';
import { PaginatorComponent } from '../../../shared/paginator/paginator.component';

const DEFAULT_SIZE = 20;

interface LoadTrigger {
  companyId: string;
  page: number;
}

/**
 * Cheque Register screen. Gated CHEQUE.MANAGE.
 * - Lists cheques with status badges.
 * - Inline form to register a new cheque (bank accounts only).
 * - Clear / Cancel actions per cheque.
 */
@Component({
  selector: 'app-cheque-register',
  imports: [FormsModule, PaginatorComponent],
  templateUrl: './cheque-register.component.html',
  styleUrl: './cheque-register.component.scss',
})
export class ChequeRegisterComponent {
  private readonly cashbankService = inject(CashbankService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  // ── Company context ────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── List state ─────────────────────────────────────────────────────────────
  readonly rows = signal<ChequeDto[]>([]);
  readonly meta = signal<PageMeta>({ page: 0, size: DEFAULT_SIZE, totalElements: 0, totalPages: 0, hasNext: false });
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('idle');
  readonly currentPage = signal(0);

  // ── Bank accounts for picker ───────────────────────────────────────────────
  readonly bankAccounts = signal<CashBankAccountDto[]>([]);

  // ── Register form ──────────────────────────────────────────────────────────
  readonly showForm = signal(false);
  readonly registering = signal(false);
  readonly registerError = signal<string | null>(null);

  readonly newAccountUid = signal('');
  readonly newChequeNumber = signal('');
  readonly newPayee = signal('');
  readonly newAmount = signal('');
  readonly newIssueDate = signal('');
  readonly newValueDate = signal('');

  // ── Row actions ────────────────────────────────────────────────────────────
  readonly actionUid = signal<string | null>(null);

  // ── Permissions ────────────────────────────────────────────────────────────
  readonly canManage = computed(() => this.session.hasPermission('CHEQUE.MANAGE'));

  readonly isEmpty = computed(() => this.state() === 'idle' && this.rows().length === 0);

  private readonly loadTrigger$ = new Subject<LoadTrigger>();

  constructor() {
    const today = new Date().toISOString().slice(0, 10);
    this.newIssueDate.set(today);
    this.newValueDate.set(today);

    this.loadTrigger$
      .pipe(
        switchMap(({ companyId, page }) => {
          if (!companyId) return [];
          this.state.set('loading');
          this.currentPage.set(page);
          return this.cashbankService.listCheques(companyId, page, DEFAULT_SIZE);
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
              this.loadBankAccounts(list[0].id);
              this.load(0);
            }
          },
          error: () => this.companyState.set('error'),
        });
      },
      error: () => this.companyState.set('error'),
    });
  }

  private loadBankAccounts(companyId: string): void {
    this.cashbankService.listAllAccounts(companyId).subscribe({
      next: (list) => this.bankAccounts.set(list.filter((a) => a.accountType === 'BANK')),
      error: () => {},
    });
  }

  onCompanyChange(id: string): void {
    this.selectedCompanyId.set(id);
    if (id) {
      this.loadBankAccounts(id);
      this.load(0);
    }
  }

  load(page: number): void {
    const companyId = this.selectedCompanyId();
    if (!companyId) return;
    this.loadTrigger$.next({ companyId, page });
  }

  goToPage(page: number): void { this.load(page); }

  prevPage(): void {
    const p = this.currentPage();
    if (p > 0) this.load(p - 1);
  }

  nextPage(): void {
    if (this.meta().hasNext) this.load(this.currentPage() + 1);
  }

  // ── Register form ──────────────────────────────────────────────────────────

  openForm(): void {
    this.showForm.set(true);
    this.registerError.set(null);
    this.newAccountUid.set('');
    this.newChequeNumber.set('');
    this.newPayee.set('');
    this.newAmount.set('');
    const today = new Date().toISOString().slice(0, 10);
    this.newIssueDate.set(today);
    this.newValueDate.set(today);
  }

  cancelForm(): void {
    this.showForm.set(false);
    this.registerError.set(null);
  }

  submitRegister(): void {
    const accountUid = String(this.newAccountUid() ?? '').trim();
    const chequeNumber = String(this.newChequeNumber() ?? '').trim();
    const payee = String(this.newPayee() ?? '').trim();
    const amt = String(this.newAmount() ?? '').trim();
    const issueDate = String(this.newIssueDate() ?? '').trim();
    const valueDate = String(this.newValueDate() ?? '').trim();

    if (!accountUid) { this.registerError.set('Bank account is required.'); return; }
    if (!chequeNumber) { this.registerError.set('Cheque number is required.'); return; }
    if (!payee) { this.registerError.set('Payee is required.'); return; }
    if (!amt || +amt <= 0) { this.registerError.set('Enter a valid amount.'); return; }
    if (!issueDate) { this.registerError.set('Issue date is required.'); return; }
    if (!valueDate) { this.registerError.set('Value date is required.'); return; }

    const company = this.companies().find((c) => c.id === this.selectedCompanyId());
    if (!company) { this.registerError.set('Could not resolve company.'); return; }

    const request: RegisterChequeRequest = {
      companyUid: company.uid,
      cashBankAccountUid: accountUid,
      chequeNumber,
      payee,
      amount: amt,
      issueDate,
      valueDate,
    };

    this.registering.set(true);
    this.registerError.set(null);

    this.cashbankService.registerCheque(request).subscribe({
      next: (chq) => {
        this.registering.set(false);
        this.showForm.set(false);
        this.alerts.success('Cheque registered', chq.chequeNumber);
        this.load(this.currentPage());
      },
      error: (err) => {
        this.registerError.set(this.messageFrom(err, 'Could not register cheque.'));
        this.registering.set(false);
      },
    });
  }

  // ── Row actions ────────────────────────────────────────────────────────────

  clearCheque(chq: ChequeDto): void {
    if (this.actionUid() !== null) return;
    this.actionUid.set(chq.uid);
    this.cashbankService.clearCheque(chq.uid).subscribe({
      next: () => {
        this.actionUid.set(null);
        this.alerts.success('Cheque cleared', chq.chequeNumber);
        this.load(this.currentPage());
      },
      error: (err) => {
        this.actionUid.set(null);
        this.alerts.success('Error', this.messageFrom(err, 'Could not clear cheque.'));
      },
    });
  }

  cancelCheque(chq: ChequeDto): void {
    if (this.actionUid() !== null) return;
    this.actionUid.set(chq.uid);
    this.cashbankService.cancelCheque(chq.uid).subscribe({
      next: () => {
        this.actionUid.set(null);
        this.alerts.success('Cheque cancelled', chq.chequeNumber);
        this.load(this.currentPage());
      },
      error: (err) => {
        this.actionUid.set(null);
        this.alerts.success('Error', this.messageFrom(err, 'Could not cancel cheque.'));
      },
    });
  }

  // ── Display helpers ────────────────────────────────────────────────────────

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
