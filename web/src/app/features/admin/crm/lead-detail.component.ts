import { HttpErrorResponse } from '@angular/common/http';
import { SlicePipe } from '@angular/common';
import { Component, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import {
  DisqualifyLeadRequest,
  LeadDto,
  LeadSource,
  LeadStatus,
  QualifyLeadRequest,
  UpdateLeadRequest,
} from './models/crm.model';
import { CrmService } from './crm.service';
import { CustomerService } from '../parties/customer.service';
import { CustomerModel } from '../models/party.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { UidPickerComponent, UidOption } from '../../../shared/uid-picker/uid-picker.component';

type LoadState = 'loading' | 'idle' | 'error';

/**
 * Lead detail + lifecycle screen. Route: /admin/crm/leads/uid/:uid.
 * Actions: contact (NEW→CONTACTED), qualify (NEW|CONTACTED→QUALIFIED), disqualify (any non-terminal),
 * update fields (non-terminal only).
 */
@Component({
  selector: 'app-lead-detail',
  imports: [FormsModule, RouterLink, SlicePipe, UidPickerComponent],
  templateUrl: './lead-detail.component.html',
  styleUrl: './lead-detail.component.scss',
})
export class LeadDetailComponent {
  private readonly crmService = inject(CrmService);
  private readonly customerService = inject(CustomerService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  // ── Customer picker ────────────────────────────────────────────────────────
  readonly customers = signal<CustomerModel[]>([]);
  readonly customerOptions = computed<UidOption[]>(() =>
    this.customers().map((c) => ({ uid: c.uid, label: c.displayName })),
  );

  readonly uid = input.required<string>();

  // ── Lead header ────────────────────────────────────────────────────────────
  readonly lead = signal<LeadDto | null>(null);
  readonly leadState = signal<LoadState>('loading');

  // ── Edit form fields ───────────────────────────────────────────────────────
  readonly fDisplayName = signal('');
  readonly fLeadSource = signal<LeadSource>('WEBSITE');
  readonly fCompanyName = signal('');
  readonly fContactPerson = signal('');
  readonly fPhone = signal('');
  readonly fEmail = signal('');
  readonly fNotes = signal('');

  readonly saving = signal(false);
  readonly saveError = signal<string | null>(null);

  // ── Contact action ─────────────────────────────────────────────────────────
  readonly contacting = signal(false);
  readonly contactError = signal<string | null>(null);

  // ── Qualify action ─────────────────────────────────────────────────────────
  readonly showQualifyForm = signal(false);
  readonly qualifyMode = signal<'existing' | 'new'>('existing');
  readonly qualifyCustomerUid = signal('');
  readonly qualifyNewName = signal('');
  readonly qualifyNewKind = signal<'CASH_WALK_IN' | 'CREDIT_ACCOUNT'>('CREDIT_ACCOUNT');
  readonly qualifyNewPhone = signal('');
  readonly qualifyNewEmail = signal('');
  readonly qualifyNewAddress = signal('');
  readonly qualifying = signal(false);
  readonly qualifyError = signal<string | null>(null);

  // ── Disqualify action ──────────────────────────────────────────────────────
  readonly showDisqualifyForm = signal(false);
  readonly disqualifyReason = signal('');
  readonly disqualifying = signal(false);
  readonly disqualifyError = signal<string | null>(null);

  // ── Permissions ────────────────────────────────────────────────────────────
  readonly canManage = computed(() => this.session.hasPermission('CRM.LEAD.MANAGE'));
  readonly canQualify = computed(() => this.session.hasPermission('CRM.LEAD.QUALIFY'));

  // ── Status helpers ─────────────────────────────────────────────────────────
  readonly isNew = computed(() => this.lead()?.leadStatus === 'NEW');
  readonly isContacted = computed(() => this.lead()?.leadStatus === 'CONTACTED');
  readonly isTerminal = computed(() => {
    const s = this.lead()?.leadStatus;
    return s === 'CONVERTED' || s === 'DISQUALIFIED';
  });
  readonly canContact = computed(() => this.isNew() && this.canManage());
  readonly canQualifyNow = computed(() => !this.isTerminal() && this.canQualify());
  readonly canDisqualifyNow = computed(() => !this.isTerminal() && this.canManage());
  readonly canEdit = computed(() => !this.isTerminal() && this.canManage());

  readonly leadSourceOptions: Array<{ value: LeadSource; label: string }> = [
    { value: 'WEBSITE', label: 'Website' },
    { value: 'REFERRAL', label: 'Referral' },
    { value: 'WALK_IN', label: 'Walk-in' },
    { value: 'CAMPAIGN', label: 'Campaign' },
    { value: 'COLD_CALL', label: 'Cold Call' },
    { value: 'EXISTING_CUSTOMER', label: 'Existing Customer' },
    { value: 'OTHER', label: 'Other' },
  ];

  constructor() {
    queueMicrotask(() => this.init());
  }

  private init(): void {
    this.loadLead();
    this.loadCustomers();
  }

  private loadCustomers(): void {
    this.organisationService.current().subscribe({
      next: (org) => {
        this.companyService.list(org.uid).subscribe({
          next: (companies) => {
            // Load customers from the first company (context for qualify)
            if (companies.length > 0) {
              this.customerService.list(companies[0].id, undefined, 0, 200).subscribe({
                next: ({ rows }) => this.customers.set(rows),
                error: () => undefined,
              });
            }
          },
          error: () => undefined,
        });
      },
      error: () => undefined,
    });
  }

  private loadLead(): void {
    this.leadState.set('loading');
    this.crmService.getLeadByUid(this.uid()).subscribe({
      next: (lead) => {
        this.lead.set(lead);
        this.leadState.set('idle');
        this.patchForm(lead);
      },
      error: () => this.leadState.set('error'),
    });
  }

  private patchForm(lead: LeadDto): void {
    this.fDisplayName.set(lead.displayName ?? '');
    this.fLeadSource.set(lead.leadSource);
    this.fCompanyName.set(lead.companyName ?? '');
    this.fContactPerson.set(lead.contactPerson ?? '');
    this.fPhone.set(lead.phone ?? '');
    this.fEmail.set(lead.email ?? '');
    this.fNotes.set(lead.notes ?? '');
  }

  save(): void {
    const displayName = this.fDisplayName().trim();
    if (!displayName) { this.saveError.set('Display name is required.'); return; }

    this.saving.set(true);
    this.saveError.set(null);

    const request: UpdateLeadRequest = {
      displayName,
      leadSource: this.fLeadSource(),
      companyName: this.fCompanyName().trim() || undefined,
      contactPerson: this.fContactPerson().trim() || undefined,
      phone: this.fPhone().trim() || undefined,
      email: this.fEmail().trim() || undefined,
      notes: this.fNotes().trim() || undefined,
    };

    this.crmService.updateLead(this.uid(), request).subscribe({
      next: (updated) => {
        this.lead.set(updated);
        this.saving.set(false);
        this.alerts.success('Lead saved', updated.displayName);
      },
      error: (err: unknown) => {
        this.saveError.set(this.messageFrom(err, 'Could not save the lead.'));
        this.saving.set(false);
      },
    });
  }

  // ── Contact ────────────────────────────────────────────────────────────────

  contact(): void {
    if (this.contacting()) return;
    this.contacting.set(true);
    this.contactError.set(null);
    this.crmService.contactLead(this.uid()).subscribe({
      next: (updated) => {
        this.lead.set(updated);
        this.contacting.set(false);
        this.alerts.success('Lead marked as contacted');
      },
      error: (err: unknown) => {
        this.contactError.set(this.messageFrom(err, 'Could not mark as contacted.'));
        this.contacting.set(false);
      },
    });
  }

  // ── Qualify ────────────────────────────────────────────────────────────────

  toggleQualifyForm(): void {
    this.showQualifyForm.update((v) => !v);
    this.qualifyError.set(null);
    if (!this.showQualifyForm()) this.resetQualifyForm();
  }

  private resetQualifyForm(): void {
    this.qualifyMode.set('existing');
    this.qualifyCustomerUid.set('');
    this.qualifyNewName.set('');
    this.qualifyNewKind.set('CREDIT_ACCOUNT');
    this.qualifyNewPhone.set('');
    this.qualifyNewEmail.set('');
    this.qualifyNewAddress.set('');
  }

  qualify(): void {
    if (this.qualifying()) return;
    const mode = this.qualifyMode();

    const request: QualifyLeadRequest = {};
    if (mode === 'existing') {
      const uid = this.qualifyCustomerUid().trim();
      if (!uid) { this.qualifyError.set('Customer UID is required.'); return; }
      request.existingCustomerUid = uid;
    } else {
      const name = this.qualifyNewName().trim();
      if (!name) { this.qualifyError.set('Customer name is required.'); return; }
      request.newCustomerDetails = {
        displayName: name,
        customerKind: this.qualifyNewKind(),
        phone: this.qualifyNewPhone().trim() || undefined,
        email: this.qualifyNewEmail().trim() || undefined,
        physicalAddress: this.qualifyNewAddress().trim() || undefined,
      };
    }

    this.qualifying.set(true);
    this.qualifyError.set(null);
    this.crmService.qualifyLead(this.uid(), request).subscribe({
      next: (updated) => {
        this.lead.set(updated);
        this.qualifying.set(false);
        this.showQualifyForm.set(false);
        this.resetQualifyForm();
        this.alerts.success('Lead qualified', updated.leadNumber);
      },
      error: (err: unknown) => {
        this.qualifyError.set(this.messageFrom(err, 'Could not qualify lead.'));
        this.qualifying.set(false);
      },
    });
  }

  // ── Disqualify ─────────────────────────────────────────────────────────────

  toggleDisqualifyForm(): void {
    this.showDisqualifyForm.update((v) => !v);
    this.disqualifyError.set(null);
    if (!this.showDisqualifyForm()) this.disqualifyReason.set('');
  }

  disqualify(): void {
    if (this.disqualifying()) return;
    const reason = this.disqualifyReason().trim();
    if (!reason) { this.disqualifyError.set('Reason is required.'); return; }

    const request: DisqualifyLeadRequest = { reason };
    this.disqualifying.set(true);
    this.disqualifyError.set(null);
    this.crmService.disqualifyLead(this.uid(), request).subscribe({
      next: (updated) => {
        this.lead.set(updated);
        this.disqualifying.set(false);
        this.showDisqualifyForm.set(false);
        this.disqualifyReason.set('');
        this.alerts.success('Lead disqualified');
      },
      error: (err: unknown) => {
        this.disqualifyError.set(this.messageFrom(err, 'Could not disqualify lead.'));
        this.disqualifying.set(false);
      },
    });
  }

  statusBadgeClass(status: LeadStatus): string {
    switch (status) {
      case 'CONTACTED': return 'text-bg-info';
      case 'QUALIFIED': return 'text-bg-primary';
      case 'CONVERTED': return 'text-bg-success';
      case 'DISQUALIFIED': return 'text-bg-danger';
      default: return 'text-bg-warning';
    }
  }

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}
