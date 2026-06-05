import { Routes } from '@angular/router';

/**
 * Admin (IAM) feature routes, lazy-loaded from app.routes.ts. Slice 1: companies + their branches.
 * The `:companyUid` param is bound to BranchListComponent's required input via withComponentInputBinding.
 */
export const ADMIN_ROUTES: Routes = [
  {
    path: 'companies',
    loadComponent: () =>
      import('./company/company-list.component').then((m) => m.CompanyListComponent),
  },
  {
    path: 'companies/:companyUid/branches',
    loadComponent: () =>
      import('./branch/branch-list.component').then((m) => m.BranchListComponent),
  },
  { path: '', redirectTo: 'companies', pathMatch: 'full' },
];
