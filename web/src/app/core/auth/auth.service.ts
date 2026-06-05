import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, tap } from 'rxjs';
import { environment } from '../../../environments/environment';
import { LoginRequest, TokenResponse } from './auth.model';
import { SessionStore } from './session.store';

/**
 * Authentication façade: calls the auth API and keeps the SessionStore in sync. Login/refresh store
 * the new session; logout revokes server-side then clears local state. Typed to the unwrapped
 * TokenResponse (the interceptor strips the envelope).
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly session = inject(SessionStore);
  private readonly base = `${environment.apiBaseUrl}/auth`;

  login(request: LoginRequest): Observable<TokenResponse> {
    return this.http
      .post<TokenResponse>(`${this.base}/login`, request)
      .pipe(tap((res) => this.session.setSession(res.accessToken, res.refreshToken, res.user)));
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
