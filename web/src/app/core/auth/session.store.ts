import { Injectable, computed, signal } from '@angular/core';
import { AuthUser } from './auth.model';

/**
 * Holds the authenticated session: access token, refresh token, the active branch uid (sent as the
 * X-Branch-Uid override header per ARCHITECTURE §5), the user profile, and the effective permission
 * codes hydrated from GET /auth/me after login (Slice 3). Persisted to sessionStorage so a page
 * refresh keeps the session. The branch selector that switches `activeBranchUid` lands in Slice 5.
 */
@Injectable({ providedIn: 'root' })
export class SessionStore {
  private readonly accessTokenSig = signal<string | null>(
    sessionStorage.getItem('erp.accessToken'),
  );
  private readonly refreshTokenSig = signal<string | null>(
    sessionStorage.getItem('erp.refreshToken'),
  );
  private readonly activeBranchUidSig = signal<string | null>(
    sessionStorage.getItem('erp.activeBranchUid'),
  );
  private readonly userSig = signal<AuthUser | null>(this.readUser());
  private readonly permissionsSig = signal<string[]>(this.readPermissions());

  readonly accessToken = this.accessTokenSig.asReadonly();
  readonly refreshToken = this.refreshTokenSig.asReadonly();
  readonly activeBranchUid = this.activeBranchUidSig.asReadonly();
  readonly user = this.userSig.asReadonly();
  readonly permissions = this.permissionsSig.asReadonly();
  readonly isAuthenticated = computed(() => this.accessTokenSig() !== null);

  /**
   * True when the session user is root (root bypasses all permission checks) OR the user holds the
   * given permission code in their effective permission set. Returns false when not authenticated.
   */
  hasPermission(code: string): boolean {
    return this.user()?.isRoot === true || this.permissionsSig().includes(code);
  }

  /** Store a full session after login/refresh. */
  setSession(accessToken: string, refreshToken: string, user: AuthUser): void {
    this.setAccessToken(accessToken);
    this.setItem('erp.refreshToken', refreshToken, this.refreshTokenSig);
    this.userSig.set(user);
    sessionStorage.setItem('erp.user', JSON.stringify(user));
    this.setActiveBranchUid(user.activeBranchUid);
  }

  /** Store the effective permission codes returned by GET /auth/me. */
  setPermissions(codes: string[]): void {
    this.permissionsSig.set(codes);
    sessionStorage.setItem('erp.permissions', JSON.stringify(codes));
  }

  setAccessToken(token: string | null): void {
    this.setItem('erp.accessToken', token, this.accessTokenSig);
  }

  setActiveBranchUid(uid: string | null): void {
    this.setItem('erp.activeBranchUid', uid, this.activeBranchUidSig);
  }

  clear(): void {
    this.accessTokenSig.set(null);
    this.refreshTokenSig.set(null);
    this.activeBranchUidSig.set(null);
    this.userSig.set(null);
    this.permissionsSig.set([]);
    [
      'erp.accessToken',
      'erp.refreshToken',
      'erp.activeBranchUid',
      'erp.user',
      'erp.permissions',
    ].forEach((k) => sessionStorage.removeItem(k));
  }

  private setItem(
    key: string,
    value: string | null,
    sig: { set: (v: string | null) => void },
  ): void {
    sig.set(value);
    if (value) {
      sessionStorage.setItem(key, value);
    } else {
      sessionStorage.removeItem(key);
    }
  }

  private readUser(): AuthUser | null {
    const raw = sessionStorage.getItem('erp.user');
    return raw ? (JSON.parse(raw) as AuthUser) : null;
  }

  private readPermissions(): string[] {
    const raw = sessionStorage.getItem('erp.permissions');
    return raw ? (JSON.parse(raw) as string[]) : [];
  }
}
