import { HttpContextToken } from '@angular/common/http';

/**
 * Set this token to `true` on a request to bypass the {@link apiResponseInterceptor} envelope
 * unwrap. The caller receives the full {@code ApiResponse<T>} body (data + meta + errors) instead
 * of the raw `T`. Required for paginated endpoints where `meta` carries paging info that would
 * otherwise be lost when the interceptor replaces the body with `.data`.
 *
 * Usage:
 * ```ts
 * this.http.get<ApiResponse<Foo[]>>(url, {
 *   context: new HttpContext().set(SKIP_UNWRAP, true),
 * });
 * ```
 */
export const SKIP_UNWRAP = new HttpContextToken<boolean>(() => false);

/**
 * Set this token to `true` on a request when the calling screen surfaces that request's errors
 * itself (e.g. an inline form banner or a per-field message). The {@link authErrorInterceptor}
 * then stays SILENT for this request — it raises NEITHER the blocking "Something went wrong" modal
 * NOR the calm error toast — so the user sees only the screen's own message instead of a duplicate
 * global one.
 *
 * Scope: this only suppresses the generic error NOTIFICATION. The interceptor still clears a dead
 * session and redirects on 401, and the rejected response still propagates to the caller's
 * `error` handler. Use it on mutations that already render their own validation feedback (receive
 * goods, post journal, record payment, …).
 *
 * Usage:
 * ```ts
 * this.http.post<Foo>(url, body, {
 *   context: new HttpContext().set(SILENT_ERROR, true),
 * });
 * ```
 */
export const SILENT_ERROR = new HttpContextToken<boolean>(() => false);
