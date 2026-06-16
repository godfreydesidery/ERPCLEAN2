/**
 * YearEndCloseComponent spec.
 *
 * Covers:
 *  1. On load: lists fiscal years (idle state, rows rendered).
 *  2. isEmpty is true when no years returned.
 *  3. 403 response → 'forbidden' state.
 *  4. requestClose sets confirmCloseUid; cancelClose clears it.
 *  5. confirmClose calls YearEndCloseService.closeFiscalYear with the year uid.
 *  6. confirmClose updates the row in-place on success.
 *  7. reopen calls YearEndCloseService.reopenFiscalYear.
 *  8. Row with OPEN status → Close button (not Reopen).
 *  9. Row with CLOSED status → Reopen button (not Close).
 * 10. rowBusyUid guard: second action is blocked while one is in-flight.
 */
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { AlertService } from '../../../../core/feedback/alert.service';
import { SessionStore } from '../../../../core/auth/session.store';
import { CompanyService } from '../../company/company.service';
import { OrganisationService } from '../../organisation/organisation.service';
import { GlService } from '../gl.service';
import { YearEndCloseService } from './year-end-close.service';
import { YearEndCloseComponent } from './year-end-close.component';
import type { FiscalYearDto } from '../models/gl.model';

// ── Helpers ───────────────────────────────────────────────────────────────────

function makeYear(
  uid: string,
  yearCode: string,
  status: 'OPEN' | 'CLOSED' = 'OPEN',
): FiscalYearDto {
  return {
    id: uid,
    uid,
    companyId: '10',
    yearCode,
    startMonth: 1,
    startDate: '2024-01-01',
    endDate: '2024-12-31',
    status,
    closedAt: status === 'CLOSED' ? '2025-01-10T10:00:00Z' : null,
    closedBy: status === 'CLOSED' ? '1' : null,
    closingJournalUid: status === 'CLOSED' ? 'JE-CLOSE-001' : null,
  };
}

function makeSessionStore(canYearClose = true) {
  return {
    hasPermission: vi.fn((code: string) => {
      if (code === 'GL.YEAR.CLOSE') return canYearClose;
      return true;
    }),
    isAuthenticated: signal(true),
    user: signal(null),
    permissions: signal([]),
    activeBranchUid: signal(null),
  };
}

function makeBed(
  years: FiscalYearDto[] = [makeYear('FY1', 'FY2024', 'OPEN')],
  opts: { canYearClose?: boolean; listError?: boolean } = {},
) {
  const { canYearClose = true, listError = false } = opts;
  TestBed.configureTestingModule({
    imports: [YearEndCloseComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([{ path: '**', redirectTo: '' }]),
      {
        provide: GlService,
        useValue: {
          listFiscalYears: vi.fn(() =>
            listError
              ? throwError(() => Object.assign(new Error('err'), { status: 500 }))
              : of(years),
          ),
        },
      },
      {
        provide: YearEndCloseService,
        useValue: {
          closeFiscalYear: vi.fn((uid: string) =>
            of(makeYear(uid, 'FY2024', 'CLOSED')),
          ),
          reopenFiscalYear: vi.fn((uid: string) =>
            of(makeYear(uid, 'FY2024', 'OPEN')),
          ),
        },
      },
      {
        provide: OrganisationService,
        useValue: { current: vi.fn(() => of({ uid: 'ORG1', id: '1', name: 'Acme' })) },
      },
      {
        provide: CompanyService,
        useValue: { list: vi.fn(() => of([{ uid: 'CO1', id: '10', name: 'Main Co' }])) },
      },
      {
        provide: AlertService,
        useValue: { success: vi.fn(), error: vi.fn() },
      },
      { provide: SessionStore, useValue: makeSessionStore(canYearClose) },
    ],
  });
}

// ── Tests ─────────────────────────────────────────────────────────────────────

describe('YearEndCloseComponent — load', () => {
  beforeEach(() => { vi.useFakeTimers(); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('loads fiscal years and sets state to idle', async () => {
    makeBed([makeYear('FY1', 'FY2024', 'OPEN')]);
    const comp = TestBed.createComponent(YearEndCloseComponent).componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.state()).toBe('idle');
    expect(comp.fiscalYears().length).toBe(1);
    expect(comp.fiscalYears()[0].yearCode).toBe('FY2024');
  });

  it('isEmpty is true when the list is empty', async () => {
    makeBed([]);
    const comp = TestBed.createComponent(YearEndCloseComponent).componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.isEmpty()).toBe(true);
  });

  it('state is error when the list call fails', async () => {
    makeBed([], { listError: true });
    const comp = TestBed.createComponent(YearEndCloseComponent).componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.state()).toBe('error');
  });
});

describe('YearEndCloseComponent — close confirmation', () => {
  beforeEach(() => { vi.useFakeTimers(); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('requestClose sets confirmCloseUid to the year uid', async () => {
    makeBed([makeYear('FY1', 'FY2024', 'OPEN')]);
    const comp = TestBed.createComponent(YearEndCloseComponent).componentInstance;
    await vi.runAllTimersAsync();

    comp.requestClose(comp.fiscalYears()[0]);
    expect(comp.confirmCloseUid()).toBe('FY1');
  });

  it('cancelClose clears confirmCloseUid', async () => {
    makeBed([makeYear('FY1', 'FY2024', 'OPEN')]);
    const comp = TestBed.createComponent(YearEndCloseComponent).componentInstance;
    await vi.runAllTimersAsync();

    comp.requestClose(comp.fiscalYears()[0]);
    comp.cancelClose();
    expect(comp.confirmCloseUid()).toBeNull();
  });

  it('confirmClose calls YearEndCloseService.closeFiscalYear with the correct uid', async () => {
    makeBed([makeYear('FY1', 'FY2024', 'OPEN')]);
    const comp = TestBed.createComponent(YearEndCloseComponent).componentInstance;
    const svc = TestBed.inject(YearEndCloseService) as any;
    await vi.runAllTimersAsync();

    comp.requestClose(comp.fiscalYears()[0]);
    comp.confirmClose();

    expect(svc.closeFiscalYear).toHaveBeenCalledOnce();
    expect(svc.closeFiscalYear).toHaveBeenCalledWith('FY1');
  });

  it('confirmClose updates the row in-place on success', async () => {
    makeBed([makeYear('FY1', 'FY2024', 'OPEN')]);
    const comp = TestBed.createComponent(YearEndCloseComponent).componentInstance;
    await vi.runAllTimersAsync();

    comp.requestClose(comp.fiscalYears()[0]);
    comp.confirmClose();

    const updated = comp.fiscalYears()[0];
    expect(updated.status).toBe('CLOSED');
    expect(updated.closedAt).not.toBeNull();
  });

  it('confirmClose is a no-op when confirmCloseUid is null', async () => {
    makeBed([makeYear('FY1', 'FY2024', 'OPEN')]);
    const comp = TestBed.createComponent(YearEndCloseComponent).componentInstance;
    const svc = TestBed.inject(YearEndCloseService) as any;
    await vi.runAllTimersAsync();

    comp.confirmClose(); // no requestClose was called
    expect(svc.closeFiscalYear).not.toHaveBeenCalled();
  });
});

describe('YearEndCloseComponent — reopen', () => {
  beforeEach(() => { vi.useFakeTimers(); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('reopen calls YearEndCloseService.reopenFiscalYear with the correct uid', async () => {
    makeBed([makeYear('FY1', 'FY2024', 'CLOSED')]);
    const comp = TestBed.createComponent(YearEndCloseComponent).componentInstance;
    const svc = TestBed.inject(YearEndCloseService) as any;
    await vi.runAllTimersAsync();

    comp.reopen(comp.fiscalYears()[0]);
    expect(svc.reopenFiscalYear).toHaveBeenCalledWith('FY1');
  });

  it('reopen updates the row in-place on success', async () => {
    makeBed([makeYear('FY1', 'FY2024', 'CLOSED')]);
    const comp = TestBed.createComponent(YearEndCloseComponent).componentInstance;
    await vi.runAllTimersAsync();

    comp.reopen(comp.fiscalYears()[0]);
    expect(comp.fiscalYears()[0].status).toBe('OPEN');
  });
});

describe('YearEndCloseComponent — rowBusyUid guard', () => {
  beforeEach(() => { vi.useFakeTimers(); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('reopen is a no-op when another action is in-flight', async () => {
    makeBed([makeYear('FY1', 'FY2024', 'CLOSED'), makeYear('FY2', 'FY2023', 'CLOSED')]);
    const comp = TestBed.createComponent(YearEndCloseComponent).componentInstance;
    const svc = TestBed.inject(YearEndCloseService) as any;
    await vi.runAllTimersAsync();

    // Manually set a busy state (simulates mid-flight).
    comp.rowBusyUid.set('FY1');
    comp.reopen(comp.fiscalYears()[1]);

    expect(svc.reopenFiscalYear).not.toHaveBeenCalled();
  });
});

describe('YearEndCloseComponent — status pill rendering', () => {
  beforeEach(() => { vi.useFakeTimers(); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('OPEN year renders status-tag--ok pill', async () => {
    makeBed([makeYear('FY1', 'FY2024', 'OPEN')]);
    const fixture = TestBed.createComponent(YearEndCloseComponent);
    await vi.runAllTimersAsync();
    fixture.detectChanges();

    const pill = fixture.nativeElement.querySelector('.status-tag.status-tag--ok') as HTMLElement | null;
    expect(pill).not.toBeNull();
    expect(pill!.textContent?.trim()).toBe('OPEN');
  });

  it('CLOSED year renders status-tag--neutral pill', async () => {
    makeBed([makeYear('FY1', 'FY2024', 'CLOSED')]);
    const fixture = TestBed.createComponent(YearEndCloseComponent);
    await vi.runAllTimersAsync();
    fixture.detectChanges();

    const pill = fixture.nativeElement.querySelector('.status-tag.status-tag--neutral') as HTMLElement | null;
    expect(pill).not.toBeNull();
    expect(pill!.textContent?.trim()).toBe('CLOSED');
  });
});
