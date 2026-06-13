import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';

import { ArReceiptsListComponent } from './ar-receipts-list.component';
import { ArService } from './ar.service';
import { CustomerService } from '../parties/customer.service';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { SessionStore } from '../../../core/auth/session.store';
import { ArReceiptDto } from './models/ar.model';
import { PageMeta } from '../../../core/api/api-response.model';

function makeSession(canView = true) {
  return {
    hasPermission: vi.fn(() => canView),
    isAuthenticated: signal(true),
    user: signal(null),
    permissions: signal([]),
    activeBranchUid: signal(null),
  };
}

function makeReceipt(n: number): ArReceiptDto {
  return {
    uid: `uid-${n}`,
    customerId: '1',
    receiptNumber: `RCP-00${n}`,
    receiptDate: '2025-01-01',
    amount: '1000.00',
    unallocatedAmount: '0.00',
    currency: 'TZS',
    tenderType: 'BANK_TRANSFER',
    allocations: [],
  };
}

const META: PageMeta = { page: 0, size: 20, totalElements: 2, totalPages: 1, hasNext: false };

describe('ArReceiptsListComponent', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    TestBed.configureTestingModule({
      imports: [ArReceiptsListComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: SessionStore, useValue: makeSession() },
        {
          provide: OrganisationService,
          useValue: { current: () => of({ uid: 'org-1', name: 'Test Org' }) },
        },
        {
          provide: CompanyService,
          useValue: { list: () => of([{ id: '1', uid: 'co-1', name: 'Acme' }]) },
        },
        {
          provide: ArService,
          useValue: {
            listReceipts: vi.fn(() => of({ rows: [makeReceipt(1), makeReceipt(2)], meta: META })),
            getReceipt: vi.fn(),
          },
        },
        {
          provide: CustomerService,
          useValue: { list: vi.fn(() => of({ rows: [], meta: META })) },
        },
      ],
    });
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('loads receipts on init and populates rows', async () => {
    const fixture = TestBed.createComponent(ArReceiptsListComponent);
    const comp = fixture.componentInstance;
    fixture.detectChanges();
    await vi.runAllTimersAsync();
    fixture.detectChanges();
    expect(comp.rows().length).toBe(2);
    expect(comp.state()).toBe('idle');
  });

  it('isEmpty is false when rows exist', async () => {
    const fixture = TestBed.createComponent(ArReceiptsListComponent);
    const comp = fixture.componentInstance;
    fixture.detectChanges();
    await vi.runAllTimersAsync();
    expect(comp.isEmpty()).toBe(false);
  });

  it('sets state to forbidden on 403', async () => {
    const arService = TestBed.inject(ArService) as unknown as { listReceipts: ReturnType<typeof vi.fn> };
    arService.listReceipts.mockReturnValue(
      throwError(() => new HttpErrorResponse({ status: 403, statusText: 'Forbidden' })),
    );
    const fixture = TestBed.createComponent(ArReceiptsListComponent);
    const comp = fixture.componentInstance;
    fixture.detectChanges();
    await vi.runAllTimersAsync();
    expect(comp.state()).toBe('forbidden');
  });

  it('canView is false when session lacks AR.VIEW', async () => {
    TestBed.overrideProvider(SessionStore, { useValue: makeSession(false) });
    const fixture = TestBed.createComponent(ArReceiptsListComponent);
    const comp = fixture.componentInstance;
    fixture.detectChanges();
    expect(comp.canView()).toBe(false);
  });

  it('goToPage delegates to load with correct page', async () => {
    const fixture = TestBed.createComponent(ArReceiptsListComponent);
    const comp = fixture.componentInstance;
    fixture.detectChanges();
    await vi.runAllTimersAsync();
    const loadSpy = vi.spyOn(comp, 'load');
    comp.goToPage(2);
    expect(loadSpy).toHaveBeenCalledWith(2);
  });
});
