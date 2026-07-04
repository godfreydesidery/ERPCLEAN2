import { HttpClient, HttpContext, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { ApiResponse, PageMeta } from '../../../core/api/api-response.model';
import { SKIP_UNWRAP } from '../../../core/api/http-context.tokens';
import { environment } from '../../../../environments/environment';
import {
  BankReconciliationDto,
  CashAccountBalanceDto,
  CashAccountStatementDto,
  CashBankAccountDto,
  CashCountDto,
  CashGlReconciliationDto,
  CashTransferDto,
  CashTransactionDto,
  ChequeDto,
  CreateCashBankAccountRequest,
  MarkClearedRequest,
  OpenCashCountRequest,
  OpenReconciliationRequest,
  RecordDenominationsRequest,
  RecordDirectEntryRequest,
  RecordTransferRequest,
  RegisterChequeRequest,
  UpdateCashBankAccountRequest,
} from './models/cashbank.model';

export interface CashBankAccountPage {
  rows: CashBankAccountDto[];
  meta: PageMeta;
}

export interface CashTransferPage {
  rows: CashTransferDto[];
  meta: PageMeta;
}

export interface CashTransactionPage {
  rows: CashTransactionDto[];
  meta: PageMeta;
}

export interface ChequePage {
  rows: ChequeDto[];
  meta: PageMeta;
}

export interface BankReconciliationPage {
  rows: BankReconciliationDto[];
  meta: PageMeta;
}

/**
 * Cash & Bank API client. Base: /api/v1/cash.
 * list() methods use SKIP_UNWRAP to read both data and PageMeta.
 * All other methods use the auto-unwrap path (interceptor strips the envelope).
 */
@Injectable({ providedIn: 'root' })
export class CashbankService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/cash`;

  // ── Accounts ─────────────────────────────────────────────────────────────

  listAccounts(companyId: string, page = 0, size = 50): Observable<CashBankAccountPage> {
    const params = new HttpParams()
      .set('companyId', companyId)
      .set('page', String(page))
      .set('size', String(size));

    const context = new HttpContext().set(SKIP_UNWRAP, true);
    return this.http
      .get<ApiResponse<CashBankAccountDto[]>>(`${this.base}/accounts`, { params, context })
      .pipe(
        map((env) => ({
          rows: env.data ?? [],
          meta: env.meta ?? { page, size, totalElements: env.data?.length ?? 0, totalPages: 1, hasNext: false },
        })),
      );
  }

  getAccount(uid: string): Observable<CashBankAccountDto> {
    return this.http.get<CashBankAccountDto>(`${this.base}/accounts/uid/${uid}`);
  }

  createAccount(request: CreateCashBankAccountRequest): Observable<CashBankAccountDto> {
    return this.http.post<CashBankAccountDto>(`${this.base}/accounts`, request);
  }

  updateAccount(uid: string, request: UpdateCashBankAccountRequest): Observable<CashBankAccountDto> {
    return this.http.put<CashBankAccountDto>(`${this.base}/accounts/uid/${uid}`, request);
  }

  setDefault(uid: string): Observable<CashBankAccountDto> {
    return this.http.post<CashBankAccountDto>(`${this.base}/accounts/uid/${uid}/set-default`, {});
  }

  /** Convenience: load ALL active accounts for a company (for pickers). */
  listAllAccounts(companyId: string): Observable<CashBankAccountDto[]> {
    const params = new HttpParams()
      .set('companyId', companyId)
      .set('page', '0')
      .set('size', '200');
    const context = new HttpContext().set(SKIP_UNWRAP, true);
    return this.http
      .get<ApiResponse<CashBankAccountDto[]>>(`${this.base}/accounts`, { params, context })
      .pipe(map((env) => (env.data ?? []).filter((a) => a.active)));
  }

  // ── Transfers ─────────────────────────────────────────────────────────────

  recordTransfer(request: RecordTransferRequest): Observable<CashTransferDto> {
    return this.http.post<CashTransferDto>(`${this.base}/transfers`, request);
  }

  listTransfers(companyId: string, page = 0, size = 20): Observable<CashTransferPage> {
    const params = new HttpParams()
      .set('companyId', companyId)
      .set('page', String(page))
      .set('size', String(size));
    const context = new HttpContext().set(SKIP_UNWRAP, true);
    return this.http
      .get<ApiResponse<CashTransferDto[]>>(`${this.base}/transfers`, { params, context })
      .pipe(
        map((env) => ({
          rows: env.data ?? [],
          meta: env.meta ?? { page, size, totalElements: env.data?.length ?? 0, totalPages: 1, hasNext: false },
        })),
      );
  }

  getTransfer(uid: string): Observable<CashTransferDto> {
    return this.http.get<CashTransferDto>(`${this.base}/transfers/uid/${uid}`);
  }

  // ── Direct Entries ────────────────────────────────────────────────────────

  recordEntry(request: RecordDirectEntryRequest): Observable<CashTransactionDto> {
    return this.http.post<CashTransactionDto>(`${this.base}/entries`, request);
  }

  listEntries(companyId: string, page = 0, size = 20): Observable<CashTransactionPage> {
    const params = new HttpParams()
      .set('companyId', companyId)
      .set('page', String(page))
      .set('size', String(size));
    const context = new HttpContext().set(SKIP_UNWRAP, true);
    return this.http
      .get<ApiResponse<CashTransactionDto[]>>(`${this.base}/entries`, { params, context })
      .pipe(
        map((env) => ({
          rows: env.data ?? [],
          meta: env.meta ?? { page, size, totalElements: env.data?.length ?? 0, totalPages: 1, hasNext: false },
        })),
      );
  }

  getEntry(uid: string): Observable<CashTransactionDto> {
    return this.http.get<CashTransactionDto>(`${this.base}/entries/uid/${uid}`);
  }

  // ── Cheques ───────────────────────────────────────────────────────────────

  registerCheque(request: RegisterChequeRequest): Observable<ChequeDto> {
    return this.http.post<ChequeDto>(`${this.base}/cheques`, request);
  }

  clearCheque(uid: string): Observable<ChequeDto> {
    return this.http.post<ChequeDto>(`${this.base}/cheques/uid/${uid}/clear`, {});
  }

  cancelCheque(uid: string): Observable<ChequeDto> {
    return this.http.post<ChequeDto>(`${this.base}/cheques/uid/${uid}/cancel`, {});
  }

  listCheques(companyId: string, page = 0, size = 20): Observable<ChequePage> {
    const params = new HttpParams()
      .set('companyId', companyId)
      .set('page', String(page))
      .set('size', String(size));
    const context = new HttpContext().set(SKIP_UNWRAP, true);
    return this.http
      .get<ApiResponse<ChequeDto[]>>(`${this.base}/cheques`, { params, context })
      .pipe(
        map((env) => ({
          rows: env.data ?? [],
          meta: env.meta ?? { page, size, totalElements: env.data?.length ?? 0, totalPages: 1, hasNext: false },
        })),
      );
  }

  getCheque(uid: string): Observable<ChequeDto> {
    return this.http.get<ChequeDto>(`${this.base}/cheques/uid/${uid}`);
  }

  // ── Reconciliations ───────────────────────────────────────────────────────

  openReconciliation(request: OpenReconciliationRequest): Observable<BankReconciliationDto> {
    return this.http.post<BankReconciliationDto>(`${this.base}/reconciliations`, request);
  }

  markCleared(reconciliationUid: string, request: MarkClearedRequest): Observable<BankReconciliationDto> {
    return this.http.post<BankReconciliationDto>(
      `${this.base}/reconciliations/uid/${reconciliationUid}/mark-cleared`,
      request,
    );
  }

  completeReconciliation(reconciliationUid: string): Observable<BankReconciliationDto> {
    return this.http.post<BankReconciliationDto>(
      `${this.base}/reconciliations/uid/${reconciliationUid}/complete`,
      {},
    );
  }

  listReconciliations(companyId: string, page = 0, size = 20): Observable<BankReconciliationPage> {
    const params = new HttpParams()
      .set('companyId', companyId)
      .set('page', String(page))
      .set('size', String(size));
    const context = new HttpContext().set(SKIP_UNWRAP, true);
    return this.http
      .get<ApiResponse<BankReconciliationDto[]>>(`${this.base}/reconciliations`, { params, context })
      .pipe(
        map((env) => ({
          rows: env.data ?? [],
          meta: env.meta ?? { page, size, totalElements: env.data?.length ?? 0, totalPages: 1, hasNext: false },
        })),
      );
  }

  getReconciliation(uid: string): Observable<BankReconciliationDto> {
    return this.http.get<BankReconciliationDto>(`${this.base}/reconciliations/uid/${uid}`);
  }

  // ── Statements / Balances ─────────────────────────────────────────────────

  getAccountBalance(accountUid: string): Observable<CashAccountBalanceDto> {
    return this.http.get<CashAccountBalanceDto>(
      `${this.base}/statements/accounts/uid/${accountUid}/balance`,
    );
  }

  getAccountStatement(accountUid: string): Observable<CashAccountStatementDto> {
    return this.http.get<CashAccountStatementDto>(
      `${this.base}/statements/accounts/uid/${accountUid}/statement`,
    );
  }

  listBalances(companyId: string): Observable<CashAccountBalanceDto[]> {
    return this.http.get<CashAccountBalanceDto[]>(`${this.base}/statements/balances`, {
      params: { companyId },
    });
  }

  getGlReconciliation(accountUid: string): Observable<CashGlReconciliationDto> {
    return this.http.get<CashGlReconciliationDto>(
      `${this.base}/statements/accounts/uid/${accountUid}/gl-reconciliation`,
    );
  }

  // ── Cash Counts (D-7 PR-A) ────────────────────────────────────────────────

  /** Open a new end-of-day cash count for a till; server derives expectedAmount. Status OPEN. */
  openCashCount(request: OpenCashCountRequest): Observable<CashCountDto> {
    return this.http.post<CashCountDto>(`${this.base}/counts`, request);
  }

  /** Record/replace the denomination breakdown; server computes countedAmount + varianceAmount. Status COUNTED. */
  recordDenominations(uid: string, request: RecordDenominationsRequest): Observable<CashCountDto> {
    return this.http.put<CashCountDto>(`${this.base}/counts/uid/${uid}/denominations`, request);
  }

  /** Post the variance to GL and lock the count. Status RECONCILED. */
  reconcileCashCount(uid: string): Observable<CashCountDto> {
    return this.http.post<CashCountDto>(`${this.base}/counts/uid/${uid}/reconcile`, {});
  }

  getCashCount(uid: string): Observable<CashCountDto> {
    return this.http.get<CashCountDto>(`${this.base}/counts/uid/${uid}`);
  }

  /**
   * Cash-count history for ONE till. Note: unlike the other cashbank lists, the backend
   * (`CashCountController#listByAccount`) requires BOTH `companyId` AND `accountId` — no
   * company-wide listing and no pagination (returns a plain unwrapped array, newest first).
   * `accountId` is the till's numeric `id` (not its `uid`).
   */
  listCashCounts(companyId: string, accountId: string): Observable<CashCountDto[]> {
    const params = new HttpParams().set('companyId', companyId).set('accountId', accountId);
    return this.http.get<CashCountDto[]>(`${this.base}/counts`, { params });
  }
}
