import { HttpInterceptorFn, HttpResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { map } from 'rxjs';
import { environment } from '../../../environments/environment';
import { SessionStore } from '../auth/session.store';
import { ApiResponse } from './api-response.model';

/**
 * Attaches the JWT (Authorization: Bearer) and the active-branch override header (X-Branch-Uid)
 * to every API request (PROJECT-CONVENTIONS §3.1, ARCHITECTURE §5). Only touches calls to our API
 * base; leaves asset/other requests alone.
 */
export const authHeaderInterceptor: HttpInterceptorFn = (req, next) => {
  if (!req.url.startsWith(environment.apiBaseUrl)) {
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

function isEnvelope(body: unknown): body is ApiResponse<unknown> {
  return (
    typeof body === 'object' &&
    body !== null &&
    'data' in body &&
    'errors' in body &&
    Array.isArray((body as ApiResponse<unknown>).errors)
  );
}
