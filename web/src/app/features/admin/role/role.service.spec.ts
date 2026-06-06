import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { environment } from '../../../../environments/environment';
import { RoleService } from './role.service';

describe('RoleService', () => {
  let service: RoleService;
  let httpMock: HttpTestingController;
  const base = `${environment.apiBaseUrl}/roles`;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(RoleService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('lists roles via GET /roles', () => {
    service.list().subscribe();
    const req = httpMock.expectOne(base);
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('gets a single role by uid', () => {
    service.get('R1').subscribe();
    const req = httpMock.expectOne(`${base}/uid/R1`);
    expect(req.request.method).toBe('GET');
    req.flush({});
  });

  it('creates a role via POST /roles', () => {
    service.create({ code: 'ADMIN', name: 'Administrator' }).subscribe();
    const req = httpMock.expectOne(base);
    expect(req.request.method).toBe('POST');
    expect(req.request.body.code).toBe('ADMIN');
    req.flush({});
  });

  it('sets permissions via PUT /roles/uid/{uid}/permissions', () => {
    service.setPermissions('R1', { permissionCodes: ['COMPANY.VIEW', 'ROLE.VIEW'] }).subscribe();
    const req = httpMock.expectOne(`${base}/uid/R1/permissions`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body.permissionCodes).toEqual(['COMPANY.VIEW', 'ROLE.VIEW']);
    req.flush({});
  });

  it('lists all available permissions via GET /roles/permissions', () => {
    service.listPermissions().subscribe();
    const req = httpMock.expectOne(`${base}/permissions`);
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('archives a role via DELETE /roles/uid/{uid}', () => {
    service.archive('R1').subscribe();
    const req = httpMock.expectOne(`${base}/uid/R1`);
    expect(req.request.method).toBe('DELETE');
    req.flush(null, { status: 204, statusText: 'No Content' });
  });
});
