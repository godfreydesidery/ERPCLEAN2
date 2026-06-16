import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { Subject, switchMap } from 'rxjs';
import { PageMeta } from '../../../../core/api/api-response.model';
import { AlertService } from '../../../../core/feedback/alert.service';
import { SessionStore } from '../../../../core/auth/session.store';
import { CompanyService } from '../../company/company.service';
import { OrganisationService } from '../../organisation/organisation.service';
import { Company } from '../../models/company.model';
import { PaginatorComponent } from '../../../../shared/paginator/paginator.component';
import { ActivityService } from './activity.service';
import type { ActivityPage } from './activity.service';
import { ActivityDto, ActivityType } from './activity.model';

const DEFAULT_SIZE = 20;

interface LoadTrigger { page: number }

/**
 * Open-tasks inbox: lists all open TASK-type activities for the current company.
 * Each row has a "Complete" action (CRM.ACTIVITY.MANAGE).
 * Route: /admin/crm/activities
 * Permission gate: CRM.ACTIVITY.VIEW
 */
@Component({
  selector: 'app-activity-tasks',
  imports: [FormsModule, PaginatorComponent],
  templateUrl: './activity-tasks.component.html',
  styleUrl: './activity-tasks.component.scss',
})
export class ActivityTasksComponent {
  private readonly activityService = inject(ActivityService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  // ── Company context ────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── List state ─────────────────────────────────────────────────────────────
  readonly rows = signal<ActivityDto[]>([]);
  readonly meta = signal<PageMeta>({ page: 0, size: DEFAULT_SIZE, totalElements: 0, totalPages: 0, hasNext: false });
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('idle');
  readonly currentPage = signal(0);

  // ── Row-level busy (completing) ────────────────────────────────────────────
  readonly completingUid = signal<string | null>(null);

  private readonly immediateTrigger$ = new Subject<LoadTrigger>();

  readonly canManage = computed(() => this.session.hasPermission('CRM.ACTIVITY.MANAGE'));
  readonly isEmpty = computed(() => this.state() === 'idle' && this.rows().length === 0);

  constructor() {
    this.immediateTrigger$
      .pipe(
        switchMap(({ page }) => {
          const companyId = this.selectedCompanyId();
          if (!companyId) return [];
          this.state.set('loading');
          this.currentPage.set(page);
          return this.activityService.openTasks(companyId, page, DEFAULT_SIZE);
        }),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: ({ rows, meta }: ActivityPage) => {
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
    if (!this.selectedCompanyId()) return;
    this.immediateTrigger$.next({ page });
  }

  goToPage(page: number): void { this.load(page); }

  complete(activity: ActivityDto): void {
    if (this.completingUid() !== null) return;
    this.completingUid.set(activity.uid);
    this.activityService.complete(activity.uid).subscribe({
      next: () => {
        this.completingUid.set(null);
        this.alerts.success('Task completed', activity.subject);
        this.load(this.currentPage());
      },
      error: (err: unknown) => {
        this.completingUid.set(null);
        const msg = this.messageFrom(err, 'Could not complete task.');
        this.alerts.error('Error', msg);
      },
    });
  }

  activityTypeBadgeClass(type: ActivityType): string {
    switch (type) {
      case 'CALL':    return 'status-tag--info';
      case 'EMAIL':   return 'status-tag--neutral';
      case 'MEETING': return 'status-tag--info';
      case 'NOTE':    return 'status-tag--neutral';
      case 'TASK':    return 'status-tag--warn';
      default:        return 'status-tag--neutral';
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
