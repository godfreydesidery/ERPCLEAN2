/**
 * SalesInvoiceDetailComponent — product-scoped unit picker + Print PDF specs.
 *
 * Suite A — product-scoped unit picker:
 *  1. Selecting a product calls listProductUnits(productUid).
 *  2. Unit dropdown is populated with ONLY the returned units.
 *  3. First returned unit (base unit) is pre-selected after product pick.
 *  4. Unit list is cleared when a new product search starts.
 *  5. lineUnitsState transitions loading → idle.
 *  6. lineUnitsState set to 'error' when listProductUnits fails.
 *
 * Suite C — Print PDF (mirrors PurchaseOrderDetailComponent's pattern):
 *  1. printPdf() calls DocumentsService.renderBlob('INVOICE', uid).
 *  2. Button hidden on DRAFT (a draft has no rendered document).
 *  3. Button shown on FINALISED and on VOID.
 *  4. Button hidden without DOCUMENT.RENDER.
 *  5. printError surfaces the friendly server message on failure.
 */
import { provideHttpClient, HttpErrorResponse } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { ProductService } from '../products/product.service';
import { DocumentsService } from '../documents/documents.service';
import { SalesService } from './sales.service';
import { SalesInvoiceDetailComponent } from './sales-invoice-detail.component';

// ── Stubs ──────────────────────────────────────────────────────────────────────

const STUB_INVOICE = {
  uid: 'INV-UID-1', id: '1', companyId: '10', branchId: '1',
  invoiceNumber: null, status: 'DRAFT', documentType: 'INVOICE',
  customerId: '3', customerName: 'Customer A',
  agentId: null, agentName: null,
  routeCode: null, routeName: null,
  currency: 'TZS', netTotalAmount: '0', vatTotalAmount: '0', grossTotalAmount: '0',
  notes: null, finalisedAt: null, finalisedBy: null,
  voidReason: null, postedGlEntryUid: null as string | null, createdAt: null,
};

const STUB_UNITS = [
  { uid: 'U-EACH', id: '1', code: 'EA', name: 'Each', symbol: 'ea', status: 'ACTIVE' },
  { uid: 'U-CRATE', id: '3', code: 'CR', name: 'Crate', symbol: 'cr', status: 'ACTIVE' },
];

const STUB_PRODUCT = {
  uid: 'PROD-UID-2', id: '7', code: 'P002', name: 'Beverage',
  status: 'ACTIVE', companyId: '10',
};

// ── Fiscal receipt stubs (D-6: EFD, ADR-0049) ───────────────────────────────────

const STUB_FISCAL_ISSUED = {
  uid: 'FR-UID-1', invoiceUid: 'INV-UID-1', status: 'ISSUED',
  providerCode: 'SIMULATED', fiscalNumber: 'EFD-000123',
  verificationUrl: 'https://verify.example.tra/EFD-000123',
  deviceSerial: 'DEV-1', issuedAt: '2026-07-04T10:00:00Z',
  attemptCount: 1, errorDetail: null, version: '0', createdAt: '2026-07-04T10:00:00Z',
};

const STUB_FISCAL_NOT_CONFIGURED = {
  uid: 'FR-UID-2', invoiceUid: 'INV-UID-1', status: 'NOT_CONFIGURED',
  providerCode: 'NOT_CONFIGURED', fiscalNumber: null, verificationUrl: null,
  deviceSerial: null, issuedAt: null,
  attemptCount: 1, errorDetail: 'No EFD device is configured for this company yet.',
  version: '0', createdAt: '2026-07-04T10:00:00Z',
};

const STUB_FISCAL_FAILED = {
  uid: 'FR-UID-3', invoiceUid: 'INV-UID-1', status: 'FAILED',
  providerCode: 'TRA_VFD', fiscalNumber: null, verificationUrl: null,
  deviceSerial: null, issuedAt: null,
  attemptCount: 2, errorDetail: 'The device could not be reached.',
  version: '1', createdAt: '2026-07-04T10:00:00Z',
};

function makeBed(overrides: {
  listProductUnitsSpy?: ReturnType<typeof vi.fn>;
  invoice?: typeof STUB_INVOICE;
  renderBlobSpy?: ReturnType<typeof vi.fn>;
  hasPermission?: ReturnType<typeof vi.fn>;
  getFiscalReceiptSpy?: ReturnType<typeof vi.fn>;
  issueFiscalReceiptSpy?: ReturnType<typeof vi.fn>;
} = {}) {
  const listProductUnitsSpy =
    overrides.listProductUnitsSpy ?? vi.fn(() => of(STUB_UNITS));
  const invoice = overrides.invoice ?? STUB_INVOICE;
  const renderBlobSpy = overrides.renderBlobSpy ?? vi.fn(() => of(new Blob(['%PDF'])));
  const hasPermission = overrides.hasPermission ?? vi.fn(() => true);
  const getFiscalReceiptSpy = overrides.getFiscalReceiptSpy ?? vi.fn(() => of(null));
  const issueFiscalReceiptSpy = overrides.issueFiscalReceiptSpy ?? vi.fn(() => of(STUB_FISCAL_ISSUED));
  const alertsSpy = { success: vi.fn(), error: vi.fn() };

  TestBed.configureTestingModule({
    imports: [SalesInvoiceDetailComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      {
        provide: SalesService,
        useValue: {
          getByUid: vi.fn(() => of(invoice)),
          listLines: vi.fn(() => of([])),
          listPayments: vi.fn(() => of([])),
          addLine: vi.fn(() => of({})),
          removeLine: vi.fn(() => of(undefined)),
          addPayment: vi.fn(() => of({})),
          removePayment: vi.fn(() => of(undefined)),
          finalise: vi.fn(() => of(STUB_INVOICE)),
          void: vi.fn(() => of(STUB_INVOICE)),
          getFiscalReceipt: getFiscalReceiptSpy,
          issueFiscalReceipt: issueFiscalReceiptSpy,
        },
      },
      {
        provide: ProductService,
        useValue: {
          list: vi.fn(() => of({ rows: [], meta: {} })),
          listProductUnits: listProductUnitsSpy,
        },
      },
      {
        provide: DocumentsService,
        useValue: { renderBlob: renderBlobSpy },
      },
      {
        provide: AlertService,
        useValue: alertsSpy,
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

  return { listProductUnitsSpy, renderBlobSpy, getFiscalReceiptSpy, issueFiscalReceiptSpy, alertsSpy };
}

// ── Specs ──────────────────────────────────────────────────────────────────────

describe('SalesInvoiceDetailComponent — product-scoped unit picker', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  // ── 1. Selecting a product calls listProductUnits ──────────────────────────

  it('calls listProductUnits with the selected product uid', async () => {
    const { listProductUnitsSpy } = makeBed();
    const fixture = TestBed.createComponent(SalesInvoiceDetailComponent);
    fixture.componentRef.setInput('uid', 'INV-UID-1');
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.selectProduct(STUB_PRODUCT as any);

    expect(listProductUnitsSpy).toHaveBeenCalledWith('PROD-UID-2');
  });

  // ── 2. Unit dropdown populated with ONLY the returned units ───────────────

  it('populates lineUnits exclusively with units from listProductUnits', async () => {
    makeBed();
    const fixture = TestBed.createComponent(SalesInvoiceDetailComponent);
    fixture.componentRef.setInput('uid', 'INV-UID-1');
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.selectProduct(STUB_PRODUCT as any);
    await vi.runAllTimersAsync();

    expect(comp.lineUnits()).toHaveLength(2);
    expect(comp.lineUnits().map((u) => u.uid)).toEqual(['U-EACH', 'U-CRATE']);
  });

  // ── 3. First returned unit (base) is pre-selected ─────────────────────────

  it('pre-selects the first returned unit (base unit) after product pick', async () => {
    makeBed();
    const fixture = TestBed.createComponent(SalesInvoiceDetailComponent);
    fixture.componentRef.setInput('uid', 'INV-UID-1');
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.selectProduct(STUB_PRODUCT as any);
    await vi.runAllTimersAsync();

    expect(comp.newLineUnitUid()).toBe('U-EACH');
  });

  // ── 4. Unit list cleared when product search changes ──────────────────────

  it('clears lineUnits and resets unit selection when product search changes', async () => {
    makeBed();
    const fixture = TestBed.createComponent(SalesInvoiceDetailComponent);
    fixture.componentRef.setInput('uid', 'INV-UID-1');
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.selectProduct(STUB_PRODUCT as any);
    await vi.runAllTimersAsync();
    expect(comp.lineUnits().length).toBeGreaterThan(0);

    comp.onProductSearchChange('');
    expect(comp.lineUnits()).toHaveLength(0);
    expect(comp.newLineUnitUid()).toBe('');
    expect(comp.selectedProduct()).toBeNull();
  });

  // ── 5. lineUnitsState resolves to idle after a successful product pick ────

  it('lineUnitsState is idle after listProductUnits resolves successfully', async () => {
    makeBed();
    const fixture = TestBed.createComponent(SalesInvoiceDetailComponent);
    fixture.componentRef.setInput('uid', 'INV-UID-1');
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.selectProduct(STUB_PRODUCT as any);
    await vi.runAllTimersAsync();

    // After the synchronous of() resolves, state must be idle (not loading or error).
    expect(comp.lineUnitsState()).toBe('idle');
  });

  // ── 6. lineUnitsState set to error on failure ─────────────────────────────

  it('sets lineUnitsState to error when listProductUnits fails', async () => {
    makeBed({
      listProductUnitsSpy: vi.fn(() => throwError(() => new Error('network'))),
    });
    const fixture = TestBed.createComponent(SalesInvoiceDetailComponent);
    fixture.componentRef.setInput('uid', 'INV-UID-1');
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.selectProduct(STUB_PRODUCT as any);
    await vi.runAllTimersAsync();

    expect(comp.lineUnitsState()).toBe('error');
  });
});

// ── View Journal link (UPR: mirror AP bill-detail's posted-GL-entry link) ──────

describe('SalesInvoiceDetailComponent — View Journal link', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('shows the View Journal link when postedGlEntryUid is set', async () => {
    makeBed({ invoice: { ...STUB_INVOICE, status: 'FINALISED', postedGlEntryUid: 'GL-UID-9' } });
    const fixture = TestBed.createComponent(SalesInvoiceDetailComponent);
    fixture.componentRef.setInput('uid', 'INV-UID-1');
    await vi.runAllTimersAsync();
    fixture.detectChanges();

    const anchors: HTMLAnchorElement[] = Array.from(fixture.nativeElement.querySelectorAll('a'));
    const journalLink = anchors.find((a) => a.textContent?.includes('View Journal'));
    expect(journalLink).toBeTruthy();
    expect(journalLink?.getAttribute('href')).toBe('/admin/gl/journals/uid/GL-UID-9');
  });

  it('does not show the View Journal link when postedGlEntryUid is null', async () => {
    makeBed({ invoice: { ...STUB_INVOICE, postedGlEntryUid: null } });
    const fixture = TestBed.createComponent(SalesInvoiceDetailComponent);
    fixture.componentRef.setInput('uid', 'INV-UID-1');
    await vi.runAllTimersAsync();
    fixture.detectChanges();

    const anchors: HTMLAnchorElement[] = Array.from(fixture.nativeElement.querySelectorAll('a'));
    expect(anchors.some((a) => a.textContent?.includes('View Journal'))).toBe(false);
  });
});

// ── Print PDF (salesperson ask: hand over a rendered invoice) ──────────────────

describe('SalesInvoiceDetailComponent — Print PDF', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('calls DocumentsService.renderBlob with INVOICE + the invoice uid on click', async () => {
    const { renderBlobSpy } = makeBed({ invoice: { ...STUB_INVOICE, status: 'FINALISED' } });
    const fixture = TestBed.createComponent(SalesInvoiceDetailComponent);
    fixture.componentRef.setInput('uid', 'INV-UID-1');
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.printPdf();

    expect(renderBlobSpy).toHaveBeenCalledWith('INVOICE', 'INV-UID-1');
  });

  it('hides the Print PDF button on a DRAFT invoice', async () => {
    makeBed({ invoice: { ...STUB_INVOICE, status: 'DRAFT' } });
    const fixture = TestBed.createComponent(SalesInvoiceDetailComponent);
    fixture.componentRef.setInput('uid', 'INV-UID-1');
    await vi.runAllTimersAsync();
    fixture.detectChanges();

    const buttons: HTMLButtonElement[] = Array.from(fixture.nativeElement.querySelectorAll('button'));
    expect(buttons.some((b) => b.textContent?.includes('Print PDF'))).toBe(false);
  });

  it('shows the Print PDF button on a FINALISED invoice', async () => {
    makeBed({ invoice: { ...STUB_INVOICE, status: 'FINALISED' } });
    const fixture = TestBed.createComponent(SalesInvoiceDetailComponent);
    fixture.componentRef.setInput('uid', 'INV-UID-1');
    await vi.runAllTimersAsync();
    fixture.detectChanges();

    const buttons: HTMLButtonElement[] = Array.from(fixture.nativeElement.querySelectorAll('button'));
    expect(buttons.some((b) => b.textContent?.includes('Print PDF'))).toBe(true);
  });

  it('shows the Print PDF button on a VOID invoice', async () => {
    makeBed({ invoice: { ...STUB_INVOICE, status: 'VOID' } });
    const fixture = TestBed.createComponent(SalesInvoiceDetailComponent);
    fixture.componentRef.setInput('uid', 'INV-UID-1');
    await vi.runAllTimersAsync();
    fixture.detectChanges();

    const buttons: HTMLButtonElement[] = Array.from(fixture.nativeElement.querySelectorAll('button'));
    expect(buttons.some((b) => b.textContent?.includes('Print PDF'))).toBe(true);
  });

  it('hides the Print PDF button when the user lacks DOCUMENT.RENDER', async () => {
    makeBed({
      invoice: { ...STUB_INVOICE, status: 'FINALISED' },
      hasPermission: vi.fn((code: string) => code !== 'DOCUMENT.RENDER'),
    });
    const fixture = TestBed.createComponent(SalesInvoiceDetailComponent);
    fixture.componentRef.setInput('uid', 'INV-UID-1');
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
    makeBed({
      invoice: { ...STUB_INVOICE, status: 'FINALISED' },
      renderBlobSpy: vi.fn(() => throwError(() => errResponse)),
    });
    const fixture = TestBed.createComponent(SalesInvoiceDetailComponent);
    fixture.componentRef.setInput('uid', 'INV-UID-1');
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.printPdf();
    await vi.runAllTimersAsync();

    expect(comp.printing()).toBe(false);
    expect(comp.printError()).toBe('Document templates aren’t set up.');
  });
});

// ── Fiscal receipt panel (D-6: EFD, ADR-0049) ──────────────────────────────────

describe('SalesInvoiceDetailComponent — Fiscal receipt panel', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('hides the panel on a DRAFT invoice', async () => {
    makeBed({ invoice: { ...STUB_INVOICE, status: 'DRAFT' } });
    const fixture = TestBed.createComponent(SalesInvoiceDetailComponent);
    fixture.componentRef.setInput('uid', 'INV-UID-1');
    await vi.runAllTimersAsync();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).not.toContain('Fiscal Receipt');
  });

  it('loads the receipt for a FINALISED invoice and shows "Not issued" + Issue button when absent', async () => {
    const { getFiscalReceiptSpy } = makeBed({ invoice: { ...STUB_INVOICE, status: 'FINALISED' } });
    const fixture = TestBed.createComponent(SalesInvoiceDetailComponent);
    fixture.componentRef.setInput('uid', 'INV-UID-1');
    await vi.runAllTimersAsync();
    fixture.detectChanges();

    expect(getFiscalReceiptSpy).toHaveBeenCalledWith('INV-UID-1');
    expect(fixture.nativeElement.textContent).toContain('Not issued');
    const buttons: HTMLButtonElement[] = Array.from(fixture.nativeElement.querySelectorAll('button'));
    expect(buttons.some((b) => b.textContent?.includes('Issue EFD receipt'))).toBe(true);
  });

  it('shows a neutral badge + guidance for NOT_CONFIGURED', async () => {
    makeBed({
      invoice: { ...STUB_INVOICE, status: 'FINALISED' },
      getFiscalReceiptSpy: vi.fn(() => of(STUB_FISCAL_NOT_CONFIGURED)),
    });
    const fixture = TestBed.createComponent(SalesInvoiceDetailComponent);
    fixture.componentRef.setInput('uid', 'INV-UID-1');
    await vi.runAllTimersAsync();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('NOT_CONFIGURED');
    expect(fixture.nativeElement.textContent).toContain('No EFD device is configured for this company yet.');
    const buttons: HTMLButtonElement[] = Array.from(fixture.nativeElement.querySelectorAll('button'));
    expect(buttons.some((b) => b.textContent?.includes('Retry EFD receipt'))).toBe(true);
  });

  it('shows a danger badge + friendly error + Retry for FAILED', async () => {
    makeBed({
      invoice: { ...STUB_INVOICE, status: 'FINALISED' },
      getFiscalReceiptSpy: vi.fn(() => of(STUB_FISCAL_FAILED)),
    });
    const fixture = TestBed.createComponent(SalesInvoiceDetailComponent);
    fixture.componentRef.setInput('uid', 'INV-UID-1');
    await vi.runAllTimersAsync();
    fixture.detectChanges();

    const badge = fixture.nativeElement.querySelector('.status-tag--danger');
    expect(badge?.textContent).toContain('FAILED');
    expect(fixture.nativeElement.textContent).toContain('The device could not be reached.');
    const buttons: HTMLButtonElement[] = Array.from(fixture.nativeElement.querySelectorAll('button'));
    expect(buttons.some((b) => b.textContent?.includes('Retry EFD receipt'))).toBe(true);
  });

  it('shows fiscal number + verification link for ISSUED, with no issue/retry button', async () => {
    makeBed({
      invoice: { ...STUB_INVOICE, status: 'FINALISED' },
      getFiscalReceiptSpy: vi.fn(() => of(STUB_FISCAL_ISSUED)),
    });
    const fixture = TestBed.createComponent(SalesInvoiceDetailComponent);
    fixture.componentRef.setInput('uid', 'INV-UID-1');
    await vi.runAllTimersAsync();
    fixture.detectChanges();

    // Two `.status-tag--ok` badges exist (header invoice-status FINALISED + fiscal ISSUED) —
    // find the one carrying the fiscal status text specifically.
    const badges: HTMLElement[] = Array.from(fixture.nativeElement.querySelectorAll('.status-tag--ok'));
    expect(badges.some((b) => b.textContent?.includes('ISSUED'))).toBe(true);
    expect(fixture.nativeElement.textContent).toContain('EFD-000123');

    const link: HTMLAnchorElement | null = fixture.nativeElement.querySelector(
      'a[href="https://verify.example.tra/EFD-000123"]',
    );
    expect(link).toBeTruthy();
    expect(link?.getAttribute('target')).toBe('_blank');
    expect(link?.getAttribute('rel')).toBe('noopener');

    const buttons: HTMLButtonElement[] = Array.from(fixture.nativeElement.querySelectorAll('button'));
    expect(buttons.some((b) => b.textContent?.includes('EFD receipt'))).toBe(false);
  });

  it('hides the Issue/Retry button without FISCAL.MANAGE', async () => {
    makeBed({
      invoice: { ...STUB_INVOICE, status: 'FINALISED' },
      hasPermission: vi.fn((code: string) => code !== 'FISCAL.MANAGE'),
    });
    const fixture = TestBed.createComponent(SalesInvoiceDetailComponent);
    fixture.componentRef.setInput('uid', 'INV-UID-1');
    await vi.runAllTimersAsync();
    fixture.detectChanges();

    // Panel itself still renders (FISCAL.VIEW granted) but with no action button.
    expect(fixture.nativeElement.textContent).toContain('Fiscal Receipt');
    const buttons: HTMLButtonElement[] = Array.from(fixture.nativeElement.querySelectorAll('button'));
    expect(buttons.some((b) => b.textContent?.includes('EFD receipt'))).toBe(false);
  });

  it('hides the whole panel without FISCAL.VIEW', async () => {
    makeBed({
      invoice: { ...STUB_INVOICE, status: 'FINALISED' },
      hasPermission: vi.fn((code: string) => code !== 'FISCAL.VIEW'),
    });
    const fixture = TestBed.createComponent(SalesInvoiceDetailComponent);
    fixture.componentRef.setInput('uid', 'INV-UID-1');
    await vi.runAllTimersAsync();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).not.toContain('Fiscal Receipt');
  });

  it('clicking Issue calls issueFiscalReceipt, updates the panel, and shows a success alert', async () => {
    const { issueFiscalReceiptSpy, alertsSpy } = makeBed({
      invoice: { ...STUB_INVOICE, status: 'FINALISED' },
      getFiscalReceiptSpy: vi.fn(() => of(null)),
    });
    const fixture = TestBed.createComponent(SalesInvoiceDetailComponent);
    fixture.componentRef.setInput('uid', 'INV-UID-1');
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();
    fixture.detectChanges();

    comp.issueFiscalReceipt();
    await vi.runAllTimersAsync();
    fixture.detectChanges();

    expect(issueFiscalReceiptSpy).toHaveBeenCalledWith('INV-UID-1');
    expect(comp.fiscalReceipt()?.status).toBe('ISSUED');
    expect(alertsSpy.success).toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain('EFD-000123');
  });

  it('shows an error alert (not success) when the issue returns a non-ISSUED outcome (200 NOT_CONFIGURED)', async () => {
    // The endpoint returns 200 for every outcome — a NOT_CONFIGURED/FAILED result did NOT produce a
    // receipt and must not surface as a green success toast (D-6 review fix).
    const { alertsSpy } = makeBed({
      invoice: { ...STUB_INVOICE, status: 'FINALISED' },
      getFiscalReceiptSpy: vi.fn(() => of(null)),
      issueFiscalReceiptSpy: vi.fn(() => of(STUB_FISCAL_NOT_CONFIGURED)),
    });
    const fixture = TestBed.createComponent(SalesInvoiceDetailComponent);
    fixture.componentRef.setInput('uid', 'INV-UID-1');
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();
    fixture.detectChanges();

    comp.issueFiscalReceipt();
    await vi.runAllTimersAsync();

    expect(comp.fiscalReceipt()?.status).toBe('NOT_CONFIGURED');
    expect(alertsSpy.error).toHaveBeenCalled();
    expect(alertsSpy.success).not.toHaveBeenCalled();
  });

  it('surfaces a friendly alert.error when the issue call fails', async () => {
    const errResponse = new HttpErrorResponse({
      error: { errors: ['Could not reach the invoice.'] },
      status: 500,
    });
    const { alertsSpy } = makeBed({
      invoice: { ...STUB_INVOICE, status: 'FINALISED' },
      issueFiscalReceiptSpy: vi.fn(() => throwError(() => errResponse)),
    });
    const fixture = TestBed.createComponent(SalesInvoiceDetailComponent);
    fixture.componentRef.setInput('uid', 'INV-UID-1');
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.issueFiscalReceipt();
    await vi.runAllTimersAsync();

    expect(comp.issuingFiscal()).toBe(false);
    expect(alertsSpy.error).toHaveBeenCalledWith(
      'Could not issue the fiscal receipt.',
      'Could not reach the invoice.',
    );
  });
});
