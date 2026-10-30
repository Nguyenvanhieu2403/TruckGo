import { Routes } from '@angular/router';
import { HomeComponent } from './features/home/home.component';
import { SystemComponent } from './features/system/system.component';
import { PlaceholderComponent } from './shared/placeholder.component';
import { guestGuard } from './core/auth/auth.guards';

export const routes: Routes = [
  { path: '', component: HomeComponent },
  { path: 'he-thong', component: SystemComponent },
  { path: 'dang-phat-trien', component: PlaceholderComponent },
  { path: 'login', canActivate: [guestGuard], loadComponent: () => import('./features/auth/login.component').then(m => m.LoginComponent) },
  { path: 'register', canActivate: [guestGuard], loadComponent: () => import('./features/auth/register.component').then(m => m.RegisterComponent) },
  { path: '**', redirectTo: '' },
];
