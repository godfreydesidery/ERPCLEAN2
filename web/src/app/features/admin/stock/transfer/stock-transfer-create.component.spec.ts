/**
 * StockTransferCreateComponent — the optional, reference-only unit cost per line (V116).
 *
 * Covers:
 *  1. Picking a product prefills the cost with the average (per base unit) from Item Inquiry.
 *  2. Choosing a pack unit re-quotes it per pack (avg × factorToBase).
 *  3. A typed cost is never overwritten — not by a qty change, not by a unit change.
 *  4. No cost permission → no lookup, box stays blank; a 403 or a hidden cost also leaves it blank.
 *  5. The request carries enteredUnitCost only when the box has a value; a negative is refused.
 *  6. The cost input is labelled (WCAG 2.1 AA) and axe finds no serious/critical violations.
 */
import { HttpErrorResponse } from '@angular/common/http';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { AlertService } from '../../../../core/feedback/alert.service';
import { SessionStore } from '../../../../core/auth/session.store';
import { BranchService } from '../../branch/branch.service';
import { CompanyService } from '../../company/company.service';
import { OrganisationService } from '../../organisation/organisation.service';
import { ProductService } from '../../products/product.service';
import { ItemInquiryDto } from '../item-inquiry.model';
import { StockService } from '../stock.service';
import { StockTransferService } from './stock-transfer.service';
import { StockTransferCreateComponent } from './stock-transfer-create.component';
import { assertA11y } from '../../../../../testing/a11y.helper';

const PRODUCT = { uid: 'PRD-1', id: '5', code: 'KON500', name: 'Konyagi 500ml', status: 'ACTIVE' };
const UNITS = [
  { uid: 'U-BTL', name: 'Bottle' },
  { uid: 'U-CTN', name: 'Carton' },
];
const PACKS = [{ unitUid: 'U-CTN', unitName: 'Carton', factorToBase: '12' }];

function inquiry(overrides: Partial<ItemInquiryDto> = {}, buyingPrice: number | null = 1500): ItemInquiryDto {
  return {
    branchName: null, currency: 'TZS', priceListName: null, priceIncludesVat: false,
    costVisible: true, truncated: false,
    rows: [{
      productUid: 'PRD-1', productCode: 'KON500', productName: 'Konyagi 500ml',
      department: null, supplierName: null, unitName: 'Bottle', quantityOnHand: 10,
      stockable: true, buyingPrice, sellingPrice: null,
    }],
    ...overrides,
  };
}

interface BedOpts {
  canSeeCost?: boolean;
  inquiryImpl?: () => unknown;
}

function makeBed(opts: BedOpts = {}) {
  const canSeeCost = opts.canSeeCost ?? true;
  const stock = { itemInquiry: vi.fn(opts.inquiryImpl ?? (() => of(inquiry()))) };
  const transfers = {
    activeLocations: vi.fn(() => of([])),
    create: vi.fn((_req: unknown) => of({ uid: 'TRF1', transferNumber: 'TRF-0001' })),
  };
  TestBed.configureTestingModule({
    imports: [StockTransferCreateComponent],
    providers: [
      // A catch-all so the post-create navigation to the detail route resolves in the test.
      provideRouter([{ path: '**', children: [] }]),
      { provide: StockTransferService, useValue: transfers },
      { provide: StockService, useValue: stock },
      {
        provide: ProductService,
        useValue: {
          list: vi.fn(() => of({ rows: [PRODUCT], meta: { page: 0, size: 200, totalElements: 1, totalPages: 1, hasNext: false } })),
          listProductUnits: vi.fn(() => of(UNITS)),
          listBulkPacks: vi.fn(() => of(PACKS)),
        },
      },
      { provide: OrganisationService, useValue: { current: vi.fn(() => of({ uid: 'ORG1', id: '1', name: 'Acme' })) } },
      { provide: CompanyService, useValue: { list: vi.fn(() => of([{ uid: 'CO1', id: '10', name: 'Main Co' }])) } },
      { provide: BranchService, useValue: { list: vi.fn(() => of([])) } },
      { provide: AlertService, useValue: { success: vi.fn(), error: vi.fn() } },
      {
        provide: SessionStore,
        useValue: {
          hasPermission: vi.fn((code: string) =>
            code === 'INVENTORY.VALUATION.VIEW' ? canSeeCost : true),
          isAuthenticated: signal(true),
          user: signal({ activeCompanyUid: 'CO1', isRoot: false }),
          permissions: signal([]),
          activeBranchUid: signal(null),
        },
      },
    ],
  });
  return { stock, transfers };
}

function create() {
  const fixture = TestBed.createComponent(StockTransferCreateComponent);
  fixture.detectChanges();
  return fixture;
}

/** Fills the header so submit() reaches the request. */
function fillHeader(comp: StockTransferCreateComponent) {
  comp.sourceBranchUid.set('BR1');
  comp.sourceLocationUid.set('LOC-A');
  comp.destBranchUid.set('BR2');
  comp.destLocationUid.set('LOC-B');
  comp.transferDate.set('2026-11-21');
}

describe('StockTransferCreateComponent — unit cost prefill (V116)', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('prefills the average cost per base unit when a product is picked', () => {
    const { stock } = makeBed();
    const comp = create().componentInstance;

    comp.updateLineProduct(0, 'PRD-1');

    expect(stock.itemInquiry).toHaveBeenCalledWith('KON500', null, true);
    expect(comp.lines()[0].unitUid).toBe('U-BTL');
    expect(comp.lines()[0].unitCost).toBe('1500');
  });

  it('re-quotes the prefill per pack when a pack unit is chosen (avg × factorToBase)', () => {
    makeBed();
    const comp = create().componentInstance;
    comp.updateLineProduct(0, 'PRD-1');

    comp.updateLineUnit(0, 'U-CTN');
    expect(comp.lines()[0].unitCost).toBe('18000');

    comp.updateLineUnit(0, 'U-BTL');
    expect(comp.lines()[0].unitCost).toBe('1500');
  });

  it('never overwrites a typed cost — not on a qty change, not on a unit change', () => {
    makeBed();
    const comp = create().componentInstance;
    comp.updateLineProduct(0, 'PRD-1');

    comp.updateLineCost(0, 1750);
    comp.updateLineQty(0, 3);
    comp.updateLineUnit(0, 'U-CTN');

    expect(comp.lines()[0].unitCost).toBe('1750');
    expect(comp.lines()[0].costTouched).toBe(true);
  });

  it('does not ask for cost, and leaves the box blank, without INVENTORY.VALUATION.VIEW', () => {
    const { stock } = makeBed({ canSeeCost: false });
    const comp = create().componentInstance;

    comp.updateLineProduct(0, 'PRD-1');

    expect(stock.itemInquiry).not.toHaveBeenCalled();
    expect(comp.lines()[0].unitCost).toBe('');
  });

  it('leaves the box blank silently on a 403', () => {
    makeBed({ inquiryImpl: () => throwError(() => new HttpErrorResponse({ status: 403 })) });
    const comp = create().componentInstance;

    comp.updateLineProduct(0, 'PRD-1');

    expect(comp.lines()[0].unitCost).toBe('');
    expect(comp.formError()).toBeNull();
  });

  it('leaves the box blank when cost is withheld or the product was never costed', () => {
    makeBed({ inquiryImpl: () => of(inquiry({ costVisible: false })) });
    let comp = create().componentInstance;
    comp.updateLineProduct(0, 'PRD-1');
    expect(comp.lines()[0].unitCost).toBe('');
    TestBed.resetTestingModule();

    makeBed({ inquiryImpl: () => of(inquiry({}, null)) });
    comp = create().componentInstance;
    comp.updateLineProduct(0, 'PRD-1');
    expect(comp.lines()[0].unitCost).toBe('');
  });
});

describe('StockTransferCreateComponent — submit with unit cost (V116)', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('sends enteredUnitCost when the box has a value', () => {
    const { transfers } = makeBed();
    const comp = create().componentInstance;
    fillHeader(comp);
    comp.updateLineProduct(0, 'PRD-1');
    comp.updateLineQty(0, 2);
    comp.updateLineUnit(0, 'U-CTN');

    comp.submit();

    expect(transfers.create).toHaveBeenCalledTimes(1);
    const req = transfers.create.mock.calls[0][0] as { lines: Record<string, unknown>[] };
    expect(req.lines[0]).toEqual({
      productUid: 'PRD-1', qty: '2', unitUid: 'U-CTN', enteredUnitCost: '18000',
    });
  });

  it('omits enteredUnitCost when the box is blank', () => {
    const { transfers } = makeBed({ canSeeCost: false });
    const comp = create().componentInstance;
    fillHeader(comp);
    comp.updateLineProduct(0, 'PRD-1');
    comp.updateLineQty(0, 2);

    comp.submit();

    const req = transfers.create.mock.calls[0][0] as { lines: Record<string, unknown>[] };
    expect(req.lines[0]).not.toHaveProperty('enteredUnitCost');
  });

  it('refuses a negative cost before calling the API', () => {
    const { transfers } = makeBed();
    const comp = create().componentInstance;
    fillHeader(comp);
    comp.updateLineProduct(0, 'PRD-1');
    comp.updateLineQty(0, 2);
    comp.updateLineCost(0, -5);

    comp.submit();

    expect(transfers.create).not.toHaveBeenCalled();
    expect(comp.formError()).toContain('unit cost must be zero or more');
  });
});

describe('StockTransferCreateComponent — accessibility', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('labels the unit cost input and passes axe', async () => {
    makeBed();
    const fixture = create();
    await fixture.whenStable();
    fixture.detectChanges();

    const el = fixture.nativeElement as HTMLElement;
    const input = el.querySelector<HTMLInputElement>('#lineCost0');
    expect(input).not.toBeNull();
    expect(el.querySelector('label[for="lineCost0"]')?.textContent?.trim()).toBe('Unit cost');
    expect(input?.getAttribute('aria-describedby')).toBe('unitCostHelp');

    await assertA11y(fixture);
  });
});
