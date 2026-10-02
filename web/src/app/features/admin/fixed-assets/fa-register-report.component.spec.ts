/**
 * FaRegisterReportComponent — behaviour specs (FR-FA-17).
 *
 * Covers: run() sends the as-at date and only the filters that were set; rows group under their
 * category subtotal; an asset with no book value renders as such, not as 0.00; a branch refusal
 * (403) shows the server's user-safe wording; export gating + call.
 */
import { HttpErrorResponse } from '@angular/common/http';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { describe, it, expect, vi, afterEach } from 'vitest';
import { SessionStore } from '../../../core/auth/session.store';
import { BranchService } from '../branch/branch.service';
import { CompanyService } from '../company/company.service';
import { CostCentreService } from '../cost-centre/cost-centre.service';
import { OrganisationService } from '../organisation/organisation.service';
import { FaRegisterReportComponent } from './fa-register-report.component';
import { FixedAssetsService } from './fixed-assets.service';
import type { FixedAssetRegisterDto } from './models/fixed-assets.model';

const REGISTER: FixedAssetRegisterDto = {
  asOf: '2026-12-31',
  categoryName: null, branchName: null, status: null, location: null, costCentreName: null,
  rows: [
    {
      assetUid: 'A1', assetNumber: 'FA-00001', name: 'Forklift', categoryCode: 'EQUIP',
      categoryName: 'Equipment', branchName: 'Own Branch', location: 'Yard', costCentreName: null,
      acquisitionDate: '2026-01-01', acquisitionCost: 1200000, cost: 1500000,
      accumulatedDepreciation: 100000, nbv: 1400000, status: 'IN_SERVICE', disposedAt: null, inTotals: true,
    },
    {
      assetUid: 'B1', assetNumber: 'FA-00002', name: 'Delivery Van', categoryCode: 'EQUIP',
      categoryName: 'Equipment', branchName: 'Own Branch', location: 'Depot', costCentreName: null,
      acquisitionDate: '2026-01-01', acquisitionCost: 600000, cost: 600000,
      accumulatedDepreciation: 100000, nbv: null, status: 'DISPOSED', disposedAt: '2026-02-10', inTotals: false,
    },
  ],
  categoryTotals: [
    { categoryCode: 'EQUIP', categoryName: 'Equipment', assetCount: 1, cost: 1500000, accumulatedDepreciation: 100000, nbv: 1400000 },
  ],
  grandTotal: { categoryCode: null, categoryName: null, assetCount: 1, cost: 1500000, accumulatedDepreciation: 100000, nbv: 1400000 },
  rowsNotInTotals: 1,
  currency: 'TZS',
  generatedAt: '2026-12-31T08:00:00Z',
};

function makeBed(faOverrides: Record<string, unknown> = {}, permissions = ['FA.VIEW']) {
  const fa = {
    getRegister: vi.fn(() => of(REGISTER)),
    exportRegister: vi.fn(() => of(new Blob())),
    listCategories: vi.fn(() => of([])),
    ...faOverrides,
  };
  TestBed.configureTestingModule({
    imports: [FaRegisterReportComponent],
    providers: [
      provideRouter([]),
      { provide: FixedAssetsService, useValue: fa },
      { provide: OrganisationService, useValue: { current: vi.fn(() => of({ uid: 'ORG1', id: '1', name: 'Org' })) } },
      { provide: CompanyService, useValue: { list: vi.fn(() => of([{ uid: 'CO1', id: '10', name: 'Co' }])) } },
      { provide: BranchService, useValue: { list: vi.fn(() => of([])) } },
      { provide: CostCentreService, useValue: { listDimensions: vi.fn(() => of([])), listValues: vi.fn() } },
      {
        provide: SessionStore,
        useValue: {
          hasPermission: vi.fn((code: string) => permissions.includes(code)),
          isAuthenticated: signal(true),
          user: signal(null),
          permissions: signal([]),
          activeBranchUid: signal(null),
        },
      },
    ],
  });
  return fa;
}

describe('FaRegisterReportComponent', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('sends the as-at date and only the filters that were set', () => {
    const fa = makeBed();
    const comp = TestBed.createComponent(FaRegisterReportComponent).componentInstance;
    comp.asOf.set('2026-12-31');
    comp.location.set('  yard ');
    comp.run();
    expect(fa.getRegister).toHaveBeenCalledWith({
      asOf: '2026-12-31', categoryUid: null, status: null, branchUid: null,
      location: 'yard', costCentreUid: null,
    });
  });

  it('groups rows under the category subtotal and never shows a disposed asset as 0.00 NBV', () => {
    makeBed();
    const fixture = TestBed.createComponent(FaRegisterReportComponent);
    fixture.componentInstance.run();
    fixture.detectChanges();

    expect(fixture.componentInstance.groups()).toHaveLength(1);
    expect(fixture.componentInstance.groups()[0].rows).toHaveLength(2);
    const html = (fixture.nativeElement as HTMLElement).innerHTML;
    expect(html).toContain('Subtotal — Equipment');
    expect(html).toContain('1,400,000.00');
    expect(html).toContain('no book value');
    expect(html).toContain('left out of the totals');
  });

  it("shows the server's wording when a branch filter is refused (403)", () => {
    const msg = 'You are not assigned to that branch. Choose a branch you work in, or clear the branch filter to see the whole company.';
    makeBed({
      getRegister: vi.fn(() =>
        throwError(() => new HttpErrorResponse({ status: 403, error: { errors: [msg] } })),
      ),
    });
    const fixture = TestBed.createComponent(FaRegisterReportComponent);
    fixture.componentInstance.run();
    fixture.detectChanges();
    expect(fixture.componentInstance.state()).toBe('forbidden');
    expect((fixture.nativeElement as HTMLElement).innerHTML).toContain('not assigned to that branch');
  });

  it('gates export on FA.VIEW + REPORT.EXPORT', () => {
    makeBed();
    expect(TestBed.createComponent(FaRegisterReportComponent).componentInstance.canExport()).toBe(false);
    TestBed.resetTestingModule();

    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:mock');
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined);
    const fa = makeBed({}, ['FA.VIEW', 'REPORT.EXPORT']);
    const comp = TestBed.createComponent(FaRegisterReportComponent).componentInstance;
    expect(comp.canExport()).toBe(true);
    comp.asOf.set('2026-12-31');
    comp.export('CSV');
    expect(fa.exportRegister).toHaveBeenCalledWith(
      expect.objectContaining({ asOf: '2026-12-31' }),
      'CSV',
    );
  });
});
