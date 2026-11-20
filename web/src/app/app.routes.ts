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
    path: 'forgot',
    canActivate: [guestGuard],
    loadComponent: () => import('./features/auth/forgot.page').then((m) => m.ForgotPage),
  },
  {
    path: 'reset',
    canActivate: [guestGuard],
    loadComponent: () => import('./features/auth/reset.page').then((m) => m.ResetPage),
  },
  {
    // Not for guests only: somebody who is signed in accepts with the account they are in.
    path: 'invite',
    loadComponent: () => import('./features/auth/invite.page').then((m) => m.InvitePage),
  },
  {
    path: '',
    component: Shell,
    canActivate: [authGuard],
    children: [
      { path: '', loadComponent: () => import('./features/dashboard/dashboard.page').then((m) => m.DashboardPage) },
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
      { path: 'team', loadComponent: () => import('./features/team/team.page').then((m) => m.TeamPage) },
      { path: 'reports', loadComponent: () => import('./features/reports/reports.page').then((m) => m.ReportsPage) },
      {
        path: 'notifications',
        loadComponent: () => import('./features/notifications/notifications.page').then((m) => m.NotificationsPage),
      },
      { path: 'settings', loadComponent: () => import('./features/settings/settings.page').then((m) => m.SettingsPage) },
    ],
  },
  { path: '**', redirectTo: '' },
];
