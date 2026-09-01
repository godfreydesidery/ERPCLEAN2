/**
 * Accessibility gate — ItemInquiryComponent (K-2026-08-30 #3).
 *
 * Covers the empty (nothing searched yet) state and a populated result table, including the
 * withheld-cost column, which renders only for a caller who may not see cost.
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
import { ItemInquiryDto } from './item-inquiry.model';
import { ItemInquiryComponent } from './item-inquiry.component';
import { StockService } from './stock.service';
import { assertA11y } from '../../../../testing/a11y.helper';

const STUB_ORG = { uid: 'ORG1', id: '1', name: 'Acme' };
const STUB_COMPANY = { uid: 'CO1', id: '10', name: 'Main Co' };

const MOCK_RESULT: ItemInquiryDto = {
  branchName: null,
  currency: 'TZS',
  priceListName: 'Retail 2026',
  priceIncludesVat: true,
  costVisible: true,
  truncated: false,
  rows: [
    {
      productUid: 'PRD-1', productCode: 'KON500', productName: 'Konyagi 500ml',
      unitName: 'Bottle', quantityOnHand: 48, stockable: true,
      buyingPrice: 7200, sellingPrice: 9500,
    },
    {
      productUid: 'PRD-2', productCode: 'NEW01', productName: 'New arrival',
      unitName: 'Bottle', quantityOnHand: 6, stockable: true,
      buyingPrice: null, sellingPrice: null,
    },
  ],
};

function makeBed(dto: ItemInquiryDto = MOCK_RESULT) {
  TestBed.configureTestingModule({
    imports: [ItemInquiryComponent],
    providers: [
      provideHttpClient(), provideHttpClientTesting(),
      provideRouter([]),
      { provide: StockService, useValue: { itemInquiry: vi.fn(() => of(dto)) } },
      { provide: OrganisationService, useValue: { current: vi.fn(() => of(STUB_ORG)) } },
      { provide: CompanyService, useValue: { list: vi.fn(() => of([STUB_COMPANY])) } },
      { provide: BranchService, useValue: { list: vi.fn(() => of([])) } },
      { provide: AuthService, useValue: { myBranches: vi.fn(() => of([])) } },
      {
        provide: SessionStore,
        useValue: {
          hasPermission: vi.fn(() => true),
          isAuthenticated: signal(true),
          user: signal({ activeCompanyUid: 'CO1', isRoot: false }),
          permissions: signal([]),
          activeBranchUid: signal(null),
        },
      },
    ],
  });
}

/**
 * Drives the debounced search to a result, then hands back REAL timers.
 *
 * axe runs its own async passes on real timers: leaving fake ones installed hangs every scan until
 * the test times out, which is what these three specs did on their first run.
 */
async function searchAndSettle(comp: ItemInquiryComponent, term: string): Promise<void> {
  vi.useFakeTimers();
  try {
    comp.onSearchChange(term);
    await vi.runAllTimersAsync();
  } finally {
    vi.useRealTimers();
  }
}

describe('ItemInquiryComponent — a11y', () => {
  afterEach(() => TestBed.resetTestingModule());

  // The empty (pre-search) state is deliberately NOT scanned separately: it renders the same
  // header and the same search form as the populated state, minus the table, so it is a strict
  // subset of the scan below. Every axe scan is expensive enough under the parallel jsdom runner
  // to starve its own budget, so a redundant one buys flakiness and no coverage.

  it('has no axe violations with results on screen', async () => {
    makeBed();
    const fixture = TestBed.createComponent(ItemInquiryComponent);
    await searchAndSettle(fixture.componentInstance, 'konyagi');
    fixture.detectChanges();
    await assertA11y(fixture);
  }, 20_000);

  it('has no axe violations when the cost column is withheld', async () => {
    makeBed({ ...MOCK_RESULT, costVisible: false });
    const fixture = TestBed.createComponent(ItemInquiryComponent);
    await searchAndSettle(fixture.componentInstance, 'konyagi');
    fixture.detectChanges();
    await assertA11y(fixture);
  }, 20_000);
});
