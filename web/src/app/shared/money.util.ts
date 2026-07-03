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
