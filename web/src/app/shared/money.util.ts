/**
 * Shared money display formatter.
 *
 * BigDecimal amounts arrive on the wire as either a JSON number or (in some
 * DTOs) a string — coerce with `+v`, never call string ops (.trim/.startsWith)
 * on a raw money value. Renders with thousand-separators + 2dp, matching the
 * reporting screens (balance sheet / income statement / cash-flow statement).
 */
export function formatMoney(v: number | string | null | undefined): string {
  const n = +(v ?? 0);
  return Number.isFinite(n)
    ? n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
    : '0.00';
}

/** Message shown beside a money input whose text is not a usable amount (LUI-04). */
export const INVALID_AMOUNT_MESSAGE =
  'Enter the amount as a number, for example 1800 or 1,800.50.';

/** Plain decimal: "1800", "1800.5", ".5", "-12". */
const PLAIN_AMOUNT = /^-?(\d+\.?\d*|\.\d+)$/;
/** Comma-grouped thousands: "1,800", "68,300.00", "1,500,000". Groups must be exactly 3 digits. */
const GROUPED_AMOUNT = /^-?\d{1,3}(,\d{3})+(\.\d*)?$/;

/**
 * Normalises a typed amount to the plain decimal string the API expects (LUI-04 / ADM-25).
 *
 * Strips spaces (including the non-breaking and thin spaces a pasted figure carries) and
 * thousands commas, so "68,300", "1 500 000" and "1,800.50" all work. Only well-formed
 * thousands grouping is accepted: "1,8" or "18,00" is refused rather than guessed at, because a
 * comma is a decimal mark in some locales and silently reading it either way loses money.
 *
 * @returns the plain decimal string; `''` when the input is empty; `null` when it is not a number.
 */
export function normaliseAmount(v: unknown): string | null {
  if (v === null || v === undefined) return '';
  if (typeof v === 'number') return Number.isFinite(v) ? String(v) : null;
  const s = String(v).replace(/[\s  ]/g, '');
  if (s === '') return '';
  let plain: string;
  if (PLAIN_AMOUNT.test(s)) {
    plain = s;
  } else if (GROUPED_AMOUNT.test(s)) {
    plain = s.replace(/,/g, '');
  } else {
    return null;
  }
  return plain.endsWith('.') ? plain.slice(0, -1) : plain;
}

/**
 * Parses a typed amount to a number (see {@link normaliseAmount} for what is accepted).
 *
 * @returns the number, or `null` when the input is empty or not a valid amount. Callers decide
 *          whether empty means zero; they must never treat an invalid entry as zero silently.
 */
export function parseAmount(v: unknown): number | null {
  const s = normaliseAmount(v);
  if (s === null || s === '') return null;
  const n = Number(s);
  return Number.isFinite(n) ? n : null;
}

/** True when the input is filled in but cannot be read as an amount. */
export function isInvalidAmount(v: unknown): boolean {
  return normaliseAmount(v) === null;
}
