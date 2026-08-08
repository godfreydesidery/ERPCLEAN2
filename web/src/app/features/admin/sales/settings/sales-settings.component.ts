import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AlertService } from '../../../../core/feedback/alert.service';
import { SessionStore } from '../../../../core/auth/session.store';
import { Company } from '../../models/company.model';
import {
  BelowCostAction,
  DiscountApprovalAction,
  SalesSettingsDto,
  UpdateSalesSettingsRequest,
} from '../../models/sales.model';
import { CompanyService } from '../../company/company.service';
import { OrganisationService } from '../../organisation/organisation.service';
import { SalesSettingsService } from './sales-settings.service';
import { CurrencySelectComponent } from '../../../../shared/currency-select/currency-select.component';

type PageState = 'loading' | 'idle' | 'error' | 'forbidden';

@Component({
  selector: 'app-sales-settings',
  imports: [FormsModule, CurrencySelectComponent],
  templateUrl: './sales-settings.component.html',
  styleUrl: './sales-settings.component.scss',
})
export class SalesSettingsComponent {
  private readonly settingsService = inject(SalesSettingsService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  // ── Company context ────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyUid = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── Settings load / form state ────────────────────────────────────────────
  readonly state = signal<PageState>('idle');
  readonly settings = signal<SalesSettingsDto | null>(null);

  // ── Form fields ────────────────────────────────────────────────────────────
  readonly fSoApprovalEnabled = signal(false);
  readonly fThresholdAmount = signal('0');
  readonly fCurrency = signal('TZS');
  /** Stores the raw DTO polarity (true = allow/backorder); the template renders it as an inverted "block" switch. */
  readonly fAllowNegativeStock = signal(false);
  /** "Sale at or below cost" policy (V93). OFF = no check, the default. */
  readonly fBelowCostAction = signal<BelowCostAction>('OFF');

  /**
   * The below-cost choices, each with the plain-language consequence. Kept here rather than in the
   * template so the wording sits next to the value it belongs to and the spec can assert on it.
   */
  readonly belowCostOptions: ReadonlyArray<{ value: BelowCostAction; label: string; hint: string }> = [
    { value: 'OFF', label: 'No check', hint: 'Sell at any price. Nothing is checked against cost.' },
    { value: 'WARN', label: 'Allow, but record it', hint: 'The sale goes through and is recorded for review.' },
    {
      value: 'APPROVE',
      label: 'Needs supervisor approval',
      hint: 'The sale is stopped until a supervisor with permission to sell below cost approves it.',
    },
    { value: 'BLOCK', label: 'Never allow', hint: 'The sale is always stopped. Nobody can override it.' },
  ];

  /** The plain-language consequence of whatever is currently selected. */
  readonly belowCostHint = computed(
    () => this.belowCostOptions.find((o) => o.value === this.fBelowCostAction())?.hint ?? '',
  );

  // ── Discount policy (K7, V95) ──────────────────────────────────────────────
  // The server has enforced this since K7; until now there was no way for the manager who owns the
  // policy to turn it on, which made a shipped control undeliverable. Both fields are sent together
  // on every save: the server applies the pair atomically and refuses a ceiling on its own.

  /** "Manager-authorised discount" stance (V95). OFF = no ceiling enforced, the default. */
  readonly fDiscountApprovalAction = signal<DiscountApprovalAction>('OFF');
  /**
   * The ceiling, held as the raw text the admin typed so an in-progress entry ("7.") is never
   * silently coerced to a number the server would then store. Empty = no ceiling configured.
   */
  readonly fMaxDiscountPercent = signal('');

  readonly discountOptions: ReadonlyArray<{
    value: DiscountApprovalAction;
    label: string;
    hint: string;
  }> = [
    { value: 'OFF', label: 'No limit', hint: 'Any discount a salesperson types is accepted.' },
    {
      value: 'WARN',
      label: 'Allow, but record it',
      hint: 'A discount over the limit still goes through and is recorded for review.',
    },
    {
      value: 'APPROVE',
      label: 'Needs supervisor approval',
      hint: 'A discount over the limit is held until a supervisor with permission to approve discounts signs for it.',
    },
    {
      value: 'BLOCK',
      label: 'Never allow',
      hint: 'A discount over the limit is always refused. Nobody can override it.',
    },
  ];

  /** The plain-language consequence of the selected stance. */
  readonly discountHint = computed(
    () => this.discountOptions.find((o) => o.value === this.fDiscountApprovalAction())?.hint ?? '',
  );

  /** The ceiling only means anything once a stance is chosen; OFF ignores it entirely. */
  readonly discountCeilingApplies = computed(() => this.fDiscountApprovalAction() !== 'OFF');

  /**
   * What an empty ceiling actually does, said out loud. The server reads "not configured" as ZERO
   * once the policy is on — every discount then needs a supervisor. Showing this as "no limit"
   * would be the negative-stock toggle all over again: a screen describing the opposite of what the
   * till enforces.
   */
  readonly discountCeilingHint = computed(() =>
    this.fMaxDiscountPercent().trim() === ''
      ? 'Leave this empty and no discount at all is allowed on its own — every discount goes to the policy above.'
      : 'The most a salesperson may take off a line without help. Anything above it follows the policy above.',
  );

  // ── Save state ─────────────────────────────────────────────────────────────
  readonly saving = signal(false);
  readonly saveError = signal<string | null>(null);

  readonly canEdit = computed(() => this.session.hasPermission('SALES.SETTINGS.MANAGE'));
  readonly canView = computed(() => this.session.hasPermission('SALES.SETTINGS.MANAGE'));

  // Expose global String constructor for use in templates.
  protected readonly String = String;

  constructor() {
    this.loadCompanies();
  }

  private loadCompanies(): void {
    this.companyState.set('loading');
    this.organisationService.current().subscribe({
      next: (org) => {
        this.companyService.list(org.uid).subscribe({
          next: (list) => {
            this.companies.set(list);
            this.companyState.set('idle');
            if (list.length > 0) {
              this.selectedCompanyUid.set(list[0].uid);
              this.loadSettings(list[0].uid);
            }
          },
          error: () => this.companyState.set('error'),
        });
      },
      error: () => this.companyState.set('error'),
    });
  }

  onCompanyChange(uid: string): void {
    this.selectedCompanyUid.set(uid);
    this.settings.set(null);
    if (uid) this.loadSettings(uid);
  }

  private loadSettings(companyUid: string): void {
    this.state.set('loading');
    this.settingsService.getByCompany(companyUid).subscribe({
      next: (s) => {
        this.settings.set(s);
        this.patchForm(s);
        this.state.set('idle');
      },
      error: (err) => {
        if (err instanceof HttpErrorResponse && err.status === 403) {
          this.state.set('forbidden');
        } else {
          this.state.set('error');
        }
      },
    });
  }

  private patchForm(s: SalesSettingsDto): void {
    this.fSoApprovalEnabled.set(s.soApprovalEnabled);
    this.fThresholdAmount.set(String(s.soApprovalThresholdAmount ?? 0));
    this.fCurrency.set(s.currency ?? 'TZS');
    this.fAllowNegativeStock.set(s.allowNegativeStock);
    // Defensive `?? 'OFF'`: a company saved before V93 (or an older API) sends no value, and the
    // screen must then show exactly what the till enforces for that state — OFF.
    this.fBelowCostAction.set(s.belowCostAction ?? 'OFF');
    // Same reasoning for the discount policy (K7): a response without the field is a company the
    // server enforces as OFF, so that is what this must show.
    this.fDiscountApprovalAction.set(s.discountApprovalAction ?? 'OFF');
    this.fMaxDiscountPercent.set(
      s.maxDiscountPercent === null || s.maxDiscountPercent === undefined
        ? ''
        : String(s.maxDiscountPercent),
    );
  }

  save(): void {
    if (!this.canEdit()) return;
    const companyUid = this.selectedCompanyUid();
    if (!companyUid) return;
    const threshold = this.fThresholdAmount().trim();
    if (!threshold || isNaN(Number(threshold)) || Number(threshold) < 0) {
      this.saveError.set('Threshold amount must be zero or positive.');
      return;
    }

    const ceiling = this.parseDiscountCeiling();
    if (ceiling === 'invalid') {
      this.saveError.set(
        'The maximum discount must be a percentage between 0 and 100, with at most two decimal places.',
      );
      return;
    }

    this.saving.set(true);
    this.saveError.set(null);

    const request: UpdateSalesSettingsRequest = {
      companyUid,
      soApprovalEnabled: this.fSoApprovalEnabled(),
      soApprovalThresholdAmount: Number(threshold),
      currency: this.fCurrency().trim(),
      allowNegativeStock: this.fAllowNegativeStock(),
      belowCostAction: this.fBelowCostAction(),
      // Always BOTH: the server applies the stance and its ceiling as one policy, and refuses a
      // ceiling sent on its own (it used to answer 200 OK and save nothing — UAT finding #3).
      discountApprovalAction: this.fDiscountApprovalAction(),
      maxDiscountPercent: ceiling,
    };

    this.settingsService.update(request).subscribe({
      next: (updated) => {
        this.saving.set(false);
        this.settings.set(updated);
        this.patchForm(updated);
        this.alerts.success('Sales settings saved');
      },
      error: (err) => {
        this.saveError.set(this.messageFrom(err, 'Could not save settings.'));
        this.saving.set(false);
      },
    });
  }

  /**
   * The typed ceiling as the server's `NUMERIC(5,2)` sees it: a number in 0–100 with at most two
   * decimals, `null` for "no ceiling configured", or `'invalid'` when it cannot be either.
   *
   * Checked here rather than left to the browser's `type="number"` validation alone, because a
   * number input reports an out-of-range value as an empty string in some browsers — which would
   * turn "120" into "no ceiling", i.e. the strictest possible policy, silently.
   */
  private parseDiscountCeiling(): number | null | 'invalid' {
    const raw = this.fMaxDiscountPercent().trim();
    if (raw === '') return null;
    if (!/^\d{1,3}(\.\d{1,2})?$/.test(raw)) return 'invalid';
    const value = Number(raw);
    if (!Number.isFinite(value) || value < 0 || value > 100) return 'invalid';
    return value;
  }

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}
