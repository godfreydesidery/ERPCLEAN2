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
  {
    path: 'audit',
    canActivate: [requirePermission('AUDIT.VIEW')],
    loadComponent: () =>
      import('./audit/audit-list.component').then((m) => m.AuditListComponent),
  },
  // ── Parties ───────────────────────────────────────────────────────────────
  {
    path: 'customers',
    canActivate: [requirePermission('CUSTOMER.VIEW')],
    loadComponent: () =>
      import('./parties/customer-list.component').then((m) => m.CustomerListComponent),
  },
  {
    path: 'customers/uid/:uid',
    canActivate: [requirePermission('CUSTOMER.VIEW')],
    loadComponent: () =>
      import('./parties/customer-detail.component').then((m) => m.CustomerDetailComponent),
  },
  {
    path: 'suppliers',
    canActivate: [requirePermission('SUPPLIER.VIEW')],
    loadComponent: () =>
      import('./parties/supplier-list.component').then((m) => m.SupplierListComponent),
  },
  {
    path: 'suppliers/uid/:uid',
    canActivate: [requirePermission('SUPPLIER.VIEW')],
    loadComponent: () =>
      import('./parties/supplier-detail.component').then((m) => m.SupplierDetailComponent),
  },
  {
    path: 'agents',
    canActivate: [requirePermission('AGENT.VIEW')],
    loadComponent: () =>
      import('./parties/agent-list.component').then((m) => m.AgentListComponent),
  },
  {
    path: 'agents/uid/:uid',
    canActivate: [requirePermission('AGENT.VIEW')],
    loadComponent: () =>
      import('./parties/agent-detail.component').then((m) => m.AgentDetailComponent),
  },
  { path: '', redirectTo: 'home', pathMatch: 'full' },
];
