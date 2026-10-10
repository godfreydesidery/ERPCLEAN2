import { describe, it, expect, afterEach, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { signal } from '@angular/core';

import { CustomerDetailComponent } from './customer-detail.component';
import { CustomerService } from './customer.service';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { BranchService } from '../branch/branch.service';
import { ArService } from '../ar/ar.service';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';

// ARC-21: the customer screen shows what the customer owes and how much credit is left.

const CUSTOMER = {
  uid: 'KIBO', companyId: '10', code: 'C001', displayName: 'Kibo Bar', status: 'ACTIVE',
  customerKind: 'CREDIT_ACCOUNT', partyType: 'ORGANISATION', vatRegistered: false,
  creditLimit: { amount: '1000000', currency: 'TZS' }, paymentTermsDays: 30,
};

function setup(perms: string[] = ['CUSTOMER.MANAGE', 'AR.VIEW'], balance = 1150000) {
  const ar = { getBalance: vi.fn(() => of({ customerId: '5', balance, currency: 'TZS', unconverted: [] })) };
  TestBed.configureTestingModule({
    imports: [CustomerDetailComponent],
    providers: [
      provideHttpClient(), provideHttpClientTesting(), provideRouter([]),
      {
        provide: CustomerService,
        useValue: { getByUid: vi.fn(() => of(CUSTOMER)), listBranches: vi.fn(() => of([])) },
      },
      { provide: CompanyService, useValue: { list: vi.fn(() => of([])) } },
      { provide: OrganisationService, useValue: { current: vi.fn(() => of({ uid: 'ORG1' })) } },
      { provide: BranchService, useValue: { list: vi.fn(() => of([])) } },
      { provide: ArService, useValue: ar },
      { provide: AlertService, useValue: { success: vi.fn(), error: vi.fn() } },
      {
        provide: SessionStore,
        useValue: {
          hasPermission: vi.fn((c: string) => perms.includes(c)),
          isAuthenticated: signal(true), user: signal(null), permissions: signal(perms), activeBranchUid: signal(null),
        },
      },
    ],
  });
  const fixture = TestBed.createComponent(CustomerDetailComponent);
  fixture.componentRef.setInput('uid', 'KIBO');
  return { comp: fixture.componentInstance, ar };
}

describe('CustomerDetailComponent — account balance (ARC-21)', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('loads the balance and shows the credit left (negative when over the limit)', async () => {
    const { comp, ar } = setup();
    await Promise.resolve();
    expect(ar.getBalance).toHaveBeenCalledWith('10', 'KIBO');
    expect(+comp.arBalance()!.balance).toBe(1150000);
    expect(comp.availableCredit()).toBe(-150000);
  });

  it('does not ask for the balance without AR.VIEW', async () => {
    const { ar } = setup(['CUSTOMER.MANAGE']);
    await Promise.resolve();
    expect(ar.getBalance).not.toHaveBeenCalled();
  });
});
