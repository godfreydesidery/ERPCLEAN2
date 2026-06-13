import { HttpErrorResponse } from '@angular/common/http';
import { DecimalPipe } from '@angular/common';
import { Component, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import {
  CreateProjectTaskRequest,
  CreateTimesheetRequest,
  IssueLine,
  IssueToProjectRequest,
  IssueToProjectResultDto,
  MasterStatus,
  ProjectCostType,
  ProjectDto,
  ProjectPnlDto,
  ProjectStatus,
  ProjectTaskDto,
  ProjectTimesheetDto,
  UpdateProjectRequest,
} from './models/projects.model';
import { PageMeta } from '../../../core/api/api-response.model';
import { ProjectsService } from './projects.service';
import type { TimesheetPage } from './projects.service';
import { Company } from '../models/company.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { PaginatorComponent } from '../../../shared/paginator/paginator.component';
import { CustomerService } from '../parties/customer.service';
import { CustomerModel } from '../models/party.model';
import { UserService } from '../user/user.service';
import { User } from '../models/user.model';
import { ProductService } from '../products/product.service';
import { ProductModel } from '../models/product.model';
import { UidPickerComponent, UidOption } from '../../../shared/uid-picker/uid-picker.component';

type LoadState = 'loading' | 'idle' | 'error';

const DEFAULT_TS_SIZE = 20;

/**
 * Project detail + edit + lifecycle actions screen.
 * Route: /admin/projects/uid/:uid
 *
 * Panels:
 *  - Header (status badge + lifecycle action buttons)
 *  - Edit form (name, dates, budget, notes)
 *  - Tasks panel (list + inline create/edit/deactivate)
 *  - Timesheets panel (paginated list + record form)
 *  - Issue to Job panel (gated on PROJECTS.ISSUE.CREATE)
 *  - P&L report button (gated on PROJECTS.COSTING.VIEW)
 */
@Component({
  selector: 'app-project-detail',
  imports: [FormsModule, RouterLink, DecimalPipe, PaginatorComponent, UidPickerComponent],
  templateUrl: './project-detail.component.html',
  styleUrl: './project-detail.component.scss',
})
export class ProjectDetailComponent {
  private readonly projectsService = inject(ProjectsService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly customerService = inject(CustomerService);
  private readonly userService = inject(UserService);
  private readonly productService = inject(ProductService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  /** Route input bound via withComponentInputBinding. */
  readonly uid = input.required<string>();

  // ── Project header ─────────────────────────────────────────────────────────
  readonly project = signal<ProjectDto | null>(null);
  readonly projectState = signal<LoadState>('loading');

  // ── Edit form fields ───────────────────────────────────────────────────────
  readonly fName = signal('');
  readonly fCustomerUid = signal('');
  readonly fManagerUid = signal('');
  readonly fPlannedStart = signal('');
  readonly fPlannedEnd = signal('');
  readonly fBudget = signal('');
  readonly fNotes = signal('');

  readonly saving = signal(false);
  readonly saveError = signal<string | null>(null);

  // ── Status actions ─────────────────────────────────────────────────────────
  readonly transitioning = signal(false);
  readonly archiving = signal(false);

  // ── Tasks panel ────────────────────────────────────────────────────────────
  readonly tasks = signal<ProjectTaskDto[]>([]);
  readonly tasksState = signal<LoadState>('loading');
  readonly showTaskForm = signal(false);
  readonly tCode = signal('');
  readonly tName = signal('');
  readonly tHours = signal('');
  readonly tBillable = signal(false);
  readonly savingTask = signal(false);
  readonly taskFormError = signal<string | null>(null);
  readonly editingTaskUid = signal<string | null>(null);
  readonly taskRowBusy = signal<string | null>(null);

  // ── Timesheets panel ───────────────────────────────────────────────────────
  readonly timesheets = signal<ProjectTimesheetDto[]>([]);
  readonly tsMeta = signal<PageMeta>({ page: 0, size: DEFAULT_TS_SIZE, totalElements: 0, totalPages: 0, hasNext: false });
  readonly tsState = signal<LoadState>('loading');
  readonly tsPage = signal(0);
  readonly showTimesheetForm = signal(false);
  readonly tsUserId = signal('');
  readonly tsWorkDate = signal('');
  readonly tsHours = signal('');
  readonly tsBillable = signal(false);
  readonly tsRate = signal('');
  readonly tsNotes = signal('');
  readonly tsTaskUid = signal('');
  readonly savingTs = signal(false);
  readonly tsFormError = signal<string | null>(null);

  // ── Issue to Job panel ─────────────────────────────────────────────────────
  readonly showIssueForm = signal(false);
  readonly issueLines = signal<Array<{ productUid: string; qty: string }>>([{ productUid: '', qty: '' }]);
  readonly issueDate = signal('');
  readonly issueReason = signal('');
  readonly issuingToJob = signal(false);
  readonly issueFormError = signal<string | null>(null);
  readonly lastIssueResult = signal<IssueToProjectResultDto | null>(null);

  // ── P&L report ─────────────────────────────────────────────────────────────
  readonly pnl = signal<ProjectPnlDto | null>(null);
  readonly pnlState = signal<'idle' | 'loading' | 'error'>('idle');

  // ── Company context (for issue form + pickers) ────────────────────────────
  readonly companies = signal<Company[]>([]);

  // ── Picker option lists ────────────────────────────────────────────────────
  readonly pickerCustomers = signal<CustomerModel[]>([]);
  readonly pickerUsers = signal<User[]>([]);
  readonly pickerProducts = signal<ProductModel[]>([]);

  readonly customerOptions = computed<UidOption[]>(() =>
    this.pickerCustomers().map((c) => ({ uid: c.uid, label: c.displayName })),
  );
  readonly userOptions = computed<UidOption[]>(() =>
    this.pickerUsers().map((u) => ({ uid: u.uid, label: u.displayName, hint: u.username })),
  );
  readonly taskOptions = computed<UidOption[]>(() =>
    this.tasks().map((t) => ({ uid: t.uid, label: t.name, hint: t.taskCode })),
  );
  readonly productOptions = computed<UidOption[]>(() =>
    this.pickerProducts().map((p) => ({ uid: p.uid, label: p.name, hint: p.code })),
  );

  // ── Permissions ────────────────────────────────────────────────────────────
  readonly canManage = computed(() => this.session.hasPermission('PROJECTS.PROJECT.MANAGE'));
  readonly canTaskManage = computed(() => this.session.hasPermission('PROJECTS.TASK.MANAGE'));
  readonly canTimesheetRecord = computed(() => this.session.hasPermission('PROJECTS.TIMESHEET.RECORD'));
  readonly canTimesheetView = computed(() => this.session.hasPermission('PROJECTS.TIMESHEET.VIEW'));
  readonly canIssue = computed(() => this.session.hasPermission('PROJECTS.ISSUE.CREATE'));
  readonly canCosting = computed(() => this.session.hasPermission('PROJECTS.COSTING.VIEW'));

  // ── Computed helpers ───────────────────────────────────────────────────────

  /** Non-terminal statuses that allow transitions. */
  readonly isTerminal = computed(() => {
    const ps = this.project()?.projectStatus;
    return ps === 'COMPLETED' || ps === 'CANCELLED';
  });

  /** Statuses that allow material tagging (issue-to-job). */
  readonly isTaggable = computed(() => {
    const ps = this.project()?.projectStatus;
    return ps === 'ACTIVE' || ps === 'ON_HOLD';
  });

  constructor() {
    queueMicrotask(() => this.init());
  }

  private init(): void {
    this.loadProject();
    this.loadTasks();
    this.loadTimesheets(0);
    this.loadCompanies();
  }

  // ── Loaders ────────────────────────────────────────────────────────────────

  private loadProject(): void {
    this.projectState.set('loading');
    this.projectsService.getByUid(this.uid()).subscribe({
      next: (p) => {
        this.project.set(p);
        this.projectState.set('idle');
        this.patchForm(p);
      },
      error: () => this.projectState.set('error'),
    });
  }

  private patchForm(p: ProjectDto): void {
    this.fName.set(p.name ?? '');
    this.fPlannedStart.set(p.plannedStartDate ?? '');
    this.fPlannedEnd.set(p.plannedEndDate ?? '');
    this.fBudget.set(p.budgetAmount ?? '');
    this.fNotes.set(p.notes ?? '');
  }

  private loadTasks(): void {
    this.tasksState.set('loading');
    this.projectsService.listTasks(this.uid()).subscribe({
      next: (rows) => {
        this.tasks.set(rows);
        this.tasksState.set('idle');
      },
      error: () => this.tasksState.set('error'),
    });
  }

  loadTimesheets(page: number): void {
    this.tsState.set('loading');
    this.tsPage.set(page);
    this.projectsService.listTimesheets(this.uid(), page, DEFAULT_TS_SIZE).subscribe({
      next: ({ rows, meta }: TimesheetPage) => {
        this.timesheets.set(rows);
        this.tsMeta.set(meta);
        this.tsState.set('idle');
      },
      error: () => this.tsState.set('error'),
    });
  }

  private loadCompanies(): void {
    this.organisationService.current().subscribe({
      next: (org) => {
        this.companyService.list(org.uid).subscribe({
          next: (list) => {
            this.companies.set(list);
            if (list.length > 0) {
              this.loadPickerOptions(list[0].id);
            }
          },
          error: () => undefined,
        });
      },
      error: () => undefined,
    });
    // Users are not scoped to company
    this.userService.list().subscribe({
      next: (rows) => this.pickerUsers.set(rows),
      error: () => undefined,
    });
  }

  private loadPickerOptions(companyId: string): void {
    this.customerService.list(companyId, undefined, 0, 200).subscribe({
      next: ({ rows }) => this.pickerCustomers.set(rows),
      error: () => undefined,
    });
    this.productService.list(companyId, undefined, 0, 200).subscribe({
      next: ({ rows }) => this.pickerProducts.set(rows),
      error: () => undefined,
    });
  }

  // ── Edit project ───────────────────────────────────────────────────────────

  save(): void {
    const name = this.fName().trim();
    if (!name) { this.saveError.set('Name is required.'); return; }
    this.saving.set(true);
    this.saveError.set(null);

    const request: UpdateProjectRequest = {
      name,
      customerUid: this.fCustomerUid().trim() || undefined,
      managerUid: this.fManagerUid().trim() || undefined,
      plannedStartDate: this.fPlannedStart().trim() || undefined,
      plannedEndDate: this.fPlannedEnd().trim() || undefined,
      budgetAmount: this.fBudget().trim() || undefined,
      notes: this.fNotes().trim() || undefined,
    };

    this.projectsService.update(this.uid(), request).subscribe({
      next: (updated) => {
        this.project.set(updated);
        this.saving.set(false);
        this.alerts.success('Project saved', updated.name);
      },
      error: (err) => {
        this.saveError.set(this.messageFrom(err, 'Could not save the project.'));
        this.saving.set(false);
      },
    });
  }

  // ── Lifecycle actions ──────────────────────────────────────────────────────

  transition(targetStatus: ProjectStatus): void {
    if (this.transitioning()) return;
    this.transitioning.set(true);
    this.projectsService.changeStatus(this.uid(), targetStatus).subscribe({
      next: (updated) => {
        this.project.set(updated);
        this.transitioning.set(false);
        this.alerts.success('Status updated', updated.projectStatus);
      },
      error: (err) => {
        this.transitioning.set(false);
        this.saveError.set(this.messageFrom(err, 'Could not change status.'));
      },
    });
  }

  archive(): void {
    if (this.archiving()) return;
    this.archiving.set(true);
    this.projectsService.archive(this.uid()).subscribe({
      next: (updated) => {
        this.project.set(updated);
        this.archiving.set(false);
        this.alerts.success('Project archived');
      },
      error: (err) => {
        this.archiving.set(false);
        this.saveError.set(this.messageFrom(err, 'Could not archive project.'));
      },
    });
  }

  // ── Tasks ──────────────────────────────────────────────────────────────────

  toggleTaskForm(): void {
    this.showTaskForm.update((v) => !v);
    this.taskFormError.set(null);
    this.editingTaskUid.set(null);
    if (!this.showTaskForm()) this.resetTaskForm();
  }

  startEditTask(task: ProjectTaskDto): void {
    this.editingTaskUid.set(task.uid);
    this.tCode.set(task.taskCode);
    this.tName.set(task.name);
    this.tHours.set(task.plannedHours);
    this.tBillable.set(task.billable);
    this.taskFormError.set(null);
    this.showTaskForm.set(true);
  }

  private resetTaskForm(): void {
    this.tCode.set('');
    this.tName.set('');
    this.tHours.set('');
    this.tBillable.set(false);
    this.editingTaskUid.set(null);
  }

  saveTask(): void {
    const taskCode = this.tCode().trim();
    const name = this.tName().trim();
    if (!taskCode) { this.taskFormError.set('Task code is required.'); return; }
    if (!name) { this.taskFormError.set('Task name is required.'); return; }

    this.savingTask.set(true);
    this.taskFormError.set(null);

    const request: CreateProjectTaskRequest = {
      taskCode,
      name,
      plannedHours: this.tHours().trim() || '0',
      billable: this.tBillable(),
    };

    const editingUid = this.editingTaskUid();
    const call = editingUid
      ? this.projectsService.updateTask(editingUid, request)
      : this.projectsService.createTask(this.uid(), request);

    call.subscribe({
      next: () => {
        this.savingTask.set(false);
        this.resetTaskForm();
        this.showTaskForm.set(false);
        this.alerts.success(editingUid ? 'Task updated' : 'Task created', name);
        this.loadTasks();
      },
      error: (err) => {
        this.taskFormError.set(this.messageFrom(err, 'Could not save task.'));
        this.savingTask.set(false);
      },
    });
  }

  deactivateTask(task: ProjectTaskDto): void {
    if (this.taskRowBusy() !== null) return;
    this.taskRowBusy.set(task.uid);
    this.projectsService.deactivateTask(task.uid).subscribe({
      next: () => {
        this.taskRowBusy.set(null);
        this.alerts.success('Task deactivated', task.name);
        this.loadTasks();
      },
      error: () => this.taskRowBusy.set(null),
    });
  }

  // ── Timesheets ─────────────────────────────────────────────────────────────

  goToTsPage(page: number): void { this.loadTimesheets(page); }
  tsPrevPage(): void { if (this.tsPage() > 0) this.loadTimesheets(this.tsPage() - 1); }
  tsNextPage(): void { if (this.tsMeta().hasNext) this.loadTimesheets(this.tsPage() + 1); }

  toggleTimesheetForm(): void {
    this.showTimesheetForm.update((v) => !v);
    this.tsFormError.set(null);
    if (!this.showTimesheetForm()) this.resetTimesheetForm();
  }

  private resetTimesheetForm(): void {
    this.tsUserId.set('');
    this.tsWorkDate.set('');
    this.tsHours.set('');
    this.tsBillable.set(false);
    this.tsRate.set('');
    this.tsNotes.set('');
    this.tsTaskUid.set('');
  }

  recordTimesheet(): void {
    const userId = this.tsUserId().trim();
    const workDate = this.tsWorkDate().trim();
    const hours = this.tsHours().trim();
    if (!userId) { this.tsFormError.set('User ID is required.'); return; }
    if (!workDate) { this.tsFormError.set('Work date is required.'); return; }
    if (!hours) { this.tsFormError.set('Hours is required.'); return; }

    this.savingTs.set(true);
    this.tsFormError.set(null);

    const request: CreateTimesheetRequest = {
      userId,
      workDate,
      hours,
      billable: this.tsBillable(),
      projectTaskUid: this.tsTaskUid().trim() || undefined,
      plannedRateAmount: this.tsRate().trim() || undefined,
      notes: this.tsNotes().trim() || undefined,
    };

    this.projectsService.recordTimesheet(this.uid(), request).subscribe({
      next: () => {
        this.savingTs.set(false);
        this.resetTimesheetForm();
        this.showTimesheetForm.set(false);
        this.alerts.success('Timesheet recorded');
        this.loadTimesheets(0);
      },
      error: (err) => {
        this.tsFormError.set(this.messageFrom(err, 'Could not record timesheet.'));
        this.savingTs.set(false);
      },
    });
  }

  // ── Issue to Job ───────────────────────────────────────────────────────────

  toggleIssueForm(): void {
    this.showIssueForm.update((v) => !v);
    this.issueFormError.set(null);
    this.lastIssueResult.set(null);
    if (!this.showIssueForm()) this.resetIssueForm();
  }

  private resetIssueForm(): void {
    this.issueLines.set([{ productUid: '', qty: '' }]);
    this.issueDate.set('');
    this.issueReason.set('');
  }

  addIssueLine(): void {
    this.issueLines.update((lines) => [...lines, { productUid: '', qty: '' }]);
  }

  removeIssueLine(index: number): void {
    this.issueLines.update((lines) => lines.filter((_, i) => i !== index));
  }

  updateIssueLine(index: number, field: 'productUid' | 'qty', value: string): void {
    this.issueLines.update((lines) =>
      lines.map((l, i) => i === index ? { ...l, [field]: value } : l),
    );
  }

  submitIssue(): void {
    const project = this.project();
    if (!project) return;

    const lines = this.issueLines().filter((l) => l.productUid.trim() && l.qty.trim());
    if (lines.length === 0) { this.issueFormError.set('At least one product line is required.'); return; }

    const company = this.companies()[0];
    if (!company) { this.issueFormError.set('Could not resolve company.'); return; }

    this.issuingToJob.set(true);
    this.issueFormError.set(null);

    const request: IssueToProjectRequest = {
      companyUid: company.uid,
      branchUid: company.uid, // branch resolved from context
      projectUid: project.uid,
      lines: lines as IssueLine[],
      issueDate: this.issueDate().trim() || undefined,
      reason: this.issueReason().trim() || undefined,
    };

    this.projectsService.issueToProject(request).subscribe({
      next: (result) => {
        this.issuingToJob.set(false);
        this.lastIssueResult.set(result);
        this.resetIssueForm();
        this.alerts.success('Materials issued', result.issueNumber);
      },
      error: (err) => {
        this.issueFormError.set(this.messageFrom(err, 'Could not issue materials.'));
        this.issuingToJob.set(false);
      },
    });
  }

  // ── P&L Report ─────────────────────────────────────────────────────────────

  loadPnl(): void {
    this.pnlState.set('loading');
    this.pnl.set(null);
    this.projectsService.getProjectPnl(this.uid()).subscribe({
      next: (data) => {
        this.pnl.set(data);
        this.pnlState.set('idle');
      },
      error: () => this.pnlState.set('error'),
    });
  }

  // ── Display helpers ────────────────────────────────────────────────────────

  projectStatusBadgeClass(status: ProjectStatus): string {
    switch (status) {
      case 'ACTIVE': return 'text-bg-success';
      case 'ON_HOLD': return 'text-bg-warning';
      case 'COMPLETED': return 'text-bg-primary';
      case 'CANCELLED': return 'text-bg-danger';
      default: return 'text-bg-secondary';
    }
  }

  masterStatusBadgeClass(status: MasterStatus): string {
    switch (status) {
      case 'ACTIVE': return 'text-bg-success';
      case 'ARCHIVED': return 'text-bg-secondary';
      default: return 'text-bg-warning';
    }
  }

  costTypeBadgeClass(ct: ProjectCostType): string {
    switch (ct) {
      case 'MATERIAL': return 'text-bg-info';
      case 'LABOUR': return 'text-bg-primary';
      case 'SUBCONTRACT': return 'text-bg-warning';
      case 'OVERHEAD': return 'text-bg-secondary';
      default: return 'text-bg-light border';
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
