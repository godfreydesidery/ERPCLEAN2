/**
 * StandingOrderCreateComponent — regression specs for numeric-input string-op crash class.
 *
 * type="number" inputs bound via [(ngModel)] store a JS number at runtime.
 * The component's updateLine() now coerces numeric patches so that submit()'s
 * .trim() calls never execute on a raw number.
 */
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { AlertService } from '../../../../core/feedback/alert.service';
import { SessionStore } from '../../../../core/auth/session.store';
import { BranchService } from '../../branch/branch.service';
import { CompanyService } from '../../company/company.service';
import { CustomerService } from '../../parties/customer.service';
import { OrganisationService } from '../../organisation/organisation.service';
import { ProductService } from '../../products/product.service';
import { StandingOrderService } from './standing-order.service';
import { StandingOrderCreateComponent } from './standing-order-create.component';

const stubCompany = { uid: 'CO1', id: '10', name: 'Main Co', status: 'ACTIVE' };
const stubBranch = { uid: 'BR1', id: '2', name: 'HQ', code: 'HQ', companyId: '10', companyUid: 'CO1', isDefault: true, status: 'ACTIVE', timeZone: 'UTC' };
const stubCustomer = { uid: 'CUST1', id: '5', code: 'C001', displayName: 'Acme', status: 'ACTIVE' };
const stubProduct = { uid: 'PROD1', id: '20', code: 'P001', name: 'Widget', status: 'ACTIVE', sellable: true, baseUnitUid: 'U1', baseUnitName: 'pcs' };
const stubUnit = { uid: 'U1', id: '1', name: 'pcs', code: 'pcs' };

function makeSessionStore() {
  return {
    hasPermission: vi.fn(() => true),
    isAuthenticated: signal(true),
    user: signal(null),
    permissions: signal([]),
    activeBranchUid: signal(null),
  };
}

function makeBed() {
  TestBed.configureTestingModule({
    imports: [StandingOrderCreateComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([{ path: 'admin/standing-orders/uid/:uid', component: StandingOrderCreateComponent }]),
      { provide: OrganisationService, useValue: { current: vi.fn(() => of({ uid: 'ORG1', id: '1', name: 'Acme Org' })) } },
      { provide: CompanyService, useValue: { list: vi.fn(() => of([stubCompany])) } },
      { provide: BranchService, useValue: { list: vi.fn(() => of([stubBranch])) } },
      { provide: CustomerService, useValue: { list: vi.fn(() => of({ rows: [stubCustomer] })) } },
      { provide: ProductService, useValue: {
        list: vi.fn(() => of({ rows: [stubProduct] })),
        listProductUnits: vi.fn(() => of([stubUnit])),
      }},
      { provide: StandingOrderService, useValue: { create: vi.fn(() => of({ uid: 'STD1', orderNumber: 'STD-0001' })) } },
      { provide: AlertService, useValue: { success: vi.fn(), error: vi.fn() } },
      { provide: SessionStore, useValue: makeSessionStore() },
    ],
  });
}

// ── updateLine numeric coercion ───────────────────────────────────────────────

describe('StandingOrderCreateComponent — numeric field coercion', () => {
  beforeEach(() => { vi.useFakeTimers(); makeBed(); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('updateLine stores qty as a string when passed a number', async () => {
    const comp = TestBed.createComponent(StandingOrderCreateComponent).componentInstance;
    await vi.runAllTimersAsync();

    // Simulate NumberValueAccessor emitting a JS number from type="number" input.
    comp.updateLine(0, { qty: 5 as unknown as string });

    expect(typeof comp.lines()[0].qty).toBe('string');
    expect(comp.lines()[0].qty).toBe('5');
  });

  it('updateLine stores unitPriceAmount as a string when passed a number', async () => {
    const comp = TestBed.createComponent(StandingOrderCreateComponent).componentInstance;
    await vi.runAllTimersAsync();

    comp.updateLine(0, { unitPriceAmount: 1200.5 as unknown as string });

    expect(typeof comp.lines()[0].unitPriceAmount).toBe('string');
    expect(comp.lines()[0].unitPriceAmount).toBe('1200.5');
  });

  it('submit() does not throw when qty/unitPriceAmount are numbers (simulating type="number")', async () => {
    const comp = TestBed.createComponent(StandingOrderCreateComponent).componentInstance;
    const svc = TestBed.inject(StandingOrderService) as any;
    await vi.runAllTimersAsync();

    // Pre-fill required header fields.
    comp.selectedCompanyUid.set('CO1');
    comp.selectedCompanyId.set('10');
    comp.selectedBranchUid.set('BR1');
    comp.selectedCustomerUid.set('CUST1');
    comp.fCurrency.set('TZS');
    comp.fStartDate.set('2025-01-01');
    comp.fFrequency.set('MONTHLY');

    // Set lines with numeric values in string fields (simulating type="number").
    comp.lines.set([{
      productUid: 'PROD1', unitUid: 'U1',
      qty: 10 as unknown as string,
      qtyBase: '' as string,
      unitPriceAmount: 500 as unknown as string,
      lineUnitOptions: [{ uid: 'U1', label: 'pcs' }],
      lineUnitsLoading: false,
    }]);

    // Preload supporting data the submit() resolver needs.
    comp.products.set([stubProduct as any]);
    comp.units.set([stubUnit as any]);

    expect(() => comp.submit()).not.toThrow();
    expect(svc.create).toHaveBeenCalledOnce();
    const body = svc.create.mock.calls[0][0];
    expect(body.lines[0].qty).toBe('10');
    expect(body.lines[0].unitPriceAmount).toBe('500');
  });
});
