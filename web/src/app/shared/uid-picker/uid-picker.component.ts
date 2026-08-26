import { ChangeDetectionStrategy, Component, computed, forwardRef, input, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ControlValueAccessor, FormsModule, NG_VALUE_ACCESSOR } from '@angular/forms';
import {
  EMPTY,
  Observable,
  Subject,
  catchError,
  debounceTime,
  distinctUntilChanged,
  of,
  switchMap,
} from 'rxjs';

/** One selectable option: the human-meaningful label the user sees + the uid stored under the hood. */
export interface UidOption {
  readonly uid: string;
  readonly label: string;
  /** Optional secondary text (e.g. a code or status) shown muted next to the label. */
  readonly hint?: string;
}

/**
 * Server-side lookup for [search]. Given the typed query, return the matching options.
 * Errors are swallowed by the picker (shown as "no matches"), so the function may fail freely.
 */
export type UidSearchFn = (query: string) => Observable<readonly UidOption[]>;

/**
 * Shared resource picker (rules-compliance, INVARIANT #3): a uid is a machine identifier and must
 * NEVER be hand-typed by a user. This component lets the operator choose a resource by its
 * human-meaningful NAME from a searchable list; the bound value is the resource's uid. It is a
 * ControlValueAccessor so it drops into any `[(ngModel)]="...Uid"` exactly where a free-text uid
 * <input> used to be — replacing the violation with a picker, no parent-logic change.
 *
 *   <app-uid-picker [(ngModel)]="fCustomerUid" [options]="customerOptions()"
 *                   placeholder="Select customer" [required]="true" />
 *
 * It renders a native <select> (accessible, keyboard-friendly) with a filter box above it.
 *
 * Two filtering modes:
 *
 *  - CLIENT (default) — [options] is the whole set and the filter box narrows it in memory.
 *    Correct only when the caller really did load every row: branches, locations, price lists.
 *
 *  - SERVER — the caller also passes [search], a function that queries the API. [options] is then
 *    only a SEED (the first page, shown before anything is typed) and the typed query goes to the
 *    server, so rows beyond that seed are reachable. Use this for any set that can outgrow one
 *    page — products above all. Without it, a catalogue of 900 products behind a 200-row seed
 *    leaves 700 products invisible AND unfindable, because an in-memory filter can only narrow
 *    what was already fetched (the client-reported defect, 2026-08-26).
 *
 * In SERVER mode the current selection is pinned into the list even when it falls outside the
 * latest results, so a choice made under one query survives the next one.
 */
@Component({
  selector: 'app-uid-picker',
  standalone: true,
  imports: [FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  // Strip id / aria-labelledby / aria-label from the host element so they never linger on
  // <app-uid-picker> — the consumer-supplied values must resolve to the inner control
  // (the <select> in CLIENT mode, the search <input> in SERVER mode) so that
  // <label for="X"> / aria-labelledby / aria-label associate correctly
  // (WCAG 2.1 AA, SC 1.3.1 / 4.1.2).
  host: { '[attr.id]': 'null', '[attr.aria-labelledby]': 'null', '[attr.aria-label]': 'null' },
  providers: [
    { provide: NG_VALUE_ACCESSOR, useExisting: forwardRef(() => UidPickerComponent), multi: true },
  ],
  template: `
    <div class="position-relative">
      @if (search()) {
        <!-- SERVER mode: a combobox. There is deliberately NO <select> here — the whole point
             of this mode is that the set is bigger than any list worth rendering, so what you
             type IS the list. A dropdown alongside a search box asks the operator to look in
             two places for one answer. -->
        <input type="text" class="form-control form-control-sm" role="combobox"
               [attr.id]="id() ?? null"
               [ngModel]="query()" (ngModelChange)="onQuery($event)"
               (keydown)="onKeydown($event)"
               (blur)="onBlur()"
               (focus)="onFocus()"
               autocomplete="off"
               [placeholder]="placeholder() || 'Type to search…'"
               [disabled]="disabledState()"
               [attr.aria-labelledby]="ariaLabelledby() ?? null"
               [attr.aria-label]="ariaLabel() ?? ((id() || ariaLabelledby()) ? null : (placeholder() || 'Search'))"
               [attr.aria-required]="required() ? 'true' : null"
               aria-autocomplete="list"
               [attr.aria-expanded]="isOpen()"
               [attr.aria-controls]="listboxId"
               [attr.aria-activedescendant]="activeOptionId()" />

        @if (isOpen()) {
          <ul class="list-group position-absolute shadow-sm mt-1 w-100" role="listbox"
              [id]="listboxId"
              [attr.aria-label]="(ariaLabel() || placeholder() || 'Results')"
              style="z-index:1050;max-height:240px;overflow-y:auto;">
            @for (o of filtered(); track o.uid; let i = $index) {
              <li class="list-group-item list-group-item-action small py-2"
                  role="option"
                  [id]="optionId(i)"
                  [attr.aria-selected]="o.uid === value()"
                  [class.active]="i === activeIndex()"
                  style="cursor:pointer"
                  (mousedown)="onOptionMousedown($event, o)">
                {{ o.label }}@if (o.hint) { <span class="text-muted"> ({{ o.hint }})</span> }
              </li>
            }
          </ul>
        }

        @if (selectedLabel(); as chosen) {
          <div class="d-flex align-items-center gap-1 mt-1">
            <i class="bi bi-check-circle-fill text-success small" aria-hidden="true"></i>
            <span class="small text-muted">{{ chosen }}</span>
            @if (!disabledState()) {
              <button type="button" class="btn btn-link btn-sm p-0 ms-1 small"
                      (click)="clear()"
                      [attr.aria-label]="'Clear ' + (ariaLabel() || placeholder() || 'selection')">clear</button>
            }
          </div>
        }
      } @else {
        <!-- CLIENT mode: the caller loaded the whole set, so a native <select> is the most
             accessible and familiar control for it. Unchanged. -->
        @if (showFilter()) {
          <input type="text" class="form-control form-control-sm mb-1"
                 [attr.id]="id() ? id() + '-filter' : null"
                 [ngModel]="query()" (ngModelChange)="onQuery($event)"
                 [attr.aria-label]="(ariaLabel() || placeholder() || 'Search') + ' — filter by name'"
                 placeholder="Type to filter by name…"
                 [disabled]="disabledState()" />
        }
        <select class="form-select form-select-sm"
                [attr.id]="id() ?? null"
                [ngModel]="value()"
                (ngModelChange)="onPick($event)"
                [disabled]="disabledState()"
                [required]="required()"
                [attr.aria-labelledby]="ariaLabelledby() ?? null"
                [attr.aria-label]="ariaLabel() ?? ((id() || ariaLabelledby()) ? null : (placeholder() || 'Select a resource'))">
          <option value="">{{ placeholder() || '— select —' }}</option>
          @for (o of filtered(); track o.uid) {
            <option [value]="o.uid">{{ o.label }}{{ o.hint ? ' (' + o.hint + ')' : '' }}</option>
          }
        </select>
      }

      @if (statusText(); as status) {
        <div class="form-text small" aria-live="polite">{{ status }}</div>
      }
    </div>
  `,
})
export class UidPickerComponent implements ControlValueAccessor {
  /**
   * The selectable resources (label shown, uid stored). In SERVER mode ([search] set) this is only
   * the seed shown before anything is typed, not the whole set.
   */
  readonly options = input<readonly UidOption[]>([]);
  readonly placeholder = input<string>('');
  readonly required = input<boolean>(false);
  /** Above this many options, show the filter box. Ignored in SERVER mode (always shown). */
  readonly searchThreshold = input<number>(12);
  /**
   * Optional server-side lookup. When set, the typed query goes here (debounced) instead of
   * filtering [options] in memory — the only way to reach rows beyond the seed page.
   */
  readonly search = input<UidSearchFn | undefined>(undefined);
  /**
   * Consumer-supplied id forwarded to the inner <select> so that a sibling
   * <label for="X"> associates with the real focusable control, not the host.
   * The host id attribute is nulled out via host:{} above.
   */
  readonly id = input<string | undefined>(undefined);
  /**
   * Consumer-supplied aria-labelledby forwarded to the inner <select>.
   * Bind via the input: [ariaLabelledby]="'someId'" on <app-uid-picker> (NOT a bare
   * aria-labelledby="..." attribute — that lands on the host and is nulled out via host:{}
   * above, so it would never reach the <select>).
   * aria-labelledby takes precedence over aria-label per ARIA spec; the existing
   * aria-label remains as fallback when this is not set.
   */
  readonly ariaLabelledby = input<string | undefined>(undefined);
  /**
   * Consumer-supplied explicit aria-label for the inner <select>, for pickers with no visible
   * label (e.g. table line-item cells). Bind via [ariaLabel]="'…'". Takes precedence over the
   * placeholder fallback; a bare aria-label="…" on the host is nulled out (use the input).
   */
  readonly ariaLabel = input<string | undefined>(undefined);

  protected readonly value = signal<string>('');
  protected readonly query = signal<string>('');
  protected readonly disabledState = signal<boolean>(false);

  /** Latest server results (SERVER mode only). */
  private readonly remoteResults = signal<readonly UidOption[]>([]);
  protected readonly searching = signal<boolean>(false);
  /**
   * The option the user actually chose, kept so a selection survives a later query that does not
   * return it — otherwise the <select> would silently fall back to the blank option.
   */
  private readonly pinned = signal<UidOption | null>(null);

  private readonly queryInput = new Subject<string>();

  protected readonly showFilter = computed(
    () => !!this.search() || this.options().length > this.searchThreshold(),
  );

  /** In SERVER mode an empty box means "nothing searched yet" — show the seed. */
  private readonly remoteActive = computed(() => !!this.search() && this.query().trim().length > 0);

  protected readonly filtered = computed<readonly UidOption[]>(() => {
    const q = this.query().trim().toLowerCase();
    const list = this.remoteActive()
      ? this.remoteResults()
      : !this.search() && q
        ? this.options().filter(
            (o) => o.label.toLowerCase().includes(q) || (o.hint ?? '').toLowerCase().includes(q),
          )
        : this.options();

    // CLIENT mode only: the <select> can render nothing but its own options, so a selection
    // outside the visible set has to be pinned in or the control would blank itself. SERVER
    // mode has no <select> — it states the committed choice underneath instead — so pinning
    // there would push an unrelated row into the middle of a set of search results.
    if (this.search()) return list;
    const selected = this.value();
    if (!selected || list.some((o) => o.uid === selected)) return list;
    const held = this.pinned();
    return held && held.uid === selected ? [held, ...list] : list;
  });

  protected readonly statusText = computed<string | null>(() => {
    if (!this.search() || !this.query().trim()) return null;
    if (this.searching()) return 'Searching…';
    return this.filtered().length === 0 ? 'No matches. Try a different name or code.' : null;
  });

  // ── Combobox state (SERVER mode only) ─────────────────────────────────────
  private static instances = 0;
  /** Ids must be unique per instance: line-item tables render many pickers on one page. */
  protected readonly listboxId = `uid-picker-list-${++UidPickerComponent.instances}`;

  private readonly focused = signal(false);
  /** Keyboard cursor. -1 = nothing highlighted, so Enter does not pick a row by accident. */
  protected readonly activeIndex = signal(-1);

  /** The list is shown only while the box has focus and there is something to show. */
  protected readonly isOpen = computed(
    () => this.focused() && this.query().trim().length > 0 && this.filtered().length > 0,
  );

  protected optionId(index: number): string {
    return `${this.listboxId}-opt-${index}`;
  }

  protected readonly activeOptionId = computed<string | null>(() => {
    const i = this.activeIndex();
    return this.isOpen() && i >= 0 ? this.optionId(i) : null;
  });

  /**
   * What the picker has actually committed, shown under the box. The search text is not proof
   * of a selection — an operator can pick a product and then keep typing — so the committed
   * choice is stated separately and always tells the truth about the bound value.
   */
  protected readonly selectedLabel = computed<string | null>(() => {
    if (!this.value()) return null;
    const held = this.pinned();
    if (held) return held.hint ? `${held.label} (${held.hint})` : held.label;
    const found = this.options().find((o) => o.uid === this.value());
    return found ? (found.hint ? `${found.label} (${found.hint})` : found.label) : null;
  });

  constructor() {
    this.queryInput
      .pipe(
        debounceTime(250),
        distinctUntilChanged(),
        switchMap((q) => {
          const lookup = this.search();
          if (!lookup || !q.trim()) {
            this.remoteResults.set([]);
            this.searching.set(false);
            return EMPTY;
          }
          this.searching.set(true);
          // switchMap drops in-flight responses, so a slow earlier query can never overwrite a
          // later one. Errors degrade to "no matches" instead of killing the stream.
          return lookup(q.trim()).pipe(catchError(() => of<readonly UidOption[]>([])));
        }),
        takeUntilDestroyed(),
      )
      .subscribe((rows) => {
        this.remoteResults.set(rows);
        this.searching.set(false);
      });
  }

  protected onQuery(q: string): void {
    this.query.set(q);
    // Typing invalidates the keyboard cursor: the row under it is about to be a different one.
    this.activeIndex.set(-1);
    if (!this.search()) return;
    if (!q.trim()) {
      // Clearing the box returns to the seed at once — no debounce, nothing in flight matters.
      this.remoteResults.set([]);
      this.searching.set(false);
    }
    this.queryInput.next(q);
  }

  protected onFocus(): void {
    this.focused.set(true);
  }

  protected onBlur(): void {
    this.focused.set(false);
    this.activeIndex.set(-1);
    this.onTouched();
  }

  /**
   * mousedown, not click: click fires after blur, and blur closes the list — so by the time a
   * click arrived the row it was aimed at would no longer exist. preventDefault keeps focus in
   * the box so the operator can carry on typing.
   */
  protected onOptionMousedown(event: Event, option: UidOption): void {
    event.preventDefault();
    this.commit(option);
  }

  protected onKeydown(event: KeyboardEvent): void {
    const rows = this.filtered();
    switch (event.key) {
      case 'ArrowDown':
        event.preventDefault();
        this.focused.set(true);
        if (rows.length) this.activeIndex.set((this.activeIndex() + 1) % rows.length);
        break;
      case 'ArrowUp':
        event.preventDefault();
        if (rows.length) {
          const next = this.activeIndex() <= 0 ? rows.length - 1 : this.activeIndex() - 1;
          this.activeIndex.set(next);
        }
        break;
      case 'Enter': {
        // Always swallowed while the list is open, or Enter would submit the surrounding form
        // instead of choosing the highlighted row.
        if (!this.isOpen()) return;
        event.preventDefault();
        const row = rows[this.activeIndex()];
        if (row) this.commit(row);
        break;
      }
      case 'Escape':
        // Close without losing focus, so Escape dismisses the list rather than the field.
        this.query.set('');
        this.remoteResults.set([]);
        this.activeIndex.set(-1);
        break;
      default:
        break;
    }
  }

  /**
   * Commit a choice: the search box is emptied so the list closes and the next search starts
   * clean, and the committed row is shown beneath instead.
   */
  private commit(option: UidOption): void {
    this.value.set(option.uid);
    this.pinned.set(option);
    this.query.set('');
    this.remoteResults.set([]);
    this.activeIndex.set(-1);
    this.onChange(option.uid);
    this.onTouched();
  }

  protected clear(): void {
    this.value.set('');
    this.pinned.set(null);
    this.query.set('');
    this.remoteResults.set([]);
    this.activeIndex.set(-1);
    this.onChange('');
    this.onTouched();
  }

  // ── ControlValueAccessor ──────────────────────────────────────────────────
  private onChange: (v: string) => void = () => {};
  private onTouched: () => void = () => {};

  writeValue(v: string | null): void {
    const uid = v ?? '';
    this.value.set(uid);
    if (!uid) {
      this.pinned.set(null);
      return;
    }
    if (this.pinned()?.uid !== uid) {
      this.pinned.set(this.options().find((o) => o.uid === uid) ?? null);
    }
  }
  registerOnChange(fn: (v: string) => void): void {
    this.onChange = fn;
  }
  registerOnTouched(fn: () => void): void {
    this.onTouched = fn;
  }
  setDisabledState(isDisabled: boolean): void {
    this.disabledState.set(isDisabled);
  }

  protected onPick(uid: string): void {
    this.value.set(uid);
    this.pinned.set(
      uid
        ? (this.filtered().find((o) => o.uid === uid) ??
           this.options().find((o) => o.uid === uid) ??
           null)
        : null,
    );
    this.onChange(uid);
    this.onTouched();
  }
}
