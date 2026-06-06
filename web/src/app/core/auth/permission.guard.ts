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
