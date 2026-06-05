import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Company } from '../models/company.model';
import { CompanyService } from './company.service';

/**
 * Lists companies under the organisation and creates new ones. Slice 1 reads the organisation uid
 * from a route/query input; in a later slice it comes from the logged-in user's context. All four
 * states (loading / empty / error / populated) are handled.
 */
@Component({
  selector: 'app-company-list',
  imports: [FormsModule, RouterLink],
  templateUrl: './company-list.component.html',
  styleUrl: './company-list.component.scss',
})
export class CompanyListComponent {
  private readonly companyService = inject(CompanyService);

  // Slice 1: organisation uid is provided via the admin route. Wired to user context in S2/S3.
  readonly organisationUid = signal<string>('');
  readonly companies = signal<Company[]>([]);
  readonly state = signal<'idle' | 'loading' | 'error'>('idle');
  readonly formError = signal<string | null>(null);

  // Create form
  readonly code = signal('');
  readonly name = signal('');
  readonly saving = signal(false);

  load(orgUid: string): void {
    this.organisationUid.set(orgUid);
    if (!orgUid) {
      return;
    }
    this.state.set('loading');
    this.companyService.list(orgUid).subscribe({
      next: (rows) => {
        this.companies.set(rows);
        this.state.set('idle');
      },
      error: () => this.state.set('error'),
    });
  }

  create(): void {
    const orgUid = this.organisationUid();
    if (!orgUid || !this.code().trim() || !this.name().trim()) {
      this.formError.set('Code and name are required.');
      return;
    }
    this.saving.set(true);
    this.formError.set(null);
    this.companyService
      .create({ organisationUid: orgUid, code: this.code().trim(), name: this.name().trim() })
      .subscribe({
        next: (created) => {
          this.companies.update((list) => [...list, created]);
          this.code.set('');
          this.name.set('');
          this.saving.set(false);
        },
        error: (err) => {
          this.formError.set(this.messageFrom(err));
          this.saving.set(false);
        },
      });
  }

  private messageFrom(err: unknown): string {
    const errors = (err as { error?: { errors?: string[] } })?.error?.errors;
    return errors?.length ? errors[0] : 'Could not save the company.';
  }
}
