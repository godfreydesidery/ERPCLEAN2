/**
 * PosSaleComponent specs.
 *
 * Covers:
 *  1. Renders once: fires company + options load on startup.
 *  2. submit() requires session.
 *  3. submit() requires customer.
 *  4. submit() requires currency.
 *  5. submit() requires at least one line.
 *  6. submit() validates tendered >= subtotal.
 *  7. submit() calls processSale with correct payload (customerId resolved from uid).
 *  8. 403 is the guard — canSell computed from hasPermission.
 *  9. lineSubtotal computes qty × price − discount.
 * 10. resetSale clears form state.
 */
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { CompanyService } from '../company/company.service';
import { BranchService } from '../branch/branch.service';
import { OrganisationService } from '../organisation/organisation.service';
import { CustomerService } from '../parties/customer.service';
import { AgentService } from '../parties/agent.service';
import { ProductService } from '../products/product.service';
import { SalesService } from '../sales/sales.service';
import { PosService } from './pos.service';
import { PosSaleComponent } from './pos-sale.component';

// ── Stubs ─────────────────────────────────────────────────────────────────────

const stubCustomer = {
  id: '5', uid: 'CUST1', companyId: '10', code: 'C001', displayName: 'Walk-in',
  legalName: null, tin: null, vatRegistered: false, vrn: null, businessRegNo: null,
  mobileMoneyNo: null, phone: null, email: null, physicalAddress: null, postalAddress: null,
  region: null, district: null, partyType: 'INDIVIDUAL' as const,
  customerKind: 'CASH_WALK_IN' as const, creditLimit: null, paymentTermsDays: null,
  status: 'ACTIVE' as const,
  version: null, createdAt: null, createdBy: null, updatedAt: null, updatedBy: null,
};

// POS sale now requires an agent (BR-SALES-06 / DEFECT-POS-AGENT fix).
const stubAgent = {
  id: '7', uid: 'AGENT1', displayName: 'Sales Rep', code: 'AG1', status: 'ACTIVE' as const,
} as any;

const stubInvoice = {
  id: '99', uid: 'INV1', companyId: '10', branchId: '2', documentType: 'INVOICE' as const,
  invoiceNumber: 'INV-0001', status: 'FINALISED' as const, customerId: '5',
  customerName: 'Walk-in', agentId: null, agentName: null, routeUid: null, routeCode: null,
  routeName: null, currency: 'TZS', docDiscountAmount: null, docDiscountPercent: null,
  netTotalAmount: '1000.00', vatTotalAmount: '0.00', grossTotalAmount: '1000.00',
  taxSummary: null, finalisedAt: null, finalisedBy: null, voidedAt: null, voidedBy: null,
  voidReason: null, notes: null, version: null, createdAt: null, createdBy: null,
  updatedAt: null, updatedBy: null,
};

const emptySessionPage = () => ({
  rows: [],
  meta: { page: 0, size: 50, totalElements: 0, totalPages: 0, hasNext: false },
});

function makeSessionStore(canSell = false) {
  return {
    hasPermission: vi.fn(() => canSell),
    isAuthenticated: signal(true),
    user: signal(null),
    permissions: signal([]),
    activeBranchUid: signal(null),
  };
}

function makeBed(opts: { canSell?: boolean; processSaleImpl?: () => any } = {}) {
  const { canSell = true, processSaleImpl } = opts;
  const sessionStore = makeSessionStore(canSell);

  TestBed.configureTestingModule({
    imports: [PosSaleComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      {
        provide: PosService,
        useValue: {
          listSessions: vi.fn(() => of(emptySessionPage())),
          processSale: vi.fn(processSaleImpl ?? (() => of(stubInvoice))),
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
        provide: BranchService,
        useValue: { list: vi.fn(() => of([])) },
      },
      {
        provide: CustomerService,
        useValue: { list: vi.fn(() => of({ rows: [stubCustomer], meta: {} })) },
      },
      {
        provide: AgentService,
        useValue: { list: vi.fn(() => of({ rows: [], meta: {} })) },
      },
      {
        provide: ProductService,
        useValue: {
          list: vi.fn(() => of({ rows: [], meta: {} })),
          listProductUnits: vi.fn(() => of([])),
          listPrices: vi.fn(() => of([])),
        },
      },
      {
        provide: SalesService,
        useValue: {
          listTaxRates: vi.fn(() => of([])),
        },
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

describe('PosSaleComponent — init', () => {
  beforeEach(() => { vi.useFakeTimers(); makeBed(); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('loads companies on startup', async () => {
    const _comp = TestBed.createComponent(PosSaleComponent).componentInstance;
    const orgSvc = TestBed.inject(OrganisationService) as any;
    await vi.runAllTimersAsync();
    expect(orgSvc.current).toHaveBeenCalledTimes(1);
  });
});

// ── submit() validation ────��───────────────────────────────────────────────────

describe('PosSaleComponent — submit validation', () => {
  beforeEach(() => { vi.useFakeTimers(); makeBed(); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('requires session', async () => {
    const comp = TestBed.createComponent(PosSaleComponent).componentInstance;
    const svc = TestBed.inject(PosService) as any;
    await vi.runAllTimersAsync();

    comp.selectedSessionUid.set('');
    comp.submit();
    expect(comp.formError()).toMatch(/session/i);
    expect(svc.processSale).not.toHaveBeenCalled();
  });

  it('requires customer', async () => {
    const comp = TestBed.createComponent(PosSaleComponent).componentInstance;
    const svc = TestBed.inject(PosService) as any;
    await vi.runAllTimersAsync();

    comp.selectedSessionUid.set('SESS1');
    comp.selectedCustomerUid.set('');
    comp.submit();
    expect(comp.formError()).toMatch(/customer/i);
    expect(svc.processSale).not.toHaveBeenCalled();
  });

  it('requires currency', async () => {
    const comp = TestBed.createComponent(PosSaleComponent).componentInstance;
    const svc = TestBed.inject(PosService) as any;
    await vi.runAllTimersAsync();

    comp.selectedSessionUid.set('SESS1');
    comp.selectedCustomerUid.set('CUST1');
    comp.agents.set([stubAgent]);
    comp.selectedAgentUid.set('AGENT1');
    comp.currency.set('');
    comp.submit();
    expect(comp.formError()).toMatch(/currency/i);
    expect(svc.processSale).not.toHaveBeenCalled();
  });

  it('requires at least one line', async () => {
    const comp = TestBed.createComponent(PosSaleComponent).componentInstance;
    const svc = TestBed.inject(PosService) as any;
    await vi.runAllTimersAsync();

    comp.selectedSessionUid.set('SESS1');
    comp.selectedCustomerUid.set('CUST1');
    comp.agents.set([stubAgent]);
    comp.selectedAgentUid.set('AGENT1');
    comp.currency.set('TZS');
    comp.lines.set([]);
    comp.submit();
    expect(comp.formError()).toMatch(/line/i);
    expect(svc.processSale).not.toHaveBeenCalled();
  });

  it('rejects tendered < subtotal', async () => {
    const comp = TestBed.createComponent(PosSaleComponent).componentInstance;
    const svc = TestBed.inject(PosService) as any;
    await vi.runAllTimersAsync();

    comp.selectedSessionUid.set('SESS1');
    comp.selectedCustomerUid.set('CUST1');
    comp.agents.set([stubAgent]);
    comp.selectedAgentUid.set('AGENT1');
    comp.currency.set('TZS');
    // seed customers so resolve works
    comp.customers.set([stubCustomer]);
    comp.lines.set([{
      id: 'line-1', productUid: 'P1', productId: '10', productName: 'Widget',
      unitUid: 'U1', unitId: '1', unitName: 'pcs', quantity: '2', unitPrice: '500.00', lineDiscountAmount: '0.00', lineUnitOptions: [], lineUnitsLoading: false,
    }]);
    comp.tenderedAmount.set('500'); // subtotal=1000, tendered=500 → under
    comp.submit();
    expect(comp.formError()).toMatch(/less than/i);
    expect(svc.processSale).not.toHaveBeenCalled();
  });
});

// ── submit() success ───────────────────────────────────────────────────────────

describe('PosSaleComponent — submit success', () => {
  beforeEach(() => { vi.useFakeTimers(); makeBed(); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('calls processSale with customerId (id, not uid) resolved from uid', async () => {
    const comp = TestBed.createComponent(PosSaleComponent).componentInstance;
    const svc = TestBed.inject(PosService) as any;
    await vi.runAllTimersAsync();

    comp.customers.set([stubCustomer]);
    comp.agents.set([stubAgent]);
    comp.selectedAgentUid.set('AGENT1');
    comp.selectedSessionUid.set('SESS1');
    comp.selectedCustomerUid.set('CUST1'); // uid
    comp.currency.set('TZS');
    comp.lines.set([{
      id: 'line-1', productUid: 'P1', productId: '10', productName: 'Widget',
      unitUid: 'U1', unitId: '1', unitName: 'pcs', quantity: '2', unitPrice: '500.00', lineDiscountAmount: '0.00', lineUnitOptions: [], lineUnitsLoading: false,
    }]);
    comp.tenderedAmount.set('1000');
    comp.submit();

    expect(svc.processSale).toHaveBeenCalledOnce();
    const payload = svc.processSale.mock.calls[0][0];
    // customerId must be the numeric id '5', not the uid 'CUST1'
    expect(payload.customerId).toBe('5');
    expect(payload.sessionUid).toBe('SESS1');
    expect(payload.currency).toBe('TZS');
    expect(payload.lines).toHaveLength(1);
    expect(payload.tenderedAmount).toBe('1000');
  });
});

// ── lineSubtotal ───────────────────────────────────────────────────────────────

describe('PosSaleComponent — lineSubtotal', () => {
  beforeEach(() => { vi.useFakeTimers(); makeBed(); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('computes qty × price − discount', async () => {
    const comp = TestBed.createComponent(PosSaleComponent).componentInstance;
    await vi.runAllTimersAsync();
    const line = { id: 'l1', productUid: '', productId: '', productName: '', unitUid: '', unitId: '', unitName: '', quantity: '3', unitPrice: '200.00', lineDiscountAmount: '50.00', lineUnitOptions: [], lineUnitsLoading: false };
    expect(comp.lineSubtotal(line)).toBe(550);
  });
});

// ── numeric tendered amount (regression: Complete Sale crash) ────────────────────

describe('PosSaleComponent — numeric tendered amount', () => {
  beforeEach(() => { vi.useFakeTimers(); makeBed(); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('submits without throwing when tendered is a number (type="number" emits a number)', async () => {
    const comp = TestBed.createComponent(PosSaleComponent).componentInstance;
    const svc = TestBed.inject(PosService) as any;
    await vi.runAllTimersAsync();

    comp.customers.set([stubCustomer]);
    comp.agents.set([stubAgent]);
    comp.selectedSessionUid.set('SESS1');
    comp.selectedCustomerUid.set('CUST1');
    comp.selectedAgentUid.set('AGENT1');
    comp.currency.set('TZS');
    comp.lines.set([{
      id: 'line-1', productUid: 'P1', productId: '10', productName: 'Widget',
      unitUid: 'U1', unitId: '1', unitName: 'pcs', quantity: '2', unitPrice: '500.00',
      lineDiscountAmount: '0.00', vatRate: '0', priceState: 'ok', lineUnitOptions: [], lineUnitsLoading: false,
    }]);
    // NumberValueAccessor stores a NUMBER — this used to throw `.trim is not a function`.
    comp.tenderedAmount.set(1000 as unknown as string);

    expect(() => comp.submit()).not.toThrow();
    expect(svc.processSale).toHaveBeenCalledOnce();
    expect(svc.processSale.mock.calls[0][0].tenderedAmount).toBe('1000');
  });
});

// ── branch filter ────────────────────────────────────────────────────────────────

describe('PosSaleComponent — branch filter', () => {
  beforeEach(() => { vi.useFakeTimers(); makeBed(); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('narrows the session list to the selected branch', async () => {
    const comp = TestBed.createComponent(PosSaleComponent).componentInstance;
    await vi.runAllTimersAsync();
    comp.openSessions.set([
      { uid: 'S-AAA1', branchId: '2', posTillId: '1', status: 'OPEN' } as any,
      { uid: 'S-BBB2', branchId: '9', posTillId: '2', status: 'OPEN' } as any,
    ]);

    comp.selectedBranchId.set('2');
    expect(comp.sessionOptions().map((o: any) => o.uid)).toEqual(['S-AAA1']);

    comp.selectedBranchId.set(''); // no branch → all sessions
    expect(comp.sessionOptions().length).toBe(2);
  });

  it('onBranchChange switches branch and clears the chosen session', async () => {
    const comp = TestBed.createComponent(PosSaleComponent).componentInstance;
    await vi.runAllTimersAsync();
    comp.selectedSessionUid.set('S-AAA1');
    comp.onBranchChange('9');
    expect(comp.selectedBranchId()).toBe('9');
    expect(comp.selectedSessionUid()).toBe('');
  });
});

// ── price auto-fetch & VAT preview ───────────────────────────────────────────────

describe('PosSaleComponent — price auto-fetch & VAT preview', () => {
  const stubProduct = {
    id: '10', uid: 'P1', companyId: '10', code: 'PRD1', name: 'Widget', description: null,
    type: 'GOODS', sellable: true, stockable: true, baseUnitUid: 'U1', baseUnitCode: 'PCS',
    baseUnitName: 'Pieces', cost: null, vatStatus: 'STANDARD', status: 'ACTIVE',
    version: null, createdAt: null, createdBy: null, updatedAt: null, updatedBy: null,
  } as any;
  const stubUnit = {
    id: '1', uid: 'U1', companyId: '10', code: 'PCS', name: 'Pieces', status: 'ACTIVE' as const,
    version: null, createdAt: null, createdBy: null, updatedAt: null, updatedBy: null,
  };
  const stdRate = {
    id: '1', uid: 'TR1', companyId: '10', vatStatus: 'STANDARD', rate: '18',
    version: null, createdAt: null, createdBy: null, updatedAt: null, updatedBy: null,
  } as any;

  beforeEach(() => { vi.useFakeTimers(); makeBed(); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('auto-fetches the list price (and normalises the VAT rate) on product select', async () => {
    const comp = TestBed.createComponent(PosSaleComponent).componentInstance;
    const prodSvc = TestBed.inject(ProductService) as any;
    prodSvc.listProductUnits.mockReturnValue(of([stubUnit]));
    prodSvc.listPrices.mockReturnValue(of([
      { id: '1', productId: '10', priceListId: '1', priceListUid: 'PL1', priceListCode: 'RETAIL',
        priceListName: 'Retail', companyId: '10', price: { amount: '2500.00', currency: 'TZS' } },
    ]));
    await vi.runAllTimersAsync();

    comp.products.set([stubProduct]);
    comp.taxRates.set([stdRate]); // percentage form (18) must normalise to 0.18
    comp.addLine();
    const lineId = comp.lines()[0].id;
    comp.onLineProductChange(lineId, 'P1');
    await vi.runAllTimersAsync();

    // Unit options populated from listProductUnits
    expect(comp.lines()[0].lineUnitOptions).toHaveLength(1);
    expect(comp.lines()[0].unitUid).toBe('U1');
    expect(comp.lines()[0].unitPrice).toBe('2500.00');
    expect(comp.lines()[0].priceState).toBe('ok');
    expect(comp.lines()[0].vatRate).toBe('0.18');
  });

  it('previews VAT-inclusive gross / VAT / change from the resolved price', async () => {
    const comp = TestBed.createComponent(PosSaleComponent).componentInstance;
    await vi.runAllTimersAsync();
    comp.lines.set([{
      id: 'l1', productUid: 'P1', productId: '10', productName: 'Widget', unitUid: 'U1', unitId: '1',
      unitName: 'pcs', quantity: '1', unitPrice: '1000.00', lineDiscountAmount: '0.00', vatRate: '0.18', priceState: 'ok', lineUnitOptions: [], lineUnitsLoading: false,
    }]);
    comp.tenderedAmount.set('1200');

    expect(comp.subtotal()).toBe(1000);
    expect(comp.grossTotal()).toBeCloseTo(1180);
    expect(comp.vatTotal()).toBeCloseTo(180);
    expect(comp.change()).toBeCloseTo(20); // 1200 tendered − 1180 gross
  });

  it('flags a product with no price and blocks the sale', async () => {
    const comp = TestBed.createComponent(PosSaleComponent).componentInstance;
    const prodSvc = TestBed.inject(ProductService) as any;
    prodSvc.listProductUnits.mockReturnValue(of([stubUnit]));
    prodSvc.listPrices.mockReturnValue(of([])); // no price configured
    const svc = TestBed.inject(PosService) as any;
    await vi.runAllTimersAsync();

    comp.products.set([stubProduct]);
    comp.customers.set([stubCustomer]);
    comp.agents.set([stubAgent]);
    comp.selectedSessionUid.set('SESS1');
    comp.selectedCustomerUid.set('CUST1');
    comp.selectedAgentUid.set('AGENT1');
    comp.currency.set('TZS');
    comp.addLine();
    const lineId = comp.lines()[0].id;
    comp.onLineProductChange(lineId, 'P1');
    await vi.runAllTimersAsync();
    comp.tenderedAmount.set('1000');
    comp.submit();

    expect(comp.lines()[0].priceState).toBe('missing');
    expect(comp.formError()).toMatch(/no price/i);
    expect(svc.processSale).not.toHaveBeenCalled();
  });
});

// ── resetSale ─────────────────────────────────────────────────────────────────

describe('PosSaleComponent — resetSale', () => {
  beforeEach(() => { vi.useFakeTimers(); makeBed(); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('clears form state after reset', async () => {
    const comp = TestBed.createComponent(PosSaleComponent).componentInstance;
    await vi.runAllTimersAsync();

    comp.selectedSessionUid.set('SESS1');
    comp.selectedCustomerUid.set('CUST1');
    comp.lines.set([{ id: 'l1', productUid: 'p', productId: '1', productName: 'P', unitUid: 'u', unitId: '1', unitName: 'pcs', quantity: '1', unitPrice: '100', lineDiscountAmount: '0', lineUnitOptions: [], lineUnitsLoading: false }]);
    comp.resetSale();

    expect(comp.selectedSessionUid()).toBe('');
    expect(comp.selectedCustomerUid()).toBe('');
    expect(comp.lines()).toHaveLength(0);
  });
});

// ── canSell permission ─────────────────────────────────────────────────────────

describe('PosSaleComponent — canSell gate', () => {
  beforeEach(() => { vi.useFakeTimers(); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('canSell is false when hasPermission returns false', async () => {
    makeBed({ canSell: false });
    const comp = TestBed.createComponent(PosSaleComponent).componentInstance;
    await vi.runAllTimersAsync();
    expect(comp.canSell()).toBe(false);
  });
});

// ── product-scoped unit picker ────────────────────────────────────────────────
// Proves: selecting a product calls listProductUnits (not listUnits) and the
// resulting line unit options contain only that product's units.

describe('PosSaleComponent — product-scoped unit picker', () => {
  const stubProduct = {
    id: '10', uid: 'P1', companyId: '10', code: 'PRD1', name: 'Widget', description: null,
    type: 'GOODS', sellable: true, stockable: true, baseUnitUid: 'U1', baseUnitCode: 'PCS',
    baseUnitName: 'Pieces', cost: null, vatStatus: 'STANDARD', status: 'ACTIVE',
    version: null, createdAt: null, createdBy: null, updatedAt: null, updatedBy: null,
  } as any;
  const baseUnit = {
    id: '1', uid: 'U1', companyId: '10', code: 'PCS', name: 'Pieces', status: 'ACTIVE' as const,
    version: null, createdAt: null, createdBy: null, updatedAt: null, updatedBy: null,
  };
  const packUnit = {
    id: '2', uid: 'U2', companyId: '10', code: 'CTN', name: 'Carton', status: 'ACTIVE' as const,
    version: null, createdAt: null, createdBy: null, updatedAt: null, updatedBy: null,
  };

  beforeEach(() => { vi.useFakeTimers(); makeBed(); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('calls listProductUnits on product select and populates line unit options with only that product units', async () => {
    const comp = TestBed.createComponent(PosSaleComponent).componentInstance;
    const prodSvc = TestBed.inject(ProductService) as any;
    // Return base unit + one pack unit for this product only.
    prodSvc.listProductUnits.mockReturnValue(of([baseUnit, packUnit]));
    prodSvc.listPrices.mockReturnValue(of([]));
    await vi.runAllTimersAsync();

    comp.products.set([stubProduct]);
    comp.addLine();
    const lineId = comp.lines()[0].id;

    // Before product selection, unit options are empty.
    expect(comp.lines()[0].lineUnitOptions).toHaveLength(0);

    comp.onLineProductChange(lineId, 'P1');
    await vi.runAllTimersAsync();

    expect(prodSvc.listProductUnits).toHaveBeenCalledWith('P1');
    const line = comp.lines()[0];
    // Only this product's 2 units appear — not the company-wide list.
    expect(line.lineUnitOptions).toHaveLength(2);
    expect(line.lineUnitOptions.map((o: any) => o.uid)).toEqual(['U1', 'U2']);
    // Base unit is auto-selected.
    expect(line.unitUid).toBe('U1');
    expect(line.unitId).toBe('1');
    expect(line.lineUnitsLoading).toBe(false);
  });

  it('does not call listUnits on product select (company-wide load removed)', async () => {
    const comp = TestBed.createComponent(PosSaleComponent).componentInstance;
    const prodSvc = TestBed.inject(ProductService) as any;
    prodSvc.listProductUnits.mockReturnValue(of([baseUnit]));
    prodSvc.listPrices.mockReturnValue(of([]));
    await vi.runAllTimersAsync();

    comp.products.set([stubProduct]);
    comp.addLine();
    comp.onLineProductChange(comp.lines()[0].id, 'P1');
    await vi.runAllTimersAsync();

    // listUnits (company-wide) must never be called from POS line pickers.
    expect(prodSvc.listUnits).toBeUndefined();
  });
});
