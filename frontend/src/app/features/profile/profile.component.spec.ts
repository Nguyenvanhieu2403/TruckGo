import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it } from 'vitest';
import { ProfileComponent } from './profile.component';
import { envelope, testUser } from '../../core/auth/test-helpers';

describe('ProfileComponent', () => {
  beforeEach(() => TestBed.configureTestingModule({ imports: [ProfileComponent], providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()] }));

  it('loads the current user from the API and fills the form', () => {
    const fixture = TestBed.createComponent(ProfileComponent);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/users/me').flush(envelope({ ...testUser(), fullName: 'Tran Thi C', phone: null }));
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('a@example.com');
    expect(el.textContent).toContain('Khách hàng');
    expect((el.querySelector('#fullName') as HTMLInputElement).value).toBe('Tran Thi C');
  });

  it('shows an error when the profile cannot be loaded', () => {
    const fixture = TestBed.createComponent(ProfileComponent);
    fixture.detectChanges();
    TestBed.inject(HttpTestingController).expectOne('/api/v1/users/me').flush({ success: false, message: 'Không tìm thấy người dùng', data: null }, { status: 404, statusText: 'Not Found' });
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Không tìm thấy người dùng');
  });
});
