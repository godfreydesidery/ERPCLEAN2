import { Injectable, inject } from '@angular/core';
import { Observable, finalize, map, shareReplay } from 'rxjs';
import { AuthService } from './auth.service';

/**
 * Renews an expired access token with the stored refresh token — ONE request at a time.
 *
 * The access token lives 15 minutes, the refresh token 7 days. Before this existed the web app never
 * refreshed at all: the first request after minute 15 came back 401 and the user was sent to the login
 * page mid-work, however busy they were (client report, 2026-09-29).
 *
 * Single-flight is load-bearing, not an optimisation. The server rotates refresh tokens and treats a
 * second use of the same token as theft, revoking EVERY session the user has. A screen that fires
 * several requests at once would get several 401s together; refreshing once per 401 would present the
 * same token several times and sign the user out everywhere. So every caller that arrives while a
 * refresh is in flight shares that one request and its result.
 */
@Injectable({ providedIn: 'root' })
export class TokenRefresher {
  private readonly auth = inject(AuthService);
  private inFlight: Observable<string> | null = null;

  /** Emits the new access token, or errors when the session cannot be renewed. */
  refresh(): Observable<string> {
    if (!this.inFlight) {
      this.inFlight = this.auth.refresh().pipe(
        map((res) => res.accessToken),
        // Cleared once settled, so a LATER expiry (15 minutes on) makes a fresh request rather
        // than replaying this one's now-stale token.
        finalize(() => (this.inFlight = null)),
        shareReplay({ bufferSize: 1, refCount: false }),
      );
    }
    return this.inFlight;
  }
}
