import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  forwardRef,
  inject,
  input,
  signal,
} from '@angular/core';
import { ControlValueAccessor, FormsModule, NG_VALUE_ACCESSOR } from '@angular/forms';
import { CurrencyOption, CurrencyService } from './currency.service';

type LoadState = 'idle' | 'loading' | 'error';

/**
 * Currency picker (ADR-0039 Tranche 3).
 *
 * A searchable <select> ControlValueAccessor bound to a 3-letter ISO 4217 currency code string.
 * Fetches the company's ENABLED allow-list from GET /fx/currencies/enabled, shows
 * "CODE — Name" labels, and defaults to the company's resolved base currency.
 *
 * Usage in a form:
 *
 *   <app-currency-select
 *       [(ngModel)]="currency"
 *       [companyUid]="selectedCompanyUid()"
 *       [required]="true"
 *       [disabled]="submitting()" />
 *
 * When companyUid is empty the picker is disabled and shows a placeholder.
 * When the company changes, the picker re-loads and resets the value to the resolved default.
 * When loading fails, the picker shows an error state.
 *
 * Accessibility: the <select> carries aria-label="Currency" and aria-busy during load.
 * The host must provide a visible <label> that describes the control.
 */
@Component({
  selector: 'app-currency-select',
  standalone: true,
  imports: [FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [
    {
      provide: NG_VALUE_ACCESSOR,
      useExisting: forwardRef(() => CurrencySelectComponent),
      multi: true,
    },
  ],
  template: `
    @if (loadState() === 'loading') {
      <div class="d-flex align-items-center gap-2">
        <span class="spinner-border spinner-border-sm text-secondary" aria-hidden="true"></span>
        <span class="text-muted small" aria-live="polite">Loading currencies…</span>
      </div>
    } @else if (loadState() === 'error') {
      <div class="d-flex flex-column gap-1">
        <select class="form-select form-select-sm"
                [ngModel]="value()"
                (ngModelChange)="onPick($event)"
                [disabled]="true"
                aria-label="Currency">
          <option value="">— failed to load currencies —</option>
        </select>
        <span class="text-danger small d-flex align-items-center gap-1" role="alert">
          <i class="bi bi-exclamation-circle" aria-hidden="true"></i>
          Could not load currencies
        </span>
      </div>
    } @else if (options().length === 0 && !companyUid()) {
      <select class="form-select form-select-sm"
              [disabled]="true"
              aria-label="Currency">
        <option value="">— select a company first —</option>
      </select>
    } @else {
      @if (options().length > searchThreshold()) {
        <input type="text"
               class="form-control form-control-sm mb-1"
               [ngModel]="query()"
               (ngModelChange)="query.set($event)"
               aria-label="Currency — filter by code or name"
               placeholder="Filter currency…"
               [disabled]="disabledState()" />
      }
      <select class="form-select form-select-sm"
              [ngModel]="value()"
              (ngModelChange)="onPick($event)"
              [disabled]="disabledState() || !companyUid()"
              [required]="required()"
              [attr.aria-busy]="loadState() === 'loading' ? 'true' : null"
              aria-label="Currency">
        <option value="">{{ placeholder() || '— select currency —' }}</option>
        @for (opt of filtered(); track opt.code) {
          <option [value]="opt.code">{{ opt.code }} — {{ opt.name }}</option>
        }
      </select>
    }
  `,
})
export class CurrencySelectComponent implements ControlValueAccessor {
  private readonly currencyService = inject(CurrencyService);

  /** Company uid (NOT the numeric id). Required to fetch the enabled list. */
  readonly companyUid = input<string>('');

  /** Optional branch uid — if provided, fetches the branch-effective list. */
  readonly branchUid = input<string | null | undefined>(null);

  readonly required = input<boolean>(false);
  readonly placeholder = input<string>('');
  /** Above this many options, show the filter box. */
  readonly searchThreshold = input<number>(8);

  protected readonly value = signal<string>('');
  protected readonly query = signal<string>('');
  protected readonly loadState = signal<LoadState>('idle');
  protected readonly options = signal<CurrencyOption[]>([]);
  protected readonly disabledState = signal<boolean>(false);

  protected readonly filtered = computed<CurrencyOption[]>(() => {
    const q = this.query().trim().toLowerCase();
    const opts = this.options();
    if (!q) return opts;
    return opts.filter(
      (o) => o.code.toLowerCase().includes(q) || o.name.toLowerCase().includes(q),
    );
  });

  constructor() {
    // React to companyUid (and branchUid) changes. effect() is valid here (injection context).
    effect(() => {
      const uid = this.companyUid();
      const branchUid = this.branchUid();
      if (!uid) {
        this.options.set([]);
        this.loadState.set('idle');
        return;
      }
      this.loadState.set('loading');
      this.query.set('');
      this.currencyService.getEnabledOptions(uid, branchUid).subscribe({
        next: ({ options, resolvedDefault }) => {
          this.options.set(options);
          this.loadState.set('idle');
          // Auto-default to the resolved base currency only when the form has no value yet.
          if (!this.value() && resolvedDefault) {
            this.value.set(resolvedDefault);
            this.onChange(resolvedDefault);
          }
        },
        error: () => {
          this.options.set([]);
          this.loadState.set('error');
        },
      });
    });
  }

  // ── ControlValueAccessor ────────────────────────────────────────────────────
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

  protected onPick(code: string): void {
    this.value.set(code);
    this.onChange(code);
    this.onTouched();
  }
}
