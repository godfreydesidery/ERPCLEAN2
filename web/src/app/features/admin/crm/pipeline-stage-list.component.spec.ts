/**
 * PipelineStageListComponent — regression specs for numeric-input string-op crash class.
 *
 * newDisplayOrder, newDefaultProbability (create form) and editDisplayOrder,
 * editDefaultProbability (inline edit) are all bound to type="number" inputs.
 * NumberValueAccessor stores a JS number; create() and saveEdit() previously called
 * .trim() on the raw number (crash). After the fix they coerce with String() first.
 */
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { CrmService } from './crm.service';
import { PipelineStageListComponent } from './pipeline-stage-list.component';

const stubCompany = { uid: 'CO1', id: '10', name: 'Main Co', status: 'ACTIVE' };
const stubStage = {
  uid: 'STG1', id: '1', name: 'Qualification', companyId: '10',
  displayOrder: '10', defaultProbability: '50', active: true,
};

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
    imports: [PipelineStageListComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      { provide: OrganisationService, useValue: { current: vi.fn(() => of({ uid: 'ORG1', id: '1', name: 'Acme Org' })) } },
      { provide: CompanyService, useValue: { list: vi.fn(() => of([stubCompany])) } },
      {
        provide: CrmService,
        useValue: {
          listPipelineStages: vi.fn(() => of([stubStage])),
          createPipelineStage: vi.fn(() => of({ ...stubStage, uid: 'STG2', name: 'Proposal' })),
          updatePipelineStage: vi.fn(() => of(stubStage)),
          deactivatePipelineStage: vi.fn(() => of({})),
        },
      },
      { provide: AlertService, useValue: { success: vi.fn(), error: vi.fn() } },
      { provide: SessionStore, useValue: makeSessionStore() },
    ],
  });
}

// ── create() numeric coercion ─────────────────────────────────────────────────

describe('PipelineStageListComponent — create() numeric coercion', () => {
  beforeEach(() => { vi.useFakeTimers(); makeBed(); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('create() does not throw when displayOrder and defaultProbability hold numbers', async () => {
    const comp = TestBed.createComponent(PipelineStageListComponent).componentInstance;
    const svc = TestBed.inject(CrmService) as any;
    await vi.runAllTimersAsync();

    comp.newName.set('Proposal');
    // Simulate NumberValueAccessor storing JS numbers in string-typed signals.
    comp.newDisplayOrder.set(20 as unknown as string);
    comp.newDefaultProbability.set(40 as unknown as string);

    expect(() => comp.create()).not.toThrow();
    expect(svc.createPipelineStage).toHaveBeenCalledOnce();
    const body = svc.createPipelineStage.mock.calls[0][0];
    expect(body.displayOrder).toBe('20');
    expect(body.defaultProbability).toBe('40');
  });

  it('create() rejects invalid displayOrder (NaN after coerce)', async () => {
    const comp = TestBed.createComponent(PipelineStageListComponent).componentInstance;
    const svc = TestBed.inject(CrmService) as any;
    await vi.runAllTimersAsync();

    comp.newName.set('Proposal');
    comp.newDisplayOrder.set('' as string);   // blank
    comp.newDefaultProbability.set('50');

    comp.create();

    expect(comp.formError()).toBeTruthy();
    expect(svc.createPipelineStage).not.toHaveBeenCalled();
  });
});

// ── saveEdit() numeric coercion ───────────────────────────────────────────────

describe('PipelineStageListComponent — saveEdit() numeric coercion', () => {
  beforeEach(() => { vi.useFakeTimers(); makeBed(); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('saveEdit() does not throw when editDisplayOrder and editDefaultProbability hold numbers', async () => {
    const comp = TestBed.createComponent(PipelineStageListComponent).componentInstance;
    const svc = TestBed.inject(CrmService) as any;
    await vi.runAllTimersAsync();

    comp.editName.set('Qualification');
    // Simulate NumberValueAccessor storing JS numbers.
    comp.editDisplayOrder.set(15 as unknown as string);
    comp.editDefaultProbability.set(55 as unknown as string);
    comp.editActive.set(true);

    expect(() => comp.saveEdit(stubStage as any)).not.toThrow();
    expect(svc.updatePipelineStage).toHaveBeenCalledOnce();
    const body = svc.updatePipelineStage.mock.calls[0][1];
    expect(body.displayOrder).toBe('15');
    expect(body.defaultProbability).toBe('55');
  });
});
