import { TestBed } from '@angular/core/testing';
import {
  ActivatedRouteSnapshot,
  Router,
  RouterStateSnapshot,
  UrlTree,
} from '@angular/router';
import { vi } from 'vitest';
import { authGuard } from './auth.guard';
import { SessionStore } from './session.store';

describe('authGuard', () => {
  let session: SessionStore;
  const createUrlTree = vi.fn(
    (commands: unknown[], extras?: unknown) => ({ commands, extras }) as unknown as UrlTree,
  );

  /** Invoke the guard in an injection context with a given attempted URL. */
  const run = (url: string) =>
    TestBed.runInInjectionContext(() =>
      authGuard({} as ActivatedRouteSnapshot, { url } as RouterStateSnapshot),
    );

  beforeEach(() => {
    createUrlTree.mockClear();
    TestBed.configureTestingModule({
      providers: [{ provide: Router, useValue: { createUrlTree } }],
    });
    session = TestBed.inject(SessionStore);
    session.clear();
  });

  it('allows the route when authenticated', () => {
    session.setAccessToken('tok');
    expect(run('/admin/companies')).toBe(true);
    expect(createUrlTree).not.toHaveBeenCalled();
  });

  it('redirects to /login carrying the attempted url as returnUrl when unauthenticated', () => {
    const result = run('/admin/purchase-orders/uid/01ABC');
    expect(result).not.toBe(true);
    expect(createUrlTree).toHaveBeenCalledWith(['/login'], {
      queryParams: { returnUrl: '/admin/purchase-orders/uid/01ABC' },
    });
  });
});
