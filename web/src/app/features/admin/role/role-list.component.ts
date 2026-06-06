import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Role } from '../models/role.model';
import { RoleService } from './role.service';
import { AlertService } from '../../../core/feedback/alert.service';

/**
 * Lists all roles and provides an inline create form (code + name + description). Each row links
 * to the role-edit screen. All four states (loading / empty / error / populated) are handled.
 */
@Component({
  selector: 'app-role-list',
  imports: [FormsModule, RouterLink],
  templateUrl: './role-list.component.html',
  styleUrl: './role-list.component.scss',
})
export class RoleListComponent {
  private readonly roleService = inject(RoleService);
  private readonly alerts = inject(AlertService);

  readonly roles = signal<Role[]>([]);
  readonly state = signal<'loading' | 'idle' | 'error'>('loading');
  readonly formError = signal<string | null>(null);

  // Create form
  readonly code = signal('');
  readonly name = signal('');
  readonly description = signal('');
  readonly saving = signal(false);

  readonly hasRoles = computed(() => this.roles().length > 0);

  constructor() {
    this.load();
  }

  load(): void {
    this.state.set('loading');
    this.roleService.list().subscribe({
      next: (rows) => {
        this.roles.set(rows);
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
    this.roleService
      .create({
        code: this.code().trim(),
        name: this.name().trim(),
        description: this.description().trim() || undefined,
      })
      .subscribe({
        next: (created) => {
          this.roles.update((list) => [...list, created]);
          this.code.set('');
          this.name.set('');
          this.description.set('');
          this.saving.set(false);
          this.alerts.success('Role created');
        },
        error: (err) => {
          this.formError.set(this.messageFrom(err));
          this.saving.set(false);
        },
      });
  }

  private messageFrom(err: unknown): string {
    const errors = (err as { error?: { errors?: string[] } })?.error?.errors;
    return errors?.length ? errors[0] : 'Could not save the role.';
  }
}
