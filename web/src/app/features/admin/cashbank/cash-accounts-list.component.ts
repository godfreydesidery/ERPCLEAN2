import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { Company } from '../models/company.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { AccountDto } from '../gl/models/gl.model';
import { GlService } from '../gl/gl.service';
import {
  CashBankAccountDto,
  CashBankAccountType,
  CreateCashBankAccountRequest,
} from './models/cashbank.model';
import { CashbankService } from './cashbank.service';

/**
 * Cash & Bank Accounts list screen. Gated CASH.VIEW.
 * - Shows all accounts (code, name, type, bank details, linked GL, default badge, active).
 * - Inline create form gated CASH.ACCOUNT.MANAGE.
 * - GL account picker: select of ASSET accounts from the Chart of Accounts.
 * - Set-default action per account.
 */
@Component({
  selector: 'app-cash-accounts-list',
  imports: [FormsModule],
  templateUrl: './cash-accounts-list.component.html',
  styleUrl: './cash-accounts-list.component.scss',
})
export class CashAccountsListComponent {
  private readonly cashbankService = inject(CashbankService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly glService = inject(GlService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  // ── Company context ────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── Account list ──────────────────────────────────────────────────────────
  readonly rows = signal<CashBankAccountDto[]>([]);
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('idle');

  // ── GL account picker (for create form) ──────────────────────────────────
  readonly glAccounts = signal<AccountDto[]>([]);
  readonly glAccountsState = signal<'idle' | 'loading' | 'error'>('idle');

  // ── Create form ───────────────────────────────────────────────────────────
  readonly showCreateForm = signal(false);
  readonly creating = signal(false);
  readonly createError = signal<string | null>(null);

  readonly newName = signal('');
  readonly newType = signal<CashBankAccountType>('CASH');
  readonly newBankName = signal('');
  readonly newBankAccountNo = signal('');
  readonly newBankBranch = signal('');
  readonly newGlAccountUid = signal('');
  readonly newSetAsDefault = signal(false);

  // ── Set-default action ────────────────────────────────────────────────────
  readonly settingDefaultUid = signal<string | null>(null);

  // ── Permissions ──────────────────────────────────────────────────────────
  readonly canView = computed(() => this.session.hasPermission('CASH.VIEW'));
  readonly canManage = computed(() => this.session.hasPermission('CASH.ACCOUNT.MANAGE'));

  readonly isEmpty = computed(() => this.state() === 'idle' && this.rows().length === 0);

  /** ASSET GL accounts for the GL picker. */
  readonly assetAccounts = computed(() =>
    this.glAccounts().filter((a) => a.accountType === 'ASSET'),
  );

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
              this.load();
              this.loadGlAccounts(list[0].id);
            }
          },
          error: () => this.companyState.set('error'),
        });
      },
      error: () => this.companyState.set('error'),
    });
  }

  private loadGlAccounts(companyId: string): void {
    this.glAccountsState.set('loading');
    this.glService.listAllActiveAccounts(companyId).subscribe({
      next: (list) => {
        this.glAccounts.set(list);
        this.glAccountsState.set('idle');
      },
      error: () => this.glAccountsState.set('error'),
    });
  }

  onCompanyChange(id: string): void {
    this.selectedCompanyId.set(id);
    if (id) {
      this.load();
      this.loadGlAccounts(id);
    }
  }

  load(): void {
    const companyId = this.selectedCompanyId();
    if (!companyId) return;
    this.state.set('loading');
    this.cashbankService.listAccounts(companyId, 0, 100).subscribe({
      next: ({ rows }) => {
        this.rows.set(rows);
        this.state.set('idle');
      },
      error: (err) =>
        this.state.set(err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error'),
    });
  }

  // ── Create form ───────────────────────────────────────────────────────────

  openCreateForm(): void {
    this.showCreateForm.set(true);
    this.createError.set(null);
    this.newName.set('');
    this.newType.set('CASH');
    this.newBankName.set('');
    this.newBankAccountNo.set('');
    this.newBankBranch.set('');
    this.newGlAccountUid.set('');
    this.newSetAsDefault.set(false);
  }

  cancelCreate(): void {
    this.showCreateForm.set(false);
    this.createError.set(null);
  }

  submitCreate(): void {
    const name = String(this.newName() ?? '').trim();
    const glAccountUid = String(this.newGlAccountUid() ?? '').trim();

    if (!name) { this.createError.set('Account name is required.'); return; }
    if (!glAccountUid) { this.createError.set('GL account is required.'); return; }

    const type = this.newType();
    if (type === 'BANK') {
      if (!String(this.newBankName() ?? '').trim()) {
        this.createError.set('Bank name is required for a BANK account.');
        return;
      }
    }

    const company = this.companies().find((c) => c.id === this.selectedCompanyId());
    if (!company) { this.createError.set('Could not resolve company.'); return; }

    const request: CreateCashBankAccountRequest = {
      companyUid: company.uid,
      name,
      accountType: type,
      glAccountUid,
      setAsDefault: this.newSetAsDefault(),
    };

    const bankName = String(this.newBankName() ?? '').trim();
    const bankAccountNo = String(this.newBankAccountNo() ?? '').trim();
    const bankBranch = String(this.newBankBranch() ?? '').trim();
    if (bankName) request.bankName = bankName;
    if (bankAccountNo) request.bankAccountNo = bankAccountNo;
    if (bankBranch) request.bankBranch = bankBranch;

    this.creating.set(true);
    this.createError.set(null);

    this.cashbankService.createAccount(request).subscribe({
      next: (acc) => {
        this.creating.set(false);
        this.showCreateForm.set(false);
        this.alerts.success('Account created', acc.name);
        this.load();
      },
      error: (err) => {
        this.createError.set(this.messageFrom(err, 'Could not create account.'));
        this.creating.set(false);
      },
    });
  }

  // ── Set default ───────────────────────────────────────────────────────────

  setDefault(acc: CashBankAccountDto): void {
    if (acc.isDefault || this.settingDefaultUid() !== null) return;
    this.settingDefaultUid.set(acc.uid);
    this.cashbankService.setDefault(acc.uid).subscribe({
      next: () => {
        this.settingDefaultUid.set(null);
        this.alerts.success('Default account set', acc.name);
        this.load();
      },
      error: (err) => {
        this.settingDefaultUid.set(null);
        this.alerts.success('Error', this.messageFrom(err, 'Could not set default.'));
      },
    });
  }

  // ── Display helpers ───────────────────────────────────────────────────────

  typeBadgeClass(type: CashBankAccountType): string {
    return type === 'BANK' ? 'text-bg-primary' : 'text-bg-success';
  }

  glAccountLabel(glAccountId: string): string {
    const acc = this.glAccounts().find((a) => String(a.id) === String(glAccountId));
    return acc ? `${acc.accountCode} — ${acc.name}` : String(glAccountId ?? '');
  }

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}
