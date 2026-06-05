import { Routes } from '@angular/router';
import { ShellComponent } from './layout/shell/shell.component';
import { authGuard } from './core/auth/auth.guard';

/**
 * Top-level routes. The login page stands alone (no shell). Everything under the shell is gated by
 * authGuard; feature areas (admin/iam first) lazy-load as children.
 */
export const routes: Routes = [
  {
    path: 'login',
    loadComponent: () =>
      import('./features/auth/login/login.component').then((m) => m.LoginComponent),
  },
  {
    path: '',
    component: ShellComponent,
    canActivate: [authGuard],
    children: [
      {
        path: 'admin',
        loadChildren: () =>
          import('./features/admin/admin.routes').then((m) => m.ADMIN_ROUTES),
      },
      { path: '', redirectTo: 'admin', pathMatch: 'full' },
    ],
  },
];
