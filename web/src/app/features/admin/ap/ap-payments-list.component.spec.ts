import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';

import { ApPaymentsListComponent } from './ap-payments-list.component';
import { ApService } from './ap.service';
import { SupplierService } from '../parties/supplier.service';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { ApPaymentDto } from './models/ap.model';
import { PageMeta } from '../../../core/api/api-response.model';

function makeSession(canView = true, canPay = true) {
  return {
    hasPermission: vi.fn((code: string) => {
      if (code === 'AP.VIEW') return canView;
      if (code === 'AP.PAYMENT.RUN') return canPay;
      return true;
    }),
    isAuthenticated: signal(true),
    user: signal(null),
    permissions: signal([]),
    activeBranchUid: signal(null),
  };
}

function makePayment(n: number): ApPaymentDto {
  return {
    id: String(n),
    uid: `uid-${n}`,
    companyId: '1',
    branchId: '1',
    supplierId: '1',
    paymentNumber: `PAY-00${n}`,
    kind: 'SINGLE',
    paymentDate: '2025-01-01',
    amount: '5000.00',
    currency: 'TZS',
    tenderType: 'BANK_TRANSFER',
    bankReference: null,
    glEntryUid: null,
    cashBankAccountId: '77',
    cashBankAccountUid: 'CBA-UID-077',
    cashBankAccountName: 'Main NMB Current',
    cashBankAccountNumber: '011010012345',
    allocations: [],
  };
}

const META: PageMeta = { page: 0, size: 20, totalElements: 1, totalPages: 1, hasNext: false };

describe('ApPaymentsListComponent', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    TestBed.configureTestingModule({
      imports: [ApPaymentsListComponent],
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
          provide: ApService,
          useValue: {
            listPayments: vi.fn(() => of({ rows: [makePayment(1)], meta: META })),
            paySingle: vi.fn(),
            listBills: vi.fn(() => of({ rows: [], meta: META })),
          },
        },
        {
          provide: SupplierService,
          useValue: { list: vi.fn(() => of({ rows: [], meta: META })) },
        },
        {
          provide: AlertService,
          useValue: { success: vi.fn(), error: vi.fn() },
        },
      ],
    });
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('loads payments on init', async () => {
    const fixture = TestBed.createComponent(ApPaymentsListComponent);
    const comp = fixture.componentInstance;
    fixture.detectChanges();
    await vi.runAllTimersAsync();
    expect(comp.rows().length).toBe(1);
    expect(comp.state()).toBe('idle');
  });

  it('sets forbidden state on 403', async () => {
    const apService = TestBed.inject(ApService) as unknown as { listPayments: ReturnType<typeof vi.fn> };
    apService.listPayments.mockReturnValue(
      throwError(() => new HttpErrorResponse({ status: 403, statusText: 'Forbidden' })),
    );
    const fixture = TestBed.createComponent(ApPaymentsListComponent);
    const comp = fixture.componentInstance;
    fixture.detectChanges();
    await vi.runAllTimersAsync();
    expect(comp.state()).toBe('forbidden');
  });

  it('canPay reflects AP.PAYMENT.RUN permission', () => {
    TestBed.overrideProvider(SessionStore, { useValue: makeSession(true, false) });
    const fixture = TestBed.createComponent(ApPaymentsListComponent);
    const comp = fixture.componentInstance;
    fixture.detectChanges();
    expect(comp.canPay()).toBe(false);
  });

  it('payError set when bill not selected on submit', async () => {
    const fixture = TestBed.createComponent(ApPaymentsListComponent);
    const comp = fixture.componentInstance;
    fixture.detectChanges();
    await vi.runAllTimersAsync();
    comp.showPayForm.set(true);
    comp.submitPaySingle();
    expect(comp.payError()).toBeTruthy();
  });
});
