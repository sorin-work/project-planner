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
      { path: 'time', loadComponent: () => import('./features/time/timesheet.page').then((m) => m.TimesheetPage) },
      { path: 'approvals', loadComponent: () => import('./features/approvals/approvals.page').then((m) => m.ApprovalsPage) },
      { path: 'workload', loadComponent: () => import('./features/workload/workload.page').then((m) => m.WorkloadPage) },
      { path: 'clients', loadComponent: () => import('./features/clients/clients.page').then((m) => m.ClientsPage) },
      {
        path: 'notifications',
        loadComponent: () => import('./features/notifications/notifications.page').then((m) => m.NotificationsPage),
      },
    ],
  },
  { path: '**', redirectTo: '' },
];
