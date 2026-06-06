import { HttpErrorResponse, HttpInterceptorFn, HttpResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, map, throwError } from 'rxjs';
import { environment } from '../../../environments/environment';
import { SessionStore } from '../auth/session.store';
import { ApiResponse } from './api-response.model';

/**
 * Endpoints that are reached WITHOUT a session and must never carry a bearer token. Attaching a
 * stale/expired token here makes the resource-server filter reject the call with 401 before the
 * controller runs — which silently breaks login when an old token is still in storage.
 */
const UNAUTHENTICATED_PATHS = ['/auth/login', '/auth/refresh'];

/**
 * Attaches the JWT (Authorization: Bearer) and the active-branch override header (X-Branch-Uid)
 * to every API request (PROJECT-CONVENTIONS §3.1, ARCHITECTURE §5). Only touches calls to our API
 * base; leaves asset/other requests alone. The unauthenticated auth endpoints (login/refresh) are
 * skipped so a leftover token can't poison a fresh sign-in.
 */
export const authHeaderInterceptor: HttpInterceptorFn = (req, next) => {
  if (!req.url.startsWith(environment.apiBaseUrl)) {
    return next(req);
  }
  if (UNAUTHENTICATED_PATHS.some((path) => req.url.includes(path))) {
    return next(req);
  }
  const session = inject(SessionStore);
  const headers: Record<string, string> = {};
  const token = session.accessToken();
  if (token) {
    headers['Authorization'] = `Bearer ${token}`;
  }
  const branchUid = session.activeBranchUid();
  if (branchUid) {
    headers['X-Branch-Uid'] = branchUid;
  }
  return next(Object.keys(headers).length ? req.clone({ setHeaders: headers }) : req);
};

/**
 * Unwraps the backend `ApiResponse<T>` envelope so feature services receive the raw `T`
 * (PROJECT-CONVENTIONS §3.1). Only applies to our API responses; other responses pass through.
 */
export const apiResponseInterceptor: HttpInterceptorFn = (req, next) => {
  if (!req.url.startsWith(environment.apiBaseUrl)) {
    return next(req);
  }
  return next(req).pipe(
    map((event) => {
      if (event instanceof HttpResponse && isEnvelope(event.body)) {
        return event.clone({ body: (event.body as ApiResponse<unknown>).data });
      }
      return event;
    }),
  );
};

/**
 * Catches a 401 from an authenticated API call — the signal that the stored session is expired or
 * invalid — clears the dead session and sends the user to the login page. Without this, a stale
 * token in storage leaves the user stranded in a half-logged-in shell (every call 401s, the API
 * badge shows "error") instead of being bounced to a clean sign-in. The unauthenticated auth
 * endpoints (login/refresh) are exempt: a 401 there is a normal "bad credentials"/"expired refresh"
 * result the caller handles itself, not a dead session to clear.
 */
export const authErrorInterceptor: HttpInterceptorFn = (req, next) => {
  if (!req.url.startsWith(environment.apiBaseUrl)) {
    return next(req);
  }
  const session = inject(SessionStore);
  const router = inject(Router);
  return next(req).pipe(
    catchError((err: unknown) => {
      const isAuthEndpoint = UNAUTHENTICATED_PATHS.some((path) => req.url.includes(path));
      if (err instanceof HttpErrorResponse && err.status === 401 && !isAuthEndpoint) {
        if (session.isAuthenticated()) {
          session.clear();
          void router.navigateByUrl('/login');
        }
      }
      return throwError(() => err);
    }),
  );
};

function isEnvelope(body: unknown): body is ApiResponse<unknown> {
  return (
    typeof body === 'object' &&
    body !== null &&
    'data' in body &&
    'errors' in body &&
    Array.isArray((body as ApiResponse<unknown>).errors)
  );
}
