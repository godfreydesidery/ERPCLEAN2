/**
 * Cash & Bank feature models.
 * All Long/BigDecimal fields arrive as numbers on the wire — coerce defensively with +v.
 * NEVER call .startsWith/.trim on a money value.
 * Mirrors the backend cashbank controller DTOs exactly (ADR-0016).
 */

// ── Enums (string unions) ────────────────────────────────────────────────────

export type CashBankAccountType = 'CASH' | 'BANK';
export type CashTxnDirection = 'IN' | 'OUT';
export type CashTxnType =
  | 'TRANSFER_IN'
  | 'TRANSFER_OUT'
  | 'DIRECT_ENTRY'
  | 'RECEIPT'
  | 'PAYMENT'
  | 'CHEQUE';
export type ChequeStatus = 'PENDING' | 'CLEARED' | 'CANCELLED';
export type ReconciliationStatus = 'DRAFT' | 'COMPLETED';

// ── Cash / Bank Account ──────────────────────────────────────────────────────

/**
 * CashBankAccountDto — mirrors the backend record.
 * id, companyId, branchId, glAccountId arrive as numbers on wire — coerce with +v if needed.
 */
export interface CashBankAccountDto {
  /** Wire: number */
  id: string;
  uid: string;
  /** Wire: number */
  companyId: string;
  /** Wire: number | null */
  branchId: string | null;
  code: string;
  name: string;
  accountType: CashBankAccountType;
  bankName: string | null;
  bankAccountNo: string | null;
  bankBranch: string | null;
  currency: string;
  /** Wire: number */
  glAccountId: string;
  isDefault: boolean;
  active: boolean;
}

export interface CreateCashBankAccountRequest {
  companyUid: string;
  branchUid?: string;
  name: string;
  accountType: CashBankAccountType;
  bankName?: string;
  bankAccountNo?: string;
  bankBranch?: string;
  /** uid of the linked GL asset account */
  glAccountUid: string;
  setAsDefault: boolean;
}

export interface UpdateCashBankAccountRequest {
  name?: string;
  bankName?: string;
  bankAccountNo?: string;
  bankBranch?: string;
  active?: boolean;
}

// ── Cash Transaction ─────────────────────────────────────────────────────────

/**
 * CashTransactionDto.
 * amount arrives as number on wire — coerce with +v.
 */
export interface CashTransactionDto {
  /** Wire: number */
  id: string;
  uid: string;
  /** Wire: number */
  companyId: string;
  /** Wire: number */
  cashBankAccountId: string;
  txnNumber: string;
  txnDate: string;
  direction: CashTxnDirection;
  /** Wire: number — coerce with +(dto.amount) */
  amount: number | string;
  currency: string;
  txnType: CashTxnType;
  sourceRef: string | null;
  /** Wire: number | null */
  counterGlAccountId: string | null;
  journalEntryRef: string | null;
  cleared: boolean;
  /** Wire: number | null */
  clearedInReconciliationId: string | null;
  memo: string | null;
}

// ── Cash Transfer ────────────────────────────────────────────────────────────

/**
 * CashTransferDto.
 * amount arrives as number on wire.
 */
export interface CashTransferDto {
  /** Wire: number */
  id: string;
  uid: string;
  /** Wire: number */
  companyId: string;
  transferNumber: string;
  /** Wire: number */
  sourceAccountId: string;
  sourceAccountUid: string;
  /** Wire: number */
  destinationAccountId: string;
  destinationAccountUid: string;
  transferDate: string;
  /** Wire: number — coerce with +(dto.amount) */
  amount: number | string;
  currency: string;
  reference: string | null;
  /** Wire: number */
  outTxnId: string;
  /** Wire: number */
  inTxnId: string;
  journalEntryRef: string | null;
}

export interface RecordTransferRequest {
  companyUid: string;
  sourceAccountUid: string;
  destinationAccountUid: string;
  /** Send as string representation of decimal */
  amount: string;
  transferDate: string;
  reference?: string;
}

// ── Direct Entry ─────────────────────────────────────────────────────────────

export interface RecordDirectEntryRequest {
  companyUid: string;
  cashBankAccountUid: string;
  direction: CashTxnDirection;
  /** Send as string representation of decimal */
  amount: string;
  txnDate: string;
  /** uid of the counter GL account */
  counterGlAccountUid: string;
  memo?: string;
}

// ── Cheque ───────────────────────────────────────────────────────────────────

/**
 * ChequeDto.
 * amount arrives as number on wire.
 */
export interface ChequeDto {
  /** Wire: number */
  id: string;
  uid: string;
  /** Wire: number */
  companyId: string;
  /** Wire: number */
  cashBankAccountId: string;
  chequeNumber: string;
  payee: string;
  /** Wire: number — coerce with +(dto.amount) */
  amount: number | string;
  currency: string;
  issueDate: string;
  valueDate: string;
  status: ChequeStatus;
  apPaymentUid: string | null;
  cashTransactionUid: string | null;
  clearedAt: string | null;
  cancelledAt: string | null;
}

export interface RegisterChequeRequest {
  companyUid: string;
  cashBankAccountUid: string;
  chequeNumber: string;
  payee: string;
  /** Send as string */
  amount: string;
  issueDate: string;
  valueDate: string;
  apPaymentUid?: string;
  cashTransactionUid?: string;
}

// ── Bank Reconciliation ──────────────────────────────────────────────────────

/**
 * BankReconciliationDto.
 * statementClosingBalance and clearedBookBalance arrive as numbers.
 */
export interface BankReconciliationDto {
  /** Wire: number */
  id: string;
  uid: string;
  /** Wire: number */
  companyId: string;
  /** Wire: number */
  cashBankAccountId: string;
  reconciliationNumber: string;
  statementDate: string;
  /** Wire: number — coerce with +v */
  statementClosingBalance: number | string;
  /** Wire: number — coerce with +v */
  clearedBookBalance: number | string;
  status: ReconciliationStatus;
  /** Wire: number | null */
  reconciledBy: string | null;
  completedAt: string | null;
}

export interface OpenReconciliationRequest {
  companyUid: string;
  cashBankAccountUid: string;
  statementDate: string;
  /** Send as string */
  statementClosingBalance: string;
}

export interface MarkClearedRequest {
  transactionUids: string[];
  cleared: boolean;
}

// ── Cash Count (D-7 PR-A — end-of-day drawer count) ─────────────────────────

export type CashCountStatus = 'OPEN' | 'COUNTED' | 'RECONCILED';

/**
 * CashCountDenominationLineDto — one row of the denomination breakdown.
 * lineAmount = denomination * quantity, computed server-side too.
 */
export interface CashCountDenominationLineDto {
  /** Face value, e.g. 10000, 5000, ... — wire: number */
  denomination: number;
  quantity: number;
  /** Wire: number — coerce with +v */
  lineAmount: number;
}

/**
 * CashCountDto — mirrors the backend record (ADR-0050, D-7 PR-A; cross-checked against
 * com.erp.modules.cashbank.domain.dto.CashCountDto).
 * expectedAmount/countedAmount/varianceAmount arrive as numbers — coerce with +v.
 * expectedAmount is SERVER-DERIVED (till book balance as-of businessDate) — never typed by the user.
 */
export interface CashCountDto {
  /** Wire: number */
  id: string;
  uid: string;
  /** Wire: number */
  companyId: string;
  /** Null only if the linked till could not be resolved (defensive; not expected in normal flow). */
  cashBankAccountUid: string | null;
  cashBankAccountName: string | null;
  countNumber: string;
  businessDate: string;
  /** Wire: number — coerce with +v. Derived server-side; read-only in the UI. */
  expectedAmount: number | string;
  /** Wire: number — coerce with +v. Σ denomination lines. */
  countedAmount: number | string;
  /** Wire: number — coerce with +v. counted - expected (over>0 / short<0). */
  varianceAmount: number | string;
  currency: string;
  status: CashCountStatus;
  journalEntryRef: string | null;
  denominations: CashCountDenominationLineDto[];
  countedAt: string | null;
  reconciledAt: string | null;
}

export interface OpenCashCountRequest {
  companyUid: string;
  /** uid of a CASH-type cash_bank_account (the till) */
  cashBankAccountUid: string;
  businessDate: string;
}

export interface RecordDenominationsLineInput {
  denomination: number;
  quantity: number;
}

export interface RecordDenominationsRequest {
  lines: RecordDenominationsLineInput[];
}

// ── Statements / Balances ────────────────────────────────────────────────────

/**
 * CashAccountBalanceDto.
 * bookBalance arrives as number.
 */
export interface CashAccountBalanceDto {
  /** Wire: number */
  cashBankAccountId: string;
  cashBankAccountUid: string;
  accountCode: string;
  accountName: string;
  /** Wire: number — coerce with +v */
  bookBalance: number | string;
  currency: string;
}

/**
 * CashAccountStatementDto.
 * currentBalance arrives as number.
 */
export interface CashAccountStatementDto {
  /** Wire: number */
  cashBankAccountId: string;
  cashBankAccountUid: string;
  accountName: string;
  /** Wire: number — coerce with +v */
  currentBalance: number | string;
  currency: string;
  transactions: CashTransactionDto[];
}

/**
 * CashGlReconciliationDto.
 * bookBalance, linkedGlBalance, difference arrive as numbers.
 */
export interface CashGlReconciliationDto {
  cashBankAccountUid: string;
  accountName: string;
  /** Wire: number */
  linkedGlAccountId: string;
  linkedGlAccountCode: string;
  /** Wire: number — coerce with +v */
  bookBalance: number | string;
  /** Wire: number — coerce with +v */
  linkedGlBalance: number | string;
  /** Wire: number — coerce with +v */
  difference: number | string;
}
