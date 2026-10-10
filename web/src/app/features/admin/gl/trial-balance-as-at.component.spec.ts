/**
 * TrialBalanceComponent — "as at" basis (ACC-14): opening / movement / closing, a date range and a
 * branch filter, and the export asks for the same as-at figures.
 */
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { SessionStore } from '../../../core/auth/session.store';
import { assertA11y } from '../../../../testing/a11y.helper';
import { BranchService } from '../branch/branch.service';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { GlService } from './gl.service';
import type { TrialBalanceRangeDto } from './models/gl.model';
import { TrialBalanceComponent } from './trial-balance.component';

const RANGE: TrialBalanceRangeDto = {
  companyId: '10', baseCurrency: 'TZS', from: '2026-02-01', asAt: '2026-02-28', branchUid: null,
  branchName: null, periodLabel: '2026-02-01 to 2026-02-28',
  rows: [
    { accountId: '1', accountUid: 'A1', accountCode: '5200', accountName: 'Rent', accountType: 'EXPENSE', normalBalance: 'DEBIT',
      openingDebit: 100, openingCredit: 0, movementDebit: 80, movementCredit: 0, closingDebit: 180, closingCredit: 0 },
    { accountId: '2', accountUid: 'A2', accountCode: '3000', accountName: 'Capital', accountType: 'EQUITY', normalBalance: 'CREDIT',
      openingDebit: 0, openingCredit: 100, movementDebit: 0, movementCredit: 80, closingDebit: 0, closingCredit: 180 },
  ],
  openingDebit: 100, openingCredit: 100, movementDebit: 80, movementCredit: 80, closingDebit: 180, closingCredit: 180,
};

function makeBed() {
  const rangeSpy = vi.fn(() => of(RANGE));
  const exportSpy = vi.fn(() => of(new Blob()));
  TestBed.configureTestingModule({
    imports: [TrialBalanceComponent],
    providers: [
      provideHttpClient(), provideHttpClientTesting(), provideRouter([]),
      {
        provide: GlService,
        useValue: {
          getTrialBalance: vi.fn(() => of({ rows: [], totalDebits: '0', totalCredits: '0' })),
          getTrialBalanceForPeriod: vi.fn(),
          getTrialBalanceRange: rangeSpy,
          exportTrialBalance: exportSpy,
          listPeriods: vi.fn(() => of([])),
        },
      },
      { provide: BranchService, useValue: { list: vi.fn(() => of([{ id: '7', uid: 'BR7', name: 'Arusha' }])) } },
      { provide: OrganisationService, useValue: { current: vi.fn(() => of({ uid: 'ORG1', id: '1', name: 'Acme' })) } },
      { provide: CompanyService, useValue: { list: vi.fn(() => of([{ uid: 'CO1', id: '10', name: 'Main Co' }])) } },
      {
        provide: SessionStore,
        useValue: {
          hasPermission: vi.fn(() => true),
          isAuthenticated: signal(true), user: signal(null), permissions: signal([]), activeBranchUid: signal(null),
        },
      },
    ],
  });
  return { rangeSpy, exportSpy };
}

describe('TrialBalanceComponent — as at', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('loads opening / movement / closing for the dates and branch chosen', () => {
    const { rangeSpy } = makeBed();
    const fixture = TestBed.createComponent(TrialBalanceComponent);
    fixture.detectChanges();
    const c = fixture.componentInstance;
    c.rangeFrom.set('2026-02-01');
    c.asAt.set('2026-02-28');
    c.branchUid.set('BR7');
    c.onBasisChange('asAt');
    fixture.detectChanges();

    expect(rangeSpy).toHaveBeenLastCalledWith('10', { from: '2026-02-01', asAt: '2026-02-28', branchUid: 'BR7' });
    expect(c.branches().map((b) => b.name)).toEqual(['Arusha']);
    expect(c.isBalanced()).toBe(true);
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Closing Dr');
    expect(text).toContain('180.00');
  });

  it('exports the as-at figures', () => {
    const { exportSpy } = makeBed();
    const fixture = TestBed.createComponent(TrialBalanceComponent);
    fixture.detectChanges();
    const c = fixture.componentInstance;
    c.asAt.set('2026-02-28');
    c.onBasisChange('asAt');
    c.export('PDF');
    expect(exportSpy).toHaveBeenCalledWith('10', 'PDF', null, { from: undefined, asAt: '2026-02-28', branchUid: undefined });
  });

  it('has no axe violations in the as-at view', async () => {
    makeBed();
    const fixture = TestBed.createComponent(TrialBalanceComponent);
    fixture.detectChanges();
    fixture.componentInstance.onBasisChange('asAt');
    fixture.detectChanges();
    await assertA11y(fixture);
  }, 20_000);
});
