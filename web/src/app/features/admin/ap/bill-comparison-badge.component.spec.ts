import { describe, it, expect } from 'vitest';
import { TestBed } from '@angular/core/testing';

import { BillComparisonBadgeComponent } from './bill-comparison-badge.component';
import { BillComparisonState } from './models/ap.model';

function render(
  state: BillComparisonState | null | undefined,
  variant: 'chip' | 'detail' = 'chip',
): HTMLElement {
  const fixture = TestBed.createComponent(BillComparisonBadgeComponent);
  fixture.componentRef.setInput('state', state);
  fixture.componentRef.setInput('variant', variant);
  fixture.detectChanges();
  return fixture.nativeElement as HTMLElement;
}

describe('BillComparisonBadgeComponent', () => {
  it('marks a fully checked bill as checked, quietly', () => {
    const chip = render('ALL_LINES_COMPARED').querySelector('.status-tag')!;
    expect(chip.classList.contains('status-tag--ok')).toBe(true);
    expect(chip.textContent).toContain('Checked');
  });

  it('warns when only some lines were checked', () => {
    const chip = render('SOME_LINES_COMPARED').querySelector('.status-tag')!;
    expect(chip.classList.contains('status-tag--warn')).toBe(true);
    expect(chip.textContent).toContain('Partly checked');
  });

  it('warns when nothing on the bill was checked', () => {
    const chip = render('NO_LINES_COMPARED').querySelector('.status-tag')!;
    expect(chip.classList.contains('status-tag--warn')).toBe(true);
    expect(chip.textContent).toContain('Not checked');
  });

  it('warns when the check was never run at all', () => {
    // The AP opening-balance shape: posted, payable, and the match engine never saw it.
    const chip = render('NEVER_MATCHED').querySelector('.status-tag')!;
    expect(chip.classList.contains('status-tag--warn')).toBe(true);
    expect(chip.textContent).toContain('No check run');
  });

  // The property this component exists to hold. Everything else is presentation.
  it.each([[null], [undefined]])(
    'never reports a missing state as checked (%s)',
    (state: BillComparisonState | null | undefined) => {
      const chip = render(state).querySelector('.status-tag')!;
      expect(chip.textContent).toContain('Not reported');
      expect(chip.textContent).not.toMatch(/^\s*Checked/);
      expect(chip.classList.contains('status-tag--ok')).toBe(false);
    },
  );

  it('never reports an unrecognised state as checked', () => {
    // A value the backend adds later must not fall through to the reassuring branch.
    const chip = render('SOMETHING_NEW' as BillComparisonState).querySelector('.status-tag')!;
    expect(chip.textContent).toContain('Not reported');
    expect(chip.classList.contains('status-tag--ok')).toBe(false);
  });

  it('always renders a chip, so a blank cell can never be mistaken for a pass', () => {
    const states: (BillComparisonState | null)[] = [
      'ALL_LINES_COMPARED',
      'SOME_LINES_COMPARED',
      'NO_LINES_COMPARED',
      'NEVER_MATCHED',
      null,
    ];
    for (const s of states) {
      expect(render(s).querySelector('.status-tag')).not.toBeNull();
    }
  });

  it('carries the meaning in text as well as colour', () => {
    // WCAG 1.4.1: the warn colour is not the only thing separating a checked bill from an
    // unchecked one — a screen reader gets the consequence spelled out.
    const chip = render('NO_LINES_COMPARED').querySelector('.status-tag')!;
    const srOnly = chip.querySelector('.visually-hidden')!;
    expect(srOnly.textContent).toContain('nothing on this bill was checked');
  });

  it('spells the state out in full on a detail surface', () => {
    const host = render('NEVER_MATCHED', 'detail');
    expect(host.textContent).toContain('has never been run');
    expect(host.querySelector('.status-tag')).not.toBeNull();
  });
});
