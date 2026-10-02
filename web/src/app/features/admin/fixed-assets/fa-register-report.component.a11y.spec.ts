/**
 * Accessibility gate — FaRegisterReportComponent.
 *
 * Covers: the filter bar in its empty state, and the populated register (grouped rows, subtotal
 * row, grand total, export toolbar).
 */
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { SessionStore } from '../../../core/auth/session.store';
import { BranchService } from '../branch/branch.service';
import { CompanyService } from '../company/company.service';
import { CostCentreService } from '../cost-centre/cost-centre.service';
import { OrganisationService } from '../organisation/organisation.service';
import { FaRegisterReportComponent } from './fa-register-report.component';
import { FixedAssetsService } from './fixed-assets.service';
import type { FixedAssetRegisterDto } from './models/fixed-assets.model';
import { assertA11y } from '../../../../testing/a11y.helper';

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
      categoryName: 'Equipment', branchName: 'Own Branch', location: null, costCentreName: null,
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

function makeBed() {
  TestBed.configureTestingModule({
    imports: [FaRegisterReportComponent],
    providers: [
      provideRouter([]),
      {
        provide: FixedAssetsService,
        useValue: {
          getRegister: vi.fn(() => of(REGISTER)),
          exportRegister: vi.fn(() => of(new Blob())),
          listCategories: vi.fn(() => of([])),
        },
      },
      { provide: OrganisationService, useValue: { current: vi.fn(() => of({ uid: 'ORG1', id: '1', name: 'Org' })) } },
      { provide: CompanyService, useValue: { list: vi.fn(() => of([{ uid: 'CO1', id: '10', name: 'Co' }])) } },
      { provide: BranchService, useValue: { list: vi.fn(() => of([])) } },
      { provide: CostCentreService, useValue: { listDimensions: vi.fn(() => of([])), listValues: vi.fn() } },
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
}

describe('FaRegisterReportComponent — a11y', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('has no axe violations in the empty (pre-run) state', async () => {
    makeBed();
    const fixture = TestBed.createComponent(FaRegisterReportComponent);
    fixture.detectChanges();
    await assertA11y(fixture);
  }, 20_000);

  it('has no axe violations with a populated register', async () => {
    makeBed();
    const fixture = TestBed.createComponent(FaRegisterReportComponent);
    fixture.detectChanges();
    fixture.componentInstance.run();
    fixture.detectChanges();
    await assertA11y(fixture);
  }, 20_000);
});
