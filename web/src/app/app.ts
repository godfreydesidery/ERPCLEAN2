import { Component, inject, signal } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { ScrollableRegionService } from './core/a11y/scrollable-region.service';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet],
  templateUrl: './app.html',
  styleUrl: './app.scss'
})
export class App {
  protected readonly title = signal('web');

  constructor() {
    // App-wide a11y fix: keeps every `.erp-table-wrap` scroll region keyboard-accessible
    // (tabindex/role/aria-label) as pages render — see ScrollableRegionService for why this
    // is a single root-level fix rather than 190 template edits.
    inject(ScrollableRegionService).start();
  }
}
