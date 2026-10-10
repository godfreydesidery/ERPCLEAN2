/**
 * ChangePasswordComponent + mustChangePasswordGuard (ADM-02 / PAR-14).
 */
import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import {
  ActivatedRouteSnapshot,
  Router,
  RouterStateSnapshot,
  UrlTree,
  provideRouter,
} from '@angular/router';
import { of, throwError } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { mustChangePasswordGuard } from '../../../core/auth/auth.guard';
import { SessionStore } from '../../../core/auth/session.store';
import { AlertService } from '../../../core/feedback/alert.service';
import { assertA11y } from '../../../../testing/a11y.helper';
import { ChangePasswordComponent } from './change-password.component';

const USER = {
  uid: 'U1', username: 'asha@duka', displayName: 'Asha', isRoot: false,
  activeCompanyUid: null, activeBranchUid: null, hasBranch: false,
};

function bed(changeOwnPassword = vi.fn(() => of({}))) {
  TestBed.configureTestingModule({
    imports: [ChangePasswordComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      { provide: AuthService, useValue: { changeOwnPassword } },
      { provide: AlertService, useValue: { success: vi.fn(), error: vi.fn() } },
    ],
  });
  return changeOwnPassword;
}

describe('ChangePasswordComponent', () => {
  afterEach(() => {
    TestBed.inject(SessionStore).clear();
    TestBed.resetTestingModule();
  });

  it('sends current + new password and goes to the dashboard on success', async () => {
    const call = bed();
    const fixture = TestBed.createComponent(ChangePasswordComponent);
    const router = TestBed.inject(Router);
    const nav = vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
    const comp = fixture.componentInstance;
    comp.currentPassword.set('Old-pass-1');
    comp.newPassword.set('New-pass-22');
    comp.confirmPassword.set('New-pass-22');
    comp.submit();

    expect(call).toHaveBeenCalledWith({ currentPassword: 'Old-pass-1', newPassword: 'New-pass-22' });
    expect(nav).toHaveBeenCalledWith('/admin');
  });

  it('refuses a confirmation mismatch without calling the server', () => {
    const call = bed();
    const comp = TestBed.createComponent(ChangePasswordComponent).componentInstance;
    comp.currentPassword.set('Old-pass-1');
    comp.newPassword.set('New-pass-22');
    comp.confirmPassword.set('New-pass-23');
    comp.submit();
    expect(call).not.toHaveBeenCalled();
    expect(comp.error()).toContain('do not match');
  });

  it('shows the server message on failure', () => {
    bed(vi.fn(() => throwError(() => new HttpErrorResponse({
      status: 400, error: { errors: ['Your current password is not correct.'] },
    }))));
    const comp = TestBed.createComponent(ChangePasswordComponent).componentInstance;
    comp.currentPassword.set('wrong');
    comp.newPassword.set('New-pass-22');
    comp.confirmPassword.set('New-pass-22');
    comp.submit();
    expect(comp.error()).toBe('Your current password is not correct.');
    expect(comp.saving()).toBe(false);
  });

  it('explains a forced change and has no axe violations', async () => {
    bed();
    TestBed.inject(SessionStore).setSession('tok', 'ref', { ...USER, mustChangePassword: true });
    const fixture = TestBed.createComponent(ChangePasswordComponent);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('temporary password');
    await assertA11y(fixture);
  }, 15_000);
});

describe('mustChangePasswordGuard', () => {
  const createUrlTree = vi.fn((commands: unknown[]) => ({ commands }) as unknown as UrlTree);
  const run = (url: string) =>
    TestBed.runInInjectionContext(() =>
      mustChangePasswordGuard({} as ActivatedRouteSnapshot, { url } as RouterStateSnapshot),
    );

  beforeEach(() => {
    createUrlTree.mockClear();
    TestBed.configureTestingModule({ providers: [{ provide: Router, useValue: { createUrlTree } }] });
  });
  afterEach(() => {
    TestBed.inject(SessionStore).clear();
    TestBed.resetTestingModule();
  });

  it('holds a must-change user on the password screen', () => {
    TestBed.inject(SessionStore).setSession('tok', 'ref', { ...USER, mustChangePassword: true });
    run('/admin/sales');
    expect(createUrlTree).toHaveBeenCalledWith(['/account/password']);
    expect(run('/account/password')).toBe(true);
  });

  it('lets everyone else through', () => {
    TestBed.inject(SessionStore).setSession('tok', 'ref', USER);
    expect(run('/admin/sales')).toBe(true);
  });
});
