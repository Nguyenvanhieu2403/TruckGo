import { Routes } from '@angular/router';
import { HomeComponent } from './features/home/home.component';
import { SystemComponent } from './features/system/system.component';
import { PlaceholderComponent } from './shared/placeholder.component';
import { AppShellComponent } from './shared/app-shell.component';
import { authGuard, guestGuard, roleGuard } from './core/auth/auth.guards';

export const routes: Routes = [
  { path: '', component: HomeComponent },
  { path: 'he-thong', component: SystemComponent },
  { path: 'dang-phat-trien', component: PlaceholderComponent },
  { path: 'login', canActivate: [guestGuard], loadComponent: () => import('./features/auth/login.component').then(m => m.LoginComponent) },
  { path: 'register', canActivate: [guestGuard], loadComponent: () => import('./features/auth/register.component').then(m => m.RegisterComponent) },
  {
    path: '', component: AppShellComponent, canActivate: [authGuard],
    children: [
      { path: 'profile', loadComponent: () => import('./features/profile/profile.component').then(m => m.ProfileComponent) },
      { path: 'customer', canActivate: [roleGuard('CUSTOMER')], loadComponent: () => import('./features/customer/customer-home.component').then(m => m.CustomerHomeComponent) },
      {
        path: 'driver', canActivate: [roleGuard('DRIVER')],
        children: [
          { path: '', pathMatch: 'full', redirectTo: 'verification' },
          { path: 'verification', loadComponent: () => import('./features/driver/driver-verification.component').then(m => m.DriverVerificationComponent) },
          { path: 'profile', loadComponent: () => import('./features/driver/driver-profile.component').then(m => m.DriverProfileComponent) },
          { path: 'vehicles', loadComponent: () => import('./features/driver/driver-vehicles.component').then(m => m.DriverVehiclesComponent) },
        ],
      },
    ],
  },
  { path: '**', redirectTo: '' },
];
