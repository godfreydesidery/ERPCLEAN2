import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { SKIP_UNWRAP } from '../../../core/api/http-context.tokens';
import { environment } from '../../../../environments/environment';
import { AuditPage, AuditService } from './audit.service';

describe('AuditService', () => {
  let service: AuditService;
  let httpMock: HttpTestingController;
  const base = `${environment.apiBaseUrl}/audit`;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(AuditService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('sends GET /audit with page and size params', () => {
    service.search({}, 0, 50).subscribe();

    const req = httpMock.expectOne((r) => r.url === base);
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('page')).toBe('0');
    expect(req.request.params.get('size')).toBe('50');
    req.flush({ data: [], errors: [], meta: null });
  });

  it('sets SKIP_UNWRAP context token so the full envelope is received', () => {
    service.search({ action: 'LOGIN.SUCCESS' }, 1, 50).subscribe();
    const req = httpMock.expectOne((r) => r.url === base);
    // The SKIP_UNWRAP token must be true — this is what bypasses the apiResponseInterceptor.
    expect(req.request.context.get(SKIP_UNWRAP)).toBe(true);
    req.flush({ data: [], errors: [], meta: { page: 1, size: 50, totalElements: 0, totalPages: 0, hasNext: false } });
  });

  it('returns rows and meta from the envelope', () => {
    const mockEntry = {
      actorUid: 'U1', actorUsername: 'alice', action: 'USER.CREATE',
      targetType: 'USER', targetUid: 'U2', companyUid: 'C1', branchUid: 'B1',
      detail: '{"foo":"bar"}', at: '2024-01-01T10:00:00Z', ip: '127.0.0.1',
    };
    const mockMeta = { page: 0, size: 50, totalElements: 1, totalPages: 1, hasNext: false };

    let result: AuditPage | undefined;
    service.search({}, 0, 50).subscribe((r) => { result = r; });

    const req = httpMock.expectOne((r) => r.url === base);
    req.flush({ data: [mockEntry], errors: [], meta: mockMeta });

    expect(result!.rows).toHaveLength(1);
    expect(result!.rows[0].actorUsername).toBe('alice');
    expect(result!.meta.totalElements).toBe(1);
    expect(result!.meta.hasNext).toBe(false);
  });

  it('omits blank filter params from the request', () => {
    service.search({ action: '', actorUid: '  ' }, 0, 50).subscribe();
    const req = httpMock.expectOne((r) => r.url === base);
    expect(req.request.params.has('action')).toBe(false);
    expect(req.request.params.has('actorUid')).toBe(false);
    req.flush({ data: [], errors: [], meta: null });
  });

  it('includes non-blank filter params in the request', () => {
    service.search({ action: 'ROLE.GRANT', targetType: 'ROLE' }, 0, 50).subscribe();
    const req = httpMock.expectOne((r) => r.url === base);
    expect(req.request.params.get('action')).toBe('ROLE.GRANT');
    expect(req.request.params.get('targetType')).toBe('ROLE');
    req.flush({ data: [], errors: [], meta: null });
  });

  it('falls back to a synthesised meta when backend returns null meta', () => {
    let result: AuditPage | undefined;
    service.search({}, 0, 50).subscribe((r) => { result = r; });

    const req = httpMock.expectOne((r) => r.url === base);
    req.flush({
      data: [{
        actorUid: null, actorUsername: null, action: 'LOGIN.FAIL',
        targetType: null, targetUid: null, companyUid: null, branchUid: null,
        detail: null, at: '2024-01-01T00:00:00Z', ip: null,
      }],
      errors: [],
      meta: null,
    });

    expect(result!.meta).toEqual({ page: 0, size: 50, totalElements: 1, totalPages: 1, hasNext: false });
  });
});
