import { describe, expect, it } from 'vitest';
import { isInvalidAmount, normaliseAmount, parseAmount } from './money.util';

describe('normaliseAmount / parseAmount (LUI-04)', () => {
  it('accepts plain decimals and numbers as-is', () => {
    expect(normaliseAmount('1800')).toBe('1800');
    expect(normaliseAmount('1800.50')).toBe('1800.50');
    expect(normaliseAmount(' 12.5 ')).toBe('12.5');
    expect(normaliseAmount(1800)).toBe('1800');
    expect(parseAmount('0.5')).toBe(0.5);
  });

  it('strips thousands commas and spaces instead of reading them as zero', () => {
    expect(normaliseAmount('68,300')).toBe('68300');
    expect(normaliseAmount('1,800.50')).toBe('1800.50');
    expect(normaliseAmount('1 500 000')).toBe('1500000');
    expect(normaliseAmount('1 500 000')).toBe('1500000');
    expect(parseAmount('68,300')).toBe(68300);
  });

  it('refuses ambiguous or malformed grouping rather than guessing', () => {
    expect(normaliseAmount('1,8')).toBeNull();
    expect(normaliseAmount('18,00')).toBeNull();
    expect(normaliseAmount('1,80,000')).toBeNull();
    expect(normaliseAmount('12abc')).toBeNull();
    expect(normaliseAmount('1.2.3')).toBeNull();
    expect(parseAmount('1,8')).toBeNull();
    expect(isInvalidAmount('abc')).toBe(true);
  });

  it('treats empty input as empty, not invalid', () => {
    expect(normaliseAmount('')).toBe('');
    expect(normaliseAmount('   ')).toBe('');
    expect(normaliseAmount(null)).toBe('');
    expect(parseAmount('')).toBeNull();
    expect(isInvalidAmount('')).toBe(false);
  });

  it('drops a trailing decimal point', () => {
    expect(normaliseAmount('1,800.')).toBe('1800');
    expect(normaliseAmount('25.')).toBe('25');
  });
});
