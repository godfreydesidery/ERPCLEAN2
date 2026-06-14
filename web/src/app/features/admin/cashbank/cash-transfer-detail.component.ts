import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { SessionStore } from '../../../core/auth/session.store';
import { CashBankAccountDto, CashTransferDto } from './models/cashbank.model';
import { CashbankService } from './cashbank.service';

/**
 * Cash Transfer detail view. Loaded by uid. Gated CASH.VIEW.
 * Resolves account uids to names by loading accounts for the same company.
 */
@Component({
  selector: 'app-cash-transfer-detail',
  imports: [RouterLink],
  templateUrl: './cash-transfer-detail.component.html',
  styleUrl: './cash-transfer-detail.component.scss',
})
export class CashTransferDetailComponent {
  readonly uid = input.required<string>();

  private readonly cashbankService = inject(CashbankService);
  protected readonly session = inject(SessionStore);

  readonly entity = signal<CashTransferDto | null>(null);
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('loading');
  readonly canView = computed(() => this.session.hasPermission('CASH.VIEW'));

  /** uid → name map for account display */
  private readonly accountsByUid = signal<Map<string, CashBankAccountDto>>(new Map());

  constructor() { queueMicrotask(() => this.init()); }

  private init(): void {
    const uid = this.uid();
    if (!uid) return;
    this.state.set('loading');
    this.cashbankService.getTransfer(uid).subscribe({
      next: (t) => {
        this.entity.set(t);
        this.state.set('idle');
        this.loadAccounts(String(t.companyId));
      },
      error: (err) =>
        this.state.set(err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error'),
    });
  }

  private loadAccounts(companyId: string): void {
    this.cashbankService.listAllAccounts(companyId).subscribe({
      next: (accounts) => {
        const m = new Map<string, CashBankAccountDto>();
        accounts.forEach((a) => m.set(a.uid, a));
        this.accountsByUid.set(m);
      },
      error: () => {},
    });
  }

  accountName(uid: string | null | undefined): string {
    if (!uid) return '—';
    const acc = this.accountsByUid().get(uid);
    return acc ? `${acc.code} — ${acc.name}` : uid;
  }

  fmtMoney(v: number | string | null | undefined): string {
    const n = +(v ?? 0);
    return Number.isFinite(n) ? n.toFixed(2) : '0.00';
  }
}
