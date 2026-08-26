import { describe, it, expect, afterEach, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { FormsModule } from '@angular/forms';
import { Observable, of, throwError } from 'rxjs';
import { Component } from '@angular/core';

import { UidOption, UidPickerComponent, UidSearchFn } from './uid-picker.component';

// ── Helpers ──────────────────────────────────────────────────────────────────

/** The seed page a screen preloads — deliberately small so "beyond the seed" is easy to express. */
const SEED: UidOption[] = [
  { uid: 'P1', label: 'Sunflower Oil 5L', hint: 'OIL-5L' },
  { uid: 'P2', label: 'Bar Soap 800g', hint: 'SOAP-800' },
];

/** A product the seed never contained — the whole point of server search. */
const BEYOND_SEED: UidOption = { uid: 'P900', label: 'Maize Flour 25kg', hint: 'FLOUR-25' };

@Component({
  standalone: true,
  imports: [FormsModule, UidPickerComponent],
  template: `
    <label for="testPicker">Product</label>
    <app-uid-picker id="testPicker"
        [(ngModel)]="uid"
        [options]="options"
        [search]="search"
        [searchThreshold]="searchThreshold"
        placeholder="Select product" />
  `,
})
class TestHostComponent {
  uid = '';
  options: UidOption[] = SEED;
  search: UidSearchFn | undefined = undefined;
  searchThreshold = 12;
}

function setup(patch: Partial<TestHostComponent> = {}) {
  TestBed.configureTestingModule({ imports: [TestHostComponent] });
  const fixture = TestBed.createComponent(TestHostComponent);
  Object.assign(fixture.componentInstance, patch);
  fixture.detectChanges();
  return fixture;
}

const filterBox = (fixture: ReturnType<typeof setup>): HTMLInputElement | null =>
  fixture.nativeElement.querySelector('input[type="text"]');

const selectEl = (fixture: ReturnType<typeof setup>): HTMLSelectElement | null =>
  fixture.nativeElement.querySelector('select');

/** CLIENT mode: the <select>'s rows, minus the leading placeholder. */
const optionLabels = (fixture: ReturnType<typeof setup>): string[] =>
  Array.from(fixture.nativeElement.querySelectorAll('option') as NodeListOf<HTMLOptionElement>)
    .map((o) => o.textContent?.trim() ?? '')
    .slice(1);

/** SERVER mode: the combobox's result rows. */
const resultLabels = (fixture: ReturnType<typeof setup>): string[] =>
  Array.from(
    fixture.nativeElement.querySelectorAll('[role="option"]') as NodeListOf<HTMLElement>,
  ).map((o) => (o.textContent ?? '').replace(/\s+/g, ' ').trim());

/** The committed choice shown under the box, if any. */
const committed = (fixture: ReturnType<typeof setup>): string | null => {
  const el = fixture.nativeElement.querySelector('.bi-check-circle-fill')?.nextElementSibling;
  return el ? (el.textContent ?? '').trim() : null;
};

/** Type into the box and let the debounce elapse. Focus first: the list opens on focus. */
async function type(fixture: ReturnType<typeof setup>, text: string): Promise<void> {
  const box = filterBox(fixture);
  if (!box) throw new Error('search box not rendered');
  box.dispatchEvent(new Event('focus'));
  box.value = text;
  box.dispatchEvent(new Event('input'));
  fixture.detectChanges();
  await vi.advanceTimersByTimeAsync(300);
  fixture.detectChanges();
}

/** Click a result row. mousedown, because that is what the component listens for. */
function clickResult(fixture: ReturnType<typeof setup>, index: number): void {
  const rows = fixture.nativeElement.querySelectorAll('[role="option"]') as NodeListOf<HTMLElement>;
  rows[index].dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true }));
  fixture.detectChanges();
}

function pressKey(fixture: ReturnType<typeof setup>, key: string): KeyboardEvent {
  const box = filterBox(fixture)!;
  const ev = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true });
  box.dispatchEvent(ev);
  fixture.detectChanges();
  return ev;
}

// ── Specs ─────────────────────────────────────────────────────────────────────

describe('UidPickerComponent', () => {
  afterEach(() => {
    vi.useRealTimers();
    TestBed.resetTestingModule();
  });

  describe('client mode (no [search])', () => {
    it('hides the filter box while the options sit below the threshold', () => {
      expect(filterBox(setup({ searchThreshold: 12 }))).toBeNull();
    });

    it('shows the filter box once the options pass the threshold', () => {
      expect(filterBox(setup({ searchThreshold: 1 }))).not.toBeNull();
    });

    it('filters the given options in memory, matching label or hint', async () => {
      vi.useFakeTimers();
      const fixture = setup({ searchThreshold: 1 });

      await type(fixture, 'soap');
      expect(optionLabels(fixture)).toEqual(['Bar Soap 800g (SOAP-800)']);

      await type(fixture, 'OIL-5L');
      expect(optionLabels(fixture)).toEqual(['Sunflower Oil 5L (OIL-5L)']);
    });
  });

  describe('server mode ([search] set) — a combobox, not a dropdown', () => {
    it('renders no <select> at all: results belong in the list, not a second control', async () => {
      vi.useFakeTimers();
      const fixture = setup({ search: () => of([BEYOND_SEED]), searchThreshold: 999 });

      expect(filterBox(fixture)).not.toBeNull();
      expect(selectEl(fixture)).toBeNull();

      await type(fixture, 'maize');
      expect(selectEl(fixture)).toBeNull();
      expect(resultLabels(fixture)).toEqual(['Maize Flour 25kg (FLOUR-25)']);
    });

    it('shows no list until something is typed', () => {
      const fixture = setup({ search: () => of([BEYOND_SEED]) });
      expect(resultLabels(fixture)).toEqual([]);
    });

    it('reaches a product that is not in the seed — the defect this mode exists to fix', async () => {
      vi.useFakeTimers();
      const search = vi.fn((): Observable<readonly UidOption[]> => of([BEYOND_SEED]));
      const fixture = setup({ search });

      // In-memory filtering could never surface this row: the seed does not contain it.
      expect(SEED.some((o) => o.uid === BEYOND_SEED.uid)).toBe(false);

      await type(fixture, 'maize');

      expect(search).toHaveBeenCalledWith('maize');
      expect(resultLabels(fixture)).toEqual(['Maize Flour 25kg (FLOUR-25)']);
    });

    it('debounces keystrokes into a single query', async () => {
      vi.useFakeTimers();
      const search = vi.fn((): Observable<readonly UidOption[]> => of([BEYOND_SEED]));
      const fixture = setup({ search });
      const box = filterBox(fixture)!;

      for (const text of ['m', 'ma', 'mai']) {
        box.value = text;
        box.dispatchEvent(new Event('input'));
        fixture.detectChanges();
        await vi.advanceTimersByTimeAsync(50);
      }
      await vi.advanceTimersByTimeAsync(300);

      expect(search).toHaveBeenCalledTimes(1);
      expect(search).toHaveBeenCalledWith('mai');
    });

    it('commits a clicked result, closes the list and states the choice', async () => {
      vi.useFakeTimers();
      const fixture = setup({ search: () => of([BEYOND_SEED]) });

      await type(fixture, 'maize');
      clickResult(fixture, 0);

      expect(fixture.componentInstance.uid).toBe(BEYOND_SEED.uid);
      expect(resultLabels(fixture)).toEqual([]); // list closed
      expect(filterBox(fixture)!.value).toBe(''); // box cleared, ready for the next search
      expect(committed(fixture)).toBe('Maize Flour 25kg (FLOUR-25)');
    });

    it('picks with the keyboard and does not let Enter submit the form', async () => {
      vi.useFakeTimers();
      const fixture = setup({ search: () => of([BEYOND_SEED]) });

      await type(fixture, 'maize');
      pressKey(fixture, 'ArrowDown');
      const enter = pressKey(fixture, 'Enter');

      expect(enter.defaultPrevented).toBe(true);
      expect(fixture.componentInstance.uid).toBe(BEYOND_SEED.uid);
    });

    it('Enter with nothing highlighted picks nothing', async () => {
      vi.useFakeTimers();
      const fixture = setup({ search: () => of([BEYOND_SEED]) });

      await type(fixture, 'maize');
      pressKey(fixture, 'Enter'); // no ArrowDown first

      expect(fixture.componentInstance.uid).toBe('');
    });

    it('Escape closes the list without clearing the committed choice', async () => {
      vi.useFakeTimers();
      const fixture = setup({ search: () => of([BEYOND_SEED]) });

      await type(fixture, 'maize');
      clickResult(fixture, 0);
      await type(fixture, 'something else');
      pressKey(fixture, 'Escape');

      expect(resultLabels(fixture)).toEqual([]);
      expect(fixture.componentInstance.uid).toBe(BEYOND_SEED.uid);
    });

    it('keeps the committed choice when a later search does not return it', async () => {
      vi.useFakeTimers();
      const fixture = setup({ search: () => of([BEYOND_SEED]) });

      await type(fixture, 'maize');
      clickResult(fixture, 0);
      expect(fixture.componentInstance.uid).toBe(BEYOND_SEED.uid);

      // A different search that does not return the picked product must not silently unset it,
      // and must not push it into the middle of unrelated results either.
      fixture.componentInstance.search = () => of([SEED[0]]);
      fixture.detectChanges();
      await type(fixture, 'oil');

      expect(fixture.componentInstance.uid).toBe(BEYOND_SEED.uid);
      expect(committed(fixture)).toBe('Maize Flour 25kg (FLOUR-25)');
      expect(resultLabels(fixture)).toEqual(['Sunflower Oil 5L (OIL-5L)']);
    });

    it('clear() unsets the bound value', async () => {
      vi.useFakeTimers();
      const fixture = setup({ search: () => of([BEYOND_SEED]) });

      await type(fixture, 'maize');
      clickResult(fixture, 0);
      expect(fixture.componentInstance.uid).toBe(BEYOND_SEED.uid);

      fixture.nativeElement.querySelector('button')!.click();
      fixture.detectChanges();

      expect(fixture.componentInstance.uid).toBe('');
      expect(committed(fixture)).toBeNull();
    });

    it('degrades a failing lookup to "no matches" and stays usable afterwards', async () => {
      vi.useFakeTimers();
      let fail = true;
      const search = vi.fn(
        (): Observable<readonly UidOption[]> =>
          fail ? throwError(() => new Error('500')) : of([BEYOND_SEED]),
      );
      const fixture = setup({ search });

      await type(fixture, 'maize');
      expect(resultLabels(fixture)).toEqual([]);
      expect(fixture.nativeElement.textContent).toContain('No matches');

      // The stream survived the error — the next keystroke still queries.
      fail = false;
      await type(fixture, 'maize flour');
      expect(resultLabels(fixture)).toEqual(['Maize Flour 25kg (FLOUR-25)']);
    });

    it('wires the combobox ARIA contract to the input', async () => {
      vi.useFakeTimers();
      const fixture = setup({ search: () => of([BEYOND_SEED]) });
      const box = filterBox(fixture)!;

      // The consumer's id must land on the real focusable control, not the host.
      expect(box.getAttribute('id')).toBe('testPicker');
      expect(fixture.nativeElement.querySelector('app-uid-picker')?.getAttribute('id')).toBeNull();
      expect(box.getAttribute('role')).toBe('combobox');
      expect(box.getAttribute('aria-expanded')).toBe('false');

      await type(fixture, 'maize');
      expect(box.getAttribute('aria-expanded')).toBe('true');

      const listbox = fixture.nativeElement.querySelector('[role="listbox"]');
      expect(box.getAttribute('aria-controls')).toBe(listbox.getAttribute('id'));

      pressKey(fixture, 'ArrowDown');
      const active = box.getAttribute('aria-activedescendant');
      expect(active).toBe(
        fixture.nativeElement.querySelector('[role="option"]').getAttribute('id'),
      );
    });
  });
});
