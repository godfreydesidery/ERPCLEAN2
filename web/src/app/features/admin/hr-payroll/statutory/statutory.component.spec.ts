/**
 * StatutoryComponent — unit spec.
 * Covers: load-once PAYE + rates, forbidden on 403, band management, validation.
 */
import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { describe, it, expect, vi } from 'vitest';
import { AlertService } from '../../../../core/feedback/alert.service';
import { SessionStore } from '../../../../core/auth/session.store';
import { CompanyService } from '../../company/company.service';
import { OrganisationService } from '../../organisation/organisation.service';
import { HrStatutoryService } from './hr-statutory.service';
import { StatutoryComponent } from './statutory.component';
import type { PayeBandSetDto, StatutoryRateSetDto } from '../models/hr-payroll.model';

vi.useFakeTimers();

function makePayeSet(overrides: Partial<PayeBandSetDto> = {}): PayeBandSetDto {
  return {
    id: '1', uid: 'pbs-1', companyId: '10', effectiveFrom: '2024-01-01',
    taxFreeThreshold: '270000', description: null,
    bands: [{ id: '1', uid: 'band-1', bandNo: 1, lowerBound: '0', marginalRate: '0', cumulativeFixedTax: '0' }],
    ...overrides,
  };
}

function makeRateSet(overrides: Partial<StatutoryRateSetDto> = {}): StatutoryRateSetDto {
  return {
    id: '1', uid: 'rs-1', companyId: '10', rateType: 'NSSF',
    effectiveFrom: '2024-01-01', employeeRate: '10', employerRate: '10',
    basis: 'GROSS', ceilingAmount: null, headcountThreshold: null,
    active: true, description: null,
    ...overrides,
  };
}

function makeBed(
  statSvc: Partial<{
    listPayeBandSets: ReturnType<typeof vi.fn>;
    listRateSets: ReturnType<typeof vi.fn>;
    createPayeBandSet: ReturnType<typeof vi.fn>;
    createRateSet: ReturnType<typeof vi.fn>;
  }> = {},
  canManage = true,
) {
  const svc = {
    listPayeBandSets: vi.fn(() => of([makePayeSet()])),
    listRateSets: vi.fn(() => of([makeRateSet()])),
    createPayeBandSet: vi.fn(() => of(makePayeSet())),
    createRateSet: vi.fn(() => of(makeRateSet())),
    getPayeBandSetByUid: vi.fn(() => of(makePayeSet())),
    getRateSetByUid: vi.fn(() => of(makeRateSet())),
    ...statSvc,
  };

  const orgSvc = { current: vi.fn(() => of({ uid: 'org-1' })) };
  const companySvc = {
    list: vi.fn(() => of([{ id: '10', uid: 'co-uid', name: 'Test Co', organisationUid: 'org-1' }])),
  };
  const session = {
    hasPermission: vi.fn(() => canManage),
    isAuthenticated: vi.fn(() => true),
  };

  TestBed.configureTestingModule({
    imports: [StatutoryComponent],
    providers: [
      provideRouter([]),
      { provide: HrStatutoryService, useValue: svc },
      { provide: OrganisationService, useValue: orgSvc },
      { provide: CompanyService, useValue: companySvc },
      { provide: AlertService, useValue: { success: vi.fn(), error: vi.fn() } },
      { provide: SessionStore, useValue: session },
    ],
  });

  const fixture = TestBed.createComponent(StatutoryComponent);
  const comp = fixture.componentInstance;
  return { fixture, comp, svc };
}

describe('StatutoryComponent', () => {
  it('loads PAYE band sets and rate sets on init', () => {
    const { comp, svc } = makeBed();
    expect(svc.listPayeBandSets).toHaveBeenCalledOnce();
    expect(svc.listRateSets).toHaveBeenCalledOnce();
    expect(comp.payeBandSets().length).toBe(1);
    expect(comp.rateSets().length).toBe(1);
  });

  it('sets payeState=forbidden on 403', () => {
    const err = new HttpErrorResponse({ status: 403, statusText: 'Forbidden' });
    const { comp } = makeBed({ listPayeBandSets: vi.fn(() => throwError(() => err)) });
    expect(comp.payeState()).toBe('forbidden');
  });

  it('sets rateState=error on 500', () => {
    const err = new HttpErrorResponse({ status: 500, statusText: 'Server Error' });
    const { comp } = makeBed({ listRateSets: vi.fn(() => throwError(() => err)) });
    expect(comp.rateState()).toBe('error');
  });

  it('adds and removes bands', () => {
    const { comp } = makeBed();
    const initialCount = comp.pBands().length;
    comp.addBand();
    expect(comp.pBands().length).toBe(initialCount + 1);
    comp.removeBand(0);
    expect(comp.pBands().length).toBe(initialCount);
  });

  it('validates effectiveFrom before createPayeBandSet', () => {
    const { comp, svc } = makeBed();
    comp.pEffectiveFrom.set('');
    comp.pTaxFreeThreshold.set('270000');
    comp.createPayeBandSet();
    expect(svc.createPayeBandSet).not.toHaveBeenCalled();
    expect(comp.payeFormError()).toBeTruthy();
  });

  it('validates taxFreeThreshold before createPayeBandSet', () => {
    const { comp, svc } = makeBed();
    comp.pEffectiveFrom.set('2024-01-01');
    comp.pTaxFreeThreshold.set('');
    comp.createPayeBandSet();
    expect(svc.createPayeBandSet).not.toHaveBeenCalled();
  });

  it('calls createPayeBandSet and reloads on success', () => {
    const { comp, svc } = makeBed();
    comp.pEffectiveFrom.set('2024-01-01');
    comp.pTaxFreeThreshold.set('270000');
    comp.createPayeBandSet();
    expect(svc.createPayeBandSet).toHaveBeenCalledOnce();
    // list refreshed
    expect(svc.listPayeBandSets).toHaveBeenCalledTimes(2);
  });

  it('validates effectiveFrom before createRateSet', () => {
    const { comp, svc } = makeBed();
    comp.rEffectiveFrom.set('');
    comp.rBasis.set('GROSS');
    comp.createRateSet();
    expect(svc.createRateSet).not.toHaveBeenCalled();
  });

  it('calls createRateSet and reloads on success', () => {
    const { comp, svc } = makeBed();
    comp.rRateType.set('NSSF');
    comp.rEffectiveFrom.set('2024-01-01');
    comp.rBasis.set('GROSS');
    comp.createRateSet();
    expect(svc.createRateSet).toHaveBeenCalledOnce();
    expect(svc.listRateSets).toHaveBeenCalledTimes(2);
  });

  it('canManage is false when permission absent', () => {
    const { comp } = makeBed({}, false);
    expect(comp.canManage()).toBe(false);
  });
});
