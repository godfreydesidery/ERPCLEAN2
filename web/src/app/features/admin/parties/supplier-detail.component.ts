import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { Branch } from '../models/branch.model';
import { Company } from '../models/company.model';
import { PartyBranch, PartyType, SupplierKind, SupplierModel, UpdateSupplierRequest } from '../models/party.model';
import { BranchService } from '../branch/branch.service';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { SupplierService } from './supplier.service';

type LoadState = 'loading' | 'idle' | 'error';

@Component({
  selector: 'app-supplier-detail',
  imports: [FormsModule, RouterLink],
  templateUrl: './supplier-detail.component.html',
  styleUrl: './supplier-detail.component.scss',
})
export class SupplierDetailComponent {
  private readonly supplierService = inject(SupplierService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly branchService = inject(BranchService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  readonly uid = input.required<string>();

  readonly supplier = signal<SupplierModel | null>(null);
  readonly supplierState = signal<LoadState>('loading');

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
  readonly fSupplierKind = signal<SupplierKind>('GOODS');

  readonly saving = signal(false);
  readonly saveError = signal<string | null>(null);
  readonly archiving = signal(false);

  readonly canManage = computed(() => this.session.hasPermission('SUPPLIER.MANAGE'));
  readonly canAssign = computed(() => this.session.hasPermission('PARTY.BRANCH.ASSIGN'));

  readonly branches = signal<PartyBranch[]>([]);
  readonly branchesState = signal<LoadState>('loading');
  readonly rowBusyBranchId = signal<string | null>(null);

  readonly companies = signal<Company[]>([]);
  readonly companiesState = signal<LoadState>('loading');
  readonly selectedCompanyUid = signal('');
  readonly companyBranches = signal<Branch[]>([]);
  readonly companyBranchesState = signal<'idle' | 'loading' | 'error'>('idle');
  readonly selectedBranchUid = signal('');
  readonly assigning = signal(false);
  readonly assignError = signal<string | null>(null);

  private readonly branchById = signal<Map<string, Branch>>(new Map());

  readonly hasBranches = computed(() => this.branches().length > 0);

  branchDisplay(branchId: string): string {
    const b = this.branchById().get(branchId);
    return b ? `${b.code} — ${b.name}` : branchId;
  }

  constructor() {
    queueMicrotask(() => this.init());
  }

  private init(): void {
    this.loadSupplier();
    this.loadBranches();
    this.loadCompanies();
  }

  private loadSupplier(): void {
    this.supplierState.set('loading');
    this.supplierService.getByUid(this.uid()).subscribe({
      next: (s) => {
        this.supplier.set(s);
        this.supplierState.set('idle');
        this.patchForm(s);
      },
      error: () => this.supplierState.set('error'),
    });
  }

  private patchForm(s: SupplierModel): void {
    this.fDisplayName.set(s.displayName ?? '');
    this.fLegalName.set(s.legalName ?? '');
    this.fPartyType.set(s.partyType);
    this.fTin.set(s.tin ?? '');
    this.fVatRegistered.set(s.vatRegistered);
    this.fVrn.set(s.vrn ?? '');
    this.fBusinessRegNo.set(s.businessRegNo ?? '');
    this.fMobileMoneyNo.set(s.mobileMoneyNo ?? '');
    this.fPhone.set(s.phone ?? '');
    this.fEmail.set(s.email ?? '');
    this.fPhysicalAddress.set(s.physicalAddress ?? '');
    this.fPostalAddress.set(s.postalAddress ?? '');
    this.fRegion.set(s.region ?? '');
    this.fDistrict.set(s.district ?? '');
    this.fSupplierKind.set(s.supplierKind);
  }

  loadBranches(): void {
    this.branchesState.set('loading');
    this.supplierService.listBranches(this.uid()).subscribe({
      next: (rows) => { this.branches.set(rows); this.branchesState.set('idle'); },
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
    if (!companyUid) { this.companyBranchesState.set('idle'); return; }
    this.companyBranchesState.set('loading');
    this.branchService.list(companyUid).subscribe({
      next: (rows) => {
        this.companyBranches.set(rows);
        this.companyBranchesState.set('idle');
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
    if (!branchUid) { this.assignError.set('Select a branch to assign.'); return; }
    this.assigning.set(true);
    this.assignError.set(null);
    this.supplierService.assignBranch(this.uid(), { branchUid }).subscribe({
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
    const branch = this.branchById().get(pb.branchId);
    if (!branch) return;
    this.rowBusyBranchId.set(pb.branchId);
    this.supplierService.removeBranch(this.uid(), branch.uid).subscribe({
      next: () => {
        this.rowBusyBranchId.set(null);
        this.alerts.success('Branch removed', branch.name);
        this.loadBranches();
      },
      error: () => this.rowBusyBranchId.set(null),
    });
  }

  save(): void {
    const displayName = this.fDisplayName().trim();
    if (!displayName) { this.saveError.set('Display name is required.'); return; }
    this.saving.set(true);
    this.saveError.set(null);

    const request: UpdateSupplierRequest = {
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
      supplierKind: this.fSupplierKind(),
    };

    this.supplierService.update(this.uid(), request).subscribe({
      next: (updated) => {
        this.supplier.set(updated);
        this.saving.set(false);
        this.alerts.success('Supplier saved', updated.displayName);
      },
      error: (err) => {
        this.saveError.set(this.messageFrom(err, 'Could not save the supplier.'));
        this.saving.set(false);
      },
    });
  }

  archive(): void {
    if (this.archiving()) return;
    this.archiving.set(true);
    this.supplierService.archive(this.uid()).subscribe({
      next: () => {
        this.archiving.set(false);
        this.supplier.update((s) => (s ? { ...s, status: 'ARCHIVED' } : s));
        this.alerts.success('Supplier archived');
      },
      error: (err) => { this.archiving.set(false); this.saveError.set(this.messageFrom(err, 'Could not archive.')); },
    });
  }

  restore(): void {
    if (this.archiving()) return;
    this.archiving.set(true);
    this.supplierService.restore(this.uid()).subscribe({
      next: () => {
        this.archiving.set(false);
        this.supplier.update((s) => (s ? { ...s, status: 'ACTIVE' } : s));
        this.alerts.success('Supplier restored');
      },
      error: (err) => { this.archiving.set(false); this.saveError.set(this.messageFrom(err, 'Could not restore.')); },
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
