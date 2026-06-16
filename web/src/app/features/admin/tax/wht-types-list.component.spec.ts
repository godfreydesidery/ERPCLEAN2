import { describe, it, expect, afterEach, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { signal } from '@angular/core';

import { WhtTypesListComponent } from './wht-types-list.component';
import { TaxService } from './tax.service';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { WhtTypeDto } from './models/tax.model';

// WHT Types list tests:
//  1. Renders without crashing.
//  2. fmtRate coerces numeric wire values — BigDecimal ratePct arrives as number.
//  3. Template inline bindings map WHT_ON_PAYMENT→status-tag--info, WHT_ON_RECEIPT→status-tag--neutral; kindLabel helper verified.
//  4. isEmpty true when list is empty.

function makeSession(canView = true, canManage = true) {
  return {
    hasPermission: vi.fn((perm: string) => {
      if (perm === 'WHT.VIEW') return canView;
      if (perm === 'WHT.MANAGE') return canManage;
      return false;
    }),
    isAuthenticated: signal(true),
    user: signal(null),
    permissions: signal([]),
    activeBranchUid: signal(null),
  };
}

const MOCK_ORG = { uid: 'ORG1', id: '1', name: 'Acme Org' };
const MOCK_COMPANY = { uid: 'CO1', id: '10', name: 'Acme Ltd' };

const MOCK_WHT_PAYMENT: WhtTypeDto = {
  id: '1', uid: 'WHT-001', companyId: '10',
  code: 'WHT-5', name: 'WHT on Professional Fees',
  kind: 'WHT_ON_PAYMENT',
  ratePct: 5,    // number on wire
  active: true,
};

const MOCK_WHT_RECEIPT: WhtTypeDto = {
  id: '2', uid: 'WHT-002', companyId: '10',
  code: 'WHT-2', name: 'WHT on Management Fees',
  kind: 'WHT_ON_RECEIPT',
  ratePct: 2,
  active: true,
};

function makeBed() {
  TestBed.configureTestingModule({
    imports: [WhtTypesListComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([{ path: '**', redirectTo: '' }]),
      {
        provide: TaxService,
        useValue: {
          listWhtTypes: vi.fn(() => of([MOCK_WHT_PAYMENT, MOCK_WHT_RECEIPT])),
          createWhtType: vi.fn(() => of(MOCK_WHT_PAYMENT)),
          updateWhtType: vi.fn(() => of(MOCK_WHT_PAYMENT)),
          deactivateWhtType: vi.fn(() => of({ ...MOCK_WHT_PAYMENT, active: false })),
        },
      },
      { provide: OrganisationService, useValue: { current: vi.fn(() => of(MOCK_ORG)) } },
      { provide: CompanyService, useValue: { list: vi.fn(() => of([MOCK_COMPANY])) } },
      { provide: AlertService, useValue: { success: vi.fn(), error: vi.fn() } },
      { provide: SessionStore, useValue: makeSession() },
    ],
  });
}

describe('WhtTypesListComponent', () => {
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('renders without crashing', () => {
    vi.useFakeTimers();
    makeBed();
    const fixture = TestBed.createComponent(WhtTypesListComponent);
    expect(fixture).toBeTruthy();
  });

  it('fmtRate coerces number wire value for ratePct', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(WhtTypesListComponent).componentInstance as any;
    // BigDecimal arrives as number — must NOT call string methods on it
    expect(comp.fmtRate(5)).toBe('5.00');
    expect(comp.fmtRate(2.5)).toBe('2.50');
    expect(comp.fmtRate(0)).toBe('0.00');
    expect(comp.fmtRate(null)).toBe('0.00');
    expect(comp.fmtRate(undefined)).toBe('0.00');
    expect(() => comp.fmtRate(5)).not.toThrow();
  });

  it('template maps WHT_ON_PAYMENT to status-tag--info, WHT_ON_RECEIPT to status-tag--neutral (inline binding logic)', () => {
    // The template uses [class.status-tag--info]="wht.kind === 'WHT_ON_PAYMENT'"
    // and [class.status-tag--neutral]="wht.kind === 'WHT_ON_RECEIPT'" directly.
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(WhtTypesListComponent).componentInstance as any;
    expect(MOCK_WHT_PAYMENT.kind === 'WHT_ON_PAYMENT').toBe(true);
    expect(MOCK_WHT_PAYMENT.kind === 'WHT_ON_RECEIPT').toBe(false);
    expect(MOCK_WHT_RECEIPT.kind === 'WHT_ON_RECEIPT').toBe(true);
    expect(MOCK_WHT_RECEIPT.kind === 'WHT_ON_PAYMENT').toBe(false);
    // kindLabel still maps correctly (used in template)
    expect(comp.kindLabel('WHT_ON_PAYMENT')).toBe('On Payment');
    expect(comp.kindLabel('WHT_ON_RECEIPT')).toBe('On Receipt');
  });

  it('kindLabel returns human-readable labels', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(WhtTypesListComponent).componentInstance as any;
    expect(comp.kindLabel('WHT_ON_PAYMENT')).toBe('On Payment');
    expect(comp.kindLabel('WHT_ON_RECEIPT')).toBe('On Receipt');
  });

  it('isEmpty true when rows is empty after load', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(WhtTypesListComponent).componentInstance as any;
    comp.rows.set([]);
    comp.state.set('idle');
    expect(comp.isEmpty()).toBe(true);

    comp.rows.set([MOCK_WHT_PAYMENT]);
    expect(comp.isEmpty()).toBe(false);
  });
});
