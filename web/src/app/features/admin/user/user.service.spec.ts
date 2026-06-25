import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { environment } from '../../../../environments/environment';
import { UserService } from './user.service';

describe('UserService', () => {
  let service: UserService;
  let httpMock: HttpTestingController;
  const base = `${environment.apiBaseUrl}/users`;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(UserService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('lists users via GET /users', () => {
    service.list().subscribe();
    const req = httpMock.expectOne(base);
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('lists org-wide users via GET /users/org-wide', () => {
    service.listOrgWide().subscribe();
    const req = httpMock.expectOne(`${base}/org-wide`);
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('gets a single user by uid', () => {
    service.get('U1').subscribe();
    const req = httpMock.expectOne(`${base}/uid/U1`);
    expect(req.request.method).toBe('GET');
    req.flush({});
  });

  it('creates a user via POST /users', () => {
    service.create({ username: 'jdoe', displayName: 'John Doe', password: 'secret' }).subscribe();
    const req = httpMock.expectOne(base);
    expect(req.request.method).toBe('POST');
    expect(req.request.body.username).toBe('jdoe');
    req.flush({}, { status: 201, statusText: 'Created' });
  });

  it('disables a user via PUT /users/uid/{uid}/disable', () => {
    service.disable('U1').subscribe();
    const req = httpMock.expectOne(`${base}/uid/U1/disable`);
    expect(req.request.method).toBe('PUT');
    req.flush(null, { status: 204, statusText: 'No Content' });
  });

  it('enables a user via PUT /users/uid/{uid}/enable', () => {
    service.enable('U1').subscribe();
    const req = httpMock.expectOne(`${base}/uid/U1/enable`);
    expect(req.request.method).toBe('PUT');
    req.flush(null, { status: 204, statusText: 'No Content' });
  });

  it('unlocks a user via PUT /users/uid/{uid}/unlock', () => {
    service.unlock('U1').subscribe();
    const req = httpMock.expectOne(`${base}/uid/U1/unlock`);
    expect(req.request.method).toBe('PUT');
    req.flush(null, { status: 204, statusText: 'No Content' });
  });

  it('sets password via PUT /users/uid/{uid}/password', () => {
    service.setPassword('U1', { password: 'newpass' }).subscribe();
    const req = httpMock.expectOne(`${base}/uid/U1/password`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body.password).toBe('newpass');
    req.flush(null, { status: 204, statusText: 'No Content' });
  });

  it('updates a user via PUT /users/uid/{uid}', () => {
    service.update('U1', { displayName: 'Jane Doe' }).subscribe();
    const req = httpMock.expectOne(`${base}/uid/U1`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body.displayName).toBe('Jane Doe');
    req.flush({});
  });
});
