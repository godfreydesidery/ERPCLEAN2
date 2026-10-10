/**
 * VatReturnDetailComponent — turnover-by-band breakdown.
 *
 * Backend bug fix: VatReturnDto.bands / salesTurnover / zeroRatedSales / exemptSales
 * were previously null/empty on the wire; now populated. Covers:
 *  - model mapping: the DTO's turnover fields flow through to the component signal
 *    untouched (numbers, not strings — coerce with fmtMoney/fmtOrDash, never string ops)
 *  - the "Turnover by rate band" table renders one row per band + a total row
 *  - the empty-bands case renders "No supplies in this period" (not a stale
 *    "recompute" prompt — bands now populate correctly once the backend bug is fixed)
 *  - purchasesTurnover (input-side, out of scope) renders as an em-dash when null
 */
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { describe, it, expect, vi, afterEach } from 'vitest';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { TaxService } from './tax.service';
import { CashbankService } from '../cashbank/cashbank.service';
import { VatReturnDetailComponent } from './vat-return-detail.component';
import type { VatReturnBandDto, VatReturnDto } from './models/tax.model';

vi.useFakeTimers();

// ── Fixtures ──────────────────────────────────────────────────────────────────

const BANDS: VatReturnBandDto[] = [
  { id: '1', vatReturnId: '1', taxBand: 'STANDARD', taxableBase: 930000, outputVat: 167400 },
  { id: '2', vatReturnId: '1', taxBand: 'ZERO_RATED', taxableBase: 50000, outputVat: 0 },
  { id: '3', vatReturnId: '1', taxBand: 'EXEMPT', taxableBase: 20000, outputVat: 0 },
];

function makeReturn(overrides: Partial<VatReturnDto> = {}): VatReturnDto {
  return {
    id: '1', uid: 'vat-1', companyId: '10',
    returnNumber: 'VAT-2026-06',
    periodYear: 2026, periodMonth: 6,
    periodStart: '2026-06-01', periodEnd: '2026-06-30',
    dueDate: '2026-07-20',
    status: 'DRAFT',
    outputVat: 167400,
    inputVat: 60000,
    adjustmentsTotal: 0,
    openingCredit: 0,
    netVat: 107400,
    closingCredit: 0,
    priorReturnId: null, filingReference: null, filingDate: null,
    postedJournalUid: null, filedAt: null, filedBy: null,
    // turnover figures (the previously-null fields)
    salesTurnover: 1000000,
    purchasesTurnover: null,
    zeroRatedSales: 50000,
    exemptSales: 20000,
    bands: BANDS,
    ...overrides,
  };
}

function makeBed(taxService: Partial<{
  getReturn: ReturnType<typeof vi.fn>;
  listAdjustments: ReturnType<typeof vi.fn>;
  recordReturnPayment: ReturnType<typeof vi.fn>;
  exportSchedule: ReturnType<typeof vi.fn>;
}> = {}, permissions: string[] = []) {
  const svc = {
    getReturn: vi.fn(() => of(makeReturn())),
    listAdjustments: vi.fn(() => of([])),
    recomputeReturn: vi.fn(() => of(makeReturn())),
    fileReturn: vi.fn(() => of(makeReturn())),
    addAdjustment: vi.fn(),
    removeAdjustment: vi.fn(),
    recordReturnPayment: vi.fn(() => of(makeReturn())),
    exportSchedule: vi.fn(() => of(new Blob(['x']))),
    ...taxService,
  };

  TestBed.configureTestingModule({
    imports: [VatReturnDetailComponent],
    providers: [
      provideRouter([]),
      { provide: TaxService, useValue: svc },
      {
        provide: CashbankService,
        useValue: {
          listAccountOptions: vi.fn(() => of([
            { id: '5', uid: 'bank-1', code: 'CRDB', name: 'CRDB Main', accountType: 'BANK',
              branchId: null, currency: 'TZS', isDefault: true, inCurrentBranch: true },
          ])),
        },
      },
      { provide: AlertService, useValue: { success: vi.fn(), error: vi.fn() } },
      {
        provide: SessionStore,
        useValue: {
          hasPermission: vi.fn((code: string) => permissions.includes(code)),
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

function createAndLoad(taxService: Parameters<typeof makeBed>[0] = {}, permissions: string[] = []) {
  const svc = makeBed(taxService, permissions);
  const fixture = TestBed.createComponent(VatReturnDetailComponent);
  fixture.componentRef.setInput('uid', 'vat-1');
  vi.runAllTimers();
  fixture.detectChanges();
  return { fixture, svc };
}

// ── Model mapping ─────────────────────────────────────────────────────────────

describe('VatReturnDetailComponent — turnover model mapping', () => {
  afterEach(() => { vi.clearAllTimers(); TestBed.resetTestingModule(); });

  it('loads the return and exposes bands + turnover figures as numbers (not strings)', () => {
    const { fixture } = createAndLoad();
    const ret = fixture.componentInstance.vatReturn();

    expect(ret).toBeTruthy();
    expect(ret!.bands.length).toBe(3);
    expect(ret!.salesTurnover).toBe(1000000);
    expect(ret!.zeroRatedSales).toBe(50000);
    expect(ret!.exemptSales).toBe(20000);
    expect(ret!.purchasesTurnover).toBeNull();
  });

  it('fmtMoney coerces a band taxableBase/outputVat number (BigDecimal on the wire)', () => {
    const { fixture } = createAndLoad();
    const comp = fixture.componentInstance;
    expect(comp.fmtMoney(BANDS[0].taxableBase)).toBe('930,000.00');
    expect(comp.fmtMoney(BANDS[0].outputVat)).toBe('167,400.00');
  });

  it('fmtOrDash renders an em-dash for null (purchasesTurnover, out of scope) and money otherwise', () => {
    const { fixture } = createAndLoad();
    const comp = fixture.componentInstance;
    expect(comp.fmtOrDash(null)).toBe('—');
    expect(comp.fmtOrDash(undefined)).toBe('—');
    expect(comp.fmtOrDash(1000000)).toBe('1,000,000.00');
  });
});

// ── Turnover by rate band table ────────────────────────────────────────────────

describe('VatReturnDetailComponent — Turnover by rate band table', () => {
  afterEach(() => { vi.clearAllTimers(); TestBed.resetTestingModule(); });

  it('renders one row per band', () => {
    const { fixture } = createAndLoad();
    const table = fixture.nativeElement.querySelector('table[aria-label="Turnover by rate band"]');
    expect(table).toBeTruthy();

    const rows: HTMLTableRowElement[] = Array.from(table!.querySelectorAll('tbody tr'));
    expect(rows.length).toBe(3);
    expect(rows[0].textContent).toContain('STANDARD');
    expect(rows[0].textContent).toContain('930,000.00');
    expect(rows[0].textContent).toContain('167,400.00');
    expect(rows[1].textContent).toContain('ZERO_RATED');
    expect(rows[2].textContent).toContain('EXEMPT');
  });

  it('renders a total row with the return-level salesTurnover + outputVat', () => {
    const { fixture } = createAndLoad();
    const table = fixture.nativeElement.querySelector('table[aria-label="Turnover by rate band"]');
    const totalRow = table!.querySelector('tfoot tr');
    expect(totalRow?.textContent).toContain('Total');
    expect(totalRow?.textContent).toContain('1,000,000.00');
    expect(totalRow?.textContent).toContain('167,400.00');
  });

  it('shows "No supplies in this period" when bands is empty (not a stale recompute prompt)', () => {
    const { fixture } = createAndLoad({
      getReturn: vi.fn(() => of(makeReturn({ bands: [], salesTurnover: 0, zeroRatedSales: 0, exemptSales: 0 }))),
    });
    const table = fixture.nativeElement.querySelector('table[aria-label="Turnover by rate band"]');
    expect(table!.textContent).toContain('No supplies in this period');
    expect(table!.querySelectorAll('tbody tr').length).toBe(1); // the empty-state row
  });
});

// ── Summary area — turnover figures ────────────────────────────────────────────

describe('VatReturnDetailComponent — summary area turnover figures', () => {
  afterEach(() => { vi.clearAllTimers(); TestBed.resetTestingModule(); });

  it('surfaces salesTurnover, zeroRatedSales, exemptSales near the existing totals', () => {
    const { fixture } = createAndLoad();
    const summary = fixture.nativeElement.querySelector('table[aria-label="VAT return summary"]');
    expect(summary).toBeTruthy();
    expect(summary!.textContent).toContain('Sales Turnover');
    expect(summary!.textContent).toContain('1,000,000.00');
    expect(summary!.textContent).toContain('Zero-Rated Sales');
    expect(summary!.textContent).toContain('50,000.00');
    expect(summary!.textContent).toContain('Exempt Sales');
    expect(summary!.textContent).toContain('20,000.00');
  });

  it('renders an em-dash for purchasesTurnover when null (input-side, out of scope)', () => {
    const { fixture } = createAndLoad();
    const summary = fixture.nativeElement.querySelector('table[aria-label="VAT return summary"]');
    expect(summary!.textContent).toContain('Purchases Turnover');
    expect(summary!.textContent).toContain('—');
  });

  it('renders a formatted amount for purchasesTurnover when populated', () => {
    const { fixture } = createAndLoad({
      getReturn: vi.fn(() => of(makeReturn({ purchasesTurnover: 800000 }))),
    });
    const summary = fixture.nativeElement.querySelector('table[aria-label="VAT return summary"]');
    expect(summary!.textContent).toContain('800,000.00');
  });
});

// ── ACC-07: record payment on a FILED return ───────────────────────────────────

describe('VatReturnDetailComponent — record payment (ACC-07)', () => {
  afterEach(() => { vi.clearAllTimers(); TestBed.resetTestingModule(); });

  const filed = (o: Partial<VatReturnDto> = {}) => makeReturn({
    status: 'FILED', filingReference: 'TRA-1', filingDate: '2026-07-15', netVat: 107400, ...o,
  });

  it('offers "Record payment" only on a FILED return with VAT still owed, to VAT.RETURN.FILE', () => {
    const { fixture } = createAndLoad({ getReturn: vi.fn(() => of(filed())) }, ['VAT.RETURN.FILE']);
    expect(fixture.componentInstance.canRecordPayment()).toBe(true);
    expect(fixture.nativeElement.textContent).toContain('Record payment');
    TestBed.resetTestingModule();

    const draft = createAndLoad({}, ['VAT.RETURN.FILE']);
    expect(draft.fixture.componentInstance.canRecordPayment()).toBe(false);
    TestBed.resetTestingModule();

    const paid = createAndLoad({ getReturn: vi.fn(() => of(filed({ paidAmount: 107400 }))) },
      ['VAT.RETURN.FILE']);
    expect(paid.fixture.componentInstance.canRecordPayment()).toBe(false);
    TestBed.resetTestingModule();

    const noPerm = createAndLoad({ getReturn: vi.fn(() => of(filed())) });
    expect(noPerm.fixture.componentInstance.canRecordPayment()).toBe(false);
  });

  it('prefills the outstanding amount and the default bank, then posts the payment', () => {
    const recordReturnPayment = vi.fn(() => of(filed({ paidAmount: 107400 })));
    const { fixture } = createAndLoad(
      { getReturn: vi.fn(() => of(filed({ paidAmount: 7400 }))), recordReturnPayment },
      ['VAT.RETURN.FILE']);
    const comp = fixture.componentInstance;
    comp.openPayForm();
    expect(+comp.payAmount()).toBe(100000);
    expect(comp.payAccountUid()).toBe('bank-1');

    comp.payRef.set('PRN-9');
    comp.submitPayment();
    expect(recordReturnPayment).toHaveBeenCalledWith('vat-1', expect.objectContaining({
      cashBankAccountUid: 'bank-1', amount: '100000', reference: 'PRN-9',
    }));
    expect(comp.showPayForm()).toBe(false);
    expect(comp.outstanding()).toBe(0);
  });

  it('refuses to submit without an account', () => {
    const recordReturnPayment = vi.fn();
    const { fixture } = createAndLoad(
      { getReturn: vi.fn(() => of(filed())), recordReturnPayment }, ['VAT.RETURN.FILE']);
    const comp = fixture.componentInstance;
    comp.showPayForm.set(true);
    comp.payAccountUid.set('');
    comp.submitPayment();
    expect(recordReturnPayment).not.toHaveBeenCalled();
    expect(comp.payError()).toContain('account');
  });
});

// ── RPT-14 / PAR-06: schedule exports ──────────────────────────────────────────

describe('VatReturnDetailComponent — VAT schedules (RPT-14)', () => {
  afterEach(() => { vi.clearAllTimers(); TestBed.resetTestingModule(); });

  it('shows the schedule buttons to REPORT.EXPORT and requests the chosen schedule', () => {
    const exportSchedule = vi.fn(() => of(new Blob(['x'])));
    const { fixture } = createAndLoad({ exportSchedule }, ['REPORT.EXPORT']);
    expect(fixture.nativeElement.textContent).toContain('Sales schedule');
    expect(fixture.nativeElement.textContent).toContain('Purchases schedule');

    URL.createObjectURL = vi.fn(() => 'blob:x');
    URL.revokeObjectURL = vi.fn();
    fixture.componentInstance.exportSchedule('purchases', 'CSV');
    expect(exportSchedule).toHaveBeenCalledWith('vat-1', 'purchases', 'CSV');
  });
});
