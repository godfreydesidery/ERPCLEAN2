import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { SessionStore } from './session.store';

/**
 * Blocks protected routes for unauthenticated users, redirecting to the login page. Slice 2 gates on
 * the presence of an access token; permission-based gating arrives in Slice 3.
 */
export const authGuard: CanActivateFn = () => {
  const session = inject(SessionStore);
  const router = inject(Router);
  if (session.isAuthenticated()) {
    return true;
  }
  return router.createUrlTree(['/login']);
};
