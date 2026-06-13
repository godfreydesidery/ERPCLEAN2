/**
 * PurchaseReturnListComponent — key behaviour specs.
 * 1. Loads list on company selection.
 * 2. 403 → state = 'forbidden'.
 * 3. Empty result → isEmpty() true.
 */
import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { AlertService } from '../../../../core/feedback/alert.service';
import { SessionStore } from '../../../../core/auth/session.store';
import { CompanyService } from '../../company/company.service';
import { OrganisationService } from '../../organisation/organisation.service';
import { PurchaseReturnService } from './purchase-return.service';
import { PurchaseReturnListComponent } from './purchase-return-list.component';

const STUB_ORG = { uid: 'ORG1', id: '1', name: 'Acme' };
const STUB_COMPANY = { uid: 'CO1', id: '10', name: 'Main Co' };

const STUB_RETURN = {
  uid: 'RET1', id: '1', companyId: '10', branchId: '1',
  returnNumber: 'PR-0001', status: 'DRAFT',
  goodsReceiptId: '5', goodsReceiptUid: 'GR1',
  supplierId: '2', supplierCode: 'SUP001', supplierName: 'Supplier A',
  reason: 'Damaged', netAmount: '1000', vatAmount: '0', grossAmount: '1000',
  currency: 'TZS', debitNoteUid: null, glEntryUid: null,
  confirmedAt: null, createdAt: null, lines: null,
};

const emptyPage = () => ({
  rows: [],
  meta: { page: 0, size: 20, totalElements: 0, totalPages: 0, hasNext: false },
});

const returnPage = () => ({
  rows: [STUB_RETURN],
  meta: { page: 0, size: 20, totalElements: 1, totalPages: 1, hasNext: false },
});

function makeBed(overrides: { listSpy?: ReturnType<typeof vi.fn> } = {}) {
  const listSpy = overrides.listSpy ?? vi.fn(() => of(returnPage()));

  TestBed.configureTestingModule({
    imports: [PurchaseReturnListComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      { provide: PurchaseReturnService, useValue: { list: listSpy } },
      { provide: OrganisationService, useValue: { current: vi.fn(() => of(STUB_ORG)) } },
      { provide: CompanyService, useValue: { list: vi.fn(() => of([STUB_COMPANY])) } },
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

describe('PurchaseReturnListComponent', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('loads purchase returns on company selection', async () => {
    const { listSpy } = makeBed();
    const fixture = TestBed.createComponent(PurchaseReturnListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    expect(listSpy).toHaveBeenCalled();
    expect(comp.rows()).toHaveLength(1);
    expect(comp.state()).toBe('idle');
  });

  it('isEmpty() is true when no rows returned', async () => {
    makeBed({ listSpy: vi.fn(() => of(emptyPage())) });
    const fixture = TestBed.createComponent(PurchaseReturnListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.isEmpty()).toBe(true);
  });

  it('sets state=forbidden on 403 response', async () => {
    const listSpy = vi.fn(() =>
      throwError(() => new HttpErrorResponse({ status: 403, error: { errors: ['Forbidden'] } })),
    );
    makeBed({ listSpy });
    const fixture = TestBed.createComponent(PurchaseReturnListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.state()).toBe('forbidden');
  });
});
