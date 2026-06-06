import { Routes } from '@angular/router';

/**
 * Admin (IAM) feature routes, lazy-loaded from app.routes.ts.
 * Slice 1: companies + branches. Slice 3: roles + role-grants.
 * Path params (`:companyUid`, `:uid`) are bound to required component inputs via withComponentInputBinding.
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
  {
    path: 'roles',
    loadComponent: () =>
      import('./role/role-list.component').then((m) => m.RoleListComponent),
  },
  {
    path: 'roles/:uid',
    loadComponent: () =>
      import('./role/role-edit.component').then((m) => m.RoleEditComponent),
  },
  {
    path: 'role-grants',
    loadComponent: () =>
      import('./user-role/grant-role.component').then((m) => m.GrantRoleComponent),
  },
  { path: '', redirectTo: 'companies', pathMatch: 'full' },
];
