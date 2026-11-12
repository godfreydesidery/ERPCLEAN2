import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ShellComponent } from './shell.component';
import { SessionStore } from '../../core/auth/session.store';
import { AuthUser } from '../../core/auth/auth.model';
import { environment } from '../../../environments/environment';

/** Owner 2026-11-07: at home in the platform organisation the sidebar shows administration only. */
const ROOT: AuthUser = {
  uid: '01HROOTROOTROOTROOTROOTROO', username: 'rootadmin', displayName: 'Root Administrator',
  isRoot: true, activeCompanyUid: 'C', activeBranchUid: 'B', hasBranch: true,
};

function shell(alias: string | null) {
  const s = TestBed.inject(SessionStore);
  s.setSession('a', 'r', ROOT);
  const f = TestBed.createComponent(ShellComponent);
  TestBed.inject(HttpTestingController)
    .match(`${environment.apiBaseUrl}/organisations/current`)
    .forEach((r) => r.flush({ uid: 'O', name: alias ?? 'Customer', alias, status: 'ACTIVE' }));
  f.detectChanges();
  return f;
}

const routes = (f: ReturnType<typeof shell>) =>
  f.componentInstance.nav().flatMap((g) => g.items.map((i) => i.route));

describe('ShellComponent — at home in the platform organisation', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [ShellComponent],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    TestBed.inject(SessionStore).clear();
  });
  afterEach(() => TestBed.inject(SessionStore).clear());

  it('shows only platform administration and says why', () => {
    const f = shell('platform');
    expect(new Set(routes(f))).toEqual(
      new Set(['/admin/platform/tenants', '/admin/users', '/admin/roles', '/admin/audit']));
    expect(f.nativeElement.querySelector('[data-testid="platform-home-banner"]')).not.toBeNull();
  });

  it('in a customer organisation, the full menu is there', () => {
    const f = shell('kilimanjaro');
    expect(routes(f)).toContain('/admin/products');
    expect(f.nativeElement.querySelector('[data-testid="platform-home-banner"]')).toBeNull();
  });

  it('inside an entered tenant, the full menu is back even though home is Platform', () => {
    TestBed.inject(SessionStore).setPlatformTenant({
      organisationUid: 'T', organisationName: 'Otapp', branchUid: 'BR', branchName: 'Dar', branchCode: 'D',
    });
    const f = shell('platform');
    expect(routes(f)).toContain('/admin/products');
    expect(f.nativeElement.querySelector('[data-testid="platform-home-banner"]')).toBeNull();
  });
});
