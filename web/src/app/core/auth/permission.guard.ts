import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { SessionStore } from './session.store';
import { ToastService } from '../feedback/toast.service';

/**
 * Shown when a permission guard blocks navigation. A calm, non-alarming toast (not the red
 * "Something went wrong" modal) so the user understands WHY they landed back on the home screen
 * instead of being silently bounced with no feedback.
 */
export const NO_ACCESS_MESSAGE =
  "You don't have access to that screen — it isn't part of your role. Ask your administrator if you need it.";

/**
 * Route guard factory: allows the route only if the user holds {@code code} (root always passes,
 * via {@link SessionStore.hasPermission}). A user who lacks it is sent to the neutral admin home
 * — but with a brief toast explaining why, so the redirect is never silent/confusing. Pair with the
 * nav, which already hides items the user can't see; this guard is the backstop for direct
 * navigation (typed URL, stale bookmark, a quick-launch tile) and the default redirect.
 *
 * Usage: {@code canActivate: [requirePermission('COMPANY.VIEW')]}.
 */
export function requirePermission(code: string): CanActivateFn {
  return () => {
    const session = inject(SessionStore);
    const router = inject(Router);
    const toasts = inject(ToastService);
    if (session.hasPermission(code)) return true;
    toasts.info(NO_ACCESS_MESSAGE);
    return router.createUrlTree(['/admin/home']);
  };
}

/**
 * Allows the route if the user holds ANY of {@code codes} (root always passes). Use where two atomic
 * permissions both grant access to a screen — e.g. a detail route reachable by a VIEW role (read-only)
 * OR a MANAGE role (who acts on it), so a MANAGE-only role is not bounced from a VIEW-guarded page.
 */
export function requireAnyPermission(...codes: string[]): CanActivateFn {
  return () => {
    const session = inject(SessionStore);
    const router = inject(Router);
    const toasts = inject(ToastService);
    if (codes.some((c) => session.hasPermission(c))) return true;
    toasts.info(NO_ACCESS_MESSAGE);
    return router.createUrlTree(['/admin/home']);
  };
}

/**
 * Allows the route only if the user holds EVERY one of {@code codes} (root always passes). Use where
 * the backend endpoint the screen depends on is itself gated on a conjunction — e.g. Item Inquiry's
 * {@code @perm.has('PRODUCT.VIEW') and @perm.has('STOCK.VIEW')}.
 *
 * <p>Guarding such a screen on only one of the two codes is the parity bug that keeps recurring: the
 * page opens, its one request 403s, and the user reads a permissions problem as a broken screen.
 */
export function requireAllPermissions(...codes: string[]): CanActivateFn {
  return () => {
    const session = inject(SessionStore);
    const router = inject(Router);
    const toasts = inject(ToastService);
    if (codes.every((c) => session.hasPermission(c))) return true;
    toasts.info(NO_ACCESS_MESSAGE);
    return router.createUrlTree(['/admin/home']);
  };
}
