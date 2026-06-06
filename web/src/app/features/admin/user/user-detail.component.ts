import { Component, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { User } from '../models/user.model';
import { UserBranch } from '../models/user-branch.model';
import { Branch } from '../models/branch.model';
import { Company } from '../models/company.model';
import { UserService } from './user.service';
import { UserBranchService } from './user-branch.service';
import { BranchService } from '../branch/branch.service';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { AlertService } from '../../../core/feedback/alert.service';

/**
 * Shows a user header and manages their branch assignments.
 * Branch picker: company select -> branch select -> make-default checkbox -> Assign.
 * Per-row actions: Set default (if not already) and Remove, each with a busy spinner.
 * Route: /admin/users/:uid — `uid` is bound via withComponentInputBinding.
 */
@Component({
  selector: 'app-user-detail',
  imports: [FormsModule, RouterLink],
  templateUrl: './user-detail.component.html',
  styleUrl: './user-detail.component.scss',
})
export class UserDetailComponent {
  private readonly userService = inject(UserService);
  private readonly userBranchService = inject(UserBranchService);
  private readonly branchService = inject(BranchService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly alerts = inject(AlertService);

  /** Route input — Angular binds the `:uid` path param to this signal via withComponentInputBinding. */
  readonly uid = input.required<string>();

  // User header
  readonly user = signal<User | null>(null);
  readonly userState = signal<'loading' | 'idle' | 'error'>('loading');

  // Branch assignments list
  readonly assignments = signal<UserBranch[]>([]);
  readonly assignmentsState = signal<'loading' | 'idle' | 'error'>('loading');

  /** uid of the assignment whose row action (set-default or remove) is in-flight. */
  readonly rowBusyUid = signal<string | null>(null);

  // Assign form
  readonly companies = signal<Company[]>([]);
  readonly companiesState = signal<'loading' | 'idle' | 'error'>('loading');
  readonly selectedCompanyUid = signal('');
  readonly branches = signal<Branch[]>([]);
  readonly branchesState = signal<'idle' | 'loading' | 'error'>('idle');
  readonly selectedBranchUid = signal('');
  readonly makeDefault = signal(false);
  readonly assigning = signal(false);
  readonly assignError = signal<string | null>(null);

  readonly hasAssignments = computed(() => this.assignments().length > 0);

  constructor() {
    queueMicrotask(() => this.init());
  }

  private init(): void {
    this.loadUser();
    this.loadAssignments();
    this.loadCompanies();
  }

  private loadUser(): void {
    this.userState.set('loading');
    this.userService.get(this.uid()).subscribe({
      next: (u) => {
        this.user.set(u);
        this.userState.set('idle');
      },
      error: () => this.userState.set('error'),
    });
  }

  loadAssignments(): void {
    this.assignmentsState.set('loading');
    this.userBranchService.listForUser(this.uid()).subscribe({
      next: (rows) => {
        this.assignments.set(rows);
        this.assignmentsState.set('idle');
      },
      error: () => this.assignmentsState.set('error'),
    });
  }

  private loadCompanies(): void {
    this.companiesState.set('loading');
    this.organisationService.current().subscribe({
      next: (org) => {
        this.companyService.list(org.uid).subscribe({
          next: (rows) => {
            this.companies.set(rows);
            this.companiesState.set('idle');
          },
          error: () => this.companiesState.set('error'),
        });
      },
      error: () => this.companiesState.set('error'),
    });
  }

  onCompanyChange(companyUid: string): void {
    this.selectedCompanyUid.set(companyUid);
    this.selectedBranchUid.set('');
    this.branches.set([]);
    if (!companyUid) {
      this.branchesState.set('idle');
      return;
    }
    this.branchesState.set('loading');
    this.branchService.list(companyUid).subscribe({
      next: (rows) => {
        this.branches.set(rows);
        this.branchesState.set('idle');
      },
      error: () => this.branchesState.set('error'),
    });
  }

  assign(): void {
    const branchUid = this.selectedBranchUid();
    const userUid = this.uid();
    if (!branchUid) {
      this.assignError.set('Select a branch to assign.');
      return;
    }
    this.assigning.set(true);
    this.assignError.set(null);
    this.userBranchService
      .assign({ userUid, branchUid, makeDefault: this.makeDefault() })
      .subscribe({
        next: () => {
          this.selectedBranchUid.set('');
          this.makeDefault.set(false);
          this.assigning.set(false);
          this.alerts.success('Branch assigned');
          this.loadAssignments();
        },
        error: (err) => {
          this.assignError.set(this.messageFrom(err, 'Could not assign branch.'));
          this.assigning.set(false);
        },
      });
  }

  setDefault(assignment: UserBranch): void {
    if (assignment.isDefault || this.rowBusyUid() !== null) {
      return;
    }
    this.rowBusyUid.set(assignment.uid);
    this.userBranchService.setDefault(assignment.uid).subscribe({
      next: () => {
        this.rowBusyUid.set(null);
        this.alerts.success('Default branch updated', assignment.branchName);
        this.loadAssignments();
      },
      error: () => this.rowBusyUid.set(null),
    });
  }

  remove(assignment: UserBranch): void {
    if (this.rowBusyUid() !== null) {
      return;
    }
    this.rowBusyUid.set(assignment.uid);
    this.userBranchService.remove(assignment.uid).subscribe({
      next: () => {
        this.rowBusyUid.set(null);
        this.alerts.success('Branch removed', assignment.branchName);
        this.loadAssignments();
      },
      error: () => this.rowBusyUid.set(null),
    });
  }

  private messageFrom(err: unknown, fallback: string): string {
    const errors = (err as { error?: { errors?: string[] } })?.error?.errors;
    return errors?.length ? errors[0] : fallback;
  }
}
