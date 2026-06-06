import { Injectable, signal } from '@angular/core';

export type ToastKind = 'success' | 'error' | 'info';

export interface Toast {
  readonly id: number;
  readonly kind: ToastKind;
  readonly message: string;
}

/**
 * Lightweight, dependency-free toast notifications. Components/interceptors push a message; the
 * {@link ToastContainerComponent} (mounted once in the shell) renders the live list and each toast
 * auto-dismisses. Signal-based so the container re-renders without manual change detection.
 *
 * <p>Errors are pushed automatically by the HTTP error interceptor; successes are pushed explicitly
 * by feature components after a save. Default lifetimes: success/info 3.5s, error 6s (longer so the
 * user can read what went wrong). 0 = sticky (caller dismisses).
 */
@Injectable({ providedIn: 'root' })
export class ToastService {
  private readonly toastsSig = signal<Toast[]>([]);
  readonly toasts = this.toastsSig.asReadonly();

  private seq = 0;

  success(message: string, durationMs = 3500): number {
    return this.push('success', message, durationMs);
  }

  error(message: string, durationMs = 6000): number {
    return this.push('error', message, durationMs);
  }

  info(message: string, durationMs = 3500): number {
    return this.push('info', message, durationMs);
  }

  dismiss(id: number): void {
    this.toastsSig.update((list) => list.filter((t) => t.id !== id));
  }

  private push(kind: ToastKind, message: string, durationMs: number): number {
    const id = ++this.seq;
    this.toastsSig.update((list) => [...list, { id, kind, message }]);
    if (durationMs > 0) {
      setTimeout(() => this.dismiss(id), durationMs);
    }
    return id;
  }
}
