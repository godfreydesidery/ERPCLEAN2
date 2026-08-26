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

const optionLabels = (fixture: ReturnType<typeof setup>): string[] =>
  Array.from(fixture.nativeElement.querySelectorAll('option') as NodeListOf<HTMLOptionElement>)
    .map((o) => o.textContent?.trim() ?? '')
    // drop the leading placeholder row
    .slice(1);

/** Type into the filter box and let the debounce elapse. */
async function type(fixture: ReturnType<typeof setup>, text: string): Promise<void> {
  const box = filterBox(fixture);
  if (!box) throw new Error('filter box not rendered');
  box.value = text;
  box.dispatchEvent(new Event('input'));
  fixture.detectChanges();
  await vi.advanceTimersByTimeAsync(300);
  fixture.detectChanges();
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

  describe('server mode ([search] set)', () => {
    it('always shows the filter box, whatever the seed size', () => {
      const fixture = setup({ search: () => of([]), searchThreshold: 999 });
      expect(filterBox(fixture)).not.toBeNull();
    });

    it('reaches a product that is not in the seed — the defect this mode exists to fix', async () => {
      vi.useFakeTimers();
      const search = vi.fn((): Observable<readonly UidOption[]> => of([BEYOND_SEED]));
      const fixture = setup({ search });

      // In-memory filtering could never surface this row: the seed does not contain it.
      expect(SEED.some((o) => o.uid === BEYOND_SEED.uid)).toBe(false);

      await type(fixture, 'maize');

      expect(search).toHaveBeenCalledWith('maize');
      expect(optionLabels(fixture)).toEqual(['Maize Flour 25kg (FLOUR-25)']);
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

    it('returns to the seed when the box is cleared', async () => {
      vi.useFakeTimers();
      const fixture = setup({ search: () => of([BEYOND_SEED]) });

      await type(fixture, 'maize');
      expect(optionLabels(fixture)).toEqual(['Maize Flour 25kg (FLOUR-25)']);

      await type(fixture, '');
      expect(optionLabels(fixture)).toEqual([
        'Sunflower Oil 5L (OIL-5L)',
        'Bar Soap 800g (SOAP-800)',
      ]);
    });

    it('keeps the chosen option selectable after a later query drops it', async () => {
      vi.useFakeTimers();
      const fixture = setup({ search: () => of([BEYOND_SEED]) });

      await type(fixture, 'maize');
      const select: HTMLSelectElement = fixture.nativeElement.querySelector('select');
      select.value = BEYOND_SEED.uid;
      select.dispatchEvent(new Event('change'));
      fixture.detectChanges();
      expect(fixture.componentInstance.uid).toBe(BEYOND_SEED.uid);

      // A different search that does not return the picked product must not silently unset it.
      fixture.componentInstance.search = () => of([]);
      fixture.detectChanges();
      await type(fixture, 'something else');

      expect(fixture.componentInstance.uid).toBe(BEYOND_SEED.uid);
      expect(optionLabels(fixture)).toContain('Maize Flour 25kg (FLOUR-25)');
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
      expect(optionLabels(fixture)).toEqual([]);
      expect(fixture.nativeElement.textContent).toContain('No matches');

      // The stream survived the error — the next keystroke still queries.
      fail = false;
      await type(fixture, 'maize flour');
      expect(optionLabels(fixture)).toEqual(['Maize Flour 25kg (FLOUR-25)']);
    });
  });
});
