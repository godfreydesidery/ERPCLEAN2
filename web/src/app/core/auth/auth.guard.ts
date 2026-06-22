import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { SessionStore } from './session.store';

/**
 * Blocks protected routes for unauthenticated users, redirecting to the login page. Slice 2 gates on
 * the presence of an access token; permission-based gating arrives in Slice 3.
 *
 * The attempted URL is carried as a `returnUrl` query param so the login screen can send the user
 * back where they were heading (e.g. a bookmarked page hit while logged out, or a reload after the
 * session was lost) instead of dumping them on the dashboard. The 401 interceptor carries the same
 * param for the mid-session timeout case — both bounce points must, since only one fires per scenario.
 */
export const authGuard: CanActivateFn = (_route, state) => {
  const session = inject(SessionStore);
  const router = inject(Router);
  if (session.isAuthenticated()) {
    return true;
  }
  return router.createUrlTree(['/login'], { queryParams: { returnUrl: state.url } });
};
