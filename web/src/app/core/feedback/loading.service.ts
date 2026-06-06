import { Injectable, computed, signal } from '@angular/core';

/**
 * Tracks the number of in-flight API requests so the shell can show a global top progress bar while
 * anything is happening (the "something is taking place, please wait" cue). Incremented/decremented
 * by the loading HTTP interceptor; {@link active} is true whenever ≥1 request is outstanding.
 */
@Injectable({ providedIn: 'root' })
export class LoadingService {
  private readonly count = signal(0);
  readonly active = computed(() => this.count() > 0);

  begin(): void {
    this.count.update((n) => n + 1);
  }

  end(): void {
    // Floor at 0 — defensive against a double-end (shouldn't happen with finalize()).
    this.count.update((n) => (n > 0 ? n - 1 : 0));
  }
}
