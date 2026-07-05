import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AlertService } from '../../../../core/feedback/alert.service';
import { SessionStore } from '../../../../core/auth/session.store';
import { Company } from '../../models/company.model';
import { SalesSettingsDto, UpdateSalesSettingsRequest } from '../../models/sales.model';
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

    this.saving.set(true);
    this.saveError.set(null);

    const request: UpdateSalesSettingsRequest = {
      companyUid,
      soApprovalEnabled: this.fSoApprovalEnabled(),
      soApprovalThresholdAmount: Number(threshold),
      currency: this.fCurrency().trim(),
      allowNegativeStock: this.fAllowNegativeStock(),
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

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}
