import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { PageMeta } from '../../core/api/api-response.model';
import { PaginatorComponent } from './paginator.component';

// ─── Helper ──────────────────────────────────────────────────────────────────

function makeMeta(page: number, totalPages: number, totalElements?: number): PageMeta {
  return { page, size: 20, totalElements: totalElements ?? totalPages * 20, totalPages, hasNext: page < totalPages - 1 };
}

/**
 * Minimal host so we can bind [meta] and [label] and listen to (pageChange).
 */
@Component({
  standalone: true,
  imports: [PaginatorComponent],
  template: `
    <app-paginator
      [meta]="meta()"
      [label]="label()"
      (pageChange)="onPageChange($event)"
    />
  `,
})
class HostComponent {
  meta = signal<PageMeta | null>(null);
  label = signal('Items');
  emitted: number[] = [];
  onPageChange(p: number) { this.emitted.push(p); }
}

// ─── Specs ───────────────────────────────────────────────────────────────────

describe('PaginatorComponent', () => {
  function setup() {
    TestBed.configureTestingModule({ imports: [HostComponent] });
    const fixture = TestBed.createComponent(HostComponent);
    const host = fixture.componentInstance;
    fixture.detectChanges();
    return { fixture, host, el: fixture.nativeElement as HTMLElement };
  }

  // ── renders nothing when totalPages <= 1 ─────────────────────────────────

  it('renders nothing when meta is null', () => {
    const { el } = setup();
    expect(el.querySelector('nav')).toBeNull();
  });

  it('renders nothing when totalPages is 0', () => {
    const { fixture, host, el } = setup();
    host.meta.set(makeMeta(0, 0));
    fixture.detectChanges();
    expect(el.querySelector('nav')).toBeNull();
  });

  it('renders nothing when totalPages is 1', () => {
    const { fixture, host, el } = setup();
    host.meta.set(makeMeta(0, 1));
    fixture.detectChanges();
    expect(el.querySelector('nav')).toBeNull();
  });

  // ── renders first/prev/next/last + page-number buttons when > 1 ─────────

  it('renders a nav landmark when totalPages > 1', () => {
    const { fixture, host, el } = setup();
    host.meta.set(makeMeta(0, 3));
    fixture.detectChanges();
    expect(el.querySelector('nav')).not.toBeNull();
  });

  it('has aria-label on the nav including the label prop', () => {
    const { fixture, host, el } = setup();
    host.meta.set(makeMeta(0, 3));
    host.label.set('Customers');
    fixture.detectChanges();
    const nav = el.querySelector('nav')!;
    expect(nav.getAttribute('aria-label')).toContain('Customers');
    expect(nav.getAttribute('aria-label')).toContain('pagination');
  });

  it('renders First (double-left), Prev, Next, Last (double-right) buttons', () => {
    const { fixture, host, el } = setup();
    host.meta.set(makeMeta(1, 5));
    fixture.detectChanges();
    const btns = el.querySelectorAll('button');
    const ariaLabels = Array.from(btns).map((b) => b.getAttribute('aria-label'));
    expect(ariaLabels).toContain('First page');
    expect(ariaLabels).toContain('Previous page');
    expect(ariaLabels).toContain('Next page');
    expect(ariaLabels).toContain('Last page');
  });

  it('renders numbered page buttons for each page in the window', () => {
    const { fixture, host, el } = setup();
    // 5 pages, on page 2 (0-based). Window of 2 → pages 0,1,2,3,4 all visible
    host.meta.set(makeMeta(2, 5));
    fixture.detectChanges();
    const allBtns = Array.from(el.querySelectorAll('button'));
    const pageNums = allBtns
      .map((b) => Number(b.textContent?.trim()))
      .filter((n) => !isNaN(n) && n > 0);
    // At minimum pages 1,2,3,4,5 should be present (1-indexed display)
    expect(pageNums).toContain(1);
    expect(pageNums).toContain(3); // current page displayed as 3 (0-based page 2)
    expect(pageNums).toContain(5);
  });

  // ── current page is aria-current + disabled ──────────────────────────────

  it('marks the current page button aria-current="page" and disabled', () => {
    const { fixture, host, el } = setup();
    host.meta.set(makeMeta(2, 5));
    fixture.detectChanges();
    // Current page 2 → displayed as "3"
    const allBtns = Array.from(el.querySelectorAll('button'));
    const currentBtn = allBtns.find(
      (b) => b.getAttribute('aria-current') === 'page',
    );
    expect(currentBtn).not.toBeUndefined();
    expect(currentBtn?.textContent?.trim()).toBe('3');
    expect(currentBtn?.disabled).toBe(true);
  });

  // ── emits the right 0-based index ────────────────────────────────────────

  it('emits 0-based page index when a numbered page button is clicked', () => {
    const { fixture, host, el } = setup();
    host.meta.set(makeMeta(0, 5));
    fixture.detectChanges();
    const allBtns = Array.from(el.querySelectorAll('button'));
    // "5" button → 0-based index 4
    const btn5 = allBtns.find((b) => b.textContent?.trim() === '5');
    expect(btn5).not.toBeUndefined();
    btn5!.click();
    fixture.detectChanges();
    expect(host.emitted).toEqual([4]);
  });

  it('emits next page (current+1) when Next is clicked', () => {
    const { fixture, host, el } = setup();
    host.meta.set(makeMeta(1, 5));
    fixture.detectChanges();
    const nextBtn = Array.from(el.querySelectorAll('button')).find(
      (b) => b.getAttribute('aria-label') === 'Next page',
    );
    nextBtn!.click();
    fixture.detectChanges();
    expect(host.emitted).toEqual([2]);
  });

  it('emits prev page (current-1) when Prev is clicked', () => {
    const { fixture, host, el } = setup();
    host.meta.set(makeMeta(2, 5));
    fixture.detectChanges();
    const prevBtn = Array.from(el.querySelectorAll('button')).find(
      (b) => b.getAttribute('aria-label') === 'Previous page',
    );
    prevBtn!.click();
    fixture.detectChanges();
    expect(host.emitted).toEqual([1]);
  });

  it('emits 0 when First is clicked', () => {
    const { fixture, host, el } = setup();
    host.meta.set(makeMeta(3, 5));
    fixture.detectChanges();
    const firstBtn = Array.from(el.querySelectorAll('button')).find(
      (b) => b.getAttribute('aria-label') === 'First page',
    );
    firstBtn!.click();
    fixture.detectChanges();
    expect(host.emitted).toEqual([0]);
  });

  it('emits last page index when Last is clicked', () => {
    const { fixture, host, el } = setup();
    host.meta.set(makeMeta(0, 5));
    fixture.detectChanges();
    const lastBtn = Array.from(el.querySelectorAll('button')).find(
      (b) => b.getAttribute('aria-label') === 'Last page',
    );
    lastBtn!.click();
    fixture.detectChanges();
    expect(host.emitted).toEqual([4]); // totalPages=5 → last 0-based = 4
  });

  it('does NOT emit when the current page button is clicked (it is disabled)', () => {
    const { fixture, host, el } = setup();
    host.meta.set(makeMeta(2, 5));
    fixture.detectChanges();
    const currentBtn = Array.from(el.querySelectorAll('button')).find(
      (b) => b.getAttribute('aria-current') === 'page',
    );
    currentBtn?.click();
    fixture.detectChanges();
    expect(host.emitted).toHaveLength(0);
  });

  // ── First/Prev disabled on page 0; Next/Last disabled on last page ────────

  it('disables First and Prev buttons when on page 0', () => {
    const { fixture, host, el } = setup();
    host.meta.set(makeMeta(0, 3));
    fixture.detectChanges();
    const firstBtn = Array.from(el.querySelectorAll('button')).find(
      (b) => b.getAttribute('aria-label') === 'First page',
    );
    const prevBtn = Array.from(el.querySelectorAll('button')).find(
      (b) => b.getAttribute('aria-label') === 'Previous page',
    );
    expect(firstBtn?.disabled).toBe(true);
    expect(prevBtn?.disabled).toBe(true);
  });

  it('disables Next and Last buttons when on the last page', () => {
    const { fixture, host, el } = setup();
    host.meta.set(makeMeta(2, 3));
    fixture.detectChanges();
    const nextBtn = Array.from(el.querySelectorAll('button')).find(
      (b) => b.getAttribute('aria-label') === 'Next page',
    );
    const lastBtn = Array.from(el.querySelectorAll('button')).find(
      (b) => b.getAttribute('aria-label') === 'Last page',
    );
    expect(nextBtn?.disabled).toBe(true);
    expect(lastBtn?.disabled).toBe(true);
  });

  // ── Gap / window logic for many pages ────────────────────────────────────

  it('shows a gap ellipsis (…) when there are many pages and the window does not cover all', () => {
    const { fixture, host, el } = setup();
    // 20 pages, on page 0. Window radius=2: shows pages 0,1,2, gap, 19
    host.meta.set(makeMeta(0, 20));
    fixture.detectChanges();
    const spans = Array.from(el.querySelectorAll('span[aria-hidden="true"]'));
    const ellipses = spans.filter((s) => s.textContent?.trim() === '…');
    expect(ellipses.length).toBeGreaterThanOrEqual(1);
  });

  it('does NOT show gaps when all pages fit in the window', () => {
    const { fixture, host, el } = setup();
    // 5 pages, on page 2, window=2 → all pages 0-4 are within ±2 of page 2 (or first/last)
    host.meta.set(makeMeta(2, 5));
    fixture.detectChanges();
    const spans = Array.from(el.querySelectorAll('span[aria-hidden="true"]'));
    const ellipses = spans.filter((s) => s.textContent?.trim() === '…');
    expect(ellipses.length).toBe(0);
  });

  it('shows two gap ellipses when in the middle of many pages', () => {
    const { fixture, host, el } = setup();
    // 20 pages, on page 10: should have gap between 0 and window-start and between window-end and 19
    host.meta.set(makeMeta(10, 20));
    fixture.detectChanges();
    const spans = Array.from(el.querySelectorAll('span[aria-hidden="true"]'));
    const ellipses = spans.filter((s) => s.textContent?.trim() === '…');
    expect(ellipses.length).toBe(2);
  });
});
