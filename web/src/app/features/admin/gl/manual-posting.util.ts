import { AccountDto } from './models/gl.model';

/**
 * Where to post instead, per control type — mirrors ControlType.manualPostingGuidance() on the
 * server (ACC-18). CASH and BANK are control types that still take manual journals.
 */
const GUIDANCE: Record<string, string> = {
  AR: 'use Receivables',
  AP: 'use Payables',
  INVENTORY: 'use Stock',
  TAX: 'posted by sales, purchases and the VAT Return',
  PAYROLL_CLEARING: 'posted by payroll runs',
  FX_CLEARING: 'posted by FX revaluation',
};

/**
 * Why a manual journal may not use this account, as a short phrase for the picker; null when it
 * may. A blocking control type wins over the allowManualPosting flag (the server checks it first).
 */
export function manualPostingBlock(a: AccountDto): string | null {
  const control = a.controlType ?? null;
  if (control && GUIDANCE[control]) return `control account — ${GUIDANCE[control]}`;
  if (a.allowManualPosting === false) return 'closed to manual journals';
  return null;
}
