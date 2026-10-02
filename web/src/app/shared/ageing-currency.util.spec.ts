import { describe, expect, it } from 'vitest';
import { groupAgeingByCurrency } from './ageing-currency.util';

const ORDER = ['CURRENT', 'D1_30', 'D31_60', 'D61_90', 'D90_PLUS'];

describe('groupAgeingByCurrency', () => {
  it('keeps one group per currency in server order, buckets canonical, totals never mixed', () => {
    const groups = groupAgeingByCurrency(
      [
        { bucket: 'D31_60', amount: 300, currency: 'TZS' },
        { bucket: 'CURRENT', amount: '1000', currency: 'TZS' },
        { bucket: 'D31_60', amount: 500, currency: 'USD' },
        { bucket: 'CURRENT', amount: 0, currency: 'USD' },
      ],
      ORDER,
    );

    expect(groups.map((g) => g.currency)).toEqual(['TZS', 'USD']);
    expect(groups[0].buckets.map((b) => b.bucket)).toEqual(['CURRENT', 'D31_60']);
    expect(groups[0].total).toBe(1300);
    expect(groups[1].total).toBe(500);
  });

  it('returns no groups for no rows', () => {
    expect(groupAgeingByCurrency([], ORDER)).toEqual([]);
  });
});
