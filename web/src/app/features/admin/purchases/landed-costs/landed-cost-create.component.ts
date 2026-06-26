import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { AlertService } from '../../../../core/feedback/alert.service';
import { SessionStore } from '../../../../core/auth/session.store';
import { Company } from '../../models/company.model';
import {
  CreateLandedCostChargeRequest,
  CreateLandedCostRequest,
  LandedCostBasis,
  LandedCostChargeType,
  GoodsReceiptDto,
} from '../../models/purchases.model';
import { CompanyService } from '../../company/company.service';
import { OrganisationService } from '../../organisation/organisation.service';
import { PurchasesService } from '../purchases.service';
import { LandedCostService } from './landed-cost.service';
import { UidPickerComponent, UidOption } from '../../../../shared/uid-picker/uid-picker.component';

interface ChargeEntry {
  chargeType: LandedCostChargeType;
  amount: string;
}

@Component({
  selector: 'app-landed-cost-create',
  imports: [FormsModule, RouterLink, UidPickerComponent],
  templateUrl: './landed-cost-create.component.html',
  styleUrl: './landed-cost-create.component.scss',
})
export class LandedCostCreateComponent {
  private readonly landedCostService = inject(LandedCostService);
  private readonly purchasesService = inject(PurchasesService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly router = inject(Router);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── GR multi-select ────────────────────────────────────────────────────────
  readonly grOptions = signal<UidOption[]>([]);
  /** The GR the user has currently selected in the picker — added to selectedGrUids on click. */
  readonly pickedGrUid = signal('');
  readonly selectedGrUids = signal<string[]>([]);

  // ── Form fields ────────────────────────────────────────────────────────────
  readonly basis = signal<LandedCostBasis>('BY_VALUE');
  readonly notes = signal('');
  readonly charges = signal<ChargeEntry[]>([{ chargeType: 'FREIGHT', amount: '' }]);

  readonly submitting = signal(false);
  readonly formError = signal<string | null>(null);

  readonly canCreate = computed(() => this.session.hasPermission('PURCHASE.LANDEDCOST.MANAGE'));

  readonly basisOptions: Array<{ value: LandedCostBasis; label: string }> = [
    { value: 'BY_VALUE', label: 'By Value (pro-rata to line cost)' },
    { value: 'BY_QUANTITY', label: 'By Quantity (pro-rata to qty in base unit)' },
  ];

  readonly chargeTypeOptions: LandedCostChargeType[] = ['FREIGHT', 'DUTY', 'CLEARING', 'INSURANCE', 'OTHER'];

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
              this.loadGrOptions();
            }
          },
          error: () => this.companyState.set('error'),
        });
      },
      error: () => this.companyState.set('error'),
    });
  }

  private loadGrOptions(): void {
    const companyId = this.selectedCompanyId();
    if (!companyId) return;
    this.purchasesService.listReceipts(companyId, undefined, 0, 100).subscribe({
      next: ({ rows }) => {
        this.grOptions.set(
          rows
            .filter((gr) => gr.status === 'RECEIVED')
            .map((gr) => ({
              uid: gr.uid,
              label: gr.receiptNumber,
              hint: gr.status,
            })),
        );
      },
      error: () => {},
    });
  }

  onCompanyChange(id: string): void {
    this.selectedCompanyId.set(id);
    this.selectedGrUids.set([]);
    this.pickedGrUid.set('');
    if (id) this.loadGrOptions();
  }

  addGr(): void {
    const uid = this.pickedGrUid();
    if (!uid) return;
    if (this.selectedGrUids().includes(uid)) return;
    this.selectedGrUids.update((uids) => [...uids, uid]);
    this.pickedGrUid.set('');
  }

  removeGr(uid: string): void {
    this.selectedGrUids.update((uids) => uids.filter((u) => u !== uid));
  }

  grLabel(uid: string): string {
    return this.grOptions().find((o) => o.uid === uid)?.label ?? uid;
  }

  addCharge(): void {
    this.charges.update((cs) => [...cs, { chargeType: 'FREIGHT', amount: '' }]);
  }

  removeCharge(index: number): void {
    this.charges.update((cs) => cs.filter((_, i) => i !== index));
  }

  updateChargeType(index: number, type: LandedCostChargeType): void {
    this.charges.update((cs) =>
      cs.map((c, i) => (i === index ? { ...c, chargeType: type } : c)),
    );
  }

  updateChargeAmount(index: number, amount: string): void {
    this.charges.update((cs) =>
      cs.map((c, i) => (i === index ? { ...c, amount } : c)),
    );
  }

  submit(): void {
    if (!this.canCreate()) return;
    if (this.selectedGrUids().length === 0) {
      this.formError.set('Select at least one goods receipt.');
      return;
    }
    const charges = this.charges();
    if (charges.length === 0) { this.formError.set('Add at least one charge.'); return; }
    for (const c of charges) {
      const amt = Number(c.amount);
      if (!c.amount.trim() || isNaN(amt) || amt <= 0) {
        this.formError.set('All charge amounts must be greater than zero.');
        return;
      }
    }
    const company = this.companies().find((c) => c.id === this.selectedCompanyId());
    if (!company) { this.formError.set('Could not resolve company.'); return; }

    this.submitting.set(true);
    this.formError.set(null);

    const request: CreateLandedCostRequest = {
      companyUid: company.uid,
      basis: this.basis(),
      notes: this.notes().trim() || undefined,
      receiptUids: this.selectedGrUids(),
      charges: charges.map((c) => ({
        chargeType: c.chargeType,
        amount: c.amount.trim(),
      })),
    };

    this.landedCostService.create(request).subscribe({
      next: (created) => {
        this.submitting.set(false);
        this.alerts.success('Landed cost created', created.landedCostNumber);
        this.router.navigate(['/admin/landed-costs/uid', created.uid]);
      },
      error: (err) => {
        this.formError.set(this.messageFrom(err, 'Could not create landed cost.'));
        this.submitting.set(false);
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
