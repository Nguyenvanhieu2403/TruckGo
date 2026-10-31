import { Routes } from '@angular/router';
import { HomeComponent } from './features/home/home.component';
import { SystemComponent } from './features/system/system.component';
import { PlaceholderComponent } from './shared/placeholder.component';
import { AppShellComponent } from './shared/app-shell.component';
import { authGuard, guestGuard, roleGuard } from './core/auth/auth.guards';
import { unsavedChangesGuard } from './features/shipments/unsaved-changes.guard';

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
      {
        path: 'customer', canActivate: [roleGuard('CUSTOMER')],
        children: [
          { path: '', pathMatch: 'full', loadComponent: () => import('./features/customer/customer-home.component').then(m => m.CustomerHomeComponent) },
          { path: 'shipments', loadComponent: () => import('./features/shipments/shipment-list.component').then(m => m.ShipmentListComponent) },
          { path: 'shipments/create', canDeactivate: [unsavedChangesGuard], loadComponent: () => import('./features/shipments/shipment-wizard.component').then(m => m.ShipmentWizardComponent) },
          { path: 'shipments/:id', loadComponent: () => import('./features/shipments/shipment-detail.component').then(m => m.ShipmentDetailComponent) },
          { path: 'shipments/:shipmentId/tracking', loadComponent: () => import('./features/trips/customer-tracking.component').then(m => m.CustomerTrackingComponent) },
          { path: 'shipments/:id/edit', canDeactivate: [unsavedChangesGuard], loadComponent: () => import('./features/shipments/shipment-wizard.component').then(m => m.ShipmentWizardComponent) },
        ],
      },
      {
        path: 'driver', canActivate: [roleGuard('DRIVER')],
        children: [
          { path: '', pathMatch: 'full', redirectTo: 'marketplace' },
          { path: 'trips', loadComponent: () => import('./features/trips/driver-trips.component').then(m => m.DriverTripsComponent) },
          { path: 'trips/:shipmentId', loadComponent: () => import('./features/trips/driver-trip-detail.component').then(m => m.DriverTripDetailComponent) },
          { path: 'trips/:shipmentId/navigation', loadComponent: () => import('./features/trips/driver-trip-navigation.component').then(m => m.DriverTripNavigationComponent) },
          { path: 'trips/:shipmentId/incidents', loadComponent: () => import('./features/trips/driver-trip-incidents.component').then(m => m.DriverTripIncidentsComponent) },
          { path: 'marketplace', loadComponent: () => import('./features/marketplace/driver-marketplace.component').then(m => m.DriverMarketplaceComponent) },
          { path: 'marketplace/:shipmentId', loadComponent: () => import('./features/marketplace/driver-marketplace-detail.component').then(m => m.DriverMarketplaceDetailComponent) },
          { path: 'applications', loadComponent: () => import('./features/marketplace/driver-applications.component').then(m => m.DriverApplicationsComponent) },
          { path: 'applications/:applicationId', loadComponent: () => import('./features/marketplace/driver-application-detail.component').then(m => m.DriverApplicationDetailComponent) },
          { path: 'verification', loadComponent: () => import('./features/driver/driver-verification.component').then(m => m.DriverVerificationComponent) },
          { path: 'profile', loadComponent: () => import('./features/driver/driver-profile.component').then(m => m.DriverProfileComponent) },
          { path: 'vehicles', loadComponent: () => import('./features/driver/driver-vehicles.component').then(m => m.DriverVehiclesComponent) },
        ],
      },
      {
        path: 'admin', canActivate: [roleGuard('ADMIN')],
        children: [
          { path: '', pathMatch: 'full', redirectTo: 'drivers' },
          { path: 'drivers', loadComponent: () => import('./features/admin/admin-drivers.component').then(m => m.AdminDriversComponent) },
          { path: 'vehicles', loadComponent: () => import('./features/admin/admin-vehicles.component').then(m => m.AdminVehiclesComponent) },
          { path: 'users', loadComponent: () => import('./features/admin/admin-users.component').then(m => m.AdminUsersComponent) },
          { path: 'shipments', data: { admin: true }, loadComponent: () => import('./features/shipments/shipment-list.component').then(m => m.ShipmentListComponent) },
          { path: 'shipments/:id', data: { admin: true }, loadComponent: () => import('./features/shipments/shipment-detail.component').then(m => m.ShipmentDetailComponent) },
          { path: 'pricing', loadComponent: () => import('./features/pricing/admin-pricing-list.component').then(m => m.AdminPricingListComponent) },
          { path: 'pricing/new', loadComponent: () => import('./features/pricing/admin-pricing-form.component').then(m => m.AdminPricingFormComponent) },
          { path: 'pricing/:id', loadComponent: () => import('./features/pricing/admin-pricing-form.component').then(m => m.AdminPricingFormComponent) },
          { path: 'marketplace', loadComponent: () => import('./features/admin/admin-marketplace.component').then(m => m.AdminMarketplaceComponent) },
          { path: 'incidents', loadComponent: () => import('./features/admin/admin-incidents.component').then(m => m.AdminIncidentsComponent) },
          { path: 'incidents/:id', loadComponent: () => import('./features/admin/admin-incident-detail.component').then(m => m.AdminIncidentDetailComponent) },
          { path: 'quotes', loadComponent: () => import('./features/pricing/admin-quotes.component').then(m => m.AdminQuotesComponent) },
        ],
      },
    ],
  },
  { path: '**', redirectTo: '' },
];
