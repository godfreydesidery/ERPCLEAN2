import { ChangeDetectionStrategy, Component, computed, forwardRef, input, signal } from '@angular/core';
import { ControlValueAccessor, FormsModule, NG_VALUE_ACCESSOR } from '@angular/forms';

/** One selectable option: the human-meaningful label the user sees + the uid stored under the hood. */
export interface UidOption {
  readonly uid: string;
  readonly label: string;
  /** Optional secondary text (e.g. a code or status) shown muted next to the label. */
  readonly hint?: string;
}

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
 * For small/medium option sets it renders a native <select> (accessible, keyboard-friendly). It also
 * filters on a typed query so it scales to large lists without the operator ever typing a raw uid.
 */
@Component({
  selector: 'app-uid-picker',
  standalone: true,
  imports: [FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [
    { provide: NG_VALUE_ACCESSOR, useExisting: forwardRef(() => UidPickerComponent), multi: true },
  ],
  template: `
    <div class="position-relative">
      @if (options().length > searchThreshold()) {
        <!-- Large list: a filter box (filters by NAME, never accepts a raw uid) + a select. -->
        <input type="text" class="form-control form-control-sm mb-1"
               [ngModel]="query()" (ngModelChange)="query.set($event)"
               [attr.aria-label]="(placeholder() || 'Search') + ' — filter by name'"
               placeholder="Type to filter by name…"
               [disabled]="disabledState()" />
      }
      <select class="form-select form-select-sm"
              [ngModel]="value()"
              (ngModelChange)="onPick($event)"
              [disabled]="disabledState()"
              [required]="required()"
              [attr.aria-label]="placeholder() || 'Select a resource'">
        <option value="">{{ placeholder() || '— select —' }}</option>
        @for (o of filtered(); track o.uid) {
          <option [value]="o.uid">{{ o.label }}{{ o.hint ? ' (' + o.hint + ')' : '' }}</option>
        }
      </select>
    </div>
  `,
})
export class UidPickerComponent implements ControlValueAccessor {
  /** The selectable resources (label shown, uid stored). */
  readonly options = input<readonly UidOption[]>([]);
  readonly placeholder = input<string>('');
  readonly required = input<boolean>(false);
  /** Above this many options, show the filter box. */
  readonly searchThreshold = input<number>(12);

  protected readonly value = signal<string>('');
  protected readonly query = signal<string>('');
  protected readonly disabledState = signal<boolean>(false);

  protected readonly filtered = computed<readonly UidOption[]>(() => {
    const q = this.query().trim().toLowerCase();
    const opts = this.options();
    if (!q) return opts;
    return opts.filter(
      (o) => o.label.toLowerCase().includes(q) || (o.hint ?? '').toLowerCase().includes(q),
    );
  });

  // ── ControlValueAccessor ──────────────────────────────────────────────────
  private onChange: (v: string) => void = () => {};
  private onTouched: () => void = () => {};

  writeValue(v: string | null): void {
    this.value.set(v ?? '');
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
    this.onChange(uid);
    this.onTouched();
  }
}
