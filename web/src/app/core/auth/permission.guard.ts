import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { SessionStore } from './session.store';

/**
 * Route guard factory: allows the route only if the user holds {@code code} (root always passes,
 * via {@link SessionStore.hasPermission}). A user who lacks it is redirected to the neutral admin
 * home rather than landing on a screen they can't use — so a permission gap is a redirect, never a
 * 403 error pop-up. Pair with the nav, which already hides items the user can't see; this guard is
 * the backstop for direct navigation / the default redirect.
 *
 * Usage: {@code canActivate: [requirePermission('COMPANY.VIEW')]}.
 */
export function requirePermission(code: string): CanActivateFn {
  return () => {
    const session = inject(SessionStore);
    const router = inject(Router);
    return session.hasPermission(code) ? true : router.createUrlTree(['/admin/home']);
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
    return codes.some((c) => session.hasPermission(c)) ? true : router.createUrlTree(['/admin/home']);
  };
}
