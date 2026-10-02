/**
 * Ageing endpoints return one five-bucket block PER CURRENCY (base currency first): an invoice or
 * bill is aged in its own currency, and amounts in different currencies are never added together.
 * This groups those rows for display — one group per currency, buckets in the canonical order, and
 * a total per currency (there is deliberately no cross-currency total).
 */
export interface AgeingRowLike {
  bucket: string;
  /** Wire: number (BigDecimal) — coerced with `+` */
  amount: number | string;
  currency: string;
}

export interface AgeingCurrencyGroup<T extends AgeingRowLike> {
  currency: string;
  buckets: T[];
  total: number;
}

export function groupAgeingByCurrency<T extends AgeingRowLike>(
  rows: readonly T[],
  bucketOrder: readonly string[],
): AgeingCurrencyGroup<T>[] {
  const byCurrency = new Map<string, T[]>();
  for (const row of rows) {
    const list = byCurrency.get(row.currency);
    if (list) {
      list.push(row);
    } else {
      byCurrency.set(row.currency, [row]);
    }
  }
  return [...byCurrency].map(([currency, list]) => ({
    currency,
    buckets: [...list].sort((a, b) => bucketOrder.indexOf(a.bucket) - bucketOrder.indexOf(b.bucket)),
    total: list.reduce((sum, r) => sum + (Number.isFinite(+r.amount) ? +r.amount : 0), 0),
  }));
}
