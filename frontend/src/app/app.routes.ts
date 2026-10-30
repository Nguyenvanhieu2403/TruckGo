import { Routes } from '@angular/router';
import { HomeComponent } from './features/home/home.component';
import { SystemComponent } from './features/system/system.component';
import { PlaceholderComponent } from './shared/placeholder.component';

export const routes: Routes = [
  { path: '', component: HomeComponent },
  { path: 'he-thong', component: SystemComponent },
  { path: 'dang-phat-trien', component: PlaceholderComponent },
  { path: '**', redirectTo: '' },
];
