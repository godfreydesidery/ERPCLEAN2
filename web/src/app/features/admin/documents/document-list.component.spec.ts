/**
 * DocumentListComponent specs.
 * Mirrors quotation-list.component.spec.ts (Vitest + vi.useFakeTimers).
 *
 * Covers:
 *  1. Fires exactly one load on startup after company resolves.
 *  2. isEmpty is true when no rows returned.
 *  3. submitRender() validation: sourceUid required for non-statement types.
 *  4. submitRender() validation: sourceParams required for AR_STATEMENT.
 *  5. submitRender() calls docService.render with correct payload.
 *  6. 403 response sets state to 'forbidden'.
 *  7. typeBadgeClass returns correct class per type.
 */
import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { DocumentsService } from './documents.service';
import type { GeneratedDocumentPage } from './documents.service';
import { DocumentListComponent } from './document-list.component';

// ── Stubs ─────────────────────────────────────────────────────────────────────

const emptyPage = (): GeneratedDocumentPage => ({
  rows: [],
  meta: { page: 0, size: 20, totalElements: 0, totalPages: 0, hasNext: false },
});

const stubDoc = {
  uid: 'D1', id: '1', companyId: '10', branchId: '1',
  documentNumber: 'INV-0001', documentType: 'INVOICE' as const,
  sourceType: 'INVOICE', sourceUid: 'INV-UID-1', sourceParams: '',
  brandingId: 'BR1', contentHash: 'abc123', byteSize: '12345',
  mimeType: 'application/pdf', generatedBy: 'user1',
  generatedAt: '2025-01-01T10:00:00Z', downloadUrl: '/download/D1',
};

function makeSessionStore(hasRender = false) {
  return {
    hasPermission: vi.fn((code: string) => code === 'DOCUMENT.VIEW' || (hasRender && code === 'DOCUMENT.RENDER')),
    isAuthenticated: signal(true),
    user: signal(null),
    permissions: signal([]),
    activeBranchUid: signal(null),
  };
}

function makeBed(opts: {
  listImpl?: () => any;
  renderImpl?: () => any;
  hasRender?: boolean;
} = {}) {
  const { listImpl, renderImpl, hasRender = false } = opts;
  const sessionStore = makeSessionStore(hasRender);

  TestBed.configureTestingModule({
    imports: [DocumentListComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      {
        provide: DocumentsService,
        useValue: {
          list: vi.fn(listImpl ?? (() => of(emptyPage()))),
          render: vi.fn(renderImpl ?? (() => of(stubDoc))),
          downloadBlob: vi.fn(() => of(new Blob())),
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
      { provide: SessionStore, useValue: sessionStore },
    ],
  });
}

// ── Init ───────────────────────────────────────────────────────────────────────

describe('DocumentListComponent — init', () => {
  beforeEach(() => { vi.useFakeTimers(); makeBed(); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('fires exactly one load on startup after company resolves', async () => {
    const comp = TestBed.createComponent(DocumentListComponent).componentInstance;
    const svc = TestBed.inject(DocumentsService) as any;
    await vi.runAllTimersAsync();

    expect(svc.list).toHaveBeenCalledTimes(1);
    expect(svc.list.mock.calls[0][0]).toBe('10');
    expect(comp.state()).toBe('idle');
  });

  it('isEmpty is true when no documents returned', async () => {
    const comp = TestBed.createComponent(DocumentListComponent).componentInstance;
    await vi.runAllTimersAsync();
    expect(comp.isEmpty()).toBe(true);
  });
});

// ── Render form validation ────────────────────────────────────────────────────

describe('DocumentListComponent — render form validation', () => {
  beforeEach(() => { vi.useFakeTimers(); makeBed({ hasRender: true }); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('submitRender() validation: sourceUid required for INVOICE', async () => {
    const comp = TestBed.createComponent(DocumentListComponent).componentInstance;
    const svc = TestBed.inject(DocumentsService) as any;
    await vi.runAllTimersAsync();

    comp.newDocType.set('INVOICE');
    comp.newSourceUid.set('');
    comp.submitRender();

    expect(comp.formError()).toBeTruthy();
    expect(svc.render).not.toHaveBeenCalled();
  });

  it('submitRender() validation: sourceParams required for AR_STATEMENT', async () => {
    const comp = TestBed.createComponent(DocumentListComponent).componentInstance;
    const svc = TestBed.inject(DocumentsService) as any;
    await vi.runAllTimersAsync();

    comp.newDocType.set('AR_STATEMENT');
    comp.newSourceParams.set('');
    comp.submitRender();

    expect(comp.formError()).toBeTruthy();
    expect(svc.render).not.toHaveBeenCalled();
  });

  it('submitRender() calls render with correct payload for INVOICE', async () => {
    const comp = TestBed.createComponent(DocumentListComponent).componentInstance;
    const svc = TestBed.inject(DocumentsService) as any;
    await vi.runAllTimersAsync();

    comp.newDocType.set('INVOICE');
    comp.newSourceUid.set('INV-UID-99');
    comp.submitRender();

    expect(svc.render).toHaveBeenCalledOnce();
    const req = svc.render.mock.calls[0][0];
    expect(req.documentType).toBe('INVOICE');
    expect(req.sourceUid).toBe('INV-UID-99');
  });
});

// ── 403 forbidden ──────────────────────────────────────────────────────────────

describe('DocumentListComponent — 403 forbidden', () => {
  beforeEach(() => { vi.useFakeTimers(); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('sets state to forbidden when list returns 403', async () => {
    makeBed({
      listImpl: () =>
        throwError(() => new HttpErrorResponse({ status: 403, statusText: 'Forbidden' })),
    });

    const comp = TestBed.createComponent(DocumentListComponent).componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.state()).toBe('forbidden');
  });
});

// ── typeBadgeClass ─────────────────────────────────────────────────────────────

describe('DocumentListComponent — typeBadgeClass', () => {
  beforeEach(() => { vi.useFakeTimers(); makeBed(); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('INVOICE → text-bg-primary', async () => {
    const comp = TestBed.createComponent(DocumentListComponent).componentInstance;
    await vi.runAllTimersAsync();
    expect(comp.typeBadgeClass('INVOICE')).toBe('text-bg-primary');
  });

  it('PURCHASE_ORDER → text-bg-warning', async () => {
    const comp = TestBed.createComponent(DocumentListComponent).componentInstance;
    await vi.runAllTimersAsync();
    expect(comp.typeBadgeClass('PURCHASE_ORDER')).toBe('text-bg-warning');
  });

  it('GOODS_RECEIPT → text-bg-success', async () => {
    const comp = TestBed.createComponent(DocumentListComponent).componentInstance;
    await vi.runAllTimersAsync();
    expect(comp.typeBadgeClass('GOODS_RECEIPT')).toBe('text-bg-success');
  });

  it('CREDIT_NOTE → text-bg-danger', async () => {
    const comp = TestBed.createComponent(DocumentListComponent).componentInstance;
    await vi.runAllTimersAsync();
    expect(comp.typeBadgeClass('CREDIT_NOTE')).toBe('text-bg-danger');
  });
});
