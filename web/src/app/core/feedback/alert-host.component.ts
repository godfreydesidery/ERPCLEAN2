import { Component, HostListener, inject } from '@angular/core';
import { AlertService } from './alert.service';

/**
 * Renders the single active {@link AlertService} alert as a centered, animated SweetAlert-style
 * modal (mounted once in the shell). Presentation only. Esc and backdrop-click dismiss it (for both
 * kinds — the auto-dismiss success can also be closed early). The animated icon + card entrance live
 * in the SCSS.
 */
@Component({
  selector: 'app-alert-host',
  templateUrl: './alert-host.component.html',
  styleUrl: './alert-host.component.scss',
})
export class AlertHostComponent {
  protected readonly alerts = inject(AlertService);

  @HostListener('document:keydown.escape')
  onEscape(): void {
    if (this.alerts.current()) {
      this.alerts.dismiss();
    }
  }
}
