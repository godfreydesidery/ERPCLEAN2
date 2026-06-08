/**
 * RouteDetailComponent spec.
 * Mirrors product-detail.component pattern.
 * Uses Vitest fake timers.
 *
 * Covers:
 *  1. Loads route on init and patches form.
 *  2. assign-customer: assignCustomer() calls routesService.assignCustomer.
 *  3. assign-customer: requires selection before calling service.
 *  4. assign-agent: assignAgent() calls routesService.assignAgent with isPrimary.
 *  5. assign-agent: surfaces EXTERNAL-only rejection inline (server error message).
 *  6. assign-agent: agentResults filtered to EXTERNAL kind only.
 *  7. assign-branch: assignBranch() calls routesService.assignBranch.
 *  8. assign-branch: requires branchUid before calling service.
 */
import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { of, throwError } from 'rxjs';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { BranchService } from '../branch/branch.service';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { AgentService } from '../parties/agent.service';
import { CustomerService } from '../parties/customer.service';
import { RoutesService } from './routes.service';
import { RouteDetailComponent } from './route-detail.component';

// ── Fixtures ──────────────────────────────────────────────────────────────────

const stubRoute = () => ({
  id: '1', uid: 'RT-UID1', companyId: '10', code: 'RT001', name: 'Test Route',
  locationIdentifier: null, status: 'ACTIVE' as const,
  version: null, createdAt: null, createdBy: null, updatedAt: null, updatedBy: null,
});

const stubCustomer = () => ({
  id: '2', uid: 'CUST-UID1', companyId: '10', code: 'C001',
  partyType: 'INDIVIDUAL' as const, displayName: 'John Doe',
  legalName: null, tin: null, vatRegistered: false, vrn: null, businessRegNo: null,
  mobileMoneyNo: null, phone: null, email: null, physicalAddress: null, postalAddress: null,
  region: null, district: null, customerKind: 'CASH_WALK_IN' as const,
  creditLimit: null, paymentTermsDays: null, status: 'ACTIVE' as const,
  version: null, createdAt: null, createdBy: null, updatedAt: null, updatedBy: null,
});

const stubAgentExternal = () => ({
  id: '3', uid: 'AGT-UID1', companyId: '10', code: 'A001',
  partyType: 'INDIVIDUAL' as const, displayName: 'Jane Smith',
  legalName: null, tin: null, vatRegistered: false, vrn: null, businessRegNo: null,
  mobileMoneyNo: null, phone: null, email: null, physicalAddress: null, postalAddress: null,
  region: null, district: null, agentKind: 'EXTERNAL' as const,
  appUserId: null, status: 'ACTIVE' as const,
  version: null, createdAt: null, createdBy: null, updatedAt: null, updatedBy: null,
});

const stubAgentInternal = () => ({
  ...stubAgentExternal(),
  uid: 'AGT-UID2', code: 'A002', displayName: 'Internal User',
  agentKind: 'INTERNAL' as const,
});

const stubBranch = () => ({
  id: '4', uid: 'BR-UID1', companyId: '10', code: 'B001', name: 'Main Branch',
  isDefault: false, status: 'ACTIVE', version: null,
  createdAt: null, createdBy: null, updatedAt: null, updatedBy: null,
});

const stubRouteCustomer = () => ({
  routeId: '1', customerId: '2', customerUid: 'CUST-UID1',
  customerCode: 'C001', customerName: 'John Doe',
  assignedAt: null, assignedBy: null,
});

const stubRouteAgent = () => ({
  routeId: '1', agentId: '3', agentUid: 'AGT-UID1',
  agentCode: 'A001', agentName: 'Jane Smith',
  isPrimary: false, assignedAt: null, assignedBy: null,
});

const stubRouteBranch = () => ({
  routeId: '1', branchId: '4', branchUid: 'BR-UID1',
  branchCode: 'B001', branchName: 'Main Branch',
  assignedAt: null, assignedBy: null,
});

function makeSessionStore(canAssign = true) {
  return {
    hasPermission: vi.fn((p: string) => {
      if (p === 'ROUTE.VIEW') return true;
      if (p === 'ROUTE.MANAGE') return true;
      if (p === 'ROUTE.ASSIGN') return canAssign;
      return false;
    }),
    isAuthenticated: signal(true),
    user: signal(null),
    permissions: signal([]),
    activeBranchUid: signal(null),
  };
}

function makeBed(opts: {
  canAssign?: boolean;
  routesServiceOverrides?: Partial<{
    getByUid: any; listCustomers: any; assignCustomer: any; removeCustomer: any;
    listAgents: any; assignAgent: any; removeAgent: any;
    listBranches: any; assignBranch: any; removeBranch: any;
    update: any; archive: any; restore: any;
  }>;
  agentServiceOverride?: { list: any };
  customerServiceOverride?: { list: any };
} = {}) {
  const { canAssign = true, routesServiceOverrides = {}, agentServiceOverride, customerServiceOverride } = opts;

  const routesService = {
    getByUid: vi.fn(() => of(stubRoute())),
    listCustomers: vi.fn(() => of([])),
    assignCustomer: vi.fn(() => of(stubRouteCustomer())),
    removeCustomer: vi.fn(() => of(undefined)),
    listAgents: vi.fn(() => of([])),
    assignAgent: vi.fn(() => of(stubRouteAgent())),
    removeAgent: vi.fn(() => of(undefined)),
    listBranches: vi.fn(() => of([])),
    assignBranch: vi.fn(() => of(stubRouteBranch())),
    removeBranch: vi.fn(() => of(undefined)),
    update: vi.fn(() => of(stubRoute())),
    archive: vi.fn(() => of({ ...stubRoute(), status: 'ARCHIVED' })),
    restore: vi.fn(() => of(stubRoute())),
    ...routesServiceOverrides,
  };

  TestBed.configureTestingModule({
    imports: [RouteDetailComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([{ path: 'admin/routes/uid/:uid', component: RouteDetailComponent }]),
      { provide: RoutesService, useValue: routesService },
      {
        provide: CustomerService,
        useValue: customerServiceOverride ?? {
          list: vi.fn(() => of({ rows: [stubCustomer()], meta: {} })),
        },
      },
      {
        provide: AgentService,
        useValue: agentServiceOverride ?? {
          list: vi.fn(() => of({ rows: [stubAgentExternal(), stubAgentInternal()], meta: {} })),
        },
      },
      {
        provide: BranchService,
        useValue: { list: vi.fn(() => of([stubBranch()])) },
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
      { provide: SessionStore, useValue: makeSessionStore(canAssign) },
    ],
  });

  // Provide the uid input via router params simulation via the component input binding.
  // We inject the Router and navigate so withComponentInputBinding works.
  const router = TestBed.inject(Router);
  router.navigate(['/admin/routes/uid/RT-UID1']);
}

// ── Helpers ───────────────────────────────────────────────────────────────────

function createComp() {
  const fixture = TestBed.createComponent(RouteDetailComponent);
  const comp = fixture.componentInstance;
  // Manually set the uid input (withComponentInputBinding is not active in unit tests).
  (comp as any).uid = signal('RT-UID1');
  return { fixture, comp };
}

// ── Load on init ──────────────────────────────────────────────────────────────

describe('RouteDetailComponent — init', () => {
  beforeEach(() => { vi.useFakeTimers(); makeBed(); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('loads route and patches form on init', async () => {
    const { comp } = createComp();
    await vi.runAllTimersAsync();

    const svc = TestBed.inject(RoutesService) as any;
    expect(svc.getByUid).toHaveBeenCalledWith('RT-UID1');
    expect(comp.route()).not.toBeNull();
    expect(comp.fName()).toBe('Test Route');
  });

  it('loads customer/agent/branch sub-lists on init', async () => {
    const { comp } = createComp();
    await vi.runAllTimersAsync();

    const svc = TestBed.inject(RoutesService) as any;
    expect(svc.listCustomers).toHaveBeenCalledWith('RT-UID1');
    expect(svc.listAgents).toHaveBeenCalledWith('RT-UID1');
    expect(svc.listBranches).toHaveBeenCalledWith('RT-UID1');
  });
});

// ── Customer assignment ───────────────────────────────────────────────────────

describe('RouteDetailComponent — assign customer', () => {
  beforeEach(() => { vi.useFakeTimers(); makeBed(); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('assignCustomer() requires a selection', async () => {
    const { comp } = createComp();
    await vi.runAllTimersAsync();

    comp.selectedCustomer.set(null);
    comp.assignCustomer();

    expect(comp.customerAssignError()).toBeTruthy();
    const svc = TestBed.inject(RoutesService) as any;
    expect(svc.assignCustomer).not.toHaveBeenCalled();
  });

  it('assignCustomer() calls assignCustomer with customerUid', async () => {
    const { comp } = createComp();
    await vi.runAllTimersAsync();

    comp.selectedCustomer.set({ uid: 'CUST-UID1', label: 'C001 — John Doe' });
    comp.assignCustomer();

    const svc = TestBed.inject(RoutesService) as any;
    expect(svc.assignCustomer).toHaveBeenCalledOnce();
    const [routeUid, req] = svc.assignCustomer.mock.calls[0];
    expect(routeUid).toBe('RT-UID1');
    expect(req.customerUid).toBe('CUST-UID1');
  });
});

// ── Agent assignment ──────────────────────────────────────────────────────────

describe('RouteDetailComponent — assign agent', () => {
  beforeEach(() => { vi.useFakeTimers(); makeBed(); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('assignAgent() requires a selection', async () => {
    const { comp } = createComp();
    await vi.runAllTimersAsync();

    comp.selectedAgent.set(null);
    comp.assignAgent();

    expect(comp.agentAssignError()).toBeTruthy();
    const svc = TestBed.inject(RoutesService) as any;
    expect(svc.assignAgent).not.toHaveBeenCalled();
  });

  it('assignAgent() calls assignAgent with agentUid and isPrimary', async () => {
    const { comp } = createComp();
    await vi.runAllTimersAsync();

    comp.selectedAgent.set({ uid: 'AGT-UID1', label: 'A001 — Jane Smith' });
    comp.newAgentIsPrimary.set(true);
    comp.assignAgent();

    const svc = TestBed.inject(RoutesService) as any;
    expect(svc.assignAgent).toHaveBeenCalledOnce();
    const [routeUid, req] = svc.assignAgent.mock.calls[0];
    expect(routeUid).toBe('RT-UID1');
    expect(req.agentUid).toBe('AGT-UID1');
    expect(req.isPrimary).toBe(true);
  });

  it('surfaces EXTERNAL-only rejection error inline', async () => {
    // Need to reset + reconfigure to swap assignAgent implementation.
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [RouteDetailComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([{ path: 'admin/routes/uid/:uid', component: RouteDetailComponent }]),
        {
          provide: RoutesService,
          useValue: {
            getByUid: vi.fn(() => of(stubRoute())),
            listCustomers: vi.fn(() => of([])),
            assignCustomer: vi.fn(() => of(stubRouteCustomer())),
            removeCustomer: vi.fn(() => of(undefined)),
            listAgents: vi.fn(() => of([])),
            assignAgent: vi.fn(() =>
              throwError(() => new HttpErrorResponse({
                status: 422,
                error: { errors: ['Only EXTERNAL agents may be assigned to a route.'] },
              })),
            ),
            removeAgent: vi.fn(() => of(undefined)),
            listBranches: vi.fn(() => of([])),
            assignBranch: vi.fn(() => of(stubRouteBranch())),
            removeBranch: vi.fn(() => of(undefined)),
            update: vi.fn(() => of(stubRoute())),
            archive: vi.fn(() => of({ ...stubRoute(), status: 'ARCHIVED' })),
            restore: vi.fn(() => of(stubRoute())),
          },
        },
        { provide: CustomerService, useValue: { list: vi.fn(() => of({ rows: [], meta: {} })) } },
        { provide: AgentService, useValue: { list: vi.fn(() => of({ rows: [], meta: {} })) } },
        { provide: BranchService, useValue: { list: vi.fn(() => of([])) } },
        { provide: OrganisationService, useValue: { current: vi.fn(() => of({ uid: 'ORG1', id: '1', name: 'Acme' })) } },
        { provide: CompanyService, useValue: { list: vi.fn(() => of([{ uid: 'CO1', id: '10', name: 'Main Co' }])) } },
        { provide: AlertService, useValue: { success: vi.fn(), error: vi.fn() } },
        { provide: SessionStore, useValue: makeSessionStore(true) },
      ],
    });

    const { comp } = createComp();
    await vi.runAllTimersAsync();

    comp.selectedAgent.set({ uid: 'AGT-UID2', label: 'A002 — Internal User' });
    comp.assignAgent();

    expect(comp.agentAssignError()).toBe('Only EXTERNAL agents may be assigned to a route.');
  });

  it('agent picker only shows EXTERNAL agents (agentKind filter)', async () => {
    const agentSvc = TestBed.inject(AgentService) as any;
    // Simulate the debounced search response
    const allAgents = [stubAgentExternal(), stubAgentInternal()];
    agentSvc.list.mockReturnValue(of({ rows: allAgents, meta: {} }));

    const { comp } = createComp();
    await vi.runAllTimersAsync();

    // Trigger the agent search pipeline
    comp.onAgentSearchChange('Jane');
    await vi.advanceTimersByTimeAsync(300);

    // Only EXTERNAL agents should appear in results
    expect(comp.agentResults().every((a) => a.agentKind === 'EXTERNAL')).toBe(true);
    expect(comp.agentResults().some((a) => a.agentKind === 'INTERNAL')).toBe(false);
  });
});

// ── Branch assignment ─────────────────────────────────────────────────────────

describe('RouteDetailComponent — assign branch', () => {
  beforeEach(() => { vi.useFakeTimers(); makeBed(); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('assignBranch() requires a branchUid selection', async () => {
    const { comp } = createComp();
    await vi.runAllTimersAsync();

    comp.selectedBranchUid.set('');
    comp.assignBranch();

    expect(comp.branchAssignError()).toBeTruthy();
    const svc = TestBed.inject(RoutesService) as any;
    expect(svc.assignBranch).not.toHaveBeenCalled();
  });

  it('assignBranch() calls assignBranch with branchUid', async () => {
    const { comp } = createComp();
    await vi.runAllTimersAsync();

    comp.selectedBranchUid.set('BR-UID1');
    comp.assignBranch();

    const svc = TestBed.inject(RoutesService) as any;
    expect(svc.assignBranch).toHaveBeenCalledOnce();
    const [routeUid, req] = svc.assignBranch.mock.calls[0];
    expect(routeUid).toBe('RT-UID1');
    expect(req.branchUid).toBe('BR-UID1');
  });
});
