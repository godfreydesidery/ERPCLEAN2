/**
 * Unit tests for AdminHomeComponent.
 *
 * Key behaviours tested:
 *   1. Root users see the system-setup grid (setupCards), NOT the launch grid.
 *   2. Non-root users with permitted cards see the "Quick launch" grid.
 *   3. Each launch card is only shown when session.hasPermission() returns true
 *      for its permCode — a card never leads to a silent permission-guard redirect.
 *   4. A non-root user with NO permitted cards sees the calm placeholder.
 *   5. The launchCards computed always includes Dashboard (BI.VIEW) first when permitted.
 */
import { describe, it, expect, afterEach, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { signal } from '@angular/core';

import { AdminHomeComponent } from './admin-home.component';
import { SessionStore } from '../../../core/auth/session.store';
import { AuthUser } from '../../../core/auth/auth.model';
import { assertA11y } from '../../../../testing/a11y.helper';

// ── Session factory helpers ───────────────────────────────────────────────────

function makeUser(overrides: Partial<AuthUser> = {}): AuthUser {
  return {
    uid: 'U1',
    username: 'alice',
    displayName: 'Alice',
    isRoot: false,
    activeCompanyUid: 'CO1',
    activeBranchUid: 'BR1',
    hasBranch: true,
    ...overrides,
  };
}

function makeSession(perms: string[], isRoot = false) {
  const user = makeUser({ isRoot });
  return {
    hasPermission: vi.fn((code: string) => isRoot || perms.includes(code)),
    user: signal<AuthUser | null>(user),
    isAuthenticated: signal(true),
    permissions: signal<string[]>(perms),
    activeBranchUid: signal<string | null>('BR1'),
  };
}

function makeRootSession() {
  const user = makeUser({ isRoot: true, username: 'rootadmin', displayName: 'Root Admin' });
  return {
    hasPermission: vi.fn(() => true),
    user: signal<AuthUser | null>(user),
    isAuthenticated: signal(true),
    permissions: signal<string[]>([]),
    activeBranchUid: signal<string | null>(null),
  };
}

function makeBed(session: ReturnType<typeof makeSession> | ReturnType<typeof makeRootSession>) {
  TestBed.configureTestingModule({
    imports: [AdminHomeComponent],
    providers: [
      provideRouter([{ path: '**', redirectTo: '' }]),
      { provide: SessionStore, useValue: session },
    ],
  });
}

// ── Tests ─────────────────────────────────────────────────────────────────────

describe('AdminHomeComponent', () => {
  afterEach(() => TestBed.resetTestingModule());

  // 1. Root user: setup grid is rendered; launch grid is NOT
  it('root user sees the system-setup grid, not the launch grid', () => {
    makeBed(makeRootSession());
    const fixture = TestBed.createComponent(AdminHomeComponent);
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;

    // heading text
    expect(el.textContent).toContain('System setup');
    // at least one setup card label
    expect(el.textContent).toContain('Companies & branches');
    // NOT the quick-launch section
    expect(el.textContent).not.toContain('Quick launch');
  });

  // 2. Normal user with BI.VIEW: Dashboard card is rendered
  it('non-root user with BI.VIEW sees the Dashboard launch card', () => {
    makeBed(makeSession(['BI.VIEW']));
    const fixture = TestBed.createComponent(AdminHomeComponent);
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;

    expect(el.textContent).toContain('Quick launch');
    expect(el.textContent).toContain('Dashboard');
    // placeholder should NOT appear
    expect(el.textContent).not.toContain('Your workspace is ready.');
  });

  // 3. Permission filtering: only permitted cards render
  it('renders only cards whose permCode the session holds', () => {
    // Give user just SALES.ORDER.VIEW and STOCK.VIEW — no PURCHASE.ORDER.VIEW
    makeBed(makeSession(['SALES.ORDER.VIEW', 'STOCK.VIEW']));
    const fixture = TestBed.createComponent(AdminHomeComponent);
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;

    expect(el.textContent).toContain('Sales orders');
    expect(el.textContent).toContain('Stock on-hand');
    // Purchase orders card requires PURCHASE.ORDER.VIEW — must NOT appear
    expect(el.textContent).not.toContain('Purchase orders');
  });

  // 4. No permitted cards: placeholder is shown
  it('shows calm placeholder when non-root user has no permitted launch cards', () => {
    makeBed(makeSession([])); // empty permission set
    const fixture = TestBed.createComponent(AdminHomeComponent);
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;

    expect(el.textContent).toContain('Your workspace is ready.');
    expect(el.textContent).not.toContain('Quick launch');
  });

  // 5. Dashboard card is FIRST in the visible list when BI.VIEW is present
  it('Dashboard card appears before other cards when BI.VIEW is granted', () => {
    makeBed(makeSession(['BI.VIEW', 'SALES.ORDER.VIEW', 'STOCK.VIEW']));
    const fixture = TestBed.createComponent(AdminHomeComponent);
    fixture.detectChanges();
    const links: NodeListOf<HTMLAnchorElement> =
      fixture.nativeElement.querySelectorAll('.home-card');

    // The first anchor in the grid must link to the dashboard
    expect(links[0]?.getAttribute('href')).toContain('/admin/dashboard');
  });

  // 6. Cards are rendered as <a routerLink> — axe must find no violations
  it('has no axe violations with a typical GM permission set', async () => {
    makeBed(
      makeSession([
        'BI.VIEW',
        'SALES.ORDER.VIEW',
        'CUSTOMER.VIEW',
        'PURCHASE.ORDER.VIEW',
        'PURCHASE.GOODS_RECEIPT.VIEW',
        'SUPPLIER.VIEW',
        'STOCK.VIEW',
        'STOCK.COUNT.VIEW',
        'STOCK.TRANSFER.VIEW',
        'AR.RECEIPT.RECORD',
        'AP.PAYMENT.RUN',
        'AP.BILL.ENTER',
        'GL.POST',
        'MANUFACTURING.VIEW',
      ]),
    );
    const fixture = TestBed.createComponent(AdminHomeComponent);
    await assertA11y(fixture);
  }, 20_000);

  // 7. Placeholder empty state is axe-clean
  it('has no axe violations in the empty-state (no permissions) fallback', async () => {
    makeBed(makeSession([]));
    const fixture = TestBed.createComponent(AdminHomeComponent);
    await assertA11y(fixture);
  }, 20_000);

  // 8. Root setup grid is axe-clean
  it('has no axe violations with root user seeing the setup grid', async () => {
    makeBed(makeRootSession());
    const fixture = TestBed.createComponent(AdminHomeComponent);
    await assertA11y(fixture);
  }, 20_000);

  // 9. Unpermitted card is absent from the DOM (not just hidden)
  it('unpermitted card is not present in the DOM at all', () => {
    // POS.SALE.CREATE not in perms
    makeBed(makeSession(['BI.VIEW', 'SALES.ORDER.VIEW']));
    const fixture = TestBed.createComponent(AdminHomeComponent);
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;

    // 'POS sell' label should be absent
    expect(el.textContent).not.toContain('POS sell');
  });

  // 10. launchCards computed reflects live signal — simulate switching to a session with more perms
  it('launchCards computed includes cards matching the injected permission set', () => {
    const session = makeSession(['BI.VIEW', 'GL.POST']);
    makeBed(session);
    const comp = TestBed.createComponent(AdminHomeComponent).componentInstance;

    const cards = comp.launchCards();
    expect(cards.map((c) => c.label)).toContain('Dashboard');
    expect(cards.map((c) => c.label)).toContain('Post journal');
    // MANUFACTURING.VIEW not in perms → Work orders absent
    expect(cards.map((c) => c.label)).not.toContain('Work orders');
  });
});
