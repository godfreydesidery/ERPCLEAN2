import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AlertService } from '../../../../core/feedback/alert.service';
import { SessionStore } from '../../../../core/auth/session.store';
import { Branch } from '../../models/branch.model';
import { Company } from '../../models/company.model';
import { PartyBranch, PartyType, AssignPartyBranchRequest } from '../../models/party.model';
import { BranchService } from '../../branch/branch.service';
import { CompanyService } from '../../company/company.service';
import { OrganisationService } from '../../organisation/organisation.service';
import { OtherPartyModel, UpdateOtherPartyRequest } from './other-party.model';
import { OtherPartyService } from './other-party.service';

type LoadState = 'loading' | 'idle' | 'error';

/**
 * OtherParty detail + edit screen. Route: /admin/other-parties/uid/:uid.
 * Edit form: all party fields + otherKind.
 * Branch Associations panel: mirrors customer-detail's Branch Assignments.
 */
@Component({
  selector: 'app-other-party-detail',
  imports: [FormsModule, RouterLink],
  templateUrl: './other-party-detail.component.html',
  styleUrl: './other-party-detail.component.scss',
})
export class OtherPartyDetailComponent {
  private readonly otherPartyService = inject(OtherPartyService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly branchService = inject(BranchService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  /** Route input bound via withComponentInputBinding. */
  readonly uid = input.required<string>();

  // ── Entity header ──────────────────────────────────────────────────────────
  readonly entity = signal<OtherPartyModel | null>(null);
  readonly entityState = signal<LoadState>('loading');

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
  readonly fOtherKind = signal('');

  readonly saving = signal(false);
  readonly saveError = signal<string | null>(null);
  readonly archiving = signal(false);

  readonly canManage = computed(() => this.session.hasPermission('OTHERPARTY.MANAGE'));
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
    this.loadEntity();
    this.loadBranches();
    this.loadCompanies();
  }

  private loadEntity(): void {
    this.entityState.set('loading');
    this.otherPartyService.getByUid(this.uid()).subscribe({
      next: (e) => {
        this.entity.set(e);
        this.entityState.set('idle');
        this.patchForm(e);
      },
      error: () => this.entityState.set('error'),
    });
  }

  private patchForm(e: OtherPartyModel): void {
    this.fDisplayName.set(e.displayName ?? '');
    this.fLegalName.set(e.legalName ?? '');
    this.fPartyType.set(e.partyType);
    this.fTin.set(e.tin ?? '');
    this.fVatRegistered.set(e.vatRegistered);
    this.fVrn.set(e.vrn ?? '');
    this.fBusinessRegNo.set(e.businessRegNo ?? '');
    this.fMobileMoneyNo.set(e.mobileMoneyNo ?? '');
    this.fPhone.set(e.phone ?? '');
    this.fEmail.set(e.email ?? '');
    this.fPhysicalAddress.set(e.physicalAddress ?? '');
    this.fPostalAddress.set(e.postalAddress ?? '');
    this.fRegion.set(e.region ?? '');
    this.fDistrict.set(e.district ?? '');
    this.fOtherKind.set(e.otherKind ?? '');
  }

  loadBranches(): void {
    this.branchesState.set('loading');
    this.otherPartyService.listBranches(this.uid()).subscribe({
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
    const request: AssignPartyBranchRequest = { branchUid };
    this.otherPartyService.assignBranch(this.uid(), request).subscribe({
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
    if (!branch) {
      this.alerts.success('Branch not found in loaded list — refresh and try again.');
      return;
    }
    this.rowBusyBranchId.set(pb.branchId);
    this.otherPartyService.removeBranch(this.uid(), branch.uid).subscribe({
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
    if (!displayName) {
      this.saveError.set('Display name is required.');
      return;
    }
    this.saving.set(true);
    this.saveError.set(null);

    const request: UpdateOtherPartyRequest = {
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
      otherKind: this.fOtherKind().trim() || undefined,
    };

    this.otherPartyService.update(this.uid(), request).subscribe({
      next: (updated) => {
        this.entity.set(updated);
        this.saving.set(false);
        this.alerts.success('Other party saved', updated.displayName);
      },
      error: (err) => {
        this.saveError.set(this.messageFrom(err, 'Could not save the other party.'));
        this.saving.set(false);
      },
    });
  }

  archive(): void {
    if (this.archiving()) return;
    this.archiving.set(true);
    this.otherPartyService.archive(this.uid()).subscribe({
      next: () => {
        this.archiving.set(false);
        this.entity.update((e) => (e ? { ...e, status: 'ARCHIVED' } : e));
        this.alerts.success('Other party archived');
      },
      error: (err) => {
        this.archiving.set(false);
        this.saveError.set(this.messageFrom(err, 'Could not archive the other party.'));
      },
    });
  }

  restore(): void {
    if (this.archiving()) return;
    this.archiving.set(true);
    this.otherPartyService.restore(this.uid()).subscribe({
      next: () => {
        this.archiving.set(false);
        this.entity.update((e) => (e ? { ...e, status: 'ACTIVE' } : e));
        this.alerts.success('Other party restored');
      },
      error: (err) => {
        this.archiving.set(false);
        this.saveError.set(this.messageFrom(err, 'Could not restore the other party.'));
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
