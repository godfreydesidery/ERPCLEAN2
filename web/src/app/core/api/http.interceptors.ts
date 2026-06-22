import { HttpErrorResponse, HttpInterceptorFn, HttpResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, finalize, map, throwError } from 'rxjs';
import { environment } from '../../../environments/environment';
import { SessionStore } from '../auth/session.store';
import { AlertService } from '../feedback/alert.service';
import { LoadingService } from '../feedback/loading.service';
import { ToastService } from '../feedback/toast.service';
import { ApiResponse } from './api-response.model';
import { SILENT_ERROR, SKIP_UNWRAP } from './http-context.tokens';

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
 *
 * Callers that need the full envelope (e.g. paginated endpoints where `meta` carries paging info)
 * can opt out by setting the {@link SKIP_UNWRAP} context token to `true` on their request.
 */
export const apiResponseInterceptor: HttpInterceptorFn = (req, next) => {
  if (!req.url.startsWith(environment.apiBaseUrl)) {
    return next(req);
  }
  if (req.context.get(SKIP_UNWRAP)) {
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
  const toasts = inject(ToastService);
  const alerts = inject(AlertService);
  return next(req).pipe(
    catchError((err: unknown) => {
      const isAuthEndpoint = UNAUTHENTICATED_PATHS.some((path) => req.url.includes(path));
      if (err instanceof HttpErrorResponse && err.status === 401 && !isAuthEndpoint) {
        if (session.isAuthenticated()) {
          session.clear();
          // Mid-redirect to /login — a lightweight toast, not a blocking modal.
          toasts.error('Your session has expired. Please sign in again.');
          // Preserve the page the user was on so login can send them back there, not to the
          // dashboard. replaceUrl keeps the dead page out of history so Back doesn't loop.
          void router.navigate(['/login'], {
            queryParams: { returnUrl: router.url },
            replaceUrl: true,
          });
        }
        return throwError(() => err);
      }
      // A 403 is an authorization fact, not a system failure — do NOT pop the red modal. The
      // screen renders its own calm "no permission" state, and route guards keep users off pages
      // they can't use. (The rejected request still propagates so the component's error handler runs.)
      if (err instanceof HttpErrorResponse && err.status === 403) {
        return throwError(() => err);
      }
      // Surface every other API error — but match the SEVERITY to the kind of failure, and let a
      // screen that shows its own message opt out entirely (SILENT_ERROR).
      //   • Expected, user-correctable business validations (400/409/422) → a calm, non-blocking
      //     toast. These are normal outcomes ("over-receipt: reduce the quantity"), not crashes,
      //     so they must NOT wear the alarming "Something went wrong" modal.
      //   • Genuine/unexpected failures (5xx, network/0, anything else) → the centered modal the
      //     user must acknowledge, so a real fault can't be missed.
      // Auth endpoints (login/refresh) are exempt — the login form shows its own inline message.
      if (err instanceof HttpErrorResponse && !isAuthEndpoint && !req.context.get(SILENT_ERROR)) {
        if (isBusinessValidation(err.status)) {
          toasts.error(errorMessageOf(err));
        } else {
          alerts.error('Something went wrong', errorMessageOf(err));
        }
      }
      return throwError(() => err);
    }),
  );
};

/**
 * Expected, user-correctable business validations the backend returns for a well-formed request
 * that breaks a rule: 400 (bad input / `@Valid`), 409 (state conflict, e.g. over-receipt), 422
 * (business rule). These are surfaced as a calm toast, not the "Something went wrong" modal.
 * Everything else (5xx, network/0) is treated as a genuine failure.
 */
function isBusinessValidation(status: number): boolean {
  return status === 400 || status === 409 || status === 422;
}

/** A user-safe message from a failed response: the envelope's first error, else a status fallback. */
function errorMessageOf(err: HttpErrorResponse): string {
  const errors = (err.error as ApiResponse<unknown> | null)?.errors;
  if (Array.isArray(errors) && errors.length > 0) {
    return errors[0];
  }
  if (err.status === 0) {
    return 'Cannot reach the server — check your connection and try again.';
  }
  if (err.status === 403) {
    return 'You do not have permission to perform this action.';
  }
  return 'Something went wrong. Please try again.';
}

/**
 * Brackets every API request with the {@link LoadingService} counter (via finalize, so it always
 * decrements — success, error, or cancel) to drive the shell's global top progress bar.
 */
export const loadingInterceptor: HttpInterceptorFn = (req, next) => {
  if (!req.url.startsWith(environment.apiBaseUrl)) {
    return next(req);
  }
  const loading = inject(LoadingService);
  loading.begin();
  return next(req).pipe(finalize(() => loading.end()));
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
