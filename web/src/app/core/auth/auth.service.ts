import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, of, switchMap, tap } from 'rxjs';
import { environment } from '../../../environments/environment';
import { LoginRequest, MeResponse, TokenResponse } from './auth.model';
import { SessionStore } from './session.store';

/**
 * Authentication façade: calls the auth API and keeps the SessionStore in sync. Login/refresh store
 * the new session; logout revokes server-side then clears local state. After a successful login the
 * service fetches /auth/me and stores the effective permission codes in SessionStore so the shell
 * can gate nav items without a separate call. Typed to the unwrapped DTOs (the interceptor strips
 * the envelope).
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly session = inject(SessionStore);
  private readonly base = `${environment.apiBaseUrl}/auth`;

  login(request: LoginRequest): Observable<TokenResponse> {
    return this.http
      .post<TokenResponse>(`${this.base}/login`, request)
      .pipe(
        tap((res) => this.session.setSession(res.accessToken, res.refreshToken, res.user)),
        switchMap((res) =>
          this.me().pipe(
            catchError(() => of(null)), // permission fetch failure must not break login flow
            tap(() => res), // keep the TokenResponse flowing through
            switchMap(() => of(res)),
          ),
        ),
      );
  }

  /**
   * Fetches the caller's effective profile + permissions for the current branch context and stores
   * the permission codes in SessionStore. Call on shell init to re-hydrate permissions after a page
   * refresh (the access token is still valid but permissionsSig would otherwise be empty).
   */
  me(): Observable<MeResponse> {
    return this.http.get<MeResponse>(`${this.base}/me`).pipe(
      tap((me) => this.session.setPermissions(me.permissions)),
    );
  }

  refresh(): Observable<TokenResponse> {
    return this.http
      .post<TokenResponse>(`${this.base}/refresh`, {
        refreshToken: this.session.refreshToken(),
      })
      .pipe(tap((res) => this.session.setSession(res.accessToken, res.refreshToken, res.user)));
  }

  logout(): Observable<void> {
    const refreshToken = this.session.refreshToken();
    return this.http.post<void>(`${this.base}/logout`, { refreshToken }).pipe(
      tap({
        next: () => this.session.clear(),
        error: () => this.session.clear(), // clear locally even if the server call fails
      }),
    );
  }
}
