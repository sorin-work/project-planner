import { Routes } from '@angular/router';
import { authGuard, guestGuard } from './core/auth.guard';
import { Shell } from './features/shell/shell';

export const routes: Routes = [
  {
    path: 'login',
    canActivate: [guestGuard],
    loadComponent: () => import('./features/auth/login.page').then((m) => m.LoginPage),
  },
  {
    path: 'signup',
    canActivate: [guestGuard],
    loadComponent: () => import('./features/auth/signup.page').then((m) => m.SignupPage),
  },
  {
    path: '',
    component: Shell,
    canActivate: [authGuard],
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'projects' },
      { path: 'projects', loadComponent: () => import('./features/projects/projects.page').then((m) => m.ProjectsPage) },
      {
        path: 'projects/:projectId',
        loadComponent: () => import('./features/project/project.page').then((m) => m.ProjectPage),
        loadChildren: () => import('./features/project/project.routes').then((m) => m.projectRoutes),
      },
    ],
  },
  { path: '**', redirectTo: '' },
];
