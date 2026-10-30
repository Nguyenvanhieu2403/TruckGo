import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { HOME_BY_ROLE, UserRole } from './auth.models';
import { AuthService } from './auth.service';

/** UX only: guards hide screens, the backend enforces every permission independently. */
export const authGuard: CanActivateFn = (_route, state) => {
  const auth = inject(AuthService);
  return auth.isAuthenticated() ? true : inject(Router).createUrlTree(['/login'], { queryParams: { returnUrl: state.url } });
};

export function roleGuard(...roles: UserRole[]): CanActivateFn {
  return (_route, state) => {
    const auth = inject(AuthService);
    const router = inject(Router);
    const role = auth.role();
    if (!auth.isAuthenticated() || role === null) return router.createUrlTree(['/login'], { queryParams: { returnUrl: state.url } });
    return roles.includes(role) ? true : router.createUrlTree([HOME_BY_ROLE[role]]);
  };
}

/** Keeps signed-in users away from /login and /register. */
export const guestGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  const role = auth.role();
  return auth.isAuthenticated() && role ? inject(Router).createUrlTree([HOME_BY_ROLE[role]]) : true;
};
