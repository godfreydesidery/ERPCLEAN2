import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';

import { CashTransfersListComponent } from './cash-transfers-list.component';
import { CashbankService } from './cashbank.service';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { SessionStore } from '../../../core/auth/session.store';
import { CashTransferDto } from './models/cashbank.model';
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

function makeTransfer(n: number): CashTransferDto {
  return {
    id: String(n),
    uid: `uid-${n}`,
    companyId: '1',
    transferNumber: `CBT-00${n}`,
    sourceAccountId: '10',
    sourceAccountUid: 'acc-src',
    destinationAccountId: '20',
    destinationAccountUid: 'acc-dst',
    transferDate: '2025-01-01',
    amount: '500.00',
    currency: 'TZS',
    reference: null,
    outTxnId: '100',
    inTxnId: '101',
    journalEntryRef: 'CBT-001',
  };
}

const META: PageMeta = { page: 0, size: 20, totalElements: 1, totalPages: 1, hasNext: false };

describe('CashTransfersListComponent', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    TestBed.configureTestingModule({
      imports: [CashTransfersListComponent],
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
          provide: CashbankService,
          useValue: {
            listTransfers: vi.fn(() => of({ rows: [makeTransfer(1)], meta: META })),
            listAllAccounts: vi.fn(() => of([])),
            getTransfer: vi.fn(),
          },
        },
      ],
    });
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('loads transfers on init', async () => {
    const fixture = TestBed.createComponent(CashTransfersListComponent);
    const comp = fixture.componentInstance;
    fixture.detectChanges();
    await vi.runAllTimersAsync();
    expect(comp.rows().length).toBe(1);
    expect(comp.state()).toBe('idle');
  });

  it('isEmpty false when rows loaded', async () => {
    const fixture = TestBed.createComponent(CashTransfersListComponent);
    const comp = fixture.componentInstance;
    fixture.detectChanges();
    await vi.runAllTimersAsync();
    expect(comp.isEmpty()).toBe(false);
  });

  it('sets forbidden state on 403', async () => {
    const svc = TestBed.inject(CashbankService) as unknown as { listTransfers: ReturnType<typeof vi.fn> };
    svc.listTransfers.mockReturnValue(
      throwError(() => new HttpErrorResponse({ status: 403, statusText: 'Forbidden' })),
    );
    const fixture = TestBed.createComponent(CashTransfersListComponent);
    const comp = fixture.componentInstance;
    fixture.detectChanges();
    await vi.runAllTimersAsync();
    expect(comp.state()).toBe('forbidden');
  });

  it('canView false when session lacks CASH.VIEW', () => {
    TestBed.overrideProvider(SessionStore, { useValue: makeSession(false) });
    const fixture = TestBed.createComponent(CashTransfersListComponent);
    const comp = fixture.componentInstance;
    fixture.detectChanges();
    expect(comp.canView()).toBe(false);
  });
});
