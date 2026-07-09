/**
 * RoleListComponent — spec for the read-only "view permissions" popup:
 *   1. open/close toggles the selected role signal;
 *   2. permissionGroups() groups the role's codes by module, both sorted;
 *   3. closed / empty-role states yield no groups;
 *   4. Escape closes only when open;
 *   5. the popup is reachable by a view-only user (no ROLE.ADMIN).
 */
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { describe, it, expect, vi, afterEach } from 'vitest';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { RoleService } from './role.service';
import { RoleListComponent } from './role-list.component';
import type { Role } from '../models/role.model';

function makeRole(overrides: Partial<Role> = {}): Role {
  return {
    id: '1',
    uid: 'role-uid-1',
    code: 'ACCOUNTANT',
    name: 'Accountant',
    description: null,
    system: false,
    status: 'ACTIVE',
    permissionCodes: ['GL.POST', 'AR.RECEIPT.RECORD', 'GL.JOURNAL.VIEW', 'AR.VIEW'],
    ...overrides,
  };
}

function makeBed(opts: { hasPermission?: (code: string) => boolean } = {}) {
  const svc = { list: vi.fn(() => of([makeRole()])), create: vi.fn() };
  const session = { hasPermission: vi.fn((code: string) => opts.hasPermission?.(code) ?? false) };

  TestBed.configureTestingModule({
    imports: [RoleListComponent],
    providers: [
      provideRouter([]),
      { provide: RoleService, useValue: svc },
      { provide: AlertService, useValue: { success: vi.fn(), error: vi.fn() } },
      { provide: SessionStore, useValue: session },
    ],
  });
  // Override the template — we assert on component signal state, not DOM (mirrors role-edit spec).
  TestBed.overrideTemplate(RoleListComponent, '<div></div>');
  return { svc, session };
}

describe('RoleListComponent — view-permissions popup', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('opens and closes the popup', () => {
    makeBed();
    const comp = TestBed.createComponent(RoleListComponent).componentInstance;
    expect(comp.permissionsRole()).toBeNull();

    const role = makeRole();
    comp.openPermissions(role);
    expect(comp.permissionsRole()).toBe(role);

    comp.closePermissions();
    expect(comp.permissionsRole()).toBeNull();
  });

  it('groups the selected role permission codes by module, both sorted', () => {
    makeBed();
    const comp = TestBed.createComponent(RoleListComponent).componentInstance;
    comp.openPermissions(makeRole({
      permissionCodes: ['GL.POST', 'AR.VIEW', 'GL.JOURNAL.VIEW', 'AR.RECEIPT.RECORD'],
    }));

    const groups = comp.permissionGroups();
    expect(groups.map((g) => g.module)).toEqual(['AR', 'GL']);
    expect(groups[0].codes).toEqual(['AR.RECEIPT.RECORD', 'AR.VIEW']);
    expect(groups[1].codes).toEqual(['GL.JOURNAL.VIEW', 'GL.POST']);
  });

  it('returns no groups when the popup is closed or the role has no permissions', () => {
    makeBed();
    const comp = TestBed.createComponent(RoleListComponent).componentInstance;
    expect(comp.permissionGroups()).toEqual([]);

    comp.openPermissions(makeRole({ permissionCodes: [] }));
    expect(comp.permissionGroups()).toEqual([]);
  });

  it('Escape closes the popup only when it is open', () => {
    makeBed();
    const comp = TestBed.createComponent(RoleListComponent).componentInstance;

    comp.onEscape(); // no-op when closed
    expect(comp.permissionsRole()).toBeNull();

    comp.openPermissions(makeRole());
    comp.onEscape();
    expect(comp.permissionsRole()).toBeNull();
  });

  it('is reachable by a view-only user (no ROLE.ADMIN)', () => {
    makeBed({ hasPermission: () => false });
    const comp = TestBed.createComponent(RoleListComponent).componentInstance;
    expect(comp.canAdmin()).toBe(false);

    comp.openPermissions(makeRole());
    expect(comp.permissionsRole()).not.toBeNull();
  });
});
