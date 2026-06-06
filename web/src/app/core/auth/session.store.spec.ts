import { TestBed } from '@angular/core/testing';
import { SessionStore } from './session.store';
import { AuthUser } from './auth.model';

describe('SessionStore.hasPermission', () => {
  let store: SessionStore;

  const user: AuthUser = {
    uid: 'U1', username: 'alice', displayName: 'Alice',
    isRoot: false, activeCompanyUid: 'C1', activeBranchUid: 'B1', hasBranch: true,
  };

  const rootUser: AuthUser = { ...user, isRoot: true };

  beforeEach(() => {
    TestBed.configureTestingModule({});
    store = TestBed.inject(SessionStore);
    store.clear();
  });

  it('returns false when no permissions are set and user is not root', () => {
    store.setSession('tok', 'ref', user);
    expect(store.hasPermission('COMPANY.VIEW')).toBe(false);
  });

  it('returns true when the permission code is in the set', () => {
    store.setSession('tok', 'ref', user);
    store.setPermissions(['COMPANY.VIEW', 'ROLE.VIEW']);
    expect(store.hasPermission('COMPANY.VIEW')).toBe(true);
    expect(store.hasPermission('ROLE.VIEW')).toBe(true);
  });

  it('returns false for a permission not in the set', () => {
    store.setSession('tok', 'ref', user);
    store.setPermissions(['COMPANY.VIEW']);
    expect(store.hasPermission('ROLE.MANAGE')).toBe(false);
  });

  it('returns true for any permission when user isRoot', () => {
    store.setSession('tok', 'ref', rootUser);
    store.setPermissions([]);
    expect(store.hasPermission('ROLE.MANAGE')).toBe(true);
    expect(store.hasPermission('ANYTHING')).toBe(true);
  });

  it('clears permissions on clear()', () => {
    store.setSession('tok', 'ref', user);
    store.setPermissions(['COMPANY.VIEW']);
    store.clear();
    expect(store.permissions()).toEqual([]);
    expect(store.hasPermission('COMPANY.VIEW')).toBe(false);
  });
});
