/**
 * ProjectListComponent specs — Vitest + vi.useFakeTimers().
 *
 * Covers:
 *  1. Fires exactly one load on startup after company resolves.
 *  2. isEmpty is true when no rows returned.
 *  3. create() validation: requires name.
 *  4. create() calls projectsService.create with correct payload.
 *  5. 403 response sets state to 'forbidden'.
 *  6. Template renders correct status-tag--* class per ProjectStatus value.
 */
import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { ProjectsService } from './projects.service';
import type { ProjectPage } from './projects.service';
import { ProjectListComponent } from './project-list.component';

// ── Stubs ──────────────────────────────────────────────────────────────────────

const emptyPage = (): ProjectPage => ({
  rows: [],
  meta: { page: 0, size: 20, totalElements: 0, totalPages: 0, hasNext: false },
});

const stubProject = {
  uid: 'P1', id: '1', companyId: '10', branchId: '1',
  projectNumber: 'PRJ-0001', name: 'Test Project',
  customerId: '', managerUserId: '',
  projectStatus: 'DRAFT' as const,
  plannedStartDate: '2025-01-01', plannedEndDate: '2025-12-31',
  budgetAmount: '100000', currency: 'TZS',
  notes: '', status: 'ACTIVE' as const,
  activatedAt: null, completedAt: null, cancelledAt: null,
};

function makeSessionStore(canCreate = false, activeBranchUid: string | null = null) {
  return {
    hasPermission: vi.fn((code: string) => canCreate && code === 'PROJECTS.PROJECT.CREATE'),
    isAuthenticated: signal(true),
    user: signal(null),
    permissions: signal([]),
    activeBranchUid: signal(activeBranchUid),
  };
}

function makeBed(opts: {
  listImpl?: () => any;
  canCreate?: boolean;
  activeBranchUid?: string | null;
} = {}) {
  const { listImpl, canCreate = false, activeBranchUid = null } = opts;
  const sessionStore = makeSessionStore(canCreate, activeBranchUid);

  TestBed.configureTestingModule({
    imports: [ProjectListComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      {
        provide: ProjectsService,
        useValue: {
          list: vi.fn(listImpl ?? (() => of(emptyPage()))),
          create: vi.fn(() => of(stubProject)),
        },
      },
      {
        provide: OrganisationService,
        useValue: { current: vi.fn(() => of({ uid: 'ORG1', id: '1', name: 'Acme' })) },
      },
      {
        provide: CompanyService,
        useValue: { list: vi.fn(() => of([{ uid: 'CO1', id: '10', name: 'Main Co' }])) },
      },
      {
        provide: AlertService,
        useValue: { success: vi.fn(), error: vi.fn() },
      },
      { provide: SessionStore, useValue: sessionStore },
    ],
  });
}

// ── Init ───────────────────────────────────────────────────────────────────────

describe('ProjectListComponent — init', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    makeBed();
  });
  afterEach(() => {
    vi.useRealTimers();
    TestBed.resetTestingModule();
  });

  it('fires exactly one load on startup after company resolves', async () => {
    const comp = TestBed.createComponent(ProjectListComponent).componentInstance;
    const svc = TestBed.inject(ProjectsService) as any;
    await vi.runAllTimersAsync();

    expect(svc.list).toHaveBeenCalledTimes(1);
    expect(svc.list).toHaveBeenCalledWith('10', 0, 20);
    expect(comp.state()).toBe('idle');
  });

  it('isEmpty is true when no projects returned', async () => {
    const comp = TestBed.createComponent(ProjectListComponent).componentInstance;
    await vi.runAllTimersAsync();
    expect(comp.isEmpty()).toBe(true);
  });
});

// ── Create form validation ─────────────────────────────────────────────────────

describe('ProjectListComponent — create form validation', () => {
  afterEach(() => {
    vi.useRealTimers();
    TestBed.resetTestingModule();
  });

  it('create() validation: requires name', async () => {
    vi.useFakeTimers();
    makeBed({ canCreate: true, activeBranchUid: 'BR1' });
    const comp = TestBed.createComponent(ProjectListComponent).componentInstance;
    const svc = TestBed.inject(ProjectsService) as any;
    await vi.runAllTimersAsync();

    comp.fName.set('');
    comp.create();

    expect(comp.formError()).toBeTruthy();
    expect(svc.create).not.toHaveBeenCalled();
  });

  it('create() sets formError when activeBranchUid is null', async () => {
    vi.useFakeTimers();
    makeBed({ canCreate: true, activeBranchUid: null });
    const comp = TestBed.createComponent(ProjectListComponent).componentInstance;
    const svc = TestBed.inject(ProjectsService) as any;
    await vi.runAllTimersAsync();

    comp.fName.set('My Project');
    comp.create();

    expect(comp.formError()).toContain('branch');
    expect(svc.create).not.toHaveBeenCalled();
  });

  it('create() calls projectsService.create with correct payload including active branch uid', async () => {
    vi.useFakeTimers();
    makeBed({ canCreate: true, activeBranchUid: 'BR1' });
    const comp = TestBed.createComponent(ProjectListComponent).componentInstance;
    const svc = TestBed.inject(ProjectsService) as any;
    await vi.runAllTimersAsync();

    comp.fName.set('My Project');
    comp.fBudget.set('50000');
    comp.create();

    expect(svc.create).toHaveBeenCalledOnce();
    const req = svc.create.mock.calls[0][0];
    expect(req.name).toBe('My Project');
    expect(req.budgetAmount).toBe('50000');
    expect(req.companyUid).toBe('CO1');
    expect(req.branchUid).toBe('BR1');
  });
});

// ── Status-tag class per ProjectStatus (template binding) ─────────────────────
//
// statusBadgeClass() was removed; coverage is now on the template's additive
// [class.status-tag--*] bindings.  Each case loads one row with the target
// projectStatus and asserts the pill element carries the expected modifier class.

type ProjectStatus = 'DRAFT' | 'ACTIVE' | 'ON_HOLD' | 'COMPLETED' | 'CANCELLED';

function makeProjectRow(projectStatus: ProjectStatus) {
  return {
    uid: 'P1', id: '1', companyId: '10', branchId: '1',
    projectNumber: 'PRJ-0001', name: 'Test Project',
    customerId: '', managerUserId: '',
    projectStatus,
    plannedStartDate: '2025-01-01', plannedEndDate: '2025-12-31',
    budgetAmount: '100000', currency: 'TZS',
    notes: '', status: 'ACTIVE' as const,
    activatedAt: null, completedAt: null, cancelledAt: null,
  };
}

const statusTagCases: Array<{ projectStatus: ProjectStatus; expectedClass: string }> = [
  { projectStatus: 'DRAFT',     expectedClass: 'status-tag--warn'    },
  { projectStatus: 'ACTIVE',    expectedClass: 'status-tag--ok'      },
  { projectStatus: 'ON_HOLD',   expectedClass: 'status-tag--info'    },
  { projectStatus: 'COMPLETED', expectedClass: 'status-tag--neutral' },
  { projectStatus: 'CANCELLED', expectedClass: 'status-tag--danger'  },
];

describe('ProjectListComponent — status-tag template bindings', () => {
  afterEach(() => {
    vi.useRealTimers();
    TestBed.resetTestingModule();
  });

  for (const { projectStatus, expectedClass } of statusTagCases) {
    it(`${projectStatus} → .${expectedClass} on status-tag span`, async () => {
      vi.useFakeTimers();
      makeBed({
        listImpl: () => of({
          rows: [makeProjectRow(projectStatus)],
          meta: { page: 0, size: 20, totalElements: 1, totalPages: 1, hasNext: false },
        }),
      });

      const fixture = TestBed.createComponent(ProjectListComponent);
      await vi.runAllTimersAsync();
      fixture.detectChanges();

      const pill: HTMLElement | null = fixture.nativeElement.querySelector('.status-tag');
      expect(pill).not.toBeNull();
      expect(pill!.classList.contains(expectedClass)).toBe(true);
    });
  }
});

// ── 403 forbidden ──────────────────────────────────────────────────────────────

describe('ProjectListComponent — 403 forbidden', () => {
  beforeEach(() => { vi.useFakeTimers(); });
  afterEach(() => {
    vi.useRealTimers();
    TestBed.resetTestingModule();
  });

  it('sets state to forbidden when list returns 403', async () => {
    makeBed({
      listImpl: () =>
        throwError(() => new HttpErrorResponse({ status: 403, statusText: 'Forbidden' })),
    });

    const comp = TestBed.createComponent(ProjectListComponent).componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.state()).toBe('forbidden');
  });
});
