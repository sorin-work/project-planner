import { Routes } from '@angular/router';
import { authGuard, guestGuard } from './core/auth.guard';
import { Shell } from './features/shell/shell';

export const routes: Routes = [
  {
    path: 'login',
    canActivate: [guestGuard],
    title: 'Sign in', loadComponent: () => import('./features/auth/login.page').then((m) => m.LoginPage),
  },
  {
    path: 'signup',
    canActivate: [guestGuard],
    title: 'Sign up', loadComponent: () => import('./features/auth/signup.page').then((m) => m.SignupPage),
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
    // Works without signing in, from the stop link of a notification mail.
    path: 'unsubscribe',
    loadComponent: () => import('./features/auth/unsubscribe.page').then((m) => m.UnsubscribePage),
  },
  {
    path: '',
    component: Shell,
    canActivate: [authGuard],
    children: [
      { path: '', title: 'Dashboard', loadComponent: () => import('./features/dashboard/dashboard.page').then((m) => m.DashboardPage) },
      { path: 'projects', title: 'Projects', loadComponent: () => import('./features/projects/projects.page').then((m) => m.ProjectsPage) },
      {
        path: 'projects/:projectId',
        title: 'Project', loadComponent: () => import('./features/project/project.page').then((m) => m.ProjectPage),
        loadChildren: () => import('./features/project/project.routes').then((m) => m.projectRoutes),
      },
      { path: 'time', title: 'Timesheet', loadComponent: () => import('./features/time/timesheet.page').then((m) => m.TimesheetPage) },
      { path: 'approvals', title: 'Approvals', loadComponent: () => import('./features/approvals/approvals.page').then((m) => m.ApprovalsPage) },
      { path: 'workload', title: 'Workload', loadComponent: () => import('./features/workload/workload.page').then((m) => m.WorkloadPage) },
      { path: 'clients', title: 'Clients', loadComponent: () => import('./features/clients/clients.page').then((m) => m.ClientsPage) },
      { path: 'team', title: 'Team', loadComponent: () => import('./features/team/team.page').then((m) => m.TeamPage) },
      { path: 'reports', title: 'Reports', loadComponent: () => import('./features/reports/reports.page').then((m) => m.ReportsPage) },
      {
        path: 'notifications',
        title: 'Notifications', loadComponent: () => import('./features/notifications/notifications.page').then((m) => m.NotificationsPage),
      },
      { path: 'settings', title: 'Settings', loadComponent: () => import('./features/settings/settings.page').then((m) => m.SettingsPage) },
    ],
  },
  { path: '**', redirectTo: '' },
];
