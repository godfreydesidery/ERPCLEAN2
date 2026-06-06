import { Component, inject } from '@angular/core';
import { ToastService } from './toast.service';

/**
 * Renders the live toast list (mounted once in the shell). Presentation only — all state lives in
 * {@link ToastService}. Uses an assertive live region so screen readers announce errors.
 */
@Component({
  selector: 'app-toast-container',
  templateUrl: './toast-container.component.html',
  styleUrl: './toast-container.component.scss',
})
export class ToastContainerComponent {
  protected readonly toasts = inject(ToastService);

  icon(kind: string): string {
    switch (kind) {
      case 'success':
        return 'bi-check-circle-fill';
      case 'error':
        return 'bi-exclamation-triangle-fill';
      default:
        return 'bi-info-circle-fill';
    }
  }
}
