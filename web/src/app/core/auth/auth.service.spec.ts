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

    expect(session.isAuthenticated()).toBe(true);
    expect(session.accessToken()).toBe('access-1');
    expect(session.user()?.username).toBe('alice');
    expect(session.activeBranchUid()).toBe('B1');
  });

  it('clears the session on logout', () => {
    auth.login({ username: 'alice', password: 'Secret123' }).subscribe();
    httpMock.expectOne(`${base}/login`).flush(sample);

    auth.logout().subscribe();
    httpMock.expectOne(`${base}/logout`).flush(null);

    expect(session.isAuthenticated()).toBe(false);
    expect(session.user()).toBeNull();
  });
});
