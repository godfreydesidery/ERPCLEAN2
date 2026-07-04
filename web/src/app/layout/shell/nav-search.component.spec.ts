import { describe, it, expect, beforeEach, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { NavSearchComponent, NavDestination } from './nav-search.component';
import { assertA11y } from '../../../testing/a11y.helper';

// ---------------------------------------------------------------------------
// Fixture helpers
// ---------------------------------------------------------------------------

const DESTINATIONS: readonly NavDestination[] = [
  { label: 'Sales Invoices', route: '/admin/sales-invoices', group: 'Sales', icon: 'bi-receipt' },
  { label: 'Sales Orders', route: '/admin/sales-orders', group: 'Sales', icon: 'bi-bag-check' },
  { label: 'Purchase Orders', route: '/admin/purchase-orders', group: 'Purchasing', icon: 'bi-cart' },
  { label: 'Quotations', route: '/admin/quotations', group: 'Sales', icon: 'bi-file-earmark-text' },
  { label: 'Customers', route: '/admin/customers', group: 'Parties', icon: 'bi-people-fill' },
  { label: 'Chart of Accounts', route: '/admin/gl/accounts', group: 'Accounting', icon: 'bi-diagram-3' },
  { label: 'Journal Entries', route: '/admin/gl/journals', group: 'Accounting', icon: 'bi-journal-text' },
  { label: 'Employees', route: '/admin/hr/employees', group: 'HR & Payroll', icon: 'bi-people' },
  { label: 'Payroll Runs', route: '/admin/hr/payroll-runs', group: 'HR & Payroll', icon: 'bi-cash-stack' },
];

function createComponent() {
  const fixture = TestBed.createComponent(NavSearchComponent);
  fixture.componentRef.setInput('destinations', DESTINATIONS);
  fixture.detectChanges();
  return fixture;
}

function setQuery(fixture: ReturnType<typeof createComponent>, value: string) {
  const comp = fixture.componentInstance;
  comp.query.set(value);
  comp.open.set(true);
  fixture.detectChanges();
}

// ---------------------------------------------------------------------------
// Suite
// ---------------------------------------------------------------------------

describe('NavSearchComponent', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [NavSearchComponent],
      providers: [provideRouter([])],
    });
    // Stub navigateByUrl so tests that call router.navigateByUrl don't trigger NG04002
    // from the empty route table in provideRouter([]).
    const router = TestBed.inject(Router);
    vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
  });

  // ── Filtering ─────────────────────────────────────────────────────────────

  it('returns no results when query is empty', () => {
    const fixture = createComponent();
    const comp = fixture.componentInstance;
    expect(comp.results()).toHaveLength(0);
  });

  it('filters destinations by label substring', () => {
    const fixture = createComponent();
    setQuery(fixture, 'invoice');
    const labels = fixture.componentInstance.results().map((r) => r.label);
    expect(labels).toContain('Sales Invoices');
  });

  it('filters destinations by group name', () => {
    const fixture = createComponent();
    setQuery(fixture, 'accounting');
    const labels = fixture.componentInstance.results().map((r) => r.label);
    expect(labels).toContain('Chart of Accounts');
    expect(labels).toContain('Journal Entries');
  });

  it('caps results at 8', () => {
    // All 9 items contain 'a' somewhere — result set should be capped at 8.
    const fixture = createComponent();
    setQuery(fixture, 'a');
    expect(fixture.componentInstance.results().length).toBeLessThanOrEqual(8);
  });

  it('handles multi-word queries (all words must appear)', () => {
    const fixture = createComponent();
    setQuery(fixture, 'sales order');
    const labels = fixture.componentInstance.results().map((r) => r.label);
    expect(labels).toContain('Sales Orders');
    // "Purchase Orders" has "order" but not "sales" — should not appear.
    expect(labels).not.toContain('Purchase Orders');
  });

  // ── Ranking ───────────────────────────────────────────────────────────────

  it('ranks label-prefix match before label-includes match', () => {
    const fixture = createComponent();
    // "sal" — "Sales Invoices" and "Sales Orders" both startsWith; "Quotations" does not.
    setQuery(fixture, 'sal');
    const results = fixture.componentInstance.results();
    // All returned items should start with "Sal" (prefix match = score 3)
    // before any items that merely contain it.
    const prefixItems = results.filter((r) => r.label.toLowerCase().startsWith('sal'));
    const nonPrefixItems = results.filter((r) => !r.label.toLowerCase().startsWith('sal'));
    // All prefix items must appear before non-prefix items in the ranked list.
    if (prefixItems.length > 0 && nonPrefixItems.length > 0) {
      const lastPrefixIdx = results.indexOf(prefixItems[prefixItems.length - 1]);
      const firstNonPrefixIdx = results.indexOf(nonPrefixItems[0]);
      expect(lastPrefixIdx).toBeLessThan(firstNonPrefixIdx);
    }
  });

  it('puts a label-startsWith match before a label-includes match', () => {
    // "chart" starts "Chart of Accounts" — should rank above "Journal Entries" (no prefix).
    const fixture = createComponent();
    setQuery(fixture, 'chart');
    const results = fixture.componentInstance.results();
    expect(results[0].label).toBe('Chart of Accounts');
  });

  // ── Keyboard navigation ───────────────────────────────────────────────────

  it('ArrowDown increments activeIndex', () => {
    const fixture = createComponent();
    const comp = fixture.componentInstance;
    setQuery(fixture, 'sales');
    expect(comp.activeIndex()).toBe(0);

    const inputEl: HTMLInputElement = fixture.nativeElement.querySelector('input')!;
    inputEl.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }));
    fixture.detectChanges();
    expect(comp.activeIndex()).toBe(1);
  });

  it('ArrowUp decrements activeIndex but clamps at 0', () => {
    const fixture = createComponent();
    const comp = fixture.componentInstance;
    setQuery(fixture, 'sales');
    comp.activeIndex.set(1);

    const inputEl: HTMLInputElement = fixture.nativeElement.querySelector('input')!;
    inputEl.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowUp', bubbles: true }));
    fixture.detectChanges();
    expect(comp.activeIndex()).toBe(0);

    // Press ArrowUp again — should stay at 0.
    inputEl.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowUp', bubbles: true }));
    fixture.detectChanges();
    expect(comp.activeIndex()).toBe(0);
  });

  it('ArrowDown clamps at last result index', () => {
    const fixture = createComponent();
    const comp = fixture.componentInstance;
    setQuery(fixture, 'sales');
    const count = comp.results().length;
    comp.activeIndex.set(count - 1);

    const inputEl: HTMLInputElement = fixture.nativeElement.querySelector('input')!;
    inputEl.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }));
    fixture.detectChanges();
    expect(comp.activeIndex()).toBe(count - 1);
  });

  // ── Enter navigation ──────────────────────────────────────────────────────

  it('Enter navigates to the active result via Router.navigateByUrl', async () => {
    const fixture = createComponent();
    const comp = fixture.componentInstance;
    const router = TestBed.inject(Router);

    setQuery(fixture, 'invoice');
    comp.activeIndex.set(0);
    fixture.detectChanges();

    const inputEl: HTMLInputElement = fixture.nativeElement.querySelector('input')!;
    inputEl.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    fixture.detectChanges();

    expect(router.navigateByUrl).toHaveBeenCalledWith('/admin/sales-invoices');
  });

  it('Enter resets query and closes the dropdown after navigation', async () => {
    const fixture = createComponent();
    const comp = fixture.componentInstance;
    setQuery(fixture, 'invoice');
    comp.activeIndex.set(0);

    const inputEl: HTMLInputElement = fixture.nativeElement.querySelector('input')!;
    inputEl.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    fixture.detectChanges();

    expect(comp.query()).toBe('');
    expect(comp.open()).toBe(false);
  });

  // ── Escape ────────────────────────────────────────────────────────────────

  it('Escape clears the query and closes the dropdown', () => {
    const fixture = createComponent();
    const comp = fixture.componentInstance;
    setQuery(fixture, 'sales');
    expect(comp.open()).toBe(true);

    const inputEl: HTMLInputElement = fixture.nativeElement.querySelector('input')!;
    const escEvent = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true });
    inputEl.dispatchEvent(escEvent);
    fixture.detectChanges();

    expect(comp.query()).toBe('');
    expect(comp.open()).toBe(false);
  });

  it('Escape stops propagation when the palette is open', () => {
    const fixture = createComponent();
    setQuery(fixture, 'sales');

    const inputEl: HTMLInputElement = fixture.nativeElement.querySelector('input')!;
    const escEvent = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true });
    const stopSpy = vi.spyOn(escEvent, 'stopPropagation');
    inputEl.dispatchEvent(escEvent);

    expect(stopSpy).toHaveBeenCalled();
  });

  it('Escape does NOT stop propagation when the palette is already closed', () => {
    const fixture = createComponent();
    const comp = fixture.componentInstance;
    comp.open.set(false);
    fixture.detectChanges();

    const inputEl: HTMLInputElement = fixture.nativeElement.querySelector('input')!;
    const escEvent = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true });
    const stopSpy = vi.spyOn(escEvent, 'stopPropagation');
    inputEl.dispatchEvent(escEvent);

    expect(stopSpy).not.toHaveBeenCalled();
  });

  // ── ARIA attributes ───────────────────────────────────────────────────────

  it('sets aria-expanded=false when dropdown is closed', () => {
    const fixture = createComponent();
    const inputEl: HTMLInputElement = fixture.nativeElement.querySelector('input')!;
    expect(inputEl.getAttribute('aria-expanded')).toBe('false');
  });

  it('sets aria-expanded=true when results are shown', () => {
    const fixture = createComponent();
    setQuery(fixture, 'sales');
    const inputEl: HTMLInputElement = fixture.nativeElement.querySelector('input')!;
    expect(inputEl.getAttribute('aria-expanded')).toBe('true');
  });

  it('sets aria-controls when dropdown is open', () => {
    const fixture = createComponent();
    setQuery(fixture, 'sales');
    const inputEl: HTMLInputElement = fixture.nativeElement.querySelector('input')!;
    expect(inputEl.getAttribute('aria-controls')).toBe('nav-search-listbox');
  });

  it('clears aria-controls when dropdown is closed', () => {
    const fixture = createComponent();
    const inputEl: HTMLInputElement = fixture.nativeElement.querySelector('input')!;
    expect(inputEl.getAttribute('aria-controls')).toBeNull();
  });

  it('renders listbox with correct role when results exist', () => {
    const fixture = createComponent();
    setQuery(fixture, 'sales');
    const listbox = fixture.nativeElement.querySelector('[role="listbox"]');
    expect(listbox).not.toBeNull();
  });

  it('does not render listbox when query is empty', () => {
    const fixture = createComponent();
    const listbox = fixture.nativeElement.querySelector('[role="listbox"]');
    expect(listbox).toBeNull();
  });

  it('renders role=status for no-match state (not inside listbox)', () => {
    const fixture = createComponent();
    setQuery(fixture, 'zzznomatch');
    const status = fixture.nativeElement.querySelector('[role="status"]');
    expect(status).not.toBeNull();
    // Must NOT be inside a listbox.
    const listbox = fixture.nativeElement.querySelector('[role="listbox"]');
    expect(listbox).toBeNull();
  });

  // ── Accessibility (axe) ───────────────────────────────────────────────────

  it('has no axe violations in default (closed) state', async () => {
    const fixture = createComponent();
    await assertA11y(fixture);
  }, 15_000);

  it('has no axe violations when results are shown', async () => {
    const fixture = createComponent();
    setQuery(fixture, 'sales');
    await assertA11y(fixture);
  }, 15_000);

  it('has no axe violations in no-match state', async () => {
    const fixture = createComponent();
    setQuery(fixture, 'zzznomatch');
    await assertA11y(fixture);
  }, 15_000);
});
