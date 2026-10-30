import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router, provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { RegisterComponent } from './register.component';
import { envelope, failure, testUser } from '../../core/auth/test-helpers';

describe('RegisterComponent', () => {
  beforeEach(() => TestBed.configureTestingModule({ imports: [RegisterComponent], providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()] }));

  function create() {
    const fixture = TestBed.createComponent(RegisterComponent);
    fixture.detectChanges();
    const component = fixture.componentInstance as unknown as { form: RegisterComponent['form']; submit(): void; setType(t: 'customer' | 'driver'): void };
    return { fixture, component, el: fixture.nativeElement as HTMLElement };
  }

  it('requires email or phone, a valid password and matching confirmation', () => {
    const { component } = create();
    component.form.patchValue({ fullName: 'Nguyen Van A', password: 'short', confirmPassword: 'other' });
    expect(component.form.hasError('contactRequired')).toBe(true);
    expect(component.form.controls.password.hasError('passwordPolicy')).toBe(true);
    component.form.patchValue({ phone: '0912345678', password: 'Example-Password-123', confirmPassword: 'Example-Password-124' });
    expect(component.form.hasError('contactRequired')).toBe(false);
    expect(component.form.hasError('mismatch')).toBe(true);
    component.form.patchValue({ confirmPassword: 'Example-Password-123' });
    expect(component.form.valid).toBe(true);
    component.form.patchValue({ phone: '0123' });
    expect(component.form.controls.phone.invalid).toBe(true);
  });

  it('blocks submission while invalid', () => {
    const { component } = create();
    component.submit();
    TestBed.inject(HttpTestingController).expectNone(() => true);
  });

  it('requires licence fields only for drivers and posts to the driver endpoint', () => {
    const { component, fixture, el } = create();
    component.form.patchValue({ fullName: 'Nguyen Van B', email: 'b@example.com', password: 'Example-Password-123', confirmPassword: 'Example-Password-123' });
    expect(component.form.valid).toBe(true);
    component.setType('driver');
    fixture.detectChanges();
    expect(el.querySelector('#licenseNumber')).not.toBeNull();
    expect(component.form.valid).toBe(false);
    component.form.patchValue({ licenseNumber: '012345678901', licenseClass: 'C' });
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    component.submit();
    component.submit(); // double submit is ignored while loading
    const request = TestBed.inject(HttpTestingController).expectOne('/api/v1/auth/register/driver');
    expect(request.request.body).toMatchObject({ licenseNumber: '012345678901', licenseClass: 'C', email: 'b@example.com' });
    expect(request.request.body.role).toBeUndefined();
    request.flush(envelope({ user: testUser('DRIVER'), driverVerificationStatus: 'NOT_SUBMITTED' }), { status: 201, statusText: 'Created' });
    expect(navigate).toHaveBeenCalledWith(['/login'], { queryParams: { registered: 1 } });
  });

  it('shows server field errors next to the field', () => {
    const { component, fixture, el } = create();
    component.form.patchValue({ fullName: 'Nguyen Van A', email: 'dup@example.com', password: 'Example-Password-123', confirmPassword: 'Example-Password-123' });
    component.submit();
    TestBed.inject(HttpTestingController).expectOne('/api/v1/auth/register/customer')
      .flush(failure('Email đã được sử dụng', { email: 'Email đã được sử dụng' }), { status: 409, statusText: 'Conflict' });
    fixture.detectChanges();
    expect(el.querySelector('#email')?.parentElement?.textContent).toContain('Email đã được sử dụng');
  });
});
