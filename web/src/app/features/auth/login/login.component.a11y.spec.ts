/**
 * Accessibility gate — LoginComponent.
 *
 * Runs axe-core (via jest-axe) in jsdom. Color-contrast and
 * scrollable-region-focusable rules are disabled (jsdom cannot compute
 * layout/computed-styles). All structural, role, label and alt rules run.
 */
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { LoginComponent } from './login.component';
import { assertA11y } from '../../../../testing/a11y.helper';

describe('LoginComponent — a11y', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [LoginComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        {
          provide: AuthService,
          useValue: { login: vi.fn(() => of({})) },
        },
      ],
    });
  });

  afterEach(() => TestBed.resetTestingModule());

  it('has no axe violations in the default (idle) state', async () => {
    const fixture = TestBed.createComponent(LoginComponent);
    await assertA11y(fixture);
  }, 15_000);

  it('has no axe violations when an error alert is visible', async () => {
    const fixture = TestBed.createComponent(LoginComponent);
    const comp = fixture.componentInstance;
    comp.error.set('Enter your username and password.');
    await assertA11y(fixture);
  }, 15_000);
});
