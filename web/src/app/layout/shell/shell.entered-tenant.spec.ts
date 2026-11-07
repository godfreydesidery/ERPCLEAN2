import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ShellComponent } from './shell.component';
import { SessionStore } from '../../core/auth/session.store';
import { AuthUser } from '../../core/auth/auth.model';
import { assertA11y } from '../../../testing/a11y.helper';

/** Owner 2026-11-07: the platform operator inside another tenant always sees where it is. */
const ROOT: AuthUser = {
  uid: '01HROOTROOTROOTROOTROOTROO',
  username: 'rootadmin',
  displayName: 'Root Administrator',
  isRoot: true,
  activeCompanyUid: '01HAAAAAAAAAAAAAAAAAAAAAAA',
  activeBranchUid: '01HBBBBBBBBBBBBBBBBBBBBBBB',
  hasBranch: true,
};
const ENTERED = {
  organisationUid: '01HORGBETAORGBETAORGBETAOR',
  organisationName: 'Beta Traders Group',
  branchUid: '01HBETABRANCHBETABRANCHBET',
  branchName: 'Beta Head Office',
  branchCode: 'BHQ',
};

describe('ShellComponent — platform operator inside a tenant', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [ShellComponent],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    const s = TestBed.inject(SessionStore);
    s.clear();
    s.setSession('a', 'r', ROOT);
  });

  afterEach(() => TestBed.inject(SessionStore).clear());

  it('shows no banner at home', () => {
    const f = TestBed.createComponent(ShellComponent);
    f.detectChanges();
    expect(f.nativeElement.querySelector('[data-testid="entered-tenant-banner"]')).toBeNull();
  });

  it('names the tenant and branch, and shows the entered branch on the branch chip', () => {
    const s = TestBed.inject(SessionStore);
    s.setPlatformTenant(ENTERED);
    s.setActiveBranchUid(ENTERED.branchUid);
    const f = TestBed.createComponent(ShellComponent);
    f.detectChanges();
    const banner: HTMLElement = f.nativeElement.querySelector('[data-testid="entered-tenant-banner"]');
    expect(banner.textContent).toContain('Beta Traders Group');
    expect(banner.textContent).toContain('recorded in their audit log');
    expect(f.componentInstance.activeBranch()?.branchName).toBe('Beta Head Office');
  });

  it('Return to my organisation clears the entered tenant and the branch override', () => {
    const s = TestBed.inject(SessionStore);
    s.setPlatformTenant(ENTERED);
    s.setActiveBranchUid(ENTERED.branchUid);
    const f = TestBed.createComponent(ShellComponent);
    const nav = vi.spyOn(f.componentInstance as unknown as { navigate: (u: string) => void }, 'navigate')
      .mockImplementation(() => undefined);
    f.detectChanges();
    (f.nativeElement.querySelector('[data-testid="entered-tenant-banner"] button') as HTMLButtonElement).click();
    expect(s.platformTenant()).toBeNull();
    expect(s.activeBranchUid()).toBeNull();
    expect(nav).toHaveBeenCalledWith('/admin/platform/tenants');
  });

  it('signing out (session clear) forgets the entered tenant', () => {
    const s = TestBed.inject(SessionStore);
    s.setPlatformTenant(ENTERED);
    s.clear();
    expect(s.platformTenant()).toBeNull();
    expect(sessionStorage.getItem('erp.platformTenant')).toBeNull();
  });

  it('has no axe violations with the banner shown', async () => {
    const s = TestBed.inject(SessionStore);
    s.setPlatformTenant(ENTERED);
    s.setActiveBranchUid(ENTERED.branchUid);
    const f = TestBed.createComponent(ShellComponent);
    f.detectChanges();
    await assertA11y(f);
  }, 20_000);
});
