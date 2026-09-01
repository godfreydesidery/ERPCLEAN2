/**
 * ItemInquiryComponent — the counter lookup (K-2026-08-30 #3).
 *
 * Covers:
 *  1. Searches the SERVER as you type, debounced; an empty box asks nothing.
 *  2. A withheld cost and a never-costed item are rendered differently — the whole point of
 *     `costVisible`, and the one thing a cashier would otherwise report as a data problem.
 *  3. A null price renders as an em dash, never 0.00 (unknown is not free).
 *  4. A truncated result says so, rather than letting a clipped list read as the whole answer.
 *  5. A non-stocked item shows "Not stocked", not a quantity of zero.
 *  6. Changing the branch re-asks the same search (distinctUntilChanged must not swallow it).
 *  7. A failed lookup leaves the search box working — the stream must survive an error.
 */
import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { SessionStore } from '../../../core/auth/session.store';
import { BranchService } from '../branch/branch.service';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { ItemInquiryDto } from './item-inquiry.model';
import { ItemInquiryComponent } from './item-inquiry.component';
import { StockService } from './stock.service';

const STUB_ORG = { uid: 'ORG1', id: '1', name: 'Acme' };
const STUB_COMPANY = { uid: 'CO1', id: '10', name: 'Main Co' };
const MY_BRANCHES = [
  {
    id: '11', uid: 'UB1', userUid: 'U1', branchUid: 'BR-HQ', branchCode: 'HQ',
    branchName: 'Head Office', companyUid: 'CO1', isDefault: true,
    assignedAt: '2026-01-01T00:00:00Z',
  },
];

function result(overrides: Partial<ItemInquiryDto> = {}): ItemInquiryDto {
  return {
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
      // Never costed, never priced — must NOT render as 0.00.
      {
        productUid: 'PRD-2', productCode: 'NEW01', productName: 'New arrival',
        unitName: 'Bottle', quantityOnHand: 6, stockable: true,
        buyingPrice: null, sellingPrice: null,
      },
    ],
    ...overrides,
  };
}

function makeBed(overrides: {
  itemInquirySpy?: ReturnType<typeof vi.fn>;
  hasPermission?: (code: string) => boolean;
} = {}) {
  const itemInquirySpy = overrides.itemInquirySpy ?? vi.fn(() => of(result()));

  TestBed.configureTestingModule({
    imports: [ItemInquiryComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      { provide: StockService, useValue: { itemInquiry: itemInquirySpy } },
      { provide: OrganisationService, useValue: { current: vi.fn(() => of(STUB_ORG)) } },
      { provide: CompanyService, useValue: { list: vi.fn(() => of([STUB_COMPANY])) } },
      { provide: BranchService, useValue: { list: vi.fn(() => of([])) } },
      { provide: AuthService, useValue: { myBranches: vi.fn(() => of(MY_BRANCHES)) } },
      {
        provide: SessionStore,
        useValue: {
          hasPermission: vi.fn(overrides.hasPermission ?? (() => true)),
          isAuthenticated: signal(true),
          user: signal({ activeCompanyUid: 'CO1', isRoot: false }),
          permissions: signal([]),
          activeBranchUid: signal(null),
        },
      },
    ],
  });

  return { itemInquirySpy };
}

describe('ItemInquiryComponent', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('searches the server after the user stops typing', async () => {
    const { itemInquirySpy } = makeBed();
    const fixture = TestBed.createComponent(ItemInquiryComponent);
    const comp = fixture.componentInstance;

    comp.onSearchChange('konyagi');
    await vi.runAllTimersAsync();

    expect(itemInquirySpy).toHaveBeenCalledWith('konyagi', null);
    expect(comp.rows()).toHaveLength(2);
  });

  // An empty box is not a request for the whole catalogue.
  it('asks nothing when the search box is cleared', async () => {
    const { itemInquirySpy } = makeBed();
    const fixture = TestBed.createComponent(ItemInquiryComponent);
    const comp = fixture.componentInstance;

    comp.onSearchChange('   ');
    await vi.runAllTimersAsync();

    expect(itemInquirySpy).not.toHaveBeenCalled();
    expect(comp.result()).toBeNull();
  });

  it('shows an unknown price as a dash, never as 0.00', async () => {
    makeBed();
    const fixture = TestBed.createComponent(ItemInquiryComponent);
    const comp = fixture.componentInstance;

    comp.onSearchChange('kon');
    await vi.runAllTimersAsync();
    fixture.detectChanges();

    // Read the cells, not the page text: "9,500.00" trivially contains "0.00".
    const rows = (fixture.nativeElement as HTMLElement).querySelectorAll('tbody tr');
    const cells = (i: number) =>
      Array.from(rows[i].querySelectorAll('td')).map((td) => td.textContent?.trim() ?? '');

    expect(cells(0)[4]).toBe('7,200.00');
    expect(cells(0)[5]).toBe('9,500.00');
    // The never-costed, never-priced row: unknown, not free.
    expect(cells(1)[4]).toBe('—');
    expect(cells(1)[5]).toBe('—');
  });

  /**
   * The distinction the flag exists for: "we have never costed this" (dash) versus "you may not
   * see it" (Hidden). Rendering the second as the first would have cashiers reporting perfectly
   * good items as missing data.
   */
  it('says the cost is hidden rather than unknown when the caller may not see it', async () => {
    makeBed({
      itemInquirySpy: vi.fn(() => of(result({ costVisible: false }))),
      hasPermission: (code) => code !== 'INVENTORY.VALUATION.VIEW',
    });
    const fixture = TestBed.createComponent(ItemInquiryComponent);
    const comp = fixture.componentInstance;

    comp.onSearchChange('kon');
    await vi.runAllTimersAsync();
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Hidden');
  });

  it('says when the result was clipped', async () => {
    makeBed({ itemInquirySpy: vi.fn(() => of(result({ truncated: true }))) });
    const fixture = TestBed.createComponent(ItemInquiryComponent);
    const comp = fixture.componentInstance;

    comp.onSearchChange('a');
    await vi.runAllTimersAsync();
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('narrow it down');
  });

  it('marks a non-stocked item rather than showing it as zero on hand', async () => {
    makeBed({
      itemInquirySpy: vi.fn(() => of(result({
        rows: [{
          productUid: 'PRD-3', productCode: 'SVC1', productName: 'Delivery service',
          unitName: 'Each', quantityOnHand: 0, stockable: false,
          buyingPrice: null, sellingPrice: 5000,
        }],
      }))),
    });
    const fixture = TestBed.createComponent(ItemInquiryComponent);
    const comp = fixture.componentInstance;

    comp.onSearchChange('delivery');
    await vi.runAllTimersAsync();
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Not stocked');
  });

  // distinctUntilChanged would swallow the identical term, leaving the branch change with no effect.
  it('re-runs the same search when the branch changes', async () => {
    const { itemInquirySpy } = makeBed();
    const fixture = TestBed.createComponent(ItemInquiryComponent);
    const comp = fixture.componentInstance;

    comp.onSearchChange('kon');
    await vi.runAllTimersAsync();
    expect(itemInquirySpy).toHaveBeenCalledTimes(1);

    comp.onBranchChange('BR-HQ');
    await vi.runAllTimersAsync();

    expect(itemInquirySpy).toHaveBeenCalledTimes(2);
    expect(itemInquirySpy).toHaveBeenLastCalledWith('kon', 'BR-HQ');
  });

  /**
   * An error escaping the switchMap would terminate the stream and leave the box permanently dead
   * after one failure — the bug the direct-receipt pickers already shipped once.
   */
  it('keeps searching after a failed lookup', async () => {
    let calls = 0;
    const itemInquirySpy = vi.fn(() => {
      calls += 1;
      return calls === 1
        ? throwError(() => new HttpErrorResponse({ status: 500 }))
        : of(result());
    });
    makeBed({ itemInquirySpy });
    const fixture = TestBed.createComponent(ItemInquiryComponent);
    const comp = fixture.componentInstance;

    comp.onSearchChange('kon');
    await vi.runAllTimersAsync();
    expect(comp.state()).toBe('error');

    comp.onSearchChange('konyagi');
    await vi.runAllTimersAsync();

    expect(comp.state()).toBe('idle');
    expect(comp.rows()).toHaveLength(2);
  });

  it('never calls the API without both view permissions', async () => {
    const { itemInquirySpy } = makeBed({ hasPermission: () => false });
    const fixture = TestBed.createComponent(ItemInquiryComponent);
    fixture.detectChanges();
    await vi.runAllTimersAsync();

    expect(itemInquirySpy).not.toHaveBeenCalled();
    expect((fixture.nativeElement as HTMLElement).textContent)
      .toContain("don't have permission");
  });

  it('offers only the branches the caller is assigned to', async () => {
    makeBed();
    const fixture = TestBed.createComponent(ItemInquiryComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.branchOptions()).toEqual([
      { uid: 'BR-HQ', label: 'Head Office', hint: 'HQ' },
    ]);
  });
});
