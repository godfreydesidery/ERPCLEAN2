/**
 * WorkOrderListComponent specs — branch-everywhere display.
 *
 * Covers:
 *  1. Fires one load on startup once the company resolves.
 *  2. isEmpty is true when no rows returned.
 *  3. Renders the Branch column (name + code), never the raw branchId.
 *  4. Null-safe: falls back to "—" when branchName is missing.
 */
import { HttpErrorResponse } from '@angular/common/http';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { BranchService } from '../branch/branch.service';
import { ProductService } from '../products/product.service';
import { ManufacturingService } from './manufacturing.service';
import type { WorkOrderPage } from './manufacturing.service';
import { WorkOrderDto } from './models/manufacturing.model';
import { WorkOrderListComponent } from './work-order-list.component';

// ── Stubs ─────────────────────────────────────────────────────────────────────

function makeWO(overrides: Partial<WorkOrderDto> = {}): WorkOrderDto {
  return {
    id: '1', uid: 'wo-1', woNumber: 'WO-001', companyId: '10', branchId: 'br-1',
    branchName: 'Head Office', branchCode: 'BR-01',
    finishedProductId: 'prod-1', finishedProductCode: 'SKU-001',
    finishedProductName: 'Widget A', bomId: '', bomUid: '',
    plannedQty: '100', goodQty: '0', scrapQty: '0',
    status: 'PLANNED', notes: '',
    wipDebitTotal: '0', wipCreditTotal: '0',
    labourAppliedTotal: '0', overheadAppliedTotal: '0',
    computedUnitCost: '0', varianceAmount: '0',
    incompleteCost: false, costCentreValueId: '',
    plannedDate: '2024-06-01', releasedAt: '', completedAt: '',
    closedAt: '', cancelledAt: '',
    createdAt: '2024-01-01T00:00:00Z', createdBy: 'admin',
    components: [], operations: [],
    ...overrides,
  };
}

const emptyPage = (): WorkOrderPage => ({
  rows: [],
  meta: { page: 0, size: 20, totalElements: 0, totalPages: 0, hasNext: false },
});

const onePage = (overrides: Partial<WorkOrderDto> = {}): WorkOrderPage => ({
  rows: [makeWO(overrides)],
  meta: { page: 0, size: 20, totalElements: 1, totalPages: 1, hasNext: false },
});

function makeBed(opts: { listImpl?: () => any } = {}) {
  const listSpy = vi.fn(opts.listImpl ?? (() => of(onePage())));

  TestBed.configureTestingModule({
    imports: [WorkOrderListComponent],
    providers: [
      provideRouter([]),
      { provide: ManufacturingService, useValue: { list: listSpy, create: vi.fn(() => of(makeWO())) } },
      {
        provide: OrganisationService,
        useValue: { current: vi.fn(() => of({ uid: 'ORG1', id: '1', name: 'Acme' })) },
      },
      {
        provide: CompanyService,
        useValue: { list: vi.fn(() => of([{ uid: 'CO1', id: '10', name: 'Main Co' }])) },
      },
      { provide: BranchService, useValue: { list: vi.fn(() => of([])) } },
      { provide: ProductService, useValue: { list: vi.fn(() => of({ rows: [], meta: {} })) } },
      { provide: AlertService, useValue: { success: vi.fn(), error: vi.fn() } },
      {
        provide: SessionStore,
        useValue: {
          hasPermission: vi.fn(() => true),
          isAuthenticated: signal(true),
          user: signal(null),
          permissions: signal([]),
          activeBranchUid: signal(null),
        },
      },
    ],
  });
  return { listSpy };
}

// ── Init ───────────────────────────────────────────────────────────────────────

describe('WorkOrderListComponent — init', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('fires exactly one load on startup after company resolves', async () => {
    const { listSpy } = makeBed();
    const comp = TestBed.createComponent(WorkOrderListComponent).componentInstance;
    await vi.runAllTimersAsync();

    expect(listSpy).toHaveBeenCalledTimes(1);
    expect(comp.state()).toBe('idle');
  });

  it('isEmpty is true when no work orders returned', async () => {
    makeBed({ listImpl: () => of(emptyPage()) });
    const comp = TestBed.createComponent(WorkOrderListComponent).componentInstance;
    await vi.runAllTimersAsync();
    expect(comp.isEmpty()).toBe(true);
  });

  it('sets state=forbidden on a 403 response', async () => {
    makeBed({
      listImpl: () => throwError(() => new HttpErrorResponse({ status: 403, statusText: 'Forbidden' })),
    });
    const comp = TestBed.createComponent(WorkOrderListComponent).componentInstance;
    await vi.runAllTimersAsync();
    expect(comp.state()).toBe('forbidden');
  });
});

// ── Branch column render (branch-everywhere) ────────────────────────────────────

describe('WorkOrderListComponent — renders branch', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('renders the branch name and code in the list, never the raw branchId', async () => {
    makeBed();
    const fixture = TestBed.createComponent(WorkOrderListComponent);
    await vi.runAllTimersAsync();
    fixture.detectChanges();

    const el = fixture.nativeElement as HTMLElement;
    const branchCell = el.querySelectorAll('tbody tr')[0]?.querySelectorAll('td')[1]?.textContent ?? '';
    expect(branchCell).toContain('Head Office');
    expect(branchCell).toContain('BR-01');
    expect(branchCell.trim()).not.toBe('br-1');
  });

  it('renders "—" for the branch when branchName is null', async () => {
    makeBed({ listImpl: () => of(onePage({ branchName: null, branchCode: null })) });
    const fixture = TestBed.createComponent(WorkOrderListComponent);
    await vi.runAllTimersAsync();
    fixture.detectChanges();

    const el = fixture.nativeElement as HTMLElement;
    const branchCell = el.querySelectorAll('tbody tr')[0]?.querySelectorAll('td')[1]?.textContent ?? '';
    expect(branchCell.trim()).toBe('—');
  });
});
