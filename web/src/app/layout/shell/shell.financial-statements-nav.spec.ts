import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ShellComponent } from './shell.component';
import { SessionStore } from '../../core/auth/session.store';
import { AuthUser } from '../../core/auth/auth.model';

/**
 * Sidebar contract for the Statement of Changes in Equity and the Financial Ratios: each is linked,
 * and each is gated on exactly what its route guard and endpoint require — the equity statement on
 * REPORT.BS.VIEW, the ratios on BOTH REPORT.PL.VIEW and REPORT.BS.VIEW. Kept in its own file so the
 * shared shell spec is not edited by every report that lands.
 */

const NON_ROOT_USER: AuthUser = {
  uid: '01HZZZZZZZZZZZZZZZZZZZZZZZ',
  username: 'accountant',
  displayName: 'Amina',
  isRoot: false,
  activeCompanyUid: '01HAAAAAAAAAAAAAAAAAAAAAAA',
  activeBranchUid: '01HBBBBBBBBBBBBBBBBBBBBBBB',
  hasBranch: true,
};

function routesFor(permissions: readonly string[]): string[] {
  const session = TestBed.inject(SessionStore);
  session.setSession('access-token', 'refresh-token', NON_ROOT_USER);
  session.setPermissions([...permissions]);
  const fixture = TestBed.createComponent(ShellComponent);
  fixture.detectChanges();
  return fixture.componentInstance.nav().flatMap((g) => g.items.map((i) => i.route));
}

describe('ShellComponent — financial statements nav', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [ShellComponent],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    TestBed.inject(SessionStore).clear();
  });

  afterEach(() => TestBed.inject(SessionStore).clear());

  it('links Changes in Equity for a REPORT.BS.VIEW holder, and hides it otherwise', () => {
    expect(routesFor(['REPORT.BS.VIEW'])).toContain('/admin/reporting/changes-in-equity');
    TestBed.inject(SessionStore).clear();
    expect(routesFor(['REPORT.PL.VIEW'])).not.toContain('/admin/reporting/changes-in-equity');
  });

  it('links Financial Ratios only when BOTH statement permissions are held', () => {
    expect(routesFor(['REPORT.PL.VIEW', 'REPORT.BS.VIEW'])).toContain('/admin/reporting/ratios');
    TestBed.inject(SessionStore).clear();
    expect(routesFor(['REPORT.PL.VIEW'])).not.toContain('/admin/reporting/ratios');
    TestBed.inject(SessionStore).clear();
    expect(routesFor(['REPORT.BS.VIEW'])).not.toContain('/admin/reporting/ratios');
  });
});
