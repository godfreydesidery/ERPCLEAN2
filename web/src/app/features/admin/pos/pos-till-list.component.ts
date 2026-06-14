import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { Company } from '../models/company.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { UidPickerComponent, UidOption } from '../../../shared/uid-picker/uid-picker.component';
import { BranchService } from '../branch/branch.service';
import { Branch } from '../models/branch.model';
import { CreatePosTillRequest, PosTillDto } from './models/pos.model';
import { PosService } from './pos.service';

/**
 * POS Till management screen.
 * Lists tills for the selected company + branch.
 * Inline create: name + branch picker. Deactivate action.
 * Gated: POS.TILL.VIEW / POS.TILL.MANAGE.
 */
@Component({
  selector: 'app-pos-till-list',
  imports: [FormsModule, UidPickerComponent],
  templateUrl: './pos-till-list.component.html',
  styleUrl: './pos-till-list.component.scss',
})
export class PosTillListComponent {
  private readonly posService = inject(PosService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly branchService = inject(BranchService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  // ── Company context ────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── Branch context ─────────────────────────────────────────────────────────
  readonly branches = signal<Branch[]>([]);
  readonly selectedBranchId = signal('');

  readonly branchOptions = computed<UidOption[]>(() =>
    this.branches().map((b) => ({ uid: b.uid, label: b.name, hint: b.code })),
  );

  // ── List state ─────────────────────────────────────────────────────────────
  readonly rows = signal<PosTillDto[]>([]);
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('idle');

  // ── Create form ────────────────────────────────────────────────────────────
  readonly showCreateForm = signal(false);
  readonly newName = signal('');
  readonly newBranchUid = signal('');
  readonly saving = signal(false);
  readonly formError = signal<string | null>(null);

  // ── Row busy (deactivate) ──────────────────────────────────────────────────
  readonly rowBusyUid = signal<string | null>(null);

  readonly canView = computed(() => this.session.hasPermission('POS.TILL.VIEW'));
  readonly canManage = computed(() => this.session.hasPermission('POS.TILL.MANAGE'));
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
              this.loadBranches(list[0].uid);
            }
          },
          error: () => this.companyState.set('error'),
        });
      },
      error: () => this.companyState.set('error'),
    });
  }

  private loadBranches(companyUid: string): void {
    this.branchService.list(companyUid).subscribe({
      next: (list) => {
        this.branches.set(list);
        if (list.length > 0) {
          this.selectedBranchId.set(list[0].id);
          this.load();
        }
      },
      error: () => { /* non-fatal: can load tills without branch filter */ this.load(); },
    });
  }

  onCompanyChange(id: string): void {
    this.selectedCompanyId.set(id);
    this.rows.set([]);
    const company = this.companies().find((c) => c.id === id);
    if (company) this.loadBranches(company.uid);
  }

  onBranchChange(id: string): void {
    this.selectedBranchId.set(id);
    this.load();
  }

  load(): void {
    const companyId = this.selectedCompanyId();
    if (!companyId) return;
    this.state.set('loading');
    const branchId = this.selectedBranchId() || undefined;
    this.posService.listTills(companyId, branchId).subscribe({
      next: (rows) => {
        this.rows.set(rows);
        this.state.set('idle');
      },
      error: (err: unknown) =>
        this.state.set(err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error'),
    });
  }

  // ── Create ─────────────────────────────────────────────────────────────────

  toggleCreateForm(): void {
    this.showCreateForm.update((v) => !v);
    this.formError.set(null);
    if (!this.showCreateForm()) this.resetCreateForm();
  }

  private resetCreateForm(): void {
    this.newName.set('');
    this.newBranchUid.set('');
  }

  create(): void {
    const name = this.newName().trim();
    const branchUid = this.newBranchUid().trim();

    if (!name) { this.formError.set('Till name is required.'); return; }
    if (!branchUid) { this.formError.set('Branch is required.'); return; }

    const company = this.companies().find((c) => c.id === this.selectedCompanyId());
    if (!company) { this.formError.set('Could not resolve selected company.'); return; }

    const branch = this.branches().find((b) => b.uid === branchUid);
    if (!branch) { this.formError.set('Could not resolve selected branch.'); return; }

    this.saving.set(true);
    this.formError.set(null);

    const request: CreatePosTillRequest = {
      companyUid: company.uid,
      branchId: branch.id,
      name,
    };

    this.posService.createTill(request).subscribe({
      next: (created) => {
        this.saving.set(false);
        this.resetCreateForm();
        this.showCreateForm.set(false);
        this.alerts.success('Till created', created.name);
        this.load();
      },
      error: (err: unknown) => {
        this.formError.set(this.messageFrom(err, 'Could not create till.'));
        this.saving.set(false);
      },
    });
  }

  // ── Deactivate ─────────────────────────────────────────────────────────────

  deactivate(till: PosTillDto): void {
    if (this.rowBusyUid() !== null) return;
    this.rowBusyUid.set(till.uid);
    this.posService.deleteTill(till.uid).subscribe({
      next: () => {
        this.rowBusyUid.set(null);
        this.alerts.success('Till deactivated', till.name);
        this.load();
      },
      error: (err: unknown) => {
        this.rowBusyUid.set(null);
        this.alerts.error('Could not deactivate till', this.messageFrom(err, 'Unknown error.'));
      },
    });
  }

  // ── Display helpers ────────────────────────────────────────────────────────

  branchName(branchId: string): string {
    return this.branches().find((b) => b.id === branchId)?.name ?? branchId;
  }

  statusBadgeClass(status: string): string {
    switch (status) {
      case 'ACTIVE': return 'text-bg-success';
      case 'ARCHIVED': return 'text-bg-secondary';
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
