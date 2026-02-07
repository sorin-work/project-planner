import { Routes } from '@angular/router';
import { guestGuard } from './core/auth.guard';

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
  { path: '**', redirectTo: 'login' },
];
