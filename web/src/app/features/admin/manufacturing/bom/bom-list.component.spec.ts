/**
 * BomListComponent — unit spec.
 *
 * Covers: load-once (idle + rows), isEmpty, validation guard, success path, 403→'forbidden'.
 */
import { HttpErrorResponse } from '@angular/common/http';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { describe, it, expect, vi, afterEach } from 'vitest';
import { AlertService } from '../../../../core/feedback/alert.service';
import { SessionStore } from '../../../../core/auth/session.store';
import { CompanyService } from '../../company/company.service';
import { OrganisationService } from '../../organisation/organisation.service';
import { ProductService } from '../../products/product.service';
import { BomService } from './bom.service';
import { BomListComponent } from './bom-list.component';
import type { BomDto } from './models/bom.model';
import type { PageMeta } from '../../../../core/api/api-response.model';

vi.useFakeTimers();

// ── Fixtures ──────────────────────────────────────────────────────────────────

function makeBom(overrides: Partial<BomDto> = {}): BomDto {
  return {
    id: '1',
    uid: 'bom-1',
    companyId: '10',
    parentProductId: 'prod-1',
    parentProductUid: 'prod-uid-1',
    versionNo: '1',
    status: 'DRAFT',
    outputQty: '1',
    yieldPercent: '100',
    effectiveFrom: '',
    effectiveTo: '',
    sourceBomUid: '',
    notes: '',
    activatedAt: '',
    archivedAt: '',
    version: '0',
    createdAt: '2024-01-01T00:00:00Z',
    createdBy: 'admin',
    updatedAt: '',
    updatedBy: '',
    components: [],
    ...overrides,
  };
}

const DEFAULT_META: PageMeta = {
  page: 0,
  size: 20,
  totalElements: 1,
  totalPages: 1,
  hasNext: false,
};

function makeBed(
  bomSvc: Partial<{
    list: ReturnType<typeof vi.fn>;
    create: ReturnType<typeof vi.fn>;
  }> = {},
  perms: string[] = ['BOM.VIEW', 'BOM.MANAGE'],
  alertsSvc: { success: ReturnType<typeof vi.fn>; error: ReturnType<typeof vi.fn> } = {
    success: vi.fn(),
    error: vi.fn(),
  },
) {
  const svc = {
    list: vi.fn(() => of({ rows: [makeBom()], meta: DEFAULT_META })),
    create: vi.fn(() => of(makeBom())),
    ...bomSvc,
  };

  TestBed.configureTestingModule({
    imports: [BomListComponent],
    providers: [
      provideRouter([]),
      { provide: BomService, useValue: svc },
      { provide: AlertService, useValue: alertsSvc },
      {
        provide: OrganisationService,
        useValue: { current: vi.fn(() => of({ uid: 'org-1', name: 'Test Org' })) },
      },
      {
        provide: CompanyService,
        useValue: {
          list: vi.fn(() =>
            of([{ id: '10', uid: 'co-1', name: 'ACME Ltd' }]),
          ),
        },
      },
      {
        provide: ProductService,
        useValue: {
          list: vi.fn(() => of({ rows: [], meta: DEFAULT_META })),
        },
      },
      {
        provide: SessionStore,
        useValue: {
          hasPermission: vi.fn((p: string) => perms.includes(p)),
          user: signal(null),
          isAuthenticated: vi.fn(() => true),
        },
      },
    ],
  });

  return { svc, alertsSvc, fixture: TestBed.createComponent(BomListComponent) };
}

// ── Tests ─────────────────────────────────────────────────────────────────────

describe('BomListComponent', () => {
  afterEach(() => {
    TestBed.resetTestingModule();
    vi.clearAllTimers();
  });

  it('loads BOMs and sets idle state', async () => {
    const { fixture } = makeBed();
    fixture.detectChanges();
    await vi.runAllTimersAsync();
    fixture.detectChanges();

    const comp = fixture.componentInstance;
    expect(comp.state()).toBe('idle');
    expect(comp.rows().length).toBe(1);
    expect(comp.rows()[0].uid).toBe('bom-1');
  });

  it('isEmpty is true when rows are empty', async () => {
    const { fixture } = makeBed({
      list: vi.fn(() =>
        of({ rows: [], meta: { ...DEFAULT_META, totalElements: 0 } }),
      ),
    });
    fixture.detectChanges();
    await vi.runAllTimersAsync();
    fixture.detectChanges();

    const comp = fixture.componentInstance;
    expect(comp.isEmpty()).toBe(true);
  });

  it('sets forbidden state on 403', async () => {
    const { fixture } = makeBed({
      list: vi.fn(() =>
        throwError(
          () => new HttpErrorResponse({ status: 403, statusText: 'Forbidden' }),
        ),
      ),
    });
    fixture.detectChanges();
    await vi.runAllTimersAsync();
    fixture.detectChanges();

    expect(fixture.componentInstance.state()).toBe('forbidden');
  });

  it('sets error state on generic error', async () => {
    const { fixture } = makeBed({
      list: vi.fn(() =>
        throwError(() => new HttpErrorResponse({ status: 500 })),
      ),
    });
    fixture.detectChanges();
    await vi.runAllTimersAsync();
    fixture.detectChanges();

    expect(fixture.componentInstance.state()).toBe('error');
  });

  it('validates: parent product required before create', async () => {
    const { fixture } = makeBed();
    fixture.detectChanges();
    await vi.runAllTimersAsync();
    fixture.detectChanges();

    const comp = fixture.componentInstance;
    comp.showCreateForm.set(true);
    // leave newParentProductUid empty
    comp.create();

    expect(comp.formError()).toBe('Parent product is required.');
  });

  it('validates: positive output qty required', async () => {
    const { fixture } = makeBed();
    fixture.detectChanges();
    await vi.runAllTimersAsync();
    fixture.detectChanges();

    const comp = fixture.componentInstance;
    comp.showCreateForm.set(true);
    comp.newParentProductUid.set('prod-uid-1');
    comp.newOutputQty.set('-5');
    comp.create();

    expect(comp.formError()).toBe('Output quantity must be a positive number.');
  });

  it('calls bomService.create and shows success', async () => {
    const alerts = { success: vi.fn(), error: vi.fn() };
    const createFn = vi.fn(() => of(makeBom({ uid: 'bom-new', versionNo: '1' })));
    const { fixture } = makeBed({ create: createFn }, ['BOM.VIEW', 'BOM.MANAGE'], alerts);

    fixture.detectChanges();
    await vi.runAllTimersAsync();
    fixture.detectChanges();

    const comp = fixture.componentInstance;
    comp.showCreateForm.set(true);
    comp.newParentProductUid.set('prod-uid-1');
    comp.newOutputQty.set('10');

    comp.create();
    await vi.runAllTimersAsync();
    fixture.detectChanges();

    expect(createFn).toHaveBeenCalledOnce();
    expect(comp.showCreateForm()).toBe(false);
    expect(comp.saving()).toBe(false);
  });

  // ── number-input coercion regression ─────────────────────────────────────

  it('create() does not throw when newOutputQty was set as String(number) from number input', async () => {
    const createFn = vi.fn(() => of(makeBom({ uid: 'bom-new' })));
    const { fixture } = makeBed({ create: createFn });

    fixture.detectChanges();
    await vi.runAllTimersAsync();
    fixture.detectChanges();

    const comp = fixture.componentInstance;
    comp.showCreateForm.set(true);
    comp.newParentProductUid.set('prod-uid-1');
    // Simulate what the fixed template does: String($event ?? '').
    comp.newOutputQty.set(String(10));
    comp.newYieldPercent.set(String(95));

    expect(() => comp.create()).not.toThrow();
    await vi.runAllTimersAsync();

    expect(createFn).toHaveBeenCalledOnce();
    const req = (createFn.mock.calls as any[][])[0][1];
    expect(req.outputQty).toBe('10');
    expect(req.yieldPercent).toBe('95');
  });

  it('create() rejects zero outputQty even when set as String(0)', async () => {
    const createFn = vi.fn();
    const { fixture } = makeBed({ create: createFn });

    fixture.detectChanges();
    await vi.runAllTimersAsync();
    fixture.detectChanges();

    const comp = fixture.componentInstance;
    comp.showCreateForm.set(true);
    comp.newParentProductUid.set('prod-uid-1');
    comp.newOutputQty.set(String(0));

    expect(() => comp.create()).not.toThrow();

    expect(createFn).not.toHaveBeenCalled();
    expect(comp.formError()).toMatch(/positive number/i);
  });
});
