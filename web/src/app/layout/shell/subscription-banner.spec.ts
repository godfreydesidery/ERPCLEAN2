import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ShellComponent } from './shell.component';
import { subscriptionBanner } from './subscription-banner';
import { SessionStore } from '../../core/auth/session.store';
import { AuthUser } from '../../core/auth/auth.model';
import { SubscriptionSummary } from '../../features/admin/platform/subscription.model';
import { assertA11y } from '../../../testing/a11y.helper';

const sub = (over: Partial<SubscriptionSummary> = {}): SubscriptionSummary => ({
  planCode: 'STANDARD',
  planName: 'Standard',
  stage: 'ACTIVE',
  dueDate: '2026-12-31',
  readOnlyFrom: null,
  suspendedFrom: null,
  addOnRestaurant: false,
  addOnOrbixHq: false,
  ...over,
});

describe('subscriptionBanner (ADR-0069)', () => {
  const today = '2026-11-13';

  it('says nothing without a subscription', () => {
    expect(subscriptionBanner(null, today)).toBeNull();
    expect(subscriptionBanner(sub({ stage: 'UNRESTRICTED' }), today)).toBeNull();
  });

  it('TRIAL: info from 7 days out, silent before that', () => {
    expect(subscriptionBanner(sub({ stage: 'TRIAL', dueDate: '2026-11-21' }), today)).toBeNull(); // 8 days
    const b = subscriptionBanner(sub({ stage: 'TRIAL', dueDate: '2026-11-20' }), today); // 7 days
    expect(b?.severity).toBe('info');
    expect(b?.message).toContain('Trial ends on 20 Nov 2026');
  });

  it('TRIAL: still shown on the last day', () => {
    expect(subscriptionBanner(sub({ stage: 'TRIAL', dueDate: today }), today)?.severity).toBe('info');
  });

  it('ACTIVE: due-date notice within 7 days, silent beyond', () => {
    expect(subscriptionBanner(sub({ dueDate: '2026-12-01' }), today)).toBeNull();
    const b = subscriptionBanner(sub({ dueDate: '2026-11-18' }), today);
    expect(b?.severity).toBe('info');
    expect(b?.message).toContain('Subscription due on 18 Nov 2026');
  });

  it('ACTIVE with no due date (never lapses) says nothing', () => {
    expect(subscriptionBanner(sub({ dueDate: null }), today)).toBeNull();
  });

  it('GRACE: warning naming the read-only date', () => {
    const b = subscriptionBanner(sub({ stage: 'GRACE', readOnlyFrom: '2026-11-20' }), today);
    expect(b).toEqual({
      severity: 'warning',
      message: 'Payment overdue. The system becomes read-only on 20 Nov 2026.',
    });
  });

  it('READ_ONLY: error naming the suspension date', () => {
    const b = subscriptionBanner(sub({ stage: 'READ_ONLY', suspendedFrom: '2026-11-27' }), today);
    expect(b?.severity).toBe('error');
    expect(b?.message).toBe(
      'Your subscription is overdue, so the system is read-only. It will be suspended on 27 Nov 2026. Payment restores full access.',
    );
  });
});

describe('ShellComponent subscription banner', () => {
  const USER: AuthUser = {
    uid: '01HUSERUSERUSERUSERUSERUSE',
    username: 'owner@acme',
    displayName: 'Owner',
    isRoot: false,
    activeCompanyUid: '01HAAAAAAAAAAAAAAAAAAAAAAA',
    activeBranchUid: '01HBBBBBBBBBBBBBBBBBBBBBBB',
    hasBranch: true,
  };
  let store: SessionStore;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [ShellComponent],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    store = TestBed.inject(SessionStore);
    store.clear();
    store.setSession('a', 'r', USER);
  });
  afterEach(() => store.clear());

  const banner = (f: { nativeElement: HTMLElement }): HTMLElement | null =>
    f.nativeElement.querySelector('[data-testid="subscription-banner"]');

  it('renders nothing when unrestricted', () => {
    const f = TestBed.createComponent(ShellComponent);
    f.detectChanges();
    expect(banner(f)).toBeNull();
  });

  it('GRACE is a polite status; READ_ONLY is an alert', () => {
    store.setSubscription(sub({ stage: 'GRACE', readOnlyFrom: '2026-11-20' }));
    const f = TestBed.createComponent(ShellComponent);
    f.detectChanges();
    expect(banner(f)?.getAttribute('role')).toBe('status');
    expect(banner(f)?.textContent).toContain('Payment overdue');

    store.setSubscription(sub({ stage: 'READ_ONLY', suspendedFrom: '2026-11-27' }));
    f.detectChanges();
    expect(banner(f)?.getAttribute('role')).toBe('alert');
    expect(banner(f)?.textContent).toContain('read-only');
  });

  it('has no axe violations with the read-only banner showing', async () => {
    store.setSubscription(sub({ stage: 'READ_ONLY', suspendedFrom: '2026-11-27' }));
    const f = TestBed.createComponent(ShellComponent);
    f.detectChanges();
    await assertA11y(f);
  }, 20_000);
});
