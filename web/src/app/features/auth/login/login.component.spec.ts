import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router } from '@angular/router';
import { of } from 'rxjs';
import { vi } from 'vitest';
import { AuthService } from '../../../core/auth/auth.service';
import { LoginComponent } from './login.component';

/**
 * Post-login redirect behaviour: the user should return to the `returnUrl` they were bounced from
 * (session timeout / protected-route guard), with an open-redirect guard rejecting off-origin
 * targets and falling back to the dashboard.
 */
describe('LoginComponent — post-login redirect', () => {
  const navigateByUrl = vi.fn();
  let returnUrl: string | null = null;

  function makeComponent(): LoginComponent {
    navigateByUrl.mockReset();
    TestBed.configureTestingModule({
      imports: [LoginComponent],
      providers: [
        { provide: AuthService, useValue: { login: vi.fn(() => of({})) } },
        { provide: Router, useValue: { navigateByUrl } },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              queryParamMap: { get: (key: string) => (key === 'returnUrl' ? returnUrl : null) },
            },
          },
        },
      ],
    });
    const comp = TestBed.createComponent(LoginComponent).componentInstance;
    comp.username.set('alice');
    comp.password.set('Secret123');
    return comp;
  }

  afterEach(() => TestBed.resetTestingModule());

  it('returns to the returnUrl after a successful login', () => {
    returnUrl = '/admin/purchase-orders/uid/01ABC';
    makeComponent().submit();
    expect(navigateByUrl).toHaveBeenCalledWith('/admin/purchase-orders/uid/01ABC');
  });

  it('falls back to /admin when no returnUrl is present', () => {
    returnUrl = null;
    makeComponent().submit();
    expect(navigateByUrl).toHaveBeenCalledWith('/admin');
  });

  it('rejects a protocol-relative returnUrl (open-redirect guard)', () => {
    returnUrl = '//evil.com';
    makeComponent().submit();
    expect(navigateByUrl).toHaveBeenCalledWith('/admin');
  });

  it('rejects an absolute off-origin returnUrl', () => {
    returnUrl = 'https://evil.com/phish';
    makeComponent().submit();
    expect(navigateByUrl).toHaveBeenCalledWith('/admin');
  });
});
