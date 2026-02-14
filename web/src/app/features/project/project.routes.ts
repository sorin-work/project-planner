import { Routes } from '@angular/router';

export const projectRoutes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'list' },
  { path: 'list', loadComponent: () => import('./list.tab').then((m) => m.ListTab) },
  { path: 'settings', loadComponent: () => import('./settings.tab').then((m) => m.SettingsTab) },
];
