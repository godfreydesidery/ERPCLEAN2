/**
 * RoleEditComponent — spec covering:
 *   1. Advisory read-closure gap panel renders a missing-read line when the API returns gaps.
 *   2. An error from the gaps endpoint leaves the screen in idle state (not error).
 *   3. screenLabel() maps known keys to friendly labels and falls back to the raw key.
 *   4. gaps are re-fetched after savePermissions() succeeds.
 */
import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { AlertService } from '../../../core/feedback/alert.service';
import { RoleService } from './role.service';
import { RoleEditComponent } from './role-edit.component';
import type { Role, ScreenReadGap } from '../models/role.model';

vi.useFakeTimers();

// ── Fixtures ──────────────────────────────────────────────────────────────────

function makeRole(overrides: Partial<Role> = {}): Role {
  return {
    id: '1',
    uid: 'role-uid-1',
    code: 'CASHIER',
    name: 'Cashier',
    description: null,
    system: false,
    status: 'ACTIVE',
    permissionCodes: ['AR.RECEIPT.RECORD'],
    ...overrides,
  };
}

const GAP_FIXTURE: ScreenReadGap[] = [
  {
    screen: 'ar.record-receipt',
    accessPermission: 'AR.RECEIPT.RECORD',
    missingReads: ['CUSTOMER.VIEW', 'AR.VIEW'],
  },
];

// ── Test-bed factory ──────────────────────────────────────────────────────────

function makeBed(roleServiceOverrides: {
  get?: ReturnType<typeof vi.fn>;
  listPermissions?: ReturnType<typeof vi.fn>;
  setPermissions?: ReturnType<typeof vi.fn>;
  readClosureGaps?: ReturnType<typeof vi.fn>;
} = {}) {
  const svc = {
    get: vi.fn(() => of(makeRole())),
    listPermissions: vi.fn(() => of([])),
    setPermissions: vi.fn(() => of(makeRole())),
    update: vi.fn(() => of(makeRole())),
    readClosureGaps: vi.fn(() => of(GAP_FIXTURE)),
    ...roleServiceOverrides,
  };

  TestBed.configureTestingModule({
    imports: [RoleEditComponent],
    providers: [
      provideRouter([]),
      { provide: RoleService, useValue: svc },
      { provide: AlertService, useValue: { success: vi.fn(), error: vi.fn() } },
    ],
  });
  // Override template to avoid complex form/router binding setup in unit tests;
  // we only assert on component signal state.
  TestBed.overrideTemplate(RoleEditComponent, '<div></div>');
  return svc;
}

// ── Specs ─────────────────────────────────────────────────────────────────────

describe('RoleEditComponent — advisory gap panel', () => {
  afterEach(() => { vi.clearAllTimers(); TestBed.resetTestingModule(); });

  it('populates gaps signal when the endpoint returns gaps', async () => {
    makeBed();
    const fixture = TestBed.createComponent(RoleEditComponent);
    fixture.componentRef.setInput('uid', 'role-uid-1');
    vi.runAllTimers();
    await fixture.whenStable();

    const comp = fixture.componentInstance;
    expect(comp.state()).toBe('idle');
    expect(comp.gaps().length).toBe(1);
    expect(comp.gaps()[0].screen).toBe('ar.record-receipt');
    expect(comp.gaps()[0].missingReads).toEqual(['CUSTOMER.VIEW', 'AR.VIEW']);
  });

  it('leaves gaps empty and state idle when the gaps endpoint returns a 403', async () => {
    makeBed({
      readClosureGaps: vi.fn(() =>
        throwError(() => new HttpErrorResponse({ status: 403 })),
      ),
    });
    const fixture = TestBed.createComponent(RoleEditComponent);
    fixture.componentRef.setInput('uid', 'role-uid-1');
    vi.runAllTimers();
    await fixture.whenStable();

    const comp = fixture.componentInstance;
    // Screen must NOT enter error state — the gaps call is non-blocking.
    expect(comp.state()).toBe('idle');
    expect(comp.gaps()).toEqual([]);
  });

  it('leaves gaps empty and state idle when the gaps endpoint returns a 500', async () => {
    makeBed({
      readClosureGaps: vi.fn(() =>
        throwError(() => new HttpErrorResponse({ status: 500 })),
      ),
    });
    const fixture = TestBed.createComponent(RoleEditComponent);
    fixture.componentRef.setInput('uid', 'role-uid-1');
    vi.runAllTimers();
    await fixture.whenStable();

    const comp = fixture.componentInstance;
    expect(comp.state()).toBe('idle');
    expect(comp.gaps()).toEqual([]);
  });

  it('re-fetches gaps after savePermissions succeeds', async () => {
    const updatedRole = makeRole({ permissionCodes: ['AR.RECEIPT.RECORD', 'CUSTOMER.VIEW'] });
    const updatedGaps: ScreenReadGap[] = [
      {
        screen: 'ar.record-receipt',
        accessPermission: 'AR.RECEIPT.RECORD',
        missingReads: ['AR.VIEW'],
      },
    ];
    // readClosureGaps returns initial gaps on first call, updated gaps on second.
    const readClosureGaps = vi.fn()
      .mockReturnValueOnce(of(GAP_FIXTURE))
      .mockReturnValueOnce(of(updatedGaps));

    const svc = makeBed({
      setPermissions: vi.fn(() => of(updatedRole)),
      readClosureGaps,
    });

    const fixture = TestBed.createComponent(RoleEditComponent);
    fixture.componentRef.setInput('uid', 'role-uid-1');
    vi.runAllTimers();
    await fixture.whenStable();

    // Initial load: 1 gap with 2 missing reads.
    expect(fixture.componentInstance.gaps()[0].missingReads).toEqual(['CUSTOMER.VIEW', 'AR.VIEW']);

    // Trigger savePermissions.
    fixture.componentInstance.savePermissions();
    vi.runAllTimers();
    await fixture.whenStable();

    expect(svc.setPermissions).toHaveBeenCalledOnce();
    // After save the gaps re-fetch fires; now only 1 missing read remains.
    expect(fixture.componentInstance.gaps()[0].missingReads).toEqual(['AR.VIEW']);
    expect(readClosureGaps).toHaveBeenCalledTimes(2);
  });
});

describe('RoleEditComponent — screenLabel()', () => {
  afterEach(() => { vi.clearAllTimers(); TestBed.resetTestingModule(); });

  beforeEach(() => { makeBed(); });

  it('returns a friendly label for a known screen key', () => {
    const fixture = TestBed.createComponent(RoleEditComponent);
    fixture.componentRef.setInput('uid', 'role-uid-1');
    expect(fixture.componentInstance.screenLabel('ar.record-receipt')).toBe('AR Record Receipt');
    expect(fixture.componentInstance.screenLabel('purchases.goods-receipt')).toBe('Goods Receipt');
    expect(fixture.componentInstance.screenLabel('gl.trial-balance')).toBe('Trial Balance');
  });

  it('falls back to the raw key for an unknown screen key', () => {
    const fixture = TestBed.createComponent(RoleEditComponent);
    fixture.componentRef.setInput('uid', 'role-uid-1');
    expect(fixture.componentInstance.screenLabel('future.unknown-screen')).toBe('future.unknown-screen');
  });
});

describe('RoleEditComponent — load error state (existing behaviour preserved)', () => {
  afterEach(() => { vi.clearAllTimers(); TestBed.resetTestingModule(); });

  it('sets state to error when the role GET fails', async () => {
    makeBed({
      get: vi.fn(() => throwError(() => new HttpErrorResponse({ status: 404 }))),
    });
    const fixture = TestBed.createComponent(RoleEditComponent);
    fixture.componentRef.setInput('uid', 'role-uid-1');
    vi.runAllTimers();
    await fixture.whenStable();

    expect(fixture.componentInstance.state()).toBe('error');
  });

  it('sets state to error when listPermissions fails', async () => {
    makeBed({
      listPermissions: vi.fn(() => throwError(() => new HttpErrorResponse({ status: 403 }))),
    });
    const fixture = TestBed.createComponent(RoleEditComponent);
    fixture.componentRef.setInput('uid', 'role-uid-1');
    vi.runAllTimers();
    await fixture.whenStable();

    expect(fixture.componentInstance.state()).toBe('error');
  });
});
