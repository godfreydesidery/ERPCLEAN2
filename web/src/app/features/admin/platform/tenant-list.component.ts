import { Component, computed, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { SessionStore } from '../../../core/auth/session.store';
import { CreateTenantRequest, TenantSummary } from './tenant.model';
import { TenantService } from './tenant.service';

type ConfirmAction = 'suspend' | 'resume';

/**
 * Platform console: every tenant on this instance, plus provisioning of a new one. Reachable only
 * with ORG.CREATE (the backend gate on both the list and the create endpoint); suspend/reactivate
 * additionally need ORG.SUSPEND. Server error strings are shown as given.
 */
@Component({
  selector: 'app-tenant-list',
  imports: [FormsModule, DatePipe],
  templateUrl: './tenant-list.component.html',
  styleUrl: './tenant-list.component.scss',
})
export class TenantListComponent {
  private readonly service = inject(TenantService);
  private readonly session = inject(SessionStore);

  readonly tenants = signal<TenantSummary[]>([]);
  readonly state = signal<'loading' | 'ready' | 'error'>('loading');
  readonly canSuspend = computed(() => this.session.hasPermission('ORG.SUSPEND'));
  /** Root only: switching into another tenant is the platform operator's, read and write. */
  readonly canEnter = computed(() => this.session.user()?.isRoot === true);
  readonly entering = signal<string | null>(null);
  readonly enterError = signal<string | null>(null);

  // Suspend / reactivate confirm
  readonly confirmTarget = signal<TenantSummary | null>(null);
  readonly confirmAction = signal<ConfirmAction>('suspend');
  readonly acting = signal(false);
  readonly actionError = signal<string | null>(null);

  // Create form
  readonly showForm = signal(false);
  readonly submitted = signal(false);
  readonly saving = signal(false);
  readonly formError = signal<string | null>(null);
  /** Set after a successful create: the sign-in name to hand to the new administrator. */
  readonly created = signal<{ organisationName: string; signInName: string } | null>(null);

  readonly organisationName = signal('');
  readonly timeZone = signal('');
  readonly companyCode = signal('');
  readonly companyName = signal('');
  readonly companyLegalName = signal('');
  readonly companyTaxId = signal('');
  readonly companyVrn = signal('');
  readonly branchCode = signal('');
  readonly branchName = signal('');
  readonly adminUsername = signal('');
  readonly adminDisplayName = signal('');
  readonly adminPassword = signal('');
  readonly priceListCode = signal('');
  readonly priceListName = signal('');
  /** 'yes' | 'no' | '' (unanswered). Required whenever a price list is named. */
  readonly priceListIncludesVat = signal<'yes' | 'no' | ''>('');
  readonly walkInCustomerName = signal('');
  readonly posTillName = signal('');
  readonly baseCurrency = signal('');
  readonly defaultCurrency = signal('');
  readonly enabledCurrencies = signal('');

  /**
   * Guard for single-customer servers (owner, 2026-11-07). While this server holds only ONE
   * organisation, creating another turns a customer's own server into a shared one, which cannot
   * easily be undone. So the operator must type the current organisation's exact name first. On a
   * real shared server this only applies to the very first customer.
   */
  readonly sharedConfirm = signal('');
  readonly onlyOrganisation = computed<TenantSummary | null>(() =>
    this.state() === 'ready' && this.tenants().length === 1 ? this.tenants()[0] : null,
  );
  readonly sharedConfirmError = computed<string | null>(() => {
    // Fail closed: without the list we cannot tell how many organisations exist.
    if (this.state() !== 'ready') return 'The tenant list must load before a new organisation can be created.';
    const only = this.onlyOrganisation();
    if (!only) return null;
    return this.sharedConfirm().trim() === only.name ? null : `Type "${only.name}" exactly to confirm.`;
  });

  readonly priceListNamed = computed(
    () => this.priceListCode().trim() !== '' || this.priceListName().trim() !== '',
  );

  readonly usernameError = computed(() => {
    const u = this.adminUsername().trim();
    if (!u) return 'Enter a username for the administrator.';
    if (u.includes('@')) {
      return "Enter only the first part of the username, without '@'. The organisation's sign-in alias is added automatically.";
    }
    return null;
  });
  readonly passwordError = computed(() =>
    this.adminPassword().length < 12 ? 'The password must be at least 12 characters.' : null,
  );
  readonly priceListVatError = computed(() =>
    this.priceListNamed() && this.priceListIncludesVat() === ''
      ? 'Say whether the prices on this list include VAT.'
      : null,
  );
  readonly priceListPairError = computed(() => {
    const code = this.priceListCode().trim();
    const name = this.priceListName().trim();
    if ((code && !name) || (!code && name)) {
      return 'Enter both a price list code and a price list name, or leave both empty.';
    }
    return null;
  });

  constructor() {
    this.load();
  }

  load(): void {
    this.state.set('loading');
    this.service.list().subscribe({
      next: (rows) => {
        this.tenants.set(rows);
        this.state.set('ready');
      },
      error: () => this.state.set('error'),
    });
  }

  /** Full reload, so every cached list and permission set is rebuilt. A seam for specs. */
  protected navigate(url: string): void {
    window.location.assign(url);
  }

  // ── Enter a tenant (owner, 2026-11-07) ────────────────────────────────────

  /**
   * Switches into the tenant's default branch and reloads the app, so every screen, cached list
   * and permission set is rebuilt for that tenant. Everything done inside is recorded on the
   * tenant's own audit trail by the server.
   */
  enter(t: TenantSummary): void {
    if (this.entering()) return;
    this.enterError.set(null);
    this.entering.set(t.uid);
    this.service.entry(t.uid).subscribe({
      next: (e) => {
        this.session.setPlatformTenant({
          organisationUid: e.organisationUid,
          organisationName: e.organisationName,
          branchUid: e.branchUid,
          branchName: e.branchName,
          branchCode: e.branchCode,
        });
        this.session.setActiveBranchUid(e.branchUid);
        this.navigate('/admin/home');
      },
      error: (err) => {
        this.entering.set(null);
        this.enterError.set(this.messageFrom(err, 'Could not open this organisation.'));
      },
    });
  }

  // ── Suspend / reactivate ──────────────────────────────────────────────────

  askConfirm(t: TenantSummary, action: ConfirmAction): void {
    this.actionError.set(null);
    this.confirmAction.set(action);
    this.confirmTarget.set(t);
    setTimeout(() => document.getElementById('tenantConfirmCancel')?.focus(), 0);
  }

  cancelConfirm(): void {
    if (this.acting()) return;
    this.confirmTarget.set(null);
    this.actionError.set(null);
  }

  confirm(): void {
    const t = this.confirmTarget();
    if (!t || this.acting()) return;
    this.acting.set(true);
    this.actionError.set(null);
    const call =
      this.confirmAction() === 'suspend' ? this.service.suspend(t.uid) : this.service.resume(t.uid);
    call.subscribe({
      next: () => {
        this.acting.set(false);
        this.confirmTarget.set(null);
        this.load();
      },
      error: (err) => {
        this.acting.set(false);
        this.actionError.set(this.messageFrom(err, 'Could not change the status of this organisation.'));
      },
    });
  }

  // ── Create ────────────────────────────────────────────────────────────────

  openForm(): void {
    this.created.set(null);
    this.formError.set(null);
    this.submitted.set(false);
    this.sharedConfirm.set('');
    this.showForm.set(true);
  }

  closeForm(): void {
    if (this.saving()) return;
    this.showForm.set(false);
  }

  /** Builds the request, omitting blank optional fields. */
  buildRequest(): CreateTenantRequest {
    const opt = (s: string): string | undefined => s.trim() || undefined;
    const currencies = this.enabledCurrencies()
      .split(/[\s,]+/)
      .map((c) => c.trim().toUpperCase())
      .filter((c) => c.length > 0);
    return {
      organisationName: this.organisationName().trim(),
      timeZone: opt(this.timeZone()),
      companyCode: this.companyCode().trim(),
      companyName: this.companyName().trim(),
      companyLegalName: opt(this.companyLegalName()),
      companyTaxId: opt(this.companyTaxId()),
      companyVrn: opt(this.companyVrn()),
      branchCode: this.branchCode().trim(),
      branchName: this.branchName().trim(),
      adminUsername: this.adminUsername().trim(),
      adminPassword: this.adminPassword(),
      adminDisplayName: opt(this.adminDisplayName()),
      baseCurrency: opt(this.baseCurrency())?.toUpperCase(),
      defaultCurrency: opt(this.defaultCurrency())?.toUpperCase(),
      enabledCurrencies: currencies.length > 0 ? currencies : undefined,
      priceListCode: opt(this.priceListCode()),
      priceListName: opt(this.priceListName()),
      priceListIncludesVat: this.priceListNamed() ? this.priceListIncludesVat() === 'yes' : undefined,
      walkInCustomerName: opt(this.walkInCustomerName()),
      posTillName: opt(this.posTillName()),
    };
  }

  requiredMissing(): boolean {
    return [
      this.organisationName(),
      this.companyCode(),
      this.companyName(),
      this.branchCode(),
      this.branchName(),
    ].some((v) => !v.trim());
  }

  hasErrors(): boolean {
    return (
      this.requiredMissing() ||
      this.usernameError() !== null ||
      this.passwordError() !== null ||
      this.priceListPairError() !== null ||
      this.priceListVatError() !== null ||
      this.sharedConfirmError() !== null
    );
  }

  create(): void {
    this.submitted.set(true);
    this.formError.set(null);
    if (this.saving()) return;
    if (this.hasErrors()) {
      this.formError.set('Please correct the fields marked below.');
      return;
    }
    this.saving.set(true);
    const request = this.buildRequest();
    this.service.create(request).subscribe({
      next: (org) => {
        this.saving.set(false);
        this.created.set({
          organisationName: org.name,
          signInName: org.alias ? `${request.adminUsername}@${org.alias}` : request.adminUsername,
        });
        this.resetForm();
        this.showForm.set(false);
        this.load();
      },
      error: (err) => {
        this.saving.set(false);
        this.formError.set(this.messageFrom(err, 'Could not create the organisation.'));
      },
    });
  }

  private resetForm(): void {
    [
      this.organisationName, this.timeZone, this.companyCode, this.companyName, this.companyLegalName,
      this.companyTaxId, this.companyVrn, this.branchCode, this.branchName, this.adminUsername,
      this.adminDisplayName, this.adminPassword, this.priceListCode, this.priceListName,
      this.walkInCustomerName, this.posTillName, this.baseCurrency, this.defaultCurrency,
      this.enabledCurrencies,
    ].forEach((s) => s.set(''));
    this.priceListIncludesVat.set('');
    this.sharedConfirm.set('');
    this.submitted.set(false);
  }

  private messageFrom(err: unknown, fallback: string): string {
    const errors = (err as { error?: { errors?: string[] } })?.error?.errors;
    return errors?.length ? errors.join(' ') : fallback;
  }
}
