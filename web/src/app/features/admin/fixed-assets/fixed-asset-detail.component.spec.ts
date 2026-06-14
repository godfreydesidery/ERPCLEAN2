/**
 * FixedAssetDetailComponent — deep spec.
 *
 * Covers load/error triad, form validation (DRAFT save), and the
 * place-in-service lifecycle action (including the date guard).
 */
import { HttpErrorResponse } from '@angular/common/http';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { describe, it, expect, vi, afterEach } from 'vitest';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { BranchService } from '../branch/branch.service';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { FixedAssetsService } from './fixed-assets.service';
import { FixedAssetDetailComponent } from './fixed-asset-detail.component';
import type { FixedAssetDto } from './models/fixed-assets.model';

vi.useFakeTimers();

// ── Fixtures ──────────────────────────────────────────────────────────────────

function makeAsset(overrides: Partial<FixedAssetDto> = {}): FixedAssetDto {
  return {
    id: '1', uid: 'fa-1', companyId: '10', branchId: 'br-1',
    assetNumber: 'FA-0001', categoryId: 'cat-1', name: 'Office Laptop',
    status: 'DRAFT', acquisitionCost: '1200000', salvageValue: '100000',
    depreciationMethod: 'STRAIGHT_LINE', lifePeriods: 36, reducingRate: '0',
    acquisitionDate: '2024-01-01', depreciationStartDate: '2024-02-01',
    carryingCost: '1200000', accumulatedDepreciation: '0',
    nbv: '1200000', revaluationReserveBalance: '0',
    supplierId: '', sourceBillUid: '', location: 'HQ',
    costCentreId: '', assetTag: 'LAP-001',
    capitalisedGlEntryUid: '', disposedAt: '',
    ...overrides,
  };
}

function makeBed(
  faService: Partial<{
    getByUid: ReturnType<typeof vi.fn>;
    update: ReturnType<typeof vi.fn>;
    placeInService: ReturnType<typeof vi.fn>;
    dispose: ReturnType<typeof vi.fn>;
    listSchedule: ReturnType<typeof vi.fn>;
    listRevaluations: ReturnType<typeof vi.fn>;
  }> = {},
  canManage = true,
  canDispose = true,
) {
  const svc = {
    getByUid: vi.fn(() => of(makeAsset())),
    update: vi.fn(() => of(makeAsset())),
    placeInService: vi.fn(() => of(makeAsset({ status: 'IN_SERVICE' }))),
    dispose: vi.fn(() => of({ uid: 'd1', disposalType: 'SALE' } as any)),
    listSchedule: vi.fn(() => of([])),
    listRevaluations: vi.fn(() => of([])),
    listCategories: vi.fn(() => of([])),
    ...faService,
  };

  TestBed.configureTestingModule({
    imports: [FixedAssetDetailComponent],
    providers: [
      provideRouter([]),
      { provide: FixedAssetsService, useValue: svc },
      { provide: AlertService, useValue: { success: vi.fn(), error: vi.fn() } },
      { provide: OrganisationService, useValue: { current: vi.fn(() => of({ uid: 'org-1' })) } },
      { provide: CompanyService, useValue: { list: vi.fn(() => of([])) } },
      { provide: BranchService, useValue: { list: vi.fn(() => of([])) } },
      {
        provide: SessionStore,
        useValue: {
          hasPermission: vi.fn((p: string) => {
            if (p === 'FA.REGISTER.MANAGE') return canManage;
            if (p === 'FA.DISPOSE') return canDispose;
            return false;
          }),
          isAuthenticated: signal(true),
          user: signal(null),
          permissions: signal([]),
          activeBranchUid: signal(null),
        },
      },
    ],
  });
  return svc;
}

// ── Load triad ────────────────────────────────────────────────────────────────

describe('FixedAssetDetailComponent — load triad', () => {
  afterEach(() => { vi.clearAllTimers(); TestBed.resetTestingModule(); });

  it('loads the asset and patches form on success (DRAFT status)', async () => {
    makeBed();
    const fixture = TestBed.createComponent(FixedAssetDetailComponent);
    fixture.componentRef.setInput('uid', 'fa-1');
    vi.runAllTimers();
    await fixture.whenStable();

    const comp = fixture.componentInstance;
    expect(comp.state()).toBe('idle');
    expect(comp.asset()?.uid).toBe('fa-1');
    expect(comp.fName()).toBe('Office Laptop');
    expect(comp.fLocation()).toBe('HQ');
    expect(comp.isDraft()).toBe(true);
  });

  it('sets state = "error" on 500', async () => {
    makeBed({ getByUid: vi.fn(() => throwError(() => new HttpErrorResponse({ status: 500 }))) });
    const fixture = TestBed.createComponent(FixedAssetDetailComponent);
    fixture.componentRef.setInput('uid', 'fa-1');
    vi.runAllTimers();
    await fixture.whenStable();

    expect(fixture.componentInstance.state()).toBe('error');
  });

  it('sets state = "error" on 403', async () => {
    makeBed({ getByUid: vi.fn(() => throwError(() => new HttpErrorResponse({ status: 403 }))) });
    const fixture = TestBed.createComponent(FixedAssetDetailComponent);
    fixture.componentRef.setInput('uid', 'fa-1');
    vi.runAllTimers();
    await fixture.whenStable();

    expect(fixture.componentInstance.state()).toBe('error');
  });

  it('loads schedule and revaluations when asset is IN_SERVICE', async () => {
    const svc = makeBed({
      getByUid: vi.fn(() => of(makeAsset({ status: 'IN_SERVICE' }))),
    });
    const fixture = TestBed.createComponent(FixedAssetDetailComponent);
    fixture.componentRef.setInput('uid', 'fa-1');
    vi.runAllTimers();
    await fixture.whenStable();

    expect(svc.listSchedule).toHaveBeenCalledOnce();
    expect(svc.listRevaluations).toHaveBeenCalledOnce();
  });

  it('does NOT load schedule/revaluations for a DRAFT asset', async () => {
    const svc = makeBed();
    const fixture = TestBed.createComponent(FixedAssetDetailComponent);
    fixture.componentRef.setInput('uid', 'fa-1');
    vi.runAllTimers();
    await fixture.whenStable();

    expect(svc.listSchedule).not.toHaveBeenCalled();
    expect(svc.listRevaluations).not.toHaveBeenCalled();
  });
});

// ── Form validation (DRAFT save) ──────────────────────────────────────────────

describe('FixedAssetDetailComponent — save (DRAFT)', () => {
  afterEach(() => { vi.clearAllTimers(); TestBed.resetTestingModule(); });

  it('sets saveError when name is blank', async () => {
    makeBed();
    const fixture = TestBed.createComponent(FixedAssetDetailComponent);
    fixture.componentRef.setInput('uid', 'fa-1');
    vi.runAllTimers();
    await fixture.whenStable();

    const comp = fixture.componentInstance;
    comp.fName.set('');
    comp.save();

    expect(comp.saveError()).toBe('Name is required.');
  });

  it('calls faService.update on valid save', async () => {
    const svc = makeBed();
    const fixture = TestBed.createComponent(FixedAssetDetailComponent);
    fixture.componentRef.setInput('uid', 'fa-1');
    vi.runAllTimers();
    await fixture.whenStable();

    fixture.componentInstance.save();
    vi.runAllTimers();
    await fixture.whenStable();

    expect(svc.update).toHaveBeenCalledOnce();
    expect(fixture.componentInstance.saving()).toBe(false);
    expect(fixture.componentInstance.saveError()).toBeNull();
  });
});

// ── Place-in-service lifecycle action ─────────────────────────────────────────

describe('FixedAssetDetailComponent — placeInService action', () => {
  afterEach(() => { vi.clearAllTimers(); TestBed.resetTestingModule(); });

  it('sets placeInServiceError when posting date is blank', async () => {
    makeBed();
    const fixture = TestBed.createComponent(FixedAssetDetailComponent);
    fixture.componentRef.setInput('uid', 'fa-1');
    vi.runAllTimers();
    await fixture.whenStable();

    const comp = fixture.componentInstance;
    comp.fPostingDate.set('');
    comp.placeInService();

    expect(comp.placeInServiceError()).toBe('Posting date is required.');
  });

  it('calls faService.placeInService and transitions status to IN_SERVICE', async () => {
    const svc = makeBed();
    const fixture = TestBed.createComponent(FixedAssetDetailComponent);
    fixture.componentRef.setInput('uid', 'fa-1');
    vi.runAllTimers();
    await fixture.whenStable();

    const comp = fixture.componentInstance;
    comp.fPostingDate.set('2024-03-01');
    comp.placeInService();
    vi.runAllTimers();
    await fixture.whenStable();

    expect(svc.placeInService).toHaveBeenCalledOnce();
    expect(comp.asset()?.status).toBe('IN_SERVICE');
    expect(comp.placingInService()).toBe(false);
    expect(comp.showPlaceInServiceForm()).toBe(false);
  });

  it('sets placeInServiceError on API failure', async () => {
    makeBed({
      placeInService: vi.fn(() => throwError(() => new HttpErrorResponse({ status: 422 }))),
    });
    const fixture = TestBed.createComponent(FixedAssetDetailComponent);
    fixture.componentRef.setInput('uid', 'fa-1');
    vi.runAllTimers();
    await fixture.whenStable();

    const comp = fixture.componentInstance;
    comp.fPostingDate.set('2024-03-01');
    comp.placeInService();
    vi.runAllTimers();
    await fixture.whenStable();

    expect(comp.placeInServiceError()).toBeTruthy();
    expect(comp.placingInService()).toBe(false);
  });
});

// ── Status helpers ────────────────────────────────────────────────────────────

describe('FixedAssetDetailComponent — status helpers', () => {
  afterEach(() => { vi.clearAllTimers(); TestBed.resetTestingModule(); });

  it('isTerminal is true for DISPOSED status', async () => {
    makeBed({ getByUid: vi.fn(() => of(makeAsset({ status: 'DISPOSED' }))) });
    const fixture = TestBed.createComponent(FixedAssetDetailComponent);
    fixture.componentRef.setInput('uid', 'fa-1');
    vi.runAllTimers();
    await fixture.whenStable();

    expect(fixture.componentInstance.isTerminal()).toBe(true);
  });

  it('isTerminal is true for WRITTEN_OFF status', async () => {
    makeBed({ getByUid: vi.fn(() => of(makeAsset({ status: 'WRITTEN_OFF' }))) });
    const fixture = TestBed.createComponent(FixedAssetDetailComponent);
    fixture.componentRef.setInput('uid', 'fa-1');
    vi.runAllTimers();
    await fixture.whenStable();

    expect(fixture.componentInstance.isTerminal()).toBe(true);
  });

  it('isInService is true when status=IN_SERVICE', async () => {
    makeBed({
      getByUid: vi.fn(() => of(makeAsset({ status: 'IN_SERVICE' }))),
    });
    const fixture = TestBed.createComponent(FixedAssetDetailComponent);
    fixture.componentRef.setInput('uid', 'fa-1');
    vi.runAllTimers();
    await fixture.whenStable();

    expect(fixture.componentInstance.isInService()).toBe(true);
  });
});
