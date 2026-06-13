import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, input, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { PageMeta } from '../../../../core/api/api-response.model';
import { AlertService } from '../../../../core/feedback/alert.service';
import { SessionStore } from '../../../../core/auth/session.store';
import { PaginatorComponent } from '../../../../shared/paginator/paginator.component';
import { ActivityService } from './activity.service';
import { ActivityDto, ActivityType, CreateActivityRequest } from './activity.model';

const DEFAULT_SIZE = 10;

/**
 * Embeddable activity panel.
 * Pass exactly one of [leadUid] or [opportunityUid] plus [companyId].
 * Renders a paginated list of activities and an inline "Log activity" form.
 * Consumed by lead-detail and opportunity-detail.
 */
@Component({
  selector: 'app-activity-panel',
  imports: [FormsModule, PaginatorComponent],
  templateUrl: './activity-panel.component.html',
  styleUrl: './activity-panel.component.scss',
})
export class ActivityPanelComponent implements OnInit {
  private readonly activityService = inject(ActivityService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  // ── Inputs ─────────────────────────────────────────────────────────────────
  readonly companyId = input.required<string>();
  /** Provide when embedded in a Lead context. */
  readonly leadUid = input<string | null>(null);
  /** Provide when embedded in an Opportunity context. */
  readonly opportunityUid = input<string | null>(null);

  // ── List state ─────────────────────────────────────────────────────────────
  readonly rows = signal<ActivityDto[]>([]);
  readonly meta = signal<PageMeta>({ page: 0, size: DEFAULT_SIZE, totalElements: 0, totalPages: 0, hasNext: false });
  readonly listState = signal<'loading' | 'idle' | 'error'>('loading');
  readonly currentPage = signal(0);

  // ── Complete row busy ──────────────────────────────────────────────────────
  readonly completingUid = signal<string | null>(null);

  // ── Log form ───────────────────────────────────────────────────────────────
  readonly showLogForm = signal(false);
  readonly fType = signal<ActivityType>('CALL');
  readonly fSubject = signal('');
  readonly fBody = signal('');
  readonly fDueDate = signal('');
  readonly fOccurredAt = signal('');
  readonly saving = signal(false);
  readonly formError = signal<string | null>(null);

  // ── Permissions ────────────────────────────────────────────────────────────
  readonly canManage = computed(() => this.session.hasPermission('CRM.ACTIVITY.MANAGE'));
  readonly isEmpty = computed(() => this.listState() === 'idle' && this.rows().length === 0);
  readonly isTask = computed(() => this.fType() === 'TASK');

  readonly activityTypeOptions: Array<{ value: ActivityType; label: string }> = [
    { value: 'CALL', label: 'Call' },
    { value: 'EMAIL', label: 'Email' },
    { value: 'MEETING', label: 'Meeting' },
    { value: 'NOTE', label: 'Note' },
    { value: 'TASK', label: 'Task' },
  ];

  ngOnInit(): void {
    this.loadActivities(0);
  }

  private loadActivities(page: number): void {
    const leadUid = this.leadUid();
    const oppUid = this.opportunityUid();
    this.listState.set('loading');
    this.currentPage.set(page);

    const source$ = leadUid
      ? this.activityService.listByLead(leadUid, page, DEFAULT_SIZE)
      : oppUid
        ? this.activityService.listByOpportunity(oppUid, page, DEFAULT_SIZE)
        : null;

    if (!source$) {
      this.listState.set('idle');
      return;
    }

    source$.subscribe({
      next: ({ rows, meta }) => {
        this.rows.set(rows);
        this.meta.set(meta);
        this.listState.set('idle');
      },
      error: () => this.listState.set('error'),
    });
  }

  goToPage(page: number): void { this.loadActivities(page); }

  toggleLogForm(): void {
    this.showLogForm.update((v) => !v);
    this.formError.set(null);
    if (!this.showLogForm()) this.resetForm();
  }

  private resetForm(): void {
    this.fType.set('CALL');
    this.fSubject.set('');
    this.fBody.set('');
    this.fDueDate.set('');
    this.fOccurredAt.set('');
  }

  logActivity(): void {
    const subject = this.fSubject().trim();
    if (!subject) { this.formError.set('Subject is required.'); return; }

    const leadUid = this.leadUid();
    const oppUid = this.opportunityUid();
    if (!leadUid && !oppUid) {
      this.formError.set('No lead or opportunity context.');
      return;
    }

    const request: CreateActivityRequest = {
      companyId: this.companyId(),
      activityType: this.fType(),
      subject,
      body: this.fBody().trim() || undefined,
      occurredAt: this.fOccurredAt() || undefined,
      dueDate: this.isTask() && this.fDueDate() ? this.fDueDate() : undefined,
    };
    if (leadUid) request.leadUid = leadUid;
    else if (oppUid) request.opportunityUid = oppUid;

    this.saving.set(true);
    this.formError.set(null);
    this.activityService.create(request).subscribe({
      next: () => {
        this.saving.set(false);
        this.showLogForm.set(false);
        this.resetForm();
        this.alerts.success('Activity logged', subject);
        this.loadActivities(this.currentPage());
      },
      error: (err: unknown) => {
        this.formError.set(this.messageFrom(err, 'Could not log activity.'));
        this.saving.set(false);
      },
    });
  }

  complete(activity: ActivityDto): void {
    if (this.completingUid() !== null) return;
    this.completingUid.set(activity.uid);
    this.activityService.complete(activity.uid).subscribe({
      next: () => {
        this.completingUid.set(null);
        this.alerts.success('Task completed', activity.subject);
        this.loadActivities(this.currentPage());
      },
      error: (err: unknown) => {
        this.completingUid.set(null);
        this.alerts.error('Error', this.messageFrom(err, 'Could not complete task.'));
      },
    });
  }

  activityTypeBadgeClass(type: ActivityType): string {
    switch (type) {
      case 'CALL': return 'text-bg-info';
      case 'EMAIL': return 'text-bg-secondary';
      case 'MEETING': return 'text-bg-primary';
      case 'NOTE': return 'text-bg-light';
      case 'TASK': return 'text-bg-warning';
      default: return 'text-bg-secondary';
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
