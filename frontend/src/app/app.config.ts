import { ApplicationConfig, inject, provideAppInitializer } from '@angular/core';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { routes } from './app.routes';
import { requestIdInterceptor } from './core/request-id.interceptor';
import { authInterceptor } from './core/auth/auth.interceptor';
import { AuthService } from './core/auth/auth.service';

export const appConfig: ApplicationConfig = {
  providers: [
    // Binds route data such as { admin: true } to component inputs.
    provideRouter(routes, withComponentInputBinding()),
    provideHttpClient(withInterceptors([requestIdInterceptor, authInterceptor])),
    // The access token lives only in memory, so a reload restores the session from the HttpOnly refresh cookie
    // before the first route guard runs.
    provideAppInitializer(() => firstValueFrom(inject(AuthService).restoreSession())),
  ],
};
