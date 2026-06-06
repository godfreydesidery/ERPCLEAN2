import { Injectable, signal } from '@angular/core';

export type AlertKind = 'success' | 'error';

export interface Alert {
  readonly id: number;
  readonly kind: AlertKind;
  readonly title: string;
  readonly message?: string;
  /** When true the alert shows an OK button and waits for the user; else it auto-dismisses. */
  readonly requireAck: boolean;
}

/**
 * SweetAlert-style centered confirmation. One alert at a time (a modal, not a stack) — a newer
 * alert replaces the current one. Rendered by {@link AlertHostComponent} (mounted in the shell).
 *
 * <p>Convention (owner decision): {@link #success} is prominent but non-nagging — it animates in and
 * AUTO-DISMISSES after ~1.5s with no click. {@link #error} REQUIRES acknowledgement (OK button) so a
 * failure can't be missed. Signal-based so the host re-renders without manual change detection.
 */
@Injectable({ providedIn: 'root' })
export class AlertService {
  private readonly currentSig = signal<Alert | null>(null);
  readonly current = this.currentSig.asReadonly();

  private seq = 0;

  /** Prominent, auto-dismissing success (default 1500ms). Pass durationMs=0 to require an OK click. */
  success(title: string, message?: string, durationMs = 1500): void {
    const id = ++this.seq;
    this.currentSig.set({ id, kind: 'success', title, message, requireAck: durationMs <= 0 });
    if (durationMs > 0) {
      setTimeout(() => this.dismissIf(id), durationMs);
    }
  }

  /** Error that stays until the user acknowledges (OK button). */
  error(title: string, message?: string): void {
    this.currentSig.set({ id: ++this.seq, kind: 'error', title, message, requireAck: true });
  }

  dismiss(): void {
    this.currentSig.set(null);
  }

  /** Dismiss only if the still-current alert is the one that scheduled the timeout (no clobbering). */
  private dismissIf(id: number): void {
    if (this.currentSig()?.id === id) {
      this.currentSig.set(null);
    }
  }
}
