import { Routes } from '@angular/router';
import { requirePermission } from '../../core/auth/permission.guard';

/**
 * Admin (IAM) feature routes, lazy-loaded from app.routes.ts.
 * Slice 1: companies + branches. Slice 3: roles + role-grants.
 * Each feature route is guarded by its view permission — a user lacking it is redirected to the
 * neutral admin home rather than landing on a screen they can't use. The default ('') goes to home,
 * NOT companies, so no user is dropped onto a permissioned screen by the redirect.
 * Path params (`:companyUid`, `:uid`) are bound to required component inputs via withComponentInputBinding.
 */
export const ADMIN_ROUTES: Routes = [
  {
    path: 'home',
    loadComponent: () =>
      import('./home/admin-home.component').then((m) => m.AdminHomeComponent),
  },
  {
    path: 'companies',
    canActivate: [requirePermission('COMPANY.VIEW')],
    loadComponent: () =>
      import('./company/company-list.component').then((m) => m.CompanyListComponent),
  },
  {
    path: 'companies/:companyUid/branches',
    canActivate: [requirePermission('BRANCH.VIEW')],
    loadComponent: () =>
      import('./branch/branch-list.component').then((m) => m.BranchListComponent),
  },
  {
    path: 'roles',
    canActivate: [requirePermission('ROLE.VIEW')],
    loadComponent: () =>
      import('./role/role-list.component').then((m) => m.RoleListComponent),
  },
  {
    path: 'roles/:uid',
    canActivate: [requirePermission('ROLE.MANAGE')],
    loadComponent: () =>
      import('./role/role-edit.component').then((m) => m.RoleEditComponent),
  },
  {
    path: 'role-grants',
    canActivate: [requirePermission('ROLE.MANAGE')],
    loadComponent: () =>
      import('./user-role/grant-role.component').then((m) => m.GrantRoleComponent),
  },
  {
    path: 'users',
    canActivate: [requirePermission('USER.VIEW')],
    loadComponent: () =>
      import('./user/user-list.component').then((m) => m.UserListComponent),
  },
  {
    path: 'users/:uid',
    canActivate: [requirePermission('USER.VIEW')],
    loadComponent: () =>
      import('./user/user-detail.component').then((m) => m.UserDetailComponent),
  },
  { path: '', redirectTo: 'home', pathMatch: 'full' },
];
