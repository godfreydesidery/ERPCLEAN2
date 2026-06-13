import { HttpErrorResponse } from '@angular/common/http';
import { DecimalPipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { debounceTime, distinctUntilChanged, map, merge, skip, Subject, switchMap } from 'rxjs';
import { PageMeta } from '../../../core/api/api-response.model';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { Company } from '../models/company.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { ProjectsService } from './projects.service';
import type { ProjectPage } from './projects.service';
import { CreateProjectRequest, ProjectDto, ProjectStatus } from './models/projects.model';

const DEFAULT_SIZE = 20;

interface LoadTrigger { page: number }

/**
 * Project list screen. Company scope, status filter, inline create.
 * Route: /admin/projects
 */
@Component({
  selector: 'app-project-list',
  imports: [FormsModule, RouterLink, DecimalPipe],
  templateUrl: './project-list.component.html',
  styleUrl: './project-list.component.scss',
})
export class ProjectListComponent {
  private readonly projectsService = inject(ProjectsService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  // ── Company context ──────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── List state ───────────────────────────────────────────────────────────────
  readonly rows = signal<ProjectDto[]>([]);
  readonly meta = signal<PageMeta>({ page: 0, size: DEFAULT_SIZE, totalElements: 0, totalPages: 0, hasNext: false });
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('idle');
  readonly currentPage = signal(0);

  // ── Create form ──────────────────────────────────────────────────────────────
  readonly showCreateForm = signal(false);
  readonly fName = signal('');
  readonly fPlannedStart = signal('');
  readonly fPlannedEnd = signal('');
  readonly fBudget = signal('');
  readonly fNotes = signal('');
  readonly saving = signal(false);
  readonly formError = signal<string | null>(null);

  private readonly immediateTrigger$ = new Subject<LoadTrigger>();

  readonly canCreate = computed(() => this.session.hasPermission('PROJECTS.PROJECT.CREATE'));
  readonly isEmpty = computed(() => this.state() === 'idle' && this.rows().length === 0);

  constructor() {
    const typingTrigger$ = toObservable(this.selectedCompanyId).pipe(
      skip(1),
      debounceTime(50),
      distinctUntilChanged(),
      map((): LoadTrigger => ({ page: 0 })),
    );

    merge(typingTrigger$, this.immediateTrigger$)
      .pipe(
        switchMap(({ page }) => {
          const companyId = this.selectedCompanyId();
          if (!companyId) return [];
          this.state.set('loading');
          this.currentPage.set(page);
          return this.projectsService.list(companyId, page, DEFAULT_SIZE);
        }),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: ({ rows, meta }: ProjectPage) => {
          this.rows.set(rows);
          this.meta.set(meta);
          this.state.set('idle');
        },
        error: (err: unknown) =>
          this.state.set(err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error'),
      });

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
              this.load(0);
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
    if (id) this.load(0);
  }

  load(page: number): void {
    const companyId = this.selectedCompanyId();
    if (!companyId) return;
    this.immediateTrigger$.next({ page });
  }

  prevPage(): void { if (this.currentPage() > 0) this.load(this.currentPage() - 1); }
  nextPage(): void { if (this.meta().hasNext) this.load(this.currentPage() + 1); }

  // ── Create form ───────────────────────────────────────────────────────────────

  toggleCreateForm(): void {
    this.showCreateForm.update((v) => !v);
    this.formError.set(null);
    if (!this.showCreateForm()) this.resetCreateForm();
  }

  private resetCreateForm(): void {
    this.fName.set('');
    this.fPlannedStart.set('');
    this.fPlannedEnd.set('');
    this.fBudget.set('');
    this.fNotes.set('');
  }

  create(): void {
    const name = this.fName().trim();
    if (!name) { this.formError.set('Project name is required.'); return; }

    const company = this.companies().find((c) => c.id === this.selectedCompanyId());
    if (!company) { this.formError.set('Could not resolve selected company.'); return; }

    this.saving.set(true);
    this.formError.set(null);

    const request: CreateProjectRequest = {
      companyUid: company.uid,
      branchUid: company.uid, // branch resolved from context; will use company's default branch uid
      name,
      plannedStartDate: this.fPlannedStart().trim() || undefined,
      plannedEndDate: this.fPlannedEnd().trim() || undefined,
      budgetAmount: this.fBudget().trim() || undefined,
      notes: this.fNotes().trim() || undefined,
    };

    this.projectsService.create(request).subscribe({
      next: (created) => {
        this.saving.set(false);
        this.resetCreateForm();
        this.showCreateForm.set(false);
        this.alerts.success('Project created', created.projectNumber ?? created.name);
        this.load(this.currentPage());
      },
      error: (err: unknown) => {
        this.formError.set(this.messageFrom(err, 'Could not create project.'));
        this.saving.set(false);
      },
    });
  }

  // ── Display helpers ───────────────────────────────────────────────────────────

  statusBadgeClass(status: ProjectStatus): string {
    switch (status) {
      case 'ACTIVE': return 'text-bg-success';
      case 'ON_HOLD': return 'text-bg-warning';
      case 'COMPLETED': return 'text-bg-primary';
      case 'CANCELLED': return 'text-bg-danger';
      default: return 'text-bg-secondary'; // DRAFT
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
