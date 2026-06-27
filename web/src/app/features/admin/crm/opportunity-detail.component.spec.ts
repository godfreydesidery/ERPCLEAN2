/**
 * OpportunityDetailComponent — regression specs for numeric-input string-op crash class.
 *
 * fEstimatedValue, fWinProbability, newLineQty, newLineUnitPrice, newLineDiscountPct,
 * and advanceProbOverride are all bound to type="number" inputs. NumberValueAccessor
 * stores a JS number in the signal; the methods previously called .trim() on the raw
 * number (crash). After the fix they coerce with String() first.
 */
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { ProductService } from '../products/product.service';
import { CrmService } from './crm.service';
import { OpportunityDetailComponent } from './opportunity-detail.component';

const stubOpp = {
  uid: 'OPP1', id: '1', companyId: '10', opportunityNumber: 'OPP-0001',
  opportunityStatus: 'OPEN', title: 'Test Deal',
  estimatedValueAmount: '0', winProbability: '50',
  expectedCloseDate: null, sourceLeadUid: null, notes: null,
  currency: 'TZS', pipelineStageId: '1', lines: [],
};

const stubStage = { uid: 'STG1', id: '1', name: 'Qualification', companyId: '10', displayOrder: '10', defaultProbability: '50', active: true };
const stubProduct = {
  id: '20', uid: 'PROD1', companyId: '10', code: 'P001', name: 'Widget', status: 'ACTIVE',
  sellable: true, stockable: true, baseUnitUid: 'U1', baseUnitName: 'pcs', vatStatus: 'STANDARD',
};
const stubUnit = { id: '1', uid: 'U1', name: 'pcs', code: 'pcs', status: 'ACTIVE' };

function makeSessionStore() {
  return {
    hasPermission: vi.fn(() => true),
    isAuthenticated: signal(true),
    user: signal(null),
    permissions: signal([]),
    activeBranchUid: signal(null),
  };
}

function makeBed() {
  TestBed.configureTestingModule({
    imports: [OpportunityDetailComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      {
        provide: CrmService,
        useValue: {
          getOpportunityByUid: vi.fn(() => of(stubOpp)),
          listPipelineStages: vi.fn(() => of([stubStage])),
          updateOpportunity: vi.fn(() => of(stubOpp)),
          addOpportunityLine: vi.fn(() => of({})),
          removeOpportunityLine: vi.fn(() => of({})),
          advanceStage: vi.fn(() => of({ ...stubOpp, pipelineStageId: '2' })),
          winOpportunity: vi.fn(() => of({ ...stubOpp, opportunityStatus: 'WON' })),
          loseOpportunity: vi.fn(() => of({ ...stubOpp, opportunityStatus: 'LOST' })),
          convertOpportunity: vi.fn(() => of({ convertedDocumentKind: 'QUOTATION', convertedDocumentUid: 'Q1', convertedDocumentNumber: 'Q-001' })),
        },
      },
      {
        provide: ProductService,
        useValue: {
          list: vi.fn(() => of({ rows: [stubProduct] })),
          listProductUnits: vi.fn(() => of([stubUnit])),
        },
      },
      { provide: AlertService, useValue: { success: vi.fn(), error: vi.fn() } },
      { provide: SessionStore, useValue: makeSessionStore() },
    ],
  });
}

// ── save() numeric coercion ───────────────────────────────────────────────────

describe('OpportunityDetailComponent — save() numeric coercion', () => {
  beforeEach(() => { vi.useFakeTimers(); makeBed(); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('save() does not throw when fEstimatedValue and fWinProbability hold numbers', async () => {
    const fixture = TestBed.createComponent(OpportunityDetailComponent);
    fixture.componentRef.setInput('uid', 'OPP1');
    const comp = fixture.componentInstance;
    const svc = TestBed.inject(CrmService) as any;
    await vi.runAllTimersAsync();

    comp.fTitle.set('Updated Deal');
    // Simulate NumberValueAccessor storing JS numbers.
    comp.fEstimatedValue.set(750000 as unknown as string);
    comp.fWinProbability.set(80 as unknown as string);

    expect(() => comp.save()).not.toThrow();
    expect(svc.updateOpportunity).toHaveBeenCalledOnce();
    const body = svc.updateOpportunity.mock.calls[0][1];
    expect(body.estimatedValueAmount).toBe('750000');
    expect(body.winProbability).toBe('80');
  });
});

// ── addLine() numeric coercion ────────────────────────────────────────────────

describe('OpportunityDetailComponent — addLine() numeric coercion', () => {
  beforeEach(() => { vi.useFakeTimers(); makeBed(); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('addLine() does not throw when qty/price/discount hold numbers', async () => {
    const fixture = TestBed.createComponent(OpportunityDetailComponent);
    fixture.componentRef.setInput('uid', 'OPP1');
    const comp = fixture.componentInstance;
    const svc = TestBed.inject(CrmService) as any;
    await vi.runAllTimersAsync();

    comp.selectedProduct.set({ uid: 'PROD1', label: 'P001 — Widget' });
    comp.lineUnits.set([stubUnit as any]);
    comp.newLineUnitUid.set('U1');
    // Simulate NumberValueAccessor storing JS numbers in string-typed signals.
    comp.newLineQty.set(5 as unknown as string);
    comp.newLineUnitPrice.set(1200 as unknown as string);
    comp.newLineDiscountPct.set(10 as unknown as string);

    expect(() => comp.addLine()).not.toThrow();
    expect(svc.addOpportunityLine).toHaveBeenCalledOnce();
    const body = svc.addOpportunityLine.mock.calls[0][1];
    expect(body.estimatedQty).toBe('5');
    expect(body.estimatedUnitPriceAmount).toBe('1200');
    expect(body.lineDiscountPercent).toBe('10');
  });
});

// ── advanceStage() numeric coercion ──────────────────────────────────────────

describe('OpportunityDetailComponent — advanceStage() numeric coercion', () => {
  beforeEach(() => { vi.useFakeTimers(); makeBed(); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('advanceStage() does not throw when advanceProbOverride holds a number', async () => {
    const fixture = TestBed.createComponent(OpportunityDetailComponent);
    fixture.componentRef.setInput('uid', 'OPP1');
    const comp = fixture.componentInstance;
    const svc = TestBed.inject(CrmService) as any;
    await vi.runAllTimersAsync();

    comp.advanceStageUid.set('STG1');
    // Simulate NumberValueAccessor storing a JS number.
    comp.advanceProbOverride.set(75 as unknown as string);

    expect(() => comp.advanceStage()).not.toThrow();
    expect(svc.advanceStage).toHaveBeenCalledOnce();
    const body = svc.advanceStage.mock.calls[0][1];
    expect(body.winProbabilityOverride).toBe('75');
  });

  it('advanceStage() sends undefined winProbabilityOverride when field is null (cleared input)', async () => {
    const fixture = TestBed.createComponent(OpportunityDetailComponent);
    fixture.componentRef.setInput('uid', 'OPP1');
    const comp = fixture.componentInstance;
    const svc = TestBed.inject(CrmService) as any;
    await vi.runAllTimersAsync();

    comp.advanceStageUid.set('STG1');
    comp.advanceProbOverride.set(null as unknown as string);

    expect(() => comp.advanceStage()).not.toThrow();
    expect(svc.advanceStage).toHaveBeenCalledOnce();
    const body = svc.advanceStage.mock.calls[0][1];
    expect(body.winProbabilityOverride).toBeUndefined();
  });
});
