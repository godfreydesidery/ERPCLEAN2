import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { Company } from '../models/company.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import {
  CashBankAccountDto,
  CashTransferDto,
  RecordTransferRequest,
} from './models/cashbank.model';
import { CashbankService } from './cashbank.service';

/**
 * Record Transfer screen. Gated CASH.TRANSFER.
 * Client guard: source account must differ from destination account.
 * Shows both account balances fetched from the statements endpoint.
 */
@Component({
  selector: 'app-record-transfer',
  imports: [FormsModule],
  templateUrl: './record-transfer.component.html',
  styleUrl: './record-transfer.component.scss',
})
export class RecordTransferComponent {
  private readonly cashbankService = inject(CashbankService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  // ── Company context ────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── Account lists ──────────────────────────────────────────────────────────
  readonly accounts = signal<CashBankAccountDto[]>([]);
  readonly accountsState = signal<'idle' | 'loading' | 'error'>('idle');

  // ── Form fields ────────────────────────────────────────────────────────────
  readonly sourceAccountUid = signal('');
  readonly destinationAccountUid = signal('');
  readonly amount = signal('');
  readonly transferDate = signal('');
  readonly reference = signal('');

  // ── Source/dest balances ───────────────────────────────────────────────────
  readonly sourceBalance = signal<number | null>(null);
  readonly destBalance = signal<number | null>(null);
  readonly sourceBalanceCurrency = signal('');

  // ── Submit state ───────────────────────────────────────────────────────────
  readonly submitting = signal(false);
  readonly formError = signal<string | null>(null);
  readonly savedTransfer = signal<CashTransferDto | null>(null);

  // ── Permissions ────────────────────────────────────────────────────────────
  readonly canTransfer = computed(() => this.session.hasPermission('CASH.TRANSFER'));

  // ── Client guard: source must differ from destination ─────────────────────
  readonly sameAccountError = computed(() =>
    !!this.sourceAccountUid() &&
    !!this.destinationAccountUid() &&
    this.sourceAccountUid() === this.destinationAccountUid(),
  );

  readonly amountNum = computed(() => {
    const v = +(String(this.amount() ?? '').trim() || '0');
    return Number.isFinite(v) ? v : 0;
  });

  readonly submitDisabled = computed(() =>
    !this.sourceAccountUid() ||
    !this.destinationAccountUid() ||
    this.sameAccountError() ||
    !String(this.amount() ?? '').trim() ||
    this.amountNum() <= 0 ||
    !String(this.transferDate() ?? '').trim() ||
    !this.selectedCompanyId() ||
    this.submitting(),
  );

  constructor() {
    this.transferDate.set(new Date().toISOString().slice(0, 10));
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
              this.loadAccounts(list[0].id);
            }
          },
          error: () => this.companyState.set('error'),
        });
      },
      error: () => this.companyState.set('error'),
    });
  }

  private loadAccounts(companyId: string): void {
    this.accountsState.set('loading');
    this.cashbankService.listAllAccounts(companyId).subscribe({
      next: (list) => {
        this.accounts.set(list);
        this.accountsState.set('idle');
      },
      error: () => this.accountsState.set('error'),
    });
  }

  onCompanyChange(id: string): void {
    this.selectedCompanyId.set(id);
    this.sourceAccountUid.set('');
    this.destinationAccountUid.set('');
    this.sourceBalance.set(null);
    this.destBalance.set(null);
    if (id) this.loadAccounts(id);
  }

  onSourceChange(uid: string): void {
    this.sourceAccountUid.set(uid);
    this.sourceBalance.set(null);
    this.sourceBalanceCurrency.set('');
    if (uid) {
      this.cashbankService.getAccountBalance(uid).subscribe({
        next: (b) => {
          this.sourceBalance.set(+(b.bookBalance ?? 0));
          this.sourceBalanceCurrency.set(String(b.currency ?? ''));
        },
        error: () => this.sourceBalance.set(null),
      });
    }
  }

  onDestChange(uid: string): void {
    this.destinationAccountUid.set(uid);
    this.destBalance.set(null);
    if (uid) {
      this.cashbankService.getAccountBalance(uid).subscribe({
        next: (b) => this.destBalance.set(+(b.bookBalance ?? 0)),
        error: () => this.destBalance.set(null),
      });
    }
  }

  submit(): void {
    if (this.submitDisabled()) return;

    const company = this.companies().find((c) => c.id === this.selectedCompanyId());
    if (!company) { this.formError.set('Could not resolve company.'); return; }

    const src = String(this.sourceAccountUid() ?? '').trim();
    const dst = String(this.destinationAccountUid() ?? '').trim();
    const amt = String(this.amount() ?? '').trim();
    const date = String(this.transferDate() ?? '').trim();
    const ref = String(this.reference() ?? '').trim();

    if (src === dst) { this.formError.set('Source and destination accounts must differ.'); return; }
    if (!amt || +amt <= 0) { this.formError.set('Enter a valid transfer amount.'); return; }
    if (!date) { this.formError.set('Transfer date is required.'); return; }

    const request: RecordTransferRequest = {
      companyUid: company.uid,
      sourceAccountUid: src,
      destinationAccountUid: dst,
      amount: amt,
      transferDate: date,
      reference: ref || undefined,
    };

    this.submitting.set(true);
    this.formError.set(null);

    this.cashbankService.recordTransfer(request).subscribe({
      next: (transfer) => {
        this.submitting.set(false);
        this.alerts.success('Transfer recorded', String(transfer.transferNumber ?? ''));
        this.savedTransfer.set(transfer);
        // Refresh balances
        this.onSourceChange(src);
        this.onDestChange(dst);
      },
      error: (err) => {
        this.formError.set(this.messageFrom(err, 'Could not record transfer.'));
        this.submitting.set(false);
      },
    });
  }

  reset(): void {
    this.savedTransfer.set(null);
    this.sourceAccountUid.set('');
    this.destinationAccountUid.set('');
    this.amount.set('');
    this.reference.set('');
    this.sourceBalance.set(null);
    this.destBalance.set(null);
    this.transferDate.set(new Date().toISOString().slice(0, 10));
    this.formError.set(null);
  }

  // ── Display helpers ────────────────────────────────────────────────────────

  fmtMoney(v: number | string | null | undefined): string {
    const n = +(v ?? 0);
    return Number.isFinite(n) ? n.toFixed(2) : '0.00';
  }

  accountLabel(acc: CashBankAccountDto): string {
    return `${acc.code} — ${acc.name} (${acc.accountType})`;
  }

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}
