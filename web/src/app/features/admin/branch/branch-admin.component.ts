import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Company } from '../models/company.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { BranchListComponent } from './branch-list.component';

/**
 * Standalone branch administration: pick one of the companies you can act in, then manage its
 * branches. This decouples branch management from the Companies screen so a user who holds
 * BRANCH.VIEW / BRANCH.MANAGE but NOT COMPANY.VIEW can still reach branches — previously the only
 * navigation path to a branch list was a "Manage branches" link on the COMPANY.VIEW-gated Companies
 * page (and there was no "Branches" nav item at all), stranding branch permissions.
 *
 * The company picker is fed by GET /companies/accessible (auth-only, scoped to the companies the
 * user is assigned to via an active role grant), so opening this page never requires COMPANY.VIEW.
 * Selecting a company renders the existing BranchListComponent for it; changing the selection
 * re-creates that component (keyed `@for`) so it reloads — its load runs once on construction.
 */
@Component({
  selector: 'app-branch-admin',
  imports: [FormsModule, BranchListComponent],
  templateUrl: './branch-admin.component.html',
})
export class BranchAdminComponent {
  private readonly organisationService = inject(OrganisationService);
  private readonly companyService = inject(CompanyService);

  readonly orgState = signal<'loading' | 'ready' | 'error'>('loading');
  readonly companiesState = signal<'loading' | 'idle' | 'error'>('loading');
  readonly companies = signal<Company[]>([]);

  /** uid of the company whose branches are shown; '' = nothing picked yet. */
  readonly selectedCompanyUid = signal<string>('');

  /**
   * `['<uid>']` when a company is picked, else `[]`. Used as the `@for` source so the embedded
   * BranchListComponent is destroyed and recreated (and thus reloads its branches) whenever the
   * selected company changes.
   */
  readonly selectedKey = computed<string[]>(() => {
    const uid = this.selectedCompanyUid();
    return uid ? [uid] : [];
  });

  constructor() {
    this.organisationService.current().subscribe({
      next: (org) => {
        this.orgState.set('ready');
        this.loadCompanies(org.uid);
      },
      error: () => this.orgState.set('error'),
    });
  }

  private loadCompanies(orgUid: string): void {
    this.companiesState.set('loading');
    this.companyService.list(orgUid).subscribe({
      next: (rows) => {
        this.companies.set(rows);
        this.companiesState.set('idle');
        // Convenience: if the user can act in exactly one company, pick it for them.
        if (rows.length === 1) {
          this.selectedCompanyUid.set(rows[0].uid);
        }
      },
      error: () => this.companiesState.set('error'),
    });
  }
}
