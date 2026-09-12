/**
 * StockTransferDetailComponent specs — branch-everywhere display.
 *
 * Covers:
 *  1. Loads the transfer and sets state=idle.
 *  2. Renders the "From: ... → To: ..." route line with branch + location names.
 *  3. Renders the Source / Destination fields with branch name, code, and location.
 *  4. Null-safe: falls back to "—" when a branch name is missing (never a raw id).
 *
 * Line unit + value (Kilimanjaro 2026-09-12 #5):
 *  5. A costed line shows its value, and the foot totals qty and value.
 *  6. An uncosted line reads "—", never 0.00, and is excluded from the total with a note saying so.
 *  7. Every line uncosted leaves the total itself unknown rather than showing zero.
 */
import { HttpErrorResponse } from '@angular/common/http';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { AlertService } from '../../../../core/feedback/alert.service';
import { SessionStore } from '../../../../core/auth/session.store';
import { StockLocationService } from '../locations/stock-location.service';
import { StockTransferDto } from './stock-transfer.model';
import { StockTransferService } from './stock-transfer.service';
import { StockTransferDetailComponent } from './stock-transfer-detail.component';

// ── Stubs ─────────────────────────────────────────────────────────────────────

function makeTransfer(overrides: Partial<StockTransferDto> = {}): StockTransferDto {
  return {
    uid: 'TRF1', id: '1', companyId: '10',
    transferNumber: 'TRF-0001',
    status: 'DRAFT',
    transferMode: 'IN_TRANSIT',
    sourceBranchId: '1', sourceBranchName: 'Head Office', sourceBranchCode: 'BR-01',
    sourceLocationId: '2', sourceLocationName: 'Main Warehouse',
    destBranchId: '3', destBranchName: 'Mwanza Branch', destBranchCode: 'BR-02',
    destLocationId: '4', destLocationName: 'Mwanza Store',
    transferDate: '2025-01-15',
    dispatchedAt: null, receivedAt: null,
    notes: null, lines: [],
    ...overrides,
  };
}

function makeBed(opts: { transfer?: StockTransferDto; getByUidImpl?: () => any } = {}) {
  const transfer = opts.transfer ?? makeTransfer();
  TestBed.configureTestingModule({
    imports: [StockTransferDetailComponent],
    providers: [
      provideRouter([]),
      {
        provide: StockTransferService,
        useValue: {
          getByUid: vi.fn(opts.getByUidImpl ?? (() => of(transfer))),
        },
      },
      {
        provide: StockLocationService,
        useValue: {
          list: vi.fn(() => of({ rows: [], meta: { page: 0, size: 20, totalElements: 0, totalPages: 0, hasNext: false } })),
        },
      },
      { provide: AlertService, useValue: { success: vi.fn(), error: vi.fn() } },
      {
        provide: SessionStore,
        useValue: {
          hasPermission: vi.fn(() => true),
          isAuthenticated: signal(true),
          user: signal(null),
          permissions: signal([]),
          activeBranchUid: signal(null),
        },
      },
    ],
  });
}

function createFixture(uid = 'TRF1') {
  const fixture = TestBed.createComponent(StockTransferDetailComponent);
  fixture.componentRef.setInput('uid', uid);
  return fixture;
}

describe('StockTransferDetailComponent — load', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('loads the transfer and sets state=idle', async () => {
    makeBed();
    const fixture = createFixture();
    await vi.runAllTimersAsync();
    expect(fixture.componentInstance.state()).toBe('idle');
    expect(fixture.componentInstance.entity()?.uid).toBe('TRF1');
  });

  it('sets state=error on a 500 response', async () => {
    makeBed({ getByUidImpl: () => throwError(() => new HttpErrorResponse({ status: 500 })) });
    const fixture = createFixture();
    await vi.runAllTimersAsync();
    expect(fixture.componentInstance.state()).toBe('error');
  });
});

// ── Route display (branch-everywhere) ───────────────────────────────────────────

describe('StockTransferDetailComponent — route display', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('renders the "From: ... → To: ..." route line with branch and location names', async () => {
    makeBed();
    const fixture = createFixture();
    await vi.runAllTimersAsync();
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('From:');
    expect(text).toContain('Head Office');
    expect(text).toContain('Main Warehouse');
    expect(text).toContain('To:');
    expect(text).toContain('Mwanza Branch');
    expect(text).toContain('Mwanza Store');
  });

  it('renders Source / Destination fields with branch name, code, and location, never a raw id', async () => {
    makeBed();
    const fixture = createFixture();
    await vi.runAllTimersAsync();
    fixture.detectChanges();

    const el = fixture.nativeElement as HTMLElement;
    const dts = Array.from(el.querySelectorAll('dt')).map((d) => d.textContent?.trim());
    const sourceIdx = dts.indexOf('Source');
    const destIdx = dts.indexOf('Destination');
    expect(sourceIdx).toBeGreaterThanOrEqual(0);
    expect(destIdx).toBeGreaterThanOrEqual(0);

    const sourceDd = el.querySelectorAll('dd')[sourceIdx];
    const destDd = el.querySelectorAll('dd')[destIdx];
    expect(sourceDd.textContent).toContain('Head Office');
    expect(sourceDd.textContent).toContain('BR-01');
    expect(sourceDd.textContent).toContain('Main Warehouse');
    expect(destDd.textContent).toContain('Mwanza Branch');
    expect(destDd.textContent).toContain('BR-02');
    expect(destDd.textContent).toContain('Mwanza Store');

    // The raw numeric FK must never stand in for the name.
    expect(sourceDd.textContent?.trim()).not.toContain('"1"');
  });

  it('falls back to "—" when a branch name is null, never rendering the raw branch id', async () => {
    makeBed({ transfer: makeTransfer({ sourceBranchName: null, destBranchName: null }) });
    const fixture = createFixture();
    await vi.runAllTimersAsync();
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('—');
    expect(text).not.toContain('sourceBranchId');
  });
});


// ── Line unit + value (Kilimanjaro 2026-09-12 #5) ───────────────────────────

/**
 * The Value column used to coerce a null through `+line.valueAmount`, so goods nobody had costed
 * were reported as worth 0.00 on a document a driver carries. Unknown and zero must not look alike.
 */
describe('StockTransferDetailComponent — line value', () => {
  afterEach(() => TestBed.resetTestingModule());

  function line(overrides: Record<string, unknown> = {}) {
    return {
      id: '1', uid: 'L1', lineNo: 1,
      productId: '5', productCode: 'KON500', productName: 'Konyagi 500ml',
      unitName: 'Bottle',
      qtyTransferred: '4', qtyTransferredBase: '4',
      valueAmount: 6000, currency: 'TZS',
      ...overrides,
    } as never;
  }

  it('shows a costed line value and totals qty and value in the foot', async () => {
    makeBed({ transfer: makeTransfer({ lines: [line(), line({ uid: 'L2', lineNo: 2, qtyTransferred: '2', valueAmount: 3000 })] }) });
    const fixture = createFixture();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    const comp = fixture.componentInstance;
    expect(comp.totalQty()).toBe(6);
    expect(comp.totalValue()).toBe(9000);
    expect(comp.unvaluedLineCount()).toBe(0);

    const foot = (fixture.nativeElement as HTMLElement).querySelector('tfoot');
    expect(foot?.textContent).toContain('9,000.00');
  });

  it('reads an uncosted line as unknown, leaves it out of the total, and says so', async () => {
    makeBed({ transfer: makeTransfer({ lines: [line(), line({ uid: 'L2', lineNo: 2, valueAmount: null })] }) });
    const fixture = createFixture();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    const comp = fixture.componentInstance;
    expect(comp.totalValue()).toBe(6000);
    expect(comp.unvaluedLineCount()).toBe(1);

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('no cost on record');
    // The give-away the fix exists for: a null must never have printed as a confident zero.
    const bodyCells = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('tbody tr:nth-child(2) td'),
    ).map((td) => td.textContent?.trim() ?? '');
    expect(bodyCells).not.toContain('0.00');
  });

  it('leaves the total unknown when no line is costed at all', async () => {
    makeBed({ transfer: makeTransfer({ lines: [line({ valueAmount: null })] }) });
    const fixture = createFixture();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.componentInstance.totalValue()).toBeNull();
  });
});
