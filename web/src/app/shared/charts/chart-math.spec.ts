import { describe, expect, it } from 'vitest';
import { formatCompact, monotonePath, niceTicks, toNumber } from './chart-math';

describe('chart-math', () => {
  it('niceTicks gives round steps that include zero', () => {
    expect(niceTicks(0, 86234)).toEqual([0, 25000, 50000, 75000, 100000]);
    const mixed = niceTicks(-5000, 50000);
    expect(mixed).toContain(0);
    expect(mixed[0]).toBeLessThanOrEqual(-5000);
    expect(mixed[mixed.length - 1]).toBeGreaterThanOrEqual(50000);
  });

  it('niceTicks survives a flat or empty range', () => {
    expect(niceTicks(0, 0).length).toBeGreaterThan(1);
    expect(niceTicks(500, 500)[0]).toBe(0);
  });

  it('monotonePath never overshoots the data between points', () => {
    // Two flat-then-rising points: a Catmull-Rom curve would dip below y=100 here.
    const d = monotonePath([{ x: 0, y: 100 }, { x: 10, y: 100 }, { x: 20, y: 0 }, { x: 30, y: 0 }]);
    const ys = [...d.matchAll(/(-?\d+(?:\.\d+)?),(-?\d+(?:\.\d+)?)/g)].map((m) => Number(m[2]));
    expect(Math.max(...ys)).toBeLessThanOrEqual(100);
    expect(Math.min(...ys)).toBeGreaterThanOrEqual(0);
  });

  it('monotonePath handles 0, 1 and 2 points', () => {
    expect(monotonePath([])).toBe('');
    expect(monotonePath([{ x: 1, y: 2 }])).toBe('M1,2');
    expect(monotonePath([{ x: 0, y: 0 }, { x: 5, y: 5 }])).toBe('M0,0L5,5');
  });

  it('formats compact amounts and coerces API numbers', () => {
    expect(formatCompact(4_200_000)).toBe('4.2M');
    expect(formatCompact(0)).toBe('0');
    expect(toNumber('125000')).toBe(125000);
    expect(toNumber(null)).toBe(0);
    expect(toNumber('not a number')).toBe(0);
  });
});
