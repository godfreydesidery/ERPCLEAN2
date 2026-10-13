/**
 * PurchaseReturnDetailComponent specs — print / export (Kilimanjaro: "cannot export or print
 * purchase return").
 *  1. Print PDF / Export Excel / Export CSV render for a user with DOCUMENT.RENDER.
 *  2. They are hidden without DOCUMENT.RENDER (same gate as the endpoint and the GRN print).
 *  3. Each button requests its own format for this return's uid.
 *  4. A failed download surfaces the server's friendly message.
 */
import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { AlertService } from '../../../../core/feedback/alert.service';
import { SessionStore } from '../../../../core/auth/session.store';
import { PurchaseReturnService } from './purchase-return.service';
import { PurchaseReturnDetailComponent } from './purchase-return-detail.component';

const STUB_RETURN = {
  uid: 'RET1', id: '1', companyId: '10', branchId: '1',
  returnNumber: 'PRET-0001', status: 'CONFIRMED',
  goodsReceiptId: '5', goodsReceiptUid: 'GR1',
  supplierId: '2', supplierCode: 'SUP001', supplierName: 'Supplier A',
  reason: 'Damaged', netAmount: '1000', vatAmount: '0', grossAmount: '1000',
  currency: 'TZS', debitNoteUid: 'DN1', glEntryUid: null,
  confirmedAt: '2026-10-02T07:00:00Z', createdAt: '2026-10-01T07:00:00Z', lines: [],
};

function makeBed(opts: {
  hasPermission?: (code: string) => boolean;
  exportBlob?: ReturnType<typeof vi.fn>;
} = {}) {
  const exportBlob = opts.exportBlob ?? vi.fn(() => of(new Blob(['%PDF'])));
  TestBed.configureTestingModule({
    imports: [PurchaseReturnDetailComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      {
        provide: PurchaseReturnService,
        useValue: {
          getByUid: vi.fn(() => of(STUB_RETURN)),
          confirm: vi.fn(() => of(STUB_RETURN)),
          exportBlob,
        },
      },
      { provide: AlertService, useValue: { success: vi.fn(), error: vi.fn() } },
      {
        provide: SessionStore,
        useValue: {
          hasPermission: vi.fn(opts.hasPermission ?? (() => true)),
          isAuthenticated: signal(true),
          user: signal(null),
          permissions: signal([]),
          activeBranchUid: signal(null),
        },
      },
    ],
  });
  return { exportBlob };
}

async function setup(opts: Parameters<typeof makeBed>[0] = {}) {
  const spies = makeBed(opts);
  const fixture = TestBed.createComponent(PurchaseReturnDetailComponent);
  fixture.componentRef.setInput('uid', 'RET1');
  await vi.runAllTimersAsync();
  fixture.detectChanges();
  return { fixture, ...spies };
}

function button(fixture: { nativeElement: HTMLElement }, label: string): HTMLButtonElement | undefined {
  const buttons = Array.from(fixture.nativeElement.querySelectorAll('button')) as HTMLButtonElement[];
  return buttons.find((b) => b.textContent?.includes(label));
}

describe('PurchaseReturnDetailComponent — print / export', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:x');
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined);
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
  });
  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
    TestBed.resetTestingModule();
  });

  it('shows Print PDF, Export Excel and Export CSV for a user with DOCUMENT.RENDER', async () => {
    const { fixture } = await setup();

    expect(button(fixture, 'Print PDF')).toBeTruthy();
    expect(button(fixture, 'Export Excel')).toBeTruthy();
    expect(button(fixture, 'Export CSV')).toBeTruthy();
  });

  it('hides print and export without DOCUMENT.RENDER', async () => {
    const { fixture } = await setup({ hasPermission: (code) => code !== 'DOCUMENT.RENDER' });

    expect(button(fixture, 'Print PDF')).toBeUndefined();
    expect(button(fixture, 'Export Excel')).toBeUndefined();
    expect(button(fixture, 'Export CSV')).toBeUndefined();
  });

  it('requests each format for this return and downloads it', async () => {
    const { fixture, exportBlob } = await setup();

    button(fixture, 'Print PDF')!.click();
    await vi.runAllTimersAsync();
    button(fixture, 'Export Excel')!.click();
    await vi.runAllTimersAsync();
    button(fixture, 'Export CSV')!.click();
    await vi.runAllTimersAsync();

    expect(exportBlob).toHaveBeenNthCalledWith(1, 'RET1', 'PDF');
    expect(exportBlob).toHaveBeenNthCalledWith(2, 'RET1', 'XLSX');
    expect(exportBlob).toHaveBeenNthCalledWith(3, 'RET1', 'CSV');
    expect(HTMLAnchorElement.prototype.click).toHaveBeenCalledTimes(3);
  });

  it('shows the server message when the download fails', async () => {
    const body = new Blob([JSON.stringify({ errors: ['Purchase return not found.'] })],
      { type: 'application/json' });
    const { fixture } = await setup({
      exportBlob: vi.fn(() => throwError(() => new HttpErrorResponse({ status: 404, error: body }))),
    });

    button(fixture, 'Print PDF')!.click();
    await vi.runAllTimersAsync();
    fixture.detectChanges();

    const alert: HTMLElement | null = fixture.nativeElement.querySelector('[role="alert"]');
    expect(alert?.textContent).toContain('Purchase return not found.');
  });
});
