import { Injectable, computed, signal } from '@angular/core';
import { AuthUser } from './auth.model';

/**
 * Holds the authenticated session: access token, refresh token, the active branch uid (sent as the
 * X-Branch-Uid override header per ARCHITECTURE §5), and the user profile. Persisted to
 * sessionStorage so a page refresh keeps the session. The branch selector that switches
 * `activeBranchUid` lands in Slice 5.
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

  readonly accessToken = this.accessTokenSig.asReadonly();
  readonly refreshToken = this.refreshTokenSig.asReadonly();
  readonly activeBranchUid = this.activeBranchUidSig.asReadonly();
  readonly user = this.userSig.asReadonly();
  readonly isAuthenticated = computed(() => this.accessTokenSig() !== null);

  /** Store a full session after login/refresh. */
  setSession(accessToken: string, refreshToken: string, user: AuthUser): void {
    this.setAccessToken(accessToken);
    this.setItem('erp.refreshToken', refreshToken, this.refreshTokenSig);
    this.userSig.set(user);
    sessionStorage.setItem('erp.user', JSON.stringify(user));
    this.setActiveBranchUid(user.activeBranchUid);
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
    ['erp.accessToken', 'erp.refreshToken', 'erp.activeBranchUid', 'erp.user'].forEach((k) =>
      sessionStorage.removeItem(k),
    );
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
}
