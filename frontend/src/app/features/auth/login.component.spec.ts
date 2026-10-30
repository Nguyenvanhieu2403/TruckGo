import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router, provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { LoginComponent } from './login.component';
import { failure, tokenEnvelope } from '../../core/auth/test-helpers';
import { UserRole } from '../../core/auth/auth.models';

describe('LoginComponent', () => {
  beforeEach(() => TestBed.configureTestingModule({ imports: [LoginComponent], providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()] }));

  function submit(identifier: string, password: string) {
    const fixture = TestBed.createComponent(LoginComponent);
    fixture.detectChanges();
    const component = fixture.componentInstance as unknown as { form: LoginComponent['form']; submit(): void };
    component.form.setValue({ identifier, password });
    component.submit();
    return { fixture, http: TestBed.inject(HttpTestingController) };
  }

  for (const [role, target] of [['CUSTOMER', '/customer'], ['DRIVER', '/driver/verification'], ['ADMIN', '/admin/drivers']] as [UserRole, string][]) {
    it(`sends ${role} to ${target}`, () => {
      const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
      const { http } = submit(' a@example.com ', 'Example-Password-123');
      const request = http.expectOne('/api/v1/auth/login');
      expect(request.request.body).toEqual({ identifier: 'a@example.com', password: 'Example-Password-123' });
      expect(request.request.withCredentials).toBe(true);
      request.flush(tokenEnvelope('t', role));
      expect(navigate).toHaveBeenCalledWith(target);
    });
  }

  it('shows the API error and clears the password on failure', () => {
    const { fixture, http } = submit('a@example.com', 'Wrong-1');
    http.expectOne('/api/v1/auth/login').flush(failure('Thông tin đăng nhập không chính xác'), { status: 401, statusText: 'Unauthorized' });
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('[role=alert]')?.textContent).toContain('Thông tin đăng nhập không chính xác');
    expect((fixture.componentInstance as unknown as { form: LoginComponent['form'] }).form.controls.password.value).toBe('');
  });

  it('does not call the API when fields are empty', () => {
    const { http } = submit('', '');
    http.expectNone('/api/v1/auth/login');
  });
});
