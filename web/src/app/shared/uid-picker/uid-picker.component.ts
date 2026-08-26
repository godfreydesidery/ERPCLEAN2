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
  // <app-uid-picker> — the consumer-supplied values must resolve to the inner <select>
  // (the real form control) so that <label for="X"> / aria-labelledby / aria-label associate
  // correctly (WCAG 2.1 AA, SC 1.3.1 / 4.1.2).
  host: { '[attr.id]': 'null', '[attr.aria-labelledby]': 'null', '[attr.aria-label]': 'null' },
  providers: [
    { provide: NG_VALUE_ACCESSOR, useExisting: forwardRef(() => UidPickerComponent), multi: true },
  ],
  template: `
    <div class="position-relative">
      @if (showFilter()) {
        <!-- Filter box: filters by NAME (or code), never accepts a raw uid. In SERVER mode it
             queries the API, so the reachable set is not capped by what was preloaded. -->
        <input type="text" class="form-control form-control-sm mb-1"
               [attr.id]="id() ? id() + '-filter' : null"
               [ngModel]="query()" (ngModelChange)="onQuery($event)"
               [attr.aria-label]="(ariaLabel() || placeholder() || 'Search') + (search() ? ' — search by name or code' : ' — filter by name')"
               [placeholder]="search() ? 'Type to search by name or code…' : 'Type to filter by name…'"
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

    // Keep the current selection reachable even when it falls outside the visible result set.
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
    if (!this.search()) return;
    if (!q.trim()) {
      // Clearing the box returns to the seed at once — no debounce, nothing in flight matters.
      this.remoteResults.set([]);
      this.searching.set(false);
    }
    this.queryInput.next(q);
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
