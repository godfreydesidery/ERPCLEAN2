import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { environment } from '../../../environments/environment';
import { AuthService } from './auth.service';
import { SessionStore } from './session.store';
import { TokenResponse } from './auth.model';

describe('AuthService', () => {
  let auth: AuthService;
  let httpMock: HttpTestingController;
  let session: SessionStore;
  const base = `${environment.apiBaseUrl}/auth`;

  const sample: TokenResponse = {
    accessToken: 'access-1',
    accessTokenExpiresAt: 9999999999,
    refreshToken: 'refresh-1',
    user: {
      uid: 'U1',
      username: 'alice',
      displayName: 'Alice',
      isRoot: false,
      activeCompanyUid: 'C1',
      activeBranchUid: 'B1',
      hasBranch: true,
    },
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    auth = TestBed.inject(AuthService);
    httpMock = TestBed.inject(HttpTestingController);
    session = TestBed.inject(SessionStore);
    session.clear();
  });

  afterEach(() => httpMock.verify());

  it('stores the session on login', () => {
    auth.login({ username: 'alice', password: 'Secret123' }).subscribe();
    httpMock.expectOne(`${base}/login`).flush(sample);
    // login chains a /auth/me call to hydrate permissions; flush it so verify() passes.
    httpMock.expectOne(`${base}/me`).flush({
      uid: 'U1', username: 'alice', displayName: 'Alice',
      isRoot: false, activeCompanyUid: 'C1', activeBranchUid: 'B1',
      permissions: ['COMPANY.VIEW'],
    });

    expect(session.isAuthenticated()).toBe(true);
    expect(session.accessToken()).toBe('access-1');
    expect(session.user()?.username).toBe('alice');
    expect(session.activeBranchUid()).toBe('B1');
  });

  it('stores permissions returned by /auth/me', () => {
    auth.login({ username: 'alice', password: 'Secret123' }).subscribe();
    httpMock.expectOne(`${base}/login`).flush(sample);
    httpMock.expectOne(`${base}/me`).flush({
      uid: 'U1', username: 'alice', displayName: 'Alice',
      isRoot: false, activeCompanyUid: 'C1', activeBranchUid: 'B1',
      permissions: ['COMPANY.VIEW', 'ROLE.VIEW'],
    });

    expect(session.permissions()).toEqual(['COMPANY.VIEW', 'ROLE.VIEW']);
    expect(session.hasPermission('ROLE.VIEW')).toBe(true);
    expect(session.hasPermission('ROLE.MANAGE')).toBe(false);
  });

  it('login succeeds even when /auth/me fails', () => {
    let completed = false;
    auth.login({ username: 'alice', password: 'Secret123' }).subscribe({
      next: () => { completed = true; },
    });
    httpMock.expectOne(`${base}/login`).flush(sample);
    httpMock.expectOne(`${base}/me`).flush('', { status: 403, statusText: 'Forbidden' });

    expect(completed).toBe(true);
    expect(session.isAuthenticated()).toBe(true);
  });

  it('clears the session on logout', () => {
    auth.login({ username: 'alice', password: 'Secret123' }).subscribe();
    httpMock.expectOne(`${base}/login`).flush(sample);
    httpMock.expectOne(`${base}/me`).flush({
      uid: 'U1', username: 'alice', displayName: 'Alice',
      isRoot: false, activeCompanyUid: 'C1', activeBranchUid: 'B1',
      permissions: [],
    });

    auth.logout().subscribe();
    httpMock.expectOne(`${base}/logout`).flush(null);

    expect(session.isAuthenticated()).toBe(false);
    expect(session.user()).toBeNull();
  });
});
