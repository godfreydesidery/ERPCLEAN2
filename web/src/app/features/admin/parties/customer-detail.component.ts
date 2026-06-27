import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { Branch } from '../models/branch.model';
import { Company } from '../models/company.model';
import {
  CustomerKind,
  CustomerModel,
  Money,
  PartyBranch,
  PartyType,
  UpdateCustomerRequest,
} from '../models/party.model';
import { BranchService } from '../branch/branch.service';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { CustomerService } from './customer.service';
import { CurrencySelectComponent } from '../../../shared/currency-select/currency-select.component';

type LoadState = 'loading' | 'idle' | 'error';

/**
 * Customer detail + edit screen. Route: /admin/customers/uid/:uid.
 * Edit form: all party fields + credit-limit when CREDIT_ACCOUNT.
 * Branch Associations panel: mirrors user-detail's Branch Assignments.
 * VRN input is only enabled when vatRegistered is true (server enforces, UI guides).
 */
@Component({
  selector: 'app-customer-detail',
  imports: [FormsModule, RouterLink, CurrencySelectComponent],
  templateUrl: './customer-detail.component.html',
  styleUrl: './customer-detail.component.scss',
})
export class CustomerDetailComponent {
  private readonly customerService = inject(CustomerService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly branchService = inject(BranchService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  /** Route input bound via withComponentInputBinding. */
  readonly uid = input.required<string>();

  // ── Customer header ────────────────────────────────────────────────────────
  readonly customer = signal<CustomerModel | null>(null);
  readonly customerState = signal<LoadState>('loading');

  // ── Edit form fields ───────────────────────────────────────────────────────
  readonly fDisplayName = signal('');
  readonly fLegalName = signal('');
  readonly fPartyType = signal<PartyType>('INDIVIDUAL');
  readonly fTin = signal('');
  readonly fVatRegistered = signal(false);
  readonly fVrn = signal('');
  readonly fBusinessRegNo = signal('');
  readonly fMobileMoneyNo = signal('');
  readonly fPhone = signal('');
  readonly fEmail = signal('');
  readonly fPhysicalAddress = signal('');
  readonly fPostalAddress = signal('');
  readonly fRegion = signal('');
  readonly fDistrict = signal('');
  readonly fCustomerKind = signal<CustomerKind>('CASH_WALK_IN');
  readonly fCreditAmount = signal('');
  readonly fCreditCurrency = signal('TZS');
  readonly fPaymentTermsDays = signal('');

  readonly saving = signal(false);
  readonly saveError = signal<string | null>(null);
  readonly archiving = signal(false);

  readonly isCreditAccount = computed(() => this.fCustomerKind() === 'CREDIT_ACCOUNT');
  readonly canManage = computed(() => this.session.hasPermission('CUSTOMER.MANAGE'));
  readonly canAssign = computed(() => this.session.hasPermission('PARTY.BRANCH.ASSIGN'));

  // ── Branch associations ────────────────────────────────────────────────────
  readonly branches = signal<PartyBranch[]>([]);
  readonly branchesState = signal<LoadState>('loading');
  readonly rowBusyBranchId = signal<string | null>(null);

  // ── Branch assign form ─────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly companiesState = signal<LoadState>('loading');
  readonly selectedCompanyUid = signal('');
  readonly companyBranches = signal<Branch[]>([]);
  readonly companyBranchesState = signal<'idle' | 'loading' | 'error'>('idle');
  readonly selectedBranchUid = signal('');
  readonly assigning = signal(false);
  readonly assignError = signal<string | null>(null);

  /**
   * Map from branchId (string) -> Branch for display. Populated whenever company branches load.
   * Covers all loaded companies' branches.
   */
  private readonly branchById = signal<Map<string, Branch>>(new Map());

  readonly hasBranches = computed(() => this.branches().length > 0);

  /** Uid of the company that owns this customer — scopes the currency picker. */
  readonly customerCompanyUid = computed(() => {
    const c = this.customer();
    if (!c) return '';
    const match = this.companies().find((co) => co.id === c.companyId);
    return match?.uid ?? '';
  });

  branchDisplay(branchId: string): string {
    const b = this.branchById().get(branchId);
    return b ? `${b.code} — ${b.name}` : branchId;
  }

  constructor() {
    queueMicrotask(() => this.init());
  }

  private init(): void {
    this.loadCustomer();
    this.loadBranches();
    this.loadCompanies();
  }

  private loadCustomer(): void {
    this.customerState.set('loading');
    this.customerService.getByUid(this.uid()).subscribe({
      next: (c) => {
        this.customer.set(c);
        this.customerState.set('idle');
        this.patchForm(c);
      },
      error: () => this.customerState.set('error'),
    });
  }

  private patchForm(c: CustomerModel): void {
    this.fDisplayName.set(c.displayName ?? '');
    this.fLegalName.set(c.legalName ?? '');
    this.fPartyType.set(c.partyType);
    this.fTin.set(c.tin ?? '');
    this.fVatRegistered.set(c.vatRegistered);
    this.fVrn.set(c.vrn ?? '');
    this.fBusinessRegNo.set(c.businessRegNo ?? '');
    this.fMobileMoneyNo.set(c.mobileMoneyNo ?? '');
    this.fPhone.set(c.phone ?? '');
    this.fEmail.set(c.email ?? '');
    this.fPhysicalAddress.set(c.physicalAddress ?? '');
    this.fPostalAddress.set(c.postalAddress ?? '');
    this.fRegion.set(c.region ?? '');
    this.fDistrict.set(c.district ?? '');
    this.fCustomerKind.set(c.customerKind);
    this.fCreditAmount.set(c.creditLimit?.amount ?? '');
    this.fCreditCurrency.set(c.creditLimit?.currency ?? 'TZS');
    this.fPaymentTermsDays.set(c.paymentTermsDays != null ? String(c.paymentTermsDays) : '');
  }

  loadBranches(): void {
    this.branchesState.set('loading');
    this.customerService.listBranches(this.uid()).subscribe({
      next: (rows) => {
        this.branches.set(rows);
        this.branchesState.set('idle');
      },
      error: () => this.branchesState.set('error'),
    });
  }

  private loadCompanies(): void {
    this.companiesState.set('loading');
    this.organisationService.current().subscribe({
      next: (org) => {
        this.companyService.list(org.uid).subscribe({
          next: (list) => {
            this.companies.set(list);
            this.companiesState.set('idle');
            // Pre-load branches for all companies so we can display names
            list.forEach((co) => this.loadCompanyBranchesForDisplay(co.uid));
          },
          error: () => this.companiesState.set('error'),
        });
      },
      error: () => this.companiesState.set('error'),
    });
  }

  private loadCompanyBranchesForDisplay(companyUid: string): void {
    this.branchService.list(companyUid).subscribe({
      next: (rows) => {
        this.branchById.update((map) => {
          const next = new Map(map);
          rows.forEach((b) => next.set(b.id, b));
          return next;
        });
      },
      error: () => undefined,
    });
  }

  onCompanyChange(companyUid: string): void {
    this.selectedCompanyUid.set(companyUid);
    this.selectedBranchUid.set('');
    this.companyBranches.set([]);
    if (!companyUid) {
      this.companyBranchesState.set('idle');
      return;
    }
    this.companyBranchesState.set('loading');
    this.branchService.list(companyUid).subscribe({
      next: (rows) => {
        this.companyBranches.set(rows);
        this.companyBranchesState.set('idle');
        // Also merge into the display map
        this.branchById.update((map) => {
          const next = new Map(map);
          rows.forEach((b) => next.set(b.id, b));
          return next;
        });
      },
      error: () => this.companyBranchesState.set('error'),
    });
  }

  assign(): void {
    const branchUid = this.selectedBranchUid();
    if (!branchUid) {
      this.assignError.set('Select a branch to assign.');
      return;
    }
    this.assigning.set(true);
    this.assignError.set(null);
    this.customerService.assignBranch(this.uid(), { branchUid }).subscribe({
      next: () => {
        this.selectedBranchUid.set('');
        this.assigning.set(false);
        this.alerts.success('Branch assigned');
        this.loadBranches();
      },
      error: (err) => {
        this.assignError.set(this.messageFrom(err, 'Could not assign branch.'));
        this.assigning.set(false);
      },
    });
  }

  removeBranch(pb: PartyBranch): void {
    if (this.rowBusyBranchId() !== null) return;
    // Find the branch by id to get its uid for the DELETE endpoint
    const branch = this.branchById().get(pb.branchId);
    if (!branch) {
      this.alerts.success('Branch not found in loaded list — refresh and try again.');
      return;
    }
    this.rowBusyBranchId.set(pb.branchId);
    this.customerService.removeBranch(this.uid(), branch.uid).subscribe({
      next: () => {
        this.rowBusyBranchId.set(null);
        this.alerts.success('Branch removed', branch.name);
        this.loadBranches();
      },
      error: () => this.rowBusyBranchId.set(null),
    });
  }

  /** Coerce a value from ngModel on type="number" to string; prevents .trim() crashes. */
  coerceNumStr(v: string | number | null | undefined): string {
    return v === null || v === undefined ? '' : String(v);
  }

  save(): void {
    const displayName = this.fDisplayName().trim();
    if (!displayName) {
      this.saveError.set('Display name is required.');
      return;
    }
    this.saving.set(true);
    this.saveError.set(null);

    const creditLimit: Money | undefined =
      this.isCreditAccount() && this.fCreditAmount().trim()
        ? { amount: this.fCreditAmount().trim(), currency: this.fCreditCurrency().trim() || 'TZS' }
        : undefined;

    const paymentTermsDays =
      this.isCreditAccount() && this.fPaymentTermsDays().trim()
        ? parseInt(this.fPaymentTermsDays().trim(), 10)
        : undefined;

    const request: UpdateCustomerRequest = {
      partyType: this.fPartyType(),
      displayName,
      legalName: this.fLegalName().trim() || undefined,
      tin: this.fTin().trim() || undefined,
      vatRegistered: this.fVatRegistered(),
      vrn: this.fVatRegistered() && this.fVrn().trim() ? this.fVrn().trim() : undefined,
      businessRegNo: this.fBusinessRegNo().trim() || undefined,
      mobileMoneyNo: this.fMobileMoneyNo().trim() || undefined,
      phone: this.fPhone().trim() || undefined,
      email: this.fEmail().trim() || undefined,
      physicalAddress: this.fPhysicalAddress().trim() || undefined,
      postalAddress: this.fPostalAddress().trim() || undefined,
      region: this.fRegion().trim() || undefined,
      district: this.fDistrict().trim() || undefined,
      customerKind: this.fCustomerKind(),
      creditLimit,
      paymentTermsDays,
    };

    this.customerService.update(this.uid(), request).subscribe({
      next: (updated) => {
        this.customer.set(updated);
        this.saving.set(false);
        this.alerts.success('Customer saved', updated.displayName);
      },
      error: (err) => {
        this.saveError.set(this.messageFrom(err, 'Could not save the customer.'));
        this.saving.set(false);
      },
    });
  }

  archive(): void {
    if (this.archiving()) return;
    this.archiving.set(true);
    this.customerService.archive(this.uid()).subscribe({
      next: () => {
        this.archiving.set(false);
        this.customer.update((c) => (c ? { ...c, status: 'ARCHIVED' } : c));
        this.alerts.success('Customer archived');
      },
      error: (err) => {
        this.archiving.set(false);
        this.saveError.set(this.messageFrom(err, 'Could not archive the customer.'));
      },
    });
  }

  restore(): void {
    if (this.archiving()) return;
    this.archiving.set(true);
    this.customerService.restore(this.uid()).subscribe({
      next: () => {
        this.archiving.set(false);
        this.customer.update((c) => (c ? { ...c, status: 'ACTIVE' } : c));
        this.alerts.success('Customer restored');
      },
      error: (err) => {
        this.archiving.set(false);
        this.saveError.set(this.messageFrom(err, 'Could not restore the customer.'));
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
