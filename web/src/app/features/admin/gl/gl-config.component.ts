import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { Company } from '../models/company.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { AccountDto, GlConfigDto, SetGlConfigRequest } from './models/gl.model';
import { GlService } from './gl.service';

/**
 * GL posting accounts configuration. Gated GL.MANAGE.
 * Lists gl_configs (configKey → account) and allows setting/changing the account for each key.
 * These mappings drive sales auto-posting — labelled clearly.
 */
@Component({
  selector: 'app-gl-config',
  imports: [FormsModule],
  templateUrl: './gl-config.component.html',
  styleUrl: './gl-config.component.scss',
})
export class GlConfigComponent {
  private readonly glService = inject(GlService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  // ── Company context ────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── Config data ────────────────────────────────────────────────────────────
  readonly configs = signal<GlConfigDto[]>([]);
  readonly configsState = signal<'idle' | 'loading' | 'error'>('idle');

  // ── Account picker data ────────────────────────────────────────────────────
  readonly accounts = signal<AccountDto[]>([]);
  readonly accountsState = signal<'idle' | 'loading' | 'error'>('idle');

  // ── Set config form ────────────────────────────────────────────────────────
  readonly showSetForm = signal(false);
  readonly setConfigKey = signal('');
  readonly setAccountUid = signal('');
  readonly setting = signal(false);
  readonly setError = signal<string | null>(null);

  readonly canManage = computed(() => this.session.hasPermission('GL.MANAGE'));

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
              this.selectedCompanyId.set(list[0].id);
              this.loadConfigs(list[0].id);
              this.loadAccounts(list[0].id);
            }
          },
          error: () => this.companyState.set('error'),
        });
      },
      error: () => this.companyState.set('error'),
    });
  }

  private loadConfigs(companyId: string): void {
    this.configsState.set('loading');
    this.glService.listConfigs(companyId).subscribe({
      next: (list) => {
        this.configs.set(list);
        this.configsState.set('idle');
      },
      error: () => this.configsState.set('error'),
    });
  }

  private loadAccounts(companyId: string): void {
    this.accountsState.set('loading');
    this.glService.listAllActiveAccounts(companyId).subscribe({
      next: (list) => {
        this.accounts.set(list);
        this.accountsState.set('idle');
      },
      error: () => this.accountsState.set('error'),
    });
  }

  onCompanyChange(id: string): void {
    this.selectedCompanyId.set(id);
    this.configs.set([]);
    this.accounts.set([]);
    if (id) {
      this.loadConfigs(id);
      this.loadAccounts(id);
    }
  }

  openSetForm(configKey: string): void {
    const existing = this.configs().find((c) => c.configKey === configKey);
    this.setConfigKey.set(configKey);
    this.setAccountUid.set(existing?.accountId ?? '');
    this.setError.set(null);
    this.showSetForm.set(true);
  }

  cancelSetForm(): void {
    this.showSetForm.set(false);
    this.setError.set(null);
  }

  saveConfig(): void {
    const companyId = this.selectedCompanyId();
    const configKey = String(this.setConfigKey() ?? '').trim();
    const accountUid = String(this.setAccountUid() ?? '').trim();

    if (!configKey || !accountUid) {
      this.setError.set('Config key and account are required.');
      return;
    }

    const company = this.companies().find((c) => c.id === companyId);
    if (!company) {
      this.setError.set('Could not resolve selected company.');
      return;
    }

    // Resolve account uid from account id if needed (the picker uses uid directly).
    const account = this.accounts().find((a) => a.uid === accountUid);
    if (!account) {
      this.setError.set('Select a valid account.');
      return;
    }

    this.setting.set(true);
    this.setError.set(null);

    const request: SetGlConfigRequest = {
      companyUid: company.uid,
      configKey,
      accountUid: account.uid,
    };

    this.glService.setConfig(request).subscribe({
      next: () => {
        this.setting.set(false);
        this.showSetForm.set(false);
        this.alerts.success('Posting account saved', configKey);
        this.loadConfigs(companyId);
      },
      error: (err) => {
        this.setError.set(this.messageFrom(err, 'Could not save posting account.'));
        this.setting.set(false);
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
