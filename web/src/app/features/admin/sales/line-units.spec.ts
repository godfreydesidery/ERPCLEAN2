import { baseToUnit, lineFactor } from './line-units';

describe('line-units (SAL-01)', () => {
  it('derives base units per line unit from the order line', () => {
    expect(lineFactor({ qtyOrdered: '2', qtyOrderedBase: '48' })).toBe(24);
    expect(lineFactor({ qtyOrdered: '10', qtyOrderedBase: '10' })).toBe(1);
    expect(lineFactor({ qtyOrdered: null, qtyOrderedBase: '10' })).toBe(1);
  });

  it('shows a base counter in the line unit', () => {
    expect(baseToUnit('24', 24)).toBe(1);
    expect(baseToUnit('1', '24')).toBeCloseTo(0.041667, 6);
    expect(baseToUnit('7', null)).toBe(7);
    expect(baseToUnit('7', 0)).toBe(7);
  });
});
