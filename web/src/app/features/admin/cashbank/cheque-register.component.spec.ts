import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';

import { ChequeRegisterComponent } from './cheque-register.component';
import { CashbankService } from './cashbank.service';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { SessionStore } from '../../../core/auth/session.store';
import { AlertService } from '../../../core/feedback/alert.service';
import { ChequeDto } from './models/cashbank.model';
import { PageMeta } from '../../../core/api/api-response.model';

function makeSession(canManage = true) {
  return {
    hasPermission: vi.fn(() => canManage),
    isAuthenticated: signal(true),
    user: signal(null),
    permissions: signal([]),
    activeBranchUid: signal(null),
  };
}

function makeCheque(n: number): ChequeDto {
  return {
    id: String(n),
    uid: `chq-uid-${n}`,
    companyId: '1',
    cashBankAccountId: '10',
    chequeNumber: `CHQ-00${n}`,
    payee: 'Acme Supplies',
    amount: '1000.00',
    currency: 'TZS',
    issueDate: '2026-01-01',
    valueDate: '2026-01-01',
    status: 'PENDING',
    apPaymentUid: null,
    cashTransactionUid: null,
    clearedAt: null,
    cancelledAt: null,
  };
}

const META: PageMeta = { page: 0, size: 20, totalElements: 1, totalPages: 1, hasNext: false };

describe('ChequeRegisterComponent', () => {
  let cashbankServiceStub: {
    listCheques: ReturnType<typeof vi.fn>;
    listAllAccounts: ReturnType<typeof vi.fn>;
    clearCheque: ReturnType<typeof vi.fn>;
    cancelCheque: ReturnType<typeof vi.fn>;
    registerCheque: ReturnType<typeof vi.fn>;
  };

  beforeEach(() => {
    vi.useFakeTimers();
    cashbankServiceStub = {
      listCheques: vi.fn(() => of({ rows: [makeCheque(1)], meta: META })),
      listAllAccounts: vi.fn(() => of([])),
      clearCheque: vi.fn(),
      cancelCheque: vi.fn(),
      registerCheque: vi.fn(),
    };

    TestBed.configureTestingModule({
      imports: [ChequeRegisterComponent],
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
        { provide: CashbankService, useValue: cashbankServiceStub },
      ],
    });
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('clearCheque failure calls alerts.error (not alerts.success titled Error)', async () => {
    const alerts = TestBed.inject(AlertService);
    const errorSpy = vi.spyOn(alerts, 'error');
    const successSpy = vi.spyOn(alerts, 'success');
    cashbankServiceStub.clearCheque.mockReturnValue(
      throwError(
        () =>
          new HttpErrorResponse({
            status: 409,
            error: { errors: ['Cheque is not pending.'] },
          }),
      ),
    );

    const fixture = TestBed.createComponent(ChequeRegisterComponent);
    const comp = fixture.componentInstance;
    fixture.detectChanges();
    await vi.runAllTimersAsync();

    comp.clearCheque(makeCheque(1));
    await vi.runAllTimersAsync();

    expect(errorSpy).toHaveBeenCalledWith('Clear failed', 'Cheque is not pending.');
    expect(successSpy).not.toHaveBeenCalledWith('Error', expect.anything());
  });

  it('cancelCheque failure calls alerts.error (not alerts.success titled Error)', async () => {
    const alerts = TestBed.inject(AlertService);
    const errorSpy = vi.spyOn(alerts, 'error');
    const successSpy = vi.spyOn(alerts, 'success');
    cashbankServiceStub.cancelCheque.mockReturnValue(
      throwError(
        () =>
          new HttpErrorResponse({
            status: 409,
            error: { errors: ['Cheque already cleared.'] },
          }),
      ),
    );

    const fixture = TestBed.createComponent(ChequeRegisterComponent);
    const comp = fixture.componentInstance;
    fixture.detectChanges();
    await vi.runAllTimersAsync();

    comp.cancelCheque(makeCheque(1));
    await vi.runAllTimersAsync();

    expect(errorSpy).toHaveBeenCalledWith('Cancel failed', 'Cheque already cleared.');
    expect(successSpy).not.toHaveBeenCalledWith('Error', expect.anything());
  });

  it('clearCheque success still uses alerts.success', async () => {
    const alerts = TestBed.inject(AlertService);
    const successSpy = vi.spyOn(alerts, 'success');
    cashbankServiceStub.clearCheque.mockReturnValue(of(makeCheque(1)));

    const fixture = TestBed.createComponent(ChequeRegisterComponent);
    const comp = fixture.componentInstance;
    fixture.detectChanges();
    await vi.runAllTimersAsync();

    comp.clearCheque(makeCheque(1));
    await vi.runAllTimersAsync();

    expect(successSpy).toHaveBeenCalledWith('Cheque cleared', 'CHQ-001');
  });
});
