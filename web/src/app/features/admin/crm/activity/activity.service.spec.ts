import { describe, it, expect, vi, beforeEach } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { HttpClient, HttpContext, HttpParams } from '@angular/common/http';
import { of } from 'rxjs';
import { ActivityService } from './activity.service';
import { SKIP_UNWRAP } from '../../../../core/api/http-context.tokens';
import { ActivityDto, CreateActivityRequest } from './activity.model';

const MOCK_ACTIVITY: ActivityDto = {
  id: '1', uid: 'act-uid-1', companyId: 'co-1', branchId: 'br-1',
  activityNumber: 'ACT-001', activityType: 'TASK',
  leadId: null, opportunityId: null, subject: 'Follow up',
  body: null, occurredAt: null, dueDate: '2026-06-20',
  assigneeUserId: null, done: false, doneAt: null,
  status: 'ACTIVE', version: '1',
  createdAt: '2026-06-13T10:00:00Z', createdBy: null, updatedAt: null, updatedBy: null,
};

const MOCK_PAGE_ENV = {
  data: [MOCK_ACTIVITY],
  errors: [],
  meta: { page: 0, size: 20, totalElements: 1, totalPages: 1, hasNext: false },
};

describe('ActivityService', () => {
  let service: ActivityService;
  let httpMock: { get: ReturnType<typeof vi.fn>; post: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    httpMock = {
      get: vi.fn(),
      post: vi.fn(),
    };

    TestBed.configureTestingModule({
      providers: [
        ActivityService,
        { provide: HttpClient, useValue: httpMock },
      ],
    });

    service = TestBed.inject(ActivityService);
  });

  it('create POSTs and returns unwrapped ActivityDto', () => {
    httpMock.post.mockReturnValue(of(MOCK_ACTIVITY));
    const req: CreateActivityRequest = {
      companyId: 'co-1', activityType: 'TASK', subject: 'Follow up', leadUid: 'lead-uid-1',
    };
    let result: ActivityDto | undefined;
    service.create(req).subscribe((v) => (result = v));
    expect(result).toEqual(MOCK_ACTIVITY);
  });

  it('getByUid GETs from /uid/:uid and returns ActivityDto', () => {
    httpMock.get.mockReturnValue(of(MOCK_ACTIVITY));
    let result: ActivityDto | undefined;
    service.getByUid('act-uid-1').subscribe((v) => (result = v));
    expect(httpMock.get).toHaveBeenCalledWith(expect.stringContaining('/uid/act-uid-1'));
    expect(result).toEqual(MOCK_ACTIVITY);
  });

  it('listByLead uses SKIP_UNWRAP and maps to {rows, meta}', () => {
    httpMock.get.mockReturnValue(of(MOCK_PAGE_ENV));
    let result: { rows: ActivityDto[] } | undefined;
    service.listByLead('lead-uid-1').subscribe((v) => (result = v));
    expect(httpMock.get).toHaveBeenCalledWith(
      expect.stringContaining('/by-lead/lead-uid-1'),
      expect.objectContaining({ context: expect.any(HttpContext) }),
    );
    expect(result?.rows.length).toBe(1);
  });

  it('listByOpportunity uses SKIP_UNWRAP and maps to {rows, meta}', () => {
    httpMock.get.mockReturnValue(of(MOCK_PAGE_ENV));
    let result: { rows: ActivityDto[] } | undefined;
    service.listByOpportunity('opp-uid-1').subscribe((v) => (result = v));
    expect(httpMock.get).toHaveBeenCalledWith(
      expect.stringContaining('/by-opportunity/opp-uid-1'),
      expect.any(Object),
    );
    expect(result?.rows.length).toBe(1);
  });

  it('openTasks includes companyId param and uses SKIP_UNWRAP', () => {
    httpMock.get.mockReturnValue(of(MOCK_PAGE_ENV));
    service.openTasks('co-1').subscribe();
    const [, options] = httpMock.get.mock.calls[0];
    const params = (options as { params: HttpParams }).params;
    expect(params.get('companyId')).toBe('co-1');
  });

  it('complete POSTs to /uid/:uid/complete', () => {
    httpMock.post.mockReturnValue(of({ ...MOCK_ACTIVITY, done: true }));
    let result: ActivityDto | undefined;
    service.complete('act-uid-1').subscribe((v) => (result = v));
    expect(httpMock.post).toHaveBeenCalledWith(
      expect.stringContaining('/uid/act-uid-1/complete'),
      {},
    );
    expect(result?.done).toBe(true);
  });
});
