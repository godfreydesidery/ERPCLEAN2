import { TestBed } from '@angular/core/testing';
import { Router, UrlTree } from '@angular/router';
import { vi } from 'vitest';
import {
  NO_ACCESS_MESSAGE,
  requireAnyPermission,
  requirePermission,
} from './permission.guard';
import { SessionStore } from './session.store';
import { ToastService } from '../feedback/toast.service';

describe('permission guards', () => {
  const granted = new Set<string>();
  const createUrlTree = vi.fn(
    (commands: unknown[]) => ({ commands }) as unknown as UrlTree,
  );
  let info: ReturnType<typeof vi.spyOn>;

  const runReq = (code: string) =>
    TestBed.runInInjectionContext(() =>
      requirePermission(code)(null as never, null as never),
    );
  const runAny = (...codes: string[]) =>
    TestBed.runInInjectionContext(() =>
      requireAnyPermission(...codes)(null as never, null as never),
    );

  beforeEach(() => {
    granted.clear();
    createUrlTree.mockClear();
    TestBed.configureTestingModule({
      providers: [
        { provide: Router, useValue: { createUrlTree } },
        { provide: SessionStore, useValue: { hasPermission: (c: string) => granted.has(c) } },
      ],
    });
    info = vi.spyOn(TestBed.inject(ToastService), 'info');
  });

  it('allows the route and shows no toast when the permission is held', () => {
    granted.add('STOCK.LOCATION.VIEW');
    expect(runReq('STOCK.LOCATION.VIEW')).toBe(true);
    expect(createUrlTree).not.toHaveBeenCalled();
    expect(info).not.toHaveBeenCalled();
  });

  it('redirects home AND shows an explanatory toast when the permission is missing', () => {
    const result = runReq('STOCK.LOCATION.VIEW');
    expect(result).not.toBe(true);
    expect(createUrlTree).toHaveBeenCalledWith(['/admin/home']);
    expect(info).toHaveBeenCalledWith(NO_ACCESS_MESSAGE);
  });

  it('requireAnyPermission passes (no toast) when ANY code is held', () => {
    granted.add('AGENT.MANAGE');
    expect(runAny('AGENT.VIEW', 'AGENT.MANAGE')).toBe(true);
    expect(info).not.toHaveBeenCalled();
  });

  it('requireAnyPermission redirects + toasts when NONE is held', () => {
    expect(runAny('AGENT.VIEW', 'AGENT.MANAGE')).not.toBe(true);
    expect(createUrlTree).toHaveBeenCalledWith(['/admin/home']);
    expect(info).toHaveBeenCalledWith(NO_ACCESS_MESSAGE);
  });
});
