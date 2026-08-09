import { describe, it, expect, beforeEach } from 'vitest';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import {
  DirectReceiptRatificationComponent,
  hasRatificationNotice,
} from './direct-receipt-ratification.component';
import { DirectReceiptRatificationState } from './models/ap.model';

function render(
  state: DirectReceiptRatificationState | null | undefined,
  variant: 'chip' | 'detail' = 'chip',
): ComponentFixture<DirectReceiptRatificationComponent> {
  const fixture = TestBed.createComponent(DirectReceiptRatificationComponent);
  fixture.componentRef.setInput('state', state);
  fixture.componentRef.setInput('variant', variant);
  fixture.detectChanges();
  return fixture;
}

function text(fixture: ComponentFixture<DirectReceiptRatificationComponent>): string {
  return (fixture.nativeElement as HTMLElement).textContent ?? '';
}

describe('DirectReceiptRatificationComponent', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [DirectReceiptRatificationComponent] });
  });

  // ── Nothing at all for ordinary bills ──────────────────────────────────────

  it('renders nothing for NOT_APPLICABLE', () => {
    const fixture = render('NOT_APPLICABLE');
    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('.status-tag')).toBeNull();
    expect(host.querySelector('.alert')).toBeNull();
    expect(text(fixture).trim()).toBe('');
    // Host collapses too, so a caller's spacing utility cannot leave a gap.
    expect(host.classList.contains('d-none')).toBe(true);
  });

  it('renders nothing for NOT_APPLICABLE in the detail variant', () => {
    const fixture = render('NOT_APPLICABLE', 'detail');
    expect((fixture.nativeElement as HTMLElement).querySelector('.alert')).toBeNull();
    expect(text(fixture).trim()).toBe('');
  });

  it('renders nothing when the field is absent (null / undefined)', () => {
    for (const missing of [null, undefined]) {
      const fixture = render(missing);
      expect(text(fixture).trim()).toBe('');
      expect((fixture.nativeElement as HTMLElement).classList.contains('d-none')).toBe(true);
    }
  });

  // ── Awaiting ───────────────────────────────────────────────────────────────

  it('AWAITING_RATIFICATION renders a warning chip that says payment is held', () => {
    const fixture = render('AWAITING_RATIFICATION');
    const chip = (fixture.nativeElement as HTMLElement).querySelector('.status-tag');
    expect(chip).not.toBeNull();
    expect(chip!.classList.contains('status-tag--warn')).toBe(true);
    expect(chip!.textContent).toContain('Awaiting ratification');
    // Never colour alone: the accessible name spells the consequence out.
    expect(chip!.textContent).toContain('before the bill can be paid');
    expect((fixture.nativeElement as HTMLElement).classList.contains('d-none')).toBe(false);
  });

  it('AWAITING_RATIFICATION detail explains the wait and that payment is blocked', () => {
    const fixture = render('AWAITING_RATIFICATION', 'detail');
    const alert = (fixture.nativeElement as HTMLElement).querySelector('.alert');
    expect(alert).not.toBeNull();
    expect(alert!.classList.contains('alert-warning')).toBe(true);
    expect(alert!.getAttribute('role')).toBe('alert');
    const body = alert!.textContent ?? '';
    expect(body).toContain('manager');
    expect(body).toContain('without a purchase order');
    expect(body.toLowerCase()).toContain('before the bill can be paid');
  });

  // ── Refused ────────────────────────────────────────────────────────────────

  it('RATIFICATION_REFUSED renders a danger chip — visibly worse than awaiting', () => {
    const fixture = render('RATIFICATION_REFUSED');
    const chip = (fixture.nativeElement as HTMLElement).querySelector('.status-tag');
    expect(chip!.classList.contains('status-tag--danger')).toBe(true);
    expect(chip!.classList.contains('status-tag--warn')).toBe(false);
    expect(chip!.textContent).toContain('Ratification refused');
    expect(chip!.textContent).toContain('cannot be paid');
  });

  it('RATIFICATION_REFUSED detail says the bill will not be paid', () => {
    const fixture = render('RATIFICATION_REFUSED', 'detail');
    const alert = (fixture.nativeElement as HTMLElement).querySelector('.alert');
    expect(alert!.classList.contains('alert-danger')).toBe(true);
    expect(alert!.textContent).toContain('refused');
    expect(alert!.textContent).toContain('will not be paid');
  });

  // ── Ratified ───────────────────────────────────────────────────────────────

  it('RATIFIED renders a quiet success chip and no alert', () => {
    const fixture = render('RATIFIED');
    const chip = (fixture.nativeElement as HTMLElement).querySelector('.status-tag');
    expect(chip!.classList.contains('status-tag--ok')).toBe(true);
    expect(chip!.textContent).toContain('Ratified');
    expect((fixture.nativeElement as HTMLElement).querySelector('.alert')).toBeNull();
  });

  it('RATIFIED detail stays quiet — a muted line, not an alert', () => {
    const fixture = render('RATIFIED', 'detail');
    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('.alert')).toBeNull();
    expect(host.querySelector('.text-muted')).not.toBeNull();
    expect(host.textContent).toContain('pays as normal');
  });

  // ── Accessibility contract ─────────────────────────────────────────────────

  it('every rendered chip carries text, a hidden explanation and a tooltip', () => {
    const states: DirectReceiptRatificationState[] = [
      'AWAITING_RATIFICATION',
      'RATIFICATION_REFUSED',
      'RATIFIED',
    ];
    for (const state of states) {
      const chip = (render(state).nativeElement as HTMLElement).querySelector('.status-tag')!;
      expect(chip.querySelector('.visually-hidden')?.textContent?.trim().length).toBeGreaterThan(10);
      expect(chip.getAttribute('title')?.length).toBeGreaterThan(10);
      // The colour dot is decorative and must not reach the accessible name.
      expect(chip.querySelector('.status-tag__dot')?.getAttribute('aria-hidden')).toBe('true');
    }
  });

  // ── Guard helper ───────────────────────────────────────────────────────────

  it('hasRatificationNotice is true only for the three direct-receipt states', () => {
    expect(hasRatificationNotice(null)).toBe(false);
    expect(hasRatificationNotice(undefined)).toBe(false);
    expect(hasRatificationNotice('NOT_APPLICABLE')).toBe(false);
    expect(hasRatificationNotice('AWAITING_RATIFICATION')).toBe(true);
    expect(hasRatificationNotice('RATIFICATION_REFUSED')).toBe(true);
    expect(hasRatificationNotice('RATIFIED')).toBe(true);
  });
});
