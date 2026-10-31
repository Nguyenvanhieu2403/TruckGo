import { ApplicationConfig, LOCALE_ID, inject, provideAppInitializer } from '@angular/core';
import { registerLocaleData } from '@angular/common';
import localeVi from '@angular/common/locales/vi';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { routes } from './app.routes';
import { requestIdInterceptor } from './core/request-id.interceptor';
import { authInterceptor } from './core/auth/auth.interceptor';
import { AuthService } from './core/auth/auth.service';

// Vietnamese number/date formatting everywhere (DecimalPipe would otherwise print 18.5 next to 640.175 VNĐ).
registerLocaleData(localeVi);

export const appConfig: ApplicationConfig = {
  providers: [
    { provide: LOCALE_ID, useValue: 'vi' },
    // Binds route data such as { admin: true } to component inputs.
    provideRouter(routes, withComponentInputBinding()),
    provideHttpClient(withInterceptors([requestIdInterceptor, authInterceptor])),
    // The access token lives only in memory, so a reload restores the session from the HttpOnly refresh cookie
    // before the first route guard runs.
    provideAppInitializer(() => firstValueFrom(inject(AuthService).restoreSession())),
  ],
};
