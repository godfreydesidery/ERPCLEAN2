import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { Company } from '../models/company.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import {
  CreateWhtTypeRequest,
  UpdateWhtTypeRequest,
  WhtKind,
  WhtTypeDto,
} from './models/tax.model';
import { TaxService } from './tax.service';

/**
 * WHT Types master list screen (FR-WHT-01/02/03 / US-VAT-05).
 * - Table of WHT types: code, name, kind, rate %, active.
 * - Inline create form (WHT.MANAGE).
 * - Inline edit row (WHT.MANAGE).
 * - Deactivate action (WHT.MANAGE).
 * - Read-only view for WHT.VIEW without WHT.MANAGE.
 */
@Component({
  selector: 'app-wht-types-list',
  imports: [FormsModule],
  templateUrl: './wht-types-list.component.html',
  styleUrl: './wht-types-list.component.scss',
})
export class WhtTypesListComponent {
  private readonly taxService = inject(TaxService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  // ── Company context ────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── List ───────────────────────────────────────────────────────────────────
  readonly rows = signal<WhtTypeDto[]>([]);
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('idle');

  // ── Create form ────────────────────────────────────────────────────────────
  readonly showCreateForm = signal(false);
  readonly creating = signal(false);
  readonly createError = signal<string | null>(null);
  readonly newCode = signal('');
  readonly newName = signal('');
  readonly newKind = signal<WhtKind>('WHT_ON_PAYMENT');
  readonly newRate = signal('');

  // ── Edit ───────────────────────────────────────────────────────────────────
  readonly editingUid = signal<string | null>(null);
  readonly editName = signal('');
  readonly editRate = signal('');
  readonly editActive = signal(true);
  readonly saving = signal(false);
  readonly editError = signal<string | null>(null);

  // ── Deactivate ─────────────────────────────────────────────────────────────
  readonly deactivatingUid = signal<string | null>(null);

  // ── Permissions ───────────────────────────────────────────────────────────
  readonly canView = computed(() => this.session.hasPermission('WHT.VIEW'));
  readonly canManage = computed(() => this.session.hasPermission('WHT.MANAGE'));

  readonly isEmpty = computed(() => this.state() === 'idle' && this.rows().length === 0);

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
              this.load();
            }
          },
          error: () => this.companyState.set('error'),
        });
      },
      error: () => this.companyState.set('error'),
    });
  }

  onCompanyChange(id: string): void {
    this.selectedCompanyId.set(id);
    if (id) this.load();
  }

  load(): void {
    const companyId = this.selectedCompanyId();
    if (!companyId) return;
    this.state.set('loading');
    this.taxService.listWhtTypes(companyId).subscribe({
      next: (list) => {
        this.rows.set(list);
        this.state.set('idle');
      },
      error: (err) =>
        this.state.set(err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error'),
    });
  }

  // ── Create ─────────────────────────────────────────────────────────────────

  openCreate(): void {
    this.showCreateForm.set(true);
    this.createError.set(null);
    this.newCode.set('');
    this.newName.set('');
    this.newKind.set('WHT_ON_PAYMENT');
    this.newRate.set('');
  }

  cancelCreate(): void {
    this.showCreateForm.set(false);
    this.createError.set(null);
  }

  submitCreate(): void {
    const code = String(this.newCode() ?? '').trim();
    const name = String(this.newName() ?? '').trim();
    const rateStr = String(this.newRate() ?? '').trim();

    if (!code) { this.createError.set('Code is required.'); return; }
    if (!name) { this.createError.set('Name is required.'); return; }
    if (!rateStr || +rateStr < 0) { this.createError.set('Enter a valid rate (0 or greater).'); return; }

    const company = this.companies().find((c) => c.id === this.selectedCompanyId());
    if (!company) { this.createError.set('Could not resolve company.'); return; }

    const request: CreateWhtTypeRequest = {
      companyUid: company.uid,
      code,
      name,
      kind: this.newKind(),
      ratePct: rateStr,
    };

    this.creating.set(true);
    this.createError.set(null);

    this.taxService.createWhtType(request).subscribe({
      next: (wht) => {
        this.creating.set(false);
        this.showCreateForm.set(false);
        this.alerts.success('WHT type created', wht.name);
        this.load();
      },
      error: (err) => {
        this.createError.set(this.messageFrom(err, 'Could not create WHT type.'));
        this.creating.set(false);
      },
    });
  }

  // ── Edit ───────────────────────────────────────────────────────────────────

  startEdit(wht: WhtTypeDto): void {
    this.editingUid.set(wht.uid);
    this.editName.set(wht.name);
    this.editRate.set(String(+(wht.ratePct ?? 0)));
    this.editActive.set(wht.active);
    this.editError.set(null);
  }

  cancelEdit(): void {
    this.editingUid.set(null);
    this.editError.set(null);
  }

  saveEdit(wht: WhtTypeDto): void {
    const name = String(this.editName() ?? '').trim();
    const rateStr = String(this.editRate() ?? '').trim();
    if (!name) { this.editError.set('Name is required.'); return; }
    if (!rateStr || +rateStr < 0) { this.editError.set('Enter a valid rate.'); return; }

    const request: UpdateWhtTypeRequest = { name, ratePct: rateStr, active: this.editActive() };
    this.saving.set(true);
    this.editError.set(null);

    this.taxService.updateWhtType(wht.uid, request).subscribe({
      next: (updated) => {
        this.rows.update((list) => list.map((r) => (r.uid === updated.uid ? updated : r)));
        this.saving.set(false);
        this.editingUid.set(null);
        this.alerts.success('Saved', updated.name);
      },
      error: (err) => {
        this.editError.set(this.messageFrom(err, 'Could not save.'));
        this.saving.set(false);
      },
    });
  }

  // ── Deactivate ─────────────────────────────────────────────────────────────

  deactivate(wht: WhtTypeDto): void {
    if (!wht.active || this.deactivatingUid() !== null) return;
    this.deactivatingUid.set(wht.uid);
    this.taxService.deactivateWhtType(wht.uid).subscribe({
      next: (updated) => {
        this.rows.update((list) => list.map((r) => (r.uid === updated.uid ? updated : r)));
        this.deactivatingUid.set(null);
        this.alerts.success('Deactivated', updated.name);
      },
      error: (err) => {
        this.deactivatingUid.set(null);
        this.alerts.error('Error', this.messageFrom(err, 'Could not deactivate.'));
      },
    });
  }

  // ── Display helpers ────────────────────────────────────────────────────────

  fmtRate(v: number | string | null | undefined): string {
    const n = +(v ?? 0);
    return Number.isFinite(n) ? n.toFixed(2) : '0.00';
  }

  kindBadgeClass(kind: WhtKind): string {
    return kind === 'WHT_ON_PAYMENT' ? 'text-bg-primary' : 'text-bg-info';
  }

  kindLabel(kind: WhtKind): string {
    return kind === 'WHT_ON_PAYMENT' ? 'On Payment' : 'On Receipt';
  }

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}
