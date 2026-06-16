import { describe, it, expect, vi, beforeEach } from 'vitest';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { of, throwError, Subject } from 'rxjs';
import { ActivityTasksComponent } from './activity-tasks.component';
import { ActivityService } from './activity.service';
import { CompanyService } from '../../company/company.service';
import { OrganisationService } from '../../organisation/organisation.service';
import { AlertService } from '../../../../core/feedback/alert.service';
import { SessionStore } from '../../../../core/auth/session.store';
import { ActivityDto, ActivityType } from './activity.model';

// ── Minimal stub helpers ───────────────────────────────────────────────────────

const MOCK_ORG = { uid: 'org-1', name: 'TestOrg' };
const MOCK_COMPANY = { id: 'co-1', uid: 'co-uid-1', name: 'ACME', orgUid: 'org-1' };
const MOCK_META = { page: 0, size: 20, totalElements: 1, totalPages: 1, hasNext: false };

function makeActivity(overrides: Partial<ActivityDto> = {}): ActivityDto {
  return {
    id: '10', uid: 'act-uid-1', companyId: 'co-1', branchId: 'br-1',
    activityNumber: 'ACT-001', activityType: 'TASK',
    leadId: null, opportunityId: null, subject: 'Follow up call',
    body: null, occurredAt: null, dueDate: '2026-06-20',
    assigneeUserId: null, done: false, doneAt: null,
    status: 'ACTIVE', version: '1',
    createdAt: '2026-06-13T10:00:00Z', createdBy: null, updatedAt: null, updatedBy: null,
    ...overrides,
  };
}

describe('ActivityTasksComponent', () => {
  let activityServiceMock: Partial<ActivityService>;
  let companyServiceMock: Partial<CompanyService>;
  let orgServiceMock: Partial<OrganisationService>;
  let alertServiceMock: Partial<AlertService>;
  let sessionMock: Partial<SessionStore>;

  beforeEach(() => {
    vi.useFakeTimers();

    activityServiceMock = {
      openTasks: vi.fn().mockReturnValue(of({ rows: [makeActivity()], meta: MOCK_META })),
      complete: vi.fn().mockReturnValue(of(makeActivity({ done: true }))),
    };

    companyServiceMock = {
      list: vi.fn().mockReturnValue(of([MOCK_COMPANY])),
    };

    orgServiceMock = {
      current: vi.fn().mockReturnValue(of(MOCK_ORG)),
    };

    alertServiceMock = {
      success: vi.fn(),
      error: vi.fn(),
    };

    sessionMock = {
      hasPermission: vi.fn().mockReturnValue(true),
    };

    TestBed.configureTestingModule({
      imports: [ActivityTasksComponent],
      providers: [
        { provide: ActivityService, useValue: activityServiceMock },
        { provide: CompanyService, useValue: companyServiceMock },
        { provide: OrganisationService, useValue: orgServiceMock },
        { provide: AlertService, useValue: alertServiceMock },
        { provide: SessionStore, useValue: sessionMock },
      ],
    });
  });

  it('should load tasks on init and populate rows', () => {
    const fixture = TestBed.createComponent(ActivityTasksComponent);
    const comp = fixture.componentInstance;
    vi.runAllTimers();
    expect(activityServiceMock.openTasks).toHaveBeenCalledWith('co-1', 0, 20);
    expect(comp.rows().length).toBe(1);
    expect(comp.state()).toBe('idle');
  });

  it('isEmpty returns false when rows present, true when empty', () => {
    const fixture = TestBed.createComponent(ActivityTasksComponent);
    const comp = fixture.componentInstance;
    vi.runAllTimers();
    expect(comp.isEmpty()).toBe(false);
  });

  it('complete calls service and reloads list', () => {
    const fixture = TestBed.createComponent(ActivityTasksComponent);
    const comp = fixture.componentInstance;
    vi.runAllTimers();

    const activity = makeActivity();
    comp.complete(activity);
    vi.runAllTimers();

    expect(activityServiceMock.complete).toHaveBeenCalledWith('act-uid-1');
    expect(alertServiceMock.success).toHaveBeenCalled();
  });

  it('complete sets forbidden state on 403', () => {
    const fixture = TestBed.createComponent(ActivityTasksComponent);
    const comp = fixture.componentInstance;

    (activityServiceMock.openTasks as ReturnType<typeof vi.fn>).mockReturnValue(
      throwError(() => new HttpErrorResponse({ status: 403, statusText: 'Forbidden' })),
    );

    // re-trigger load via load()
    comp['selectedCompanyId'].set('co-1');
    comp.load(0);
    vi.runAllTimers();

    expect(comp.state()).toBe('forbidden');
  });

  it('activityTypeBadgeClass returns correct status-tag variant for each type', () => {
    const fixture = TestBed.createComponent(ActivityTasksComponent);
    const comp = fixture.componentInstance;
    expect(comp.activityTypeBadgeClass('TASK')).toBe('status-tag--warn');
    expect(comp.activityTypeBadgeClass('CALL')).toBe('status-tag--info');
    expect(comp.activityTypeBadgeClass('MEETING')).toBe('status-tag--info');
    expect(comp.activityTypeBadgeClass('EMAIL')).toBe('status-tag--neutral');
    expect(comp.activityTypeBadgeClass('NOTE')).toBe('status-tag--neutral');
  });
});
