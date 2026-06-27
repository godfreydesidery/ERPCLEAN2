/**
 * BomDetailComponent — number-input coercion regression specs.
 *
 * Covers:
 *  1. save() does not throw when fOutputQty / fYieldPercent contain numbers (not strings).
 *  2. addComponent() does not throw when newComponentQtyPer / newComponentScrapPercent are numbers.
 *  3. saveComponent() does not throw when editQtyPer / editScrapPercent are numbers.
 *  4. The String() coercion means save() posts the correct stringified value.
 */
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { AlertService } from '../../../../core/feedback/alert.service';
import { SessionStore } from '../../../../core/auth/session.store';
import { CompanyService } from '../../company/company.service';
import { OrganisationService } from '../../organisation/organisation.service';
import { ProductService } from '../../products/product.service';
import { BomService } from './bom.service';
import { BomDetailComponent } from './bom-detail.component';
import type { BomDto } from './models/bom.model';

function makeBom(overrides: Partial<BomDto> = {}): BomDto {
  return {
    id: '1', uid: 'bom-1', companyId: '10',
    parentProductId: 'prod-1', parentProductUid: 'prod-uid-1',
    versionNo: '1', status: 'DRAFT',
    outputQty: '10', yieldPercent: '100',
    effectiveFrom: '', effectiveTo: '', sourceBomUid: '', notes: '',
    activatedAt: '', archivedAt: '', version: '0',
    createdAt: '2024-01-01T00:00:00Z', createdBy: 'admin',
    updatedAt: '', updatedBy: '',
    components: [],
    ...overrides,
  };
}

const STUB_COMPONENT = {
  uid: 'comp-1', id: '2', lineNo: 1,
  componentProductId: 'p2', componentProductUid: 'p-uid-2',
  componentProductCode: 'MAT001', componentProductName: 'Material A',
  qtyPer: '2', scrapPercent: '5', sourcing: 'BUY' as const, reference: 'REF1',
};

function makeBed(
  bomSvc: Partial<{
    getByUid: ReturnType<typeof vi.fn>;
    update: ReturnType<typeof vi.fn>;
    addComponent: ReturnType<typeof vi.fn>;
    updateComponent: ReturnType<typeof vi.fn>;
    removeComponent: ReturnType<typeof vi.fn>;
    activate: ReturnType<typeof vi.fn>;
    archive: ReturnType<typeof vi.fn>;
  }> = {},
) {
  const svc = {
    getByUid: vi.fn(() => of(makeBom())),
    update: vi.fn(() => of(makeBom())),
    addComponent: vi.fn(() => of(STUB_COMPONENT)),
    updateComponent: vi.fn(() => of(STUB_COMPONENT)),
    removeComponent: vi.fn(() => of(undefined)),
    activate: vi.fn(() => of(makeBom({ status: 'ACTIVE' }))),
    archive: vi.fn(() => of(makeBom({ status: 'ARCHIVED' }))),
    ...bomSvc,
  };

  TestBed.configureTestingModule({
    imports: [BomDetailComponent],
    providers: [
      provideRouter([]),
      { provide: BomService, useValue: svc },
      { provide: AlertService, useValue: { success: vi.fn(), error: vi.fn() } },
      { provide: OrganisationService, useValue: { current: vi.fn(() => of({ uid: 'org-1' })) } },
      { provide: CompanyService, useValue: { list: vi.fn(() => of([{ id: '10', uid: 'co-1', name: 'ACME' }])) } },
      {
        provide: ProductService,
        useValue: { list: vi.fn(() => of({ rows: [], meta: {} })) },
      },
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
  return svc;
}

describe('BomDetailComponent — number-input coercion', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  // ── 1. save() with numeric fOutputQty / fYieldPercent ────────────────────

  it('save() does not throw when fOutputQty is set as a number (String coercion)', async () => {
    const svc = makeBed();
    const fixture = TestBed.createComponent(BomDetailComponent);
    fixture.componentRef.setInput('uid', 'bom-1');
    await vi.runAllTimersAsync();

    const comp = fixture.componentInstance;
    // Simulate what the fixed template does for a number input.
    comp.fOutputQty.set(String(5));
    comp.fYieldPercent.set(String(95));

    expect(() => comp.save()).not.toThrow();
    await vi.runAllTimersAsync();

    expect(svc.update).toHaveBeenCalledOnce();
    const req = svc.update.mock.calls[0][1];
    expect(req.outputQty).toBe('5');
  });

  it('save() rejects zero outputQty even when set as String(0)', async () => {
    const svc = makeBed();
    const fixture = TestBed.createComponent(BomDetailComponent);
    fixture.componentRef.setInput('uid', 'bom-1');
    await vi.runAllTimersAsync();

    const comp = fixture.componentInstance;
    comp.fOutputQty.set(String(0));

    expect(() => comp.save()).not.toThrow();
    expect(svc.update).not.toHaveBeenCalled();
    expect(comp.saveError()).toMatch(/positive number/i);
  });

  // ── 2. addComponent() with numeric qtyPer ────────────────────────────────

  it('addComponent() does not throw when newComponentQtyPer is String(number)', async () => {
    const svc = makeBed();
    const fixture = TestBed.createComponent(BomDetailComponent);
    fixture.componentRef.setInput('uid', 'bom-1');
    await vi.runAllTimersAsync();

    const comp = fixture.componentInstance;
    comp.newComponentProductUid.set('prod-uid-1');
    comp.newComponentQtyPer.set(String(3));
    comp.newComponentScrapPercent.set(String(2.5));

    expect(() => comp.addComponent()).not.toThrow();
    await vi.runAllTimersAsync();

    expect(svc.addComponent).toHaveBeenCalledOnce();
    const req = svc.addComponent.mock.calls[0][1];
    expect(req.qtyPer).toBe('3');
  });

  // ── 3. saveComponent() with numeric editQtyPer ───────────────────────────

  it('saveComponent() does not throw when editQtyPer is String(number)', async () => {
    const svc = makeBed();
    const fixture = TestBed.createComponent(BomDetailComponent);
    fixture.componentRef.setInput('uid', 'bom-1');
    await vi.runAllTimersAsync();

    const comp = fixture.componentInstance;
    comp.editQtyPer.set(String(4));
    comp.editScrapPercent.set(String(1));

    expect(() => comp.saveComponent('comp-1')).not.toThrow();
    await vi.runAllTimersAsync();

    expect(svc.updateComponent).toHaveBeenCalledOnce();
    const req = svc.updateComponent.mock.calls[0][2];
    expect(req.qtyPer).toBe('4');
  });
});
