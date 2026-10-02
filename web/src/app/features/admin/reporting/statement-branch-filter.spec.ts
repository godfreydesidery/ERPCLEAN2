/**
 * Branch filter on the P&L / Balance Sheet / Cash-Flow screens.
 *
 * Covers:
 *  1. Options: a non-root caller is offered only their own branches of the selected company, plus
 *     the company-level slice; root is offered every ACTIVE branch.
 *  2. The picked value maps to the request: a branch uid, `unassigned`, or nothing.
 *  3. The Balance Sheet sends the picked branch on both the read and the export, and prints the
 *     branch caveat only for a branch-scoped statement.
 */
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { SessionStore } from '../../../core/auth/session.store';
import { BranchService } from '../branch/branch.service';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { BalanceSheetComponent } from './balance-sheet.component';
import { BalanceSheetDto } from './models/reporting.model';
import { ReportingService } from './reporting.service';
import {
  COMPANY_LEVEL_OPTION,
  StatementBranchFilterState,
  StatementBranchOptionsService,
} from './statement-branch-filter';

const MY_BRANCHES = [
  { id: '1', uid: 'UB1', userUid: 'U1', branchUid: 'BR-ARU', branchCode: 'ARU', branchName: 'Arusha',
    companyUid: 'CO1', isDefault: true, assignedAt: '2026-01-01T00:00:00Z' },
  { id: '2', uid: 'UB2', userUid: 'U1', branchUid: 'BR-OTHER', branchCode: 'OTH', branchName: 'Elsewhere',
    companyUid: 'CO2', isDefault: false, assignedAt: '2026-01-01T00:00:00Z' },
];
const ALL_BRANCHES = [
  { uid: 'BR-ARU', name: 'Arusha', code: 'ARU', status: 'ACTIVE' },
  { uid: 'BR-DOD', name: 'Dodoma', code: 'DOD', status: 'ACTIVE' },
  { uid: 'BR-OLD', name: 'Closed', code: 'OLD', status: 'INACTIVE' },
];

function bs(branchUid: string | null, branchLabel: string): BalanceSheetDto {
  const zero = { current: 0, comparative: 0 };
  return {
    header: {
      companyId: '10', companyName: 'Main Co', currency: 'TZS', periodLabel: 'As at 2026-06-30',
      comparativeLabel: 'As at 2026-06-29', fromDate: null, toDate: null, asAtDate: '2026-06-30',
      generatedAt: '2026-10-01T08:00:00Z', branchUid, branchLabel,
    },
    sections: [],
    totalAssets: zero, totalLiabilities: zero, totalEquity: zero,
    reconciliation: { label: 'x', computed: zero, expected: zero, difference: zero, ties: true },
  };
}

function configure(isRoot: boolean, reporting: Record<string, unknown> = {}) {
  TestBed.configureTestingModule({
    imports: [BalanceSheetComponent],
    providers: [
      provideHttpClient(), provideHttpClientTesting(), provideRouter([]),
      { provide: ReportingService, useValue: reporting },
      { provide: OrganisationService, useValue: { current: vi.fn(() => of({ uid: 'ORG1' })) } },
      { provide: CompanyService, useValue: { list: vi.fn(() => of([{ uid: 'CO1', id: '10', name: 'Main Co' }])) } },
      { provide: BranchService, useValue: { list: vi.fn(() => of(ALL_BRANCHES)) } },
      { provide: AuthService, useValue: { myBranches: vi.fn(() => of(MY_BRANCHES)) } },
      {
        provide: SessionStore,
        useValue: {
          hasPermission: vi.fn(() => true),
          isAuthenticated: signal(true),
          user: signal({ isRoot }),
          permissions: signal([]),
          activeBranchUid: signal(null),
        },
      },
    ],
  });
}

describe('StatementBranchOptionsService', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('offers a non-root caller only their branches of this company, then the company-level slice', () => {
    configure(false);
    let uids: string[] = [];
    TestBed.inject(StatementBranchOptionsService).load('CO1', true)
      .subscribe((o) => (uids = o.map((x) => x.uid)));
    expect(uids).toEqual(['BR-ARU', COMPANY_LEVEL_OPTION]);
  });

  it('offers root every ACTIVE branch of the company', () => {
    configure(true);
    let uids: string[] = [];
    TestBed.inject(StatementBranchOptionsService).load('CO1', false)
      .subscribe((o) => (uids = o.map((x) => x.uid)));
    expect(uids).toEqual(['BR-ARU', 'BR-DOD']);
  });
});

describe('StatementBranchFilterState', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('maps the pick to the request filter', () => {
    configure(false);
    const state = TestBed.runInInjectionContext(() => new StatementBranchFilterState(true));
    expect(state.filter()).toEqual({});
    state.value.set('BR-ARU');
    expect(state.filter()).toEqual({ branchUid: 'BR-ARU' });
    state.value.set(COMPANY_LEVEL_OPTION);
    expect(state.filter()).toEqual({ unassigned: true });
  });
});

describe('BalanceSheetComponent — branch filter', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('sends the picked branch on the read and the export, and shows the branch caveat', () => {
    const read = vi.fn((..._args: unknown[]) => of(bs('BR-ARU', 'Arusha')));
    const exp = vi.fn((..._args: unknown[]) => of(new Blob(['x'])));
    configure(false, { balanceSheet: read, exportBalanceSheet: exp });
    const fixture = TestBed.createComponent(BalanceSheetComponent);
    fixture.detectChanges();
    const comp = fixture.componentInstance;

    expect(comp.branch.options().map((o) => o.uid)).toEqual(['BR-ARU', COMPANY_LEVEL_OPTION]);
    comp.onBranchChange('BR-ARU');
    comp.run();
    fixture.detectChanges();

    expect(read.mock.calls[0][3]).toEqual({ branchUid: 'BR-ARU' });
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Arusha');
    expect(text).toContain('only the journals posted at the chosen branch');

    comp.export('PDF');
    expect(exp.mock.calls[0][4]).toEqual({ branchUid: 'BR-ARU' });
  });

  it('prints no caveat on the company-wide statement', () => {
    configure(false, { balanceSheet: vi.fn(() => of(bs(null, 'All branches'))) });
    const fixture = TestBed.createComponent(BalanceSheetComponent);
    fixture.detectChanges();
    fixture.componentInstance.run();
    fixture.detectChanges();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('All branches');
    expect(text).not.toContain('only the journals posted at the chosen branch');
  });
});
