import { Component, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Observable, concat, map, tap } from 'rxjs';
import {
  Branch,
  CopyBranchMenuResult,
  CopyCategoryRoutesResult,
} from '../models/branch.model';
import { BranchService } from './branch.service';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';

/**
 * Lists a company's branches and manages them — create, set-default, archive. The
 * company uid comes from the route (`/admin/companies/:companyUid/branches`). Set-default is the
 * headline action: marking one default clears the previous (enforced server-side).
 */
@Component({
  selector: 'app-branch-list',
  imports: [FormsModule],
  templateUrl: './branch-list.component.html',
  styleUrl: './branch-list.component.scss',
})
export class BranchListComponent {
  private readonly branchService = inject(BranchService);
  private readonly alerts = inject(AlertService);
  private readonly session = inject(SessionStore);

  /** Rename is gated on BRANCH.MANAGE (the page itself only needs BRANCH.VIEW). */
  readonly canManage = computed(() => this.session.hasPermission('BRANCH.MANAGE'));

  /** Copying a menu & branch prices uses the branch-menu permission (ADR-0073). */
  readonly canCopyMenu = computed(() => this.session.hasPermission('PRODUCT.BRANCH.ASSIGN'));
  /** Copying kitchen routing is restaurant set-up. */
  readonly canCopyRouting = computed(() => this.session.hasPermission('RESTAURANT.SETUP.MANAGE'));
  readonly canCopy = computed(() => this.canCopyMenu() || this.canCopyRouting());

  readonly activeBranches = computed(() => this.branches().filter((b) => b.status === 'ACTIVE'));

  /** Create form: start the new branch's menu & prices from this branch ('' = don't). */
  readonly startFrom = signal('');

  // "Copy from…" panel for an existing branch. Nothing is copied unless the user asks.
  readonly copyTarget = signal<Branch | null>(null);
  readonly copySource = signal('');
  readonly copyMenuChecked = signal(true);
  /** Off by default: a branch with one printer must keep printing everything where it does. */
  readonly copyRoutingChecked = signal(false);
  readonly copying = signal(false);
  readonly copyResult = signal<string[]>([]);
  readonly copyError = signal<string | null>(null);
  readonly copySources = computed(() =>
    this.activeBranches().filter((b) => b.uid !== this.copyTarget()?.uid),
  );

  /** Route input — Angular binds the `:companyUid` path param to this signal. */
  readonly companyUid = input.required<string>();

  /**
   * When true the component is hosted inside another page (the standalone Branches admin screen),
   * which already provides the `.admin-page` chrome and the "Branches" heading — so we drop both to
   * avoid a duplicate `<h1>` and double page padding. Defaults false → unchanged standalone/route use.
   */
  readonly embedded = input(false);

  readonly branches = signal<Branch[]>([]);
  readonly state = signal<'loading' | 'idle' | 'error'>('loading');
  readonly formError = signal<string | null>(null);

  readonly code = signal('');
  readonly name = signal('');
  readonly makeDefault = signal(false);
  readonly saving = signal(false);

  /** uid of the branch whose row action (set-default or archive) is in-flight. */
  readonly busyUid = signal<string | null>(null);

  // Inline rename: at most one row is editable at a time (keyed by uid).
  readonly editingUid = signal<string | null>(null);
  readonly editName = signal('');
  readonly savingEdit = signal(false);
  readonly editError = signal<string | null>(null);

  startEdit(branch: Branch): void {
    this.editError.set(null);
    this.editName.set(branch.name);
    this.editingUid.set(branch.uid);
  }

  cancelEdit(): void {
    this.editingUid.set(null);
    this.editError.set(null);
  }

  saveEdit(branch: Branch): void {
    const name = this.editName().trim();
    if (!name) {
      this.editError.set('Name is required.');
      return;
    }
    if (name === branch.name) {
      this.cancelEdit();
      return;
    }
    this.savingEdit.set(true);
    this.editError.set(null);
    // Only the name changes; timeZone is preserved server-side when omitted/blank.
    this.branchService.update(branch.uid, { name }).subscribe({
      next: () => {
        this.savingEdit.set(false);
        this.editingUid.set(null);
        this.alerts.success('Branch updated');
        this.load();
      },
      error: (err) => {
        this.editError.set(this.messageFrom(err));
        this.savingEdit.set(false);
      },
    });
  }

  constructor() {
    // input.required is set by the router before the constructor body runs in this config; load on init.
    queueMicrotask(() => this.load());
  }

  load(): void {
    this.state.set('loading');
    this.branchService.list(this.companyUid()).subscribe({
      next: (rows) => {
        this.branches.set(rows);
        this.state.set('idle');
      },
      error: () => this.state.set('error'),
    });
  }

  create(): void {
    if (!this.code().trim() || !this.name().trim()) {
      this.formError.set('Code and name are required.');
      return;
    }
    this.saving.set(true);
    this.formError.set(null);
    this.branchService
      .create({
        companyUid: this.companyUid(),
        code: this.code().trim(),
        name: this.name().trim(),
        makeDefault: this.makeDefault(),
      })
      .subscribe({
        next: (created) => {
          const source = this.canCopyMenu() ? this.startFrom() : '';
          // Reload so a default change elsewhere is reflected consistently.
          this.code.set('');
          this.name.set('');
          this.makeDefault.set(false);
          this.startFrom.set('');
          if (!source) {
            this.saving.set(false);
            this.alerts.success('Branch created');
            this.load();
            return;
          }
          // The branch exists either way; a failed copy is reported, never undoes the create.
          this.branchService.copyMenu(source, created.uid).subscribe({
            next: (r) => {
              this.saving.set(false);
              this.alerts.success(`Branch created. ${this.menuSummary(r)}`);
              this.load();
            },
            error: (err) => {
              this.saving.set(false);
              this.formError.set(
                `Branch created, but its menu could not be copied: ${this.messageFrom(err)} ` +
                  'Use "Copy from…" on the branch to try again.',
              );
              this.load();
            },
          });
        },
        error: (err) => {
          this.formError.set(this.messageFrom(err));
          this.saving.set(false);
        },
      });
  }

  setDefault(branch: Branch): void {
    if (branch.isDefault || this.busyUid() !== null) {
      return;
    }
    this.busyUid.set(branch.uid);
    this.branchService.setDefault(branch.uid).subscribe({
      next: () => {
        this.busyUid.set(null);
        this.alerts.success('Default branch updated');
        this.load();
      },
      error: (err) => {
        this.busyUid.set(null);
        this.formError.set(this.messageFrom(err));
      },
    });
  }

  archive(branch: Branch): void {
    if (this.busyUid() !== null) {
      return;
    }
    this.busyUid.set(branch.uid);
    this.branchService.archive(branch.uid).subscribe({
      next: () => {
        this.busyUid.set(null);
        this.alerts.success('Branch archived');
        this.load();
      },
      error: (err) => {
        this.busyUid.set(null);
        this.formError.set(this.messageFrom(err));
      },
    });
  }

  openCopy(branch: Branch): void {
    this.copyTarget.set(branch);
    this.copySource.set('');
    this.copyMenuChecked.set(this.canCopyMenu());
    this.copyRoutingChecked.set(false);
    this.copyResult.set([]);
    this.copyError.set(null);
  }

  closeCopy(): void {
    this.copyTarget.set(null);
    this.copyResult.set([]);
    this.copyError.set(null);
  }

  runCopy(): void {
    const target = this.copyTarget();
    const source = this.copySource();
    if (!target || this.copying()) {
      return;
    }
    if (!source) {
      this.copyError.set('Choose the branch to copy from.');
      return;
    }
    const doMenu = this.copyMenuChecked() && this.canCopyMenu();
    const doRouting = this.copyRoutingChecked() && this.canCopyRouting();
    if (!doMenu && !doRouting) {
      this.copyError.set('Tick what to copy.');
      return;
    }
    const steps: Observable<string>[] = [];
    if (doMenu) {
      steps.push(
        this.branchService.copyMenu(source, target.uid).pipe(map((r) => this.menuSummary(r))),
      );
    }
    if (doRouting) {
      steps.push(
        this.branchService
          .copyRouting(source, target.uid)
          .pipe(map((r) => this.routingSummary(r))),
      );
    }
    // One after the other; what finished is shown even if a later step fails.
    const done: string[] = [];
    this.copying.set(true);
    this.copyError.set(null);
    this.copyResult.set([]);
    concat(...steps)
      .pipe(tap((line) => done.push(line)))
      .subscribe({
        complete: () => {
          this.copying.set(false);
          this.copyResult.set([...done]);
        },
        error: (err) => {
          this.copying.set(false);
          this.copyResult.set([...done]);
          this.copyError.set(this.messageFrom(err));
        },
      });
  }

  menuSummary(r: CopyBranchMenuResult): string {
    if (r.itemsNowSold === 0) {
      return 'Menu & prices: nothing new to copy.';
    }
    const kept = r.alreadySetAtTarget
      ? ` ${r.alreadySetAtTarget} item(s) this branch had already set were left as they were.`
      : '';
    return (
      `Menu & prices: ${r.itemsNowSold} item(s) now sold here, ` +
      `${r.pricesCopied} with the other branch's price.${kept}`
    );
  }

  routingSummary(r: CopyCategoryRoutesResult): string {
    let line = `Kitchen routing: ${r.routesCopied} department(s) routed.`;
    if (r.alreadyRouted) {
      line += ` ${r.alreadyRouted} already routed here were left as they were.`;
    }
    if (r.stationsMissingAtTarget.length) {
      line +=
        ` This branch has no ${r.stationsMissingAtTarget.join(', ')} station, so ` +
        `${r.skippedNoMatchingStation} department(s) still print at its default station.`;
    }
    return line;
  }

  private messageFrom(err: unknown): string {
    const errors = (err as { error?: { errors?: string[] } })?.error?.errors;
    return errors?.length ? errors[0] : 'Action failed. Please try again.';
  }
}
