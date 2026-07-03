/**
 * DeliveryDetailComponent — Print PDF specs.
 *
 * The backend has no renderable-state guard for DELIVERY_NOTE (unlike INVOICE /
 * PURCHASE_ORDER), so the button is available whenever DOCUMENT.RENDER is held —
 * regardless of delivery status.
 *
 * Covers:
 *  1. printPdf() calls DocumentsService.renderBlob('DELIVERY_NOTE', uid).
 *  2. Button renders when DOCUMENT.RENDER is held.
 *  3. Button hidden without DOCUMENT.RENDER.
 *  4. printError surfaces the friendly server message on failure.
 */
import { provideHttpClient, HttpErrorResponse } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { DocumentsService } from '../documents/documents.service';
import { SalesOrdersService } from './sales-orders.service';
import { DeliveryDetailComponent } from './delivery-detail.component';

// ── Stubs ──────────────────────────────────────────────────────────────────────

const STUB_DELIVERY = {
  id: '1', uid: 'DEL-UID-1', companyId: '10', branchId: '1',
  deliveryNumber: 'DEL-0001', salesOrderId: '2', salesOrderUid: 'SO-UID-1',
  status: 'CONFIRMED', customerId: '3',
  deliveryDate: '2026-07-01', confirmedAt: '2026-07-01T10:00:00Z',
  notes: null, lines: [],
};

function makeBed(overrides: {
  delivery?: typeof STUB_DELIVERY;
  renderBlobSpy?: ReturnType<typeof vi.fn>;
  hasPermission?: ReturnType<typeof vi.fn>;
} = {}) {
  const delivery = overrides.delivery ?? STUB_DELIVERY;
  const renderBlobSpy = overrides.renderBlobSpy ?? vi.fn(() => of(new Blob(['%PDF'])));
  const hasPermission = overrides.hasPermission ?? vi.fn(() => true);

  TestBed.configureTestingModule({
    imports: [DeliveryDetailComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      {
        provide: SalesOrdersService,
        useValue: {
          getDeliveryByUid: vi.fn(() => of(delivery)),
          listReturnsForDelivery: vi.fn(() => of({ rows: [], meta: {} })),
          createInvoiceFromDelivery: vi.fn(() => of({})),
        },
      },
      {
        provide: DocumentsService,
        useValue: { renderBlob: renderBlobSpy },
      },
      {
        provide: AlertService,
        useValue: { success: vi.fn(), error: vi.fn() },
      },
      {
        provide: SessionStore,
        useValue: {
          hasPermission,
          isAuthenticated: signal(true),
          user: signal(null),
          permissions: signal([]),
          activeBranchUid: signal(null),
        },
      },
    ],
  });

  return { renderBlobSpy };
}

// ── Specs ──────────────────────────────────────────────────────────────────────

describe('DeliveryDetailComponent — Print PDF', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('calls DocumentsService.renderBlob with DELIVERY_NOTE + the delivery uid on click', async () => {
    const { renderBlobSpy } = makeBed();
    const fixture = TestBed.createComponent(DeliveryDetailComponent);
    fixture.componentRef.setInput('uid', 'DEL-UID-1');
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.printPdf();

    expect(renderBlobSpy).toHaveBeenCalledWith('DELIVERY_NOTE', 'DEL-UID-1');
  });

  it('shows the Print PDF button when the user holds DOCUMENT.RENDER', async () => {
    makeBed();
    const fixture = TestBed.createComponent(DeliveryDetailComponent);
    fixture.componentRef.setInput('uid', 'DEL-UID-1');
    await vi.runAllTimersAsync();
    fixture.detectChanges();

    const buttons: HTMLButtonElement[] = Array.from(fixture.nativeElement.querySelectorAll('button'));
    expect(buttons.some((b) => b.textContent?.includes('Print PDF'))).toBe(true);
  });

  it('hides the Print PDF button when the user lacks DOCUMENT.RENDER', async () => {
    makeBed({ hasPermission: vi.fn((code: string) => code !== 'DOCUMENT.RENDER') });
    const fixture = TestBed.createComponent(DeliveryDetailComponent);
    fixture.componentRef.setInput('uid', 'DEL-UID-1');
    await vi.runAllTimersAsync();
    fixture.detectChanges();

    const buttons: HTMLButtonElement[] = Array.from(fixture.nativeElement.querySelectorAll('button'));
    expect(buttons.some((b) => b.textContent?.includes('Print PDF'))).toBe(false);
  });

  it('sets printError with the friendly server message when renderBlob fails', async () => {
    const errResponse = new HttpErrorResponse({
      error: new Blob([JSON.stringify({ errors: ['Document templates aren’t set up.'] })],
        { type: 'application/json' }),
      status: 409,
    });
    makeBed({ renderBlobSpy: vi.fn(() => throwError(() => errResponse)) });
    const fixture = TestBed.createComponent(DeliveryDetailComponent);
    fixture.componentRef.setInput('uid', 'DEL-UID-1');
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.printPdf();
    await vi.runAllTimersAsync();

    expect(comp.printing()).toBe(false);
    expect(comp.printError()).toBe('Document templates aren’t set up.');
  });
});
