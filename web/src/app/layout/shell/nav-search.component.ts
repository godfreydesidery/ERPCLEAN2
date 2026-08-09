import {
  Component,
  ElementRef,
  HostListener,
  computed,
  inject,
  input,
  signal,
  viewChild,
} from '@angular/core';
import { Router } from '@angular/router';

/** A single navigable destination exposed from the shell's permission-filtered nav. */
export interface NavDestination {
  readonly label: string;
  readonly route: string;
  readonly group: string;
  readonly icon: string;
  /**
   * Hidden synonyms for this destination — never rendered, matched only by the palette so a user
   * who searches in their own words ("opening balance", "no LPO") still lands on the right screen.
   * Keyword-only hits rank below label and group hits.
   */
  readonly keywords?: readonly string[];
}

const MAX_RESULTS = 8;

/**
 * Command-palette / menu search for the topbar.
 *
 * Keyboard shortcuts:
 *   Ctrl+K / Cmd+K  — focus the search input from anywhere in the app.
 *   ArrowDown/Up    — move the highlighted result.
 *   Enter           — navigate to the highlighted result.
 *   Escape          — clear + close (swallowed when palette is open so the shell's
 *                     own Escape handler doesn't also close the sidebar).
 *
 * ARIA: ARIA 1.2 combobox + listbox pattern.
 *   input → role="combobox", aria-autocomplete="list", aria-controls → listbox id
 *   results → role="listbox"; each row → role="option".
 *   Empty-state uses role="status" (not placed inside the listbox).
 */
@Component({
  selector: 'app-nav-search',
  standalone: true,
  templateUrl: './nav-search.component.html',
  styleUrl: './nav-search.component.scss',
})
export class NavSearchComponent {
  private readonly router = inject(Router);

  /** Flattened, permission-filtered, navigable destinations from the shell. */
  readonly destinations = input.required<readonly NavDestination[]>();

  readonly query = signal('');
  readonly open = signal(false);
  readonly activeIndex = signal(0);

  private readonly inputRef = viewChild<ElementRef<HTMLInputElement>>('searchInput');

  readonly results = computed<readonly NavDestination[]>(() => {
    const q = this.query().trim().toLowerCase();
    if (!q) return [];

    const words = q.split(/\s+/).filter(Boolean);
    const all = this.destinations();

    type Ranked = { dest: NavDestination; score: number };
    const ranked: Ranked[] = [];

    for (const dest of all) {
      const lLabel = dest.label.toLowerCase();
      const lGroup = dest.group.toLowerCase();
      const combined = `${lGroup} ${lLabel}`;
      // Hidden synonyms are searched last so a real label match always outranks them.
      const searchable = dest.keywords?.length
        ? `${combined} ${dest.keywords.join(' ').toLowerCase()}`
        : combined;

      let score = 0;

      if (lLabel.startsWith(q)) {
        score = 3;
      } else if (lLabel.includes(q)) {
        score = 2;
      } else if (combined.includes(q)) {
        score = 1;
      } else if (words.every((w) => combined.includes(w))) {
        // multi-word: all words must appear somewhere in label+group
        score = 1;
      } else if (searchable.includes(q) || words.every((w) => searchable.includes(w))) {
        score = 0.5;
      }

      if (score > 0) ranked.push({ dest, score });
    }

    return ranked
      .sort((a, b) => b.score - a.score)
      .slice(0, MAX_RESULTS)
      .map((r) => r.dest);
  });

  /** Whether the dropdown should be visible: query non-empty AND results exist. */
  readonly dropdownOpen = computed(() => this.open() && this.results().length > 0);

  /** Unique id for the listbox element. */
  readonly listboxId = 'nav-search-listbox';

  optionId(index: number): string {
    return `nav-search-opt-${index}`;
  }

  onQueryInput(event: Event): void {
    const value = (event.target as HTMLInputElement).value;
    this.query.set(value);
    this.activeIndex.set(0);
    this.open.set(true);
  }

  onInputKeydown(event: KeyboardEvent): void {
    const res = this.results();

    switch (event.key) {
      case 'ArrowDown':
        event.preventDefault();
        if (res.length > 0) {
          this.activeIndex.set(Math.min(this.activeIndex() + 1, res.length - 1));
        }
        break;

      case 'ArrowUp':
        event.preventDefault();
        if (res.length > 0) {
          this.activeIndex.set(Math.max(this.activeIndex() - 1, 0));
        }
        break;

      case 'Enter':
        event.preventDefault();
        if (this.dropdownOpen() && res[this.activeIndex()]) {
          this.navigate(res[this.activeIndex()]);
        }
        break;

      case 'Escape':
        if (this.open()) {
          // Swallow the event — don't let the shell's own Escape close the sidebar too.
          event.stopPropagation();
          this.resetAndClose();
        }
        // If not open, let Escape bubble so the shell can handle it.
        break;
    }
  }

  select(dest: NavDestination): void {
    this.navigate(dest);
  }

  private navigate(dest: NavDestination): void {
    this.router.navigateByUrl(dest.route);
    this.resetAndClose();
  }

  private resetAndClose(): void {
    this.query.set('');
    this.open.set(false);
    this.activeIndex.set(0);
    this.inputRef()?.nativeElement.blur();
  }

  /** Ctrl+K / Cmd+K global shortcut to focus the search input. */
  @HostListener('document:keydown', ['$event'])
  onGlobalKeydown(event: KeyboardEvent): void {
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
      event.preventDefault();
      const input = this.inputRef()?.nativeElement;
      if (input) {
        input.focus();
        input.select();
        this.open.set(true);
      }
    }
  }

  /** Close on click outside — the root element stops propagation so inside clicks don't self-close. */
  @HostListener('document:click')
  onDocumentClick(): void {
    if (this.open()) {
      this.open.set(false);
    }
  }
}
