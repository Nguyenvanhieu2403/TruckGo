import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { toApiError } from '../../core/api.service';
import { HOME_BY_ROLE } from '../../core/auth/auth.models';
import { AuthService } from '../../core/auth/auth.service';
import { fieldError } from '../../shared/form-utils';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [ReactiveFormsModule, RouterLink],
  template: `
  <main class="auth-page">
    <section class="auth-card">
      <a routerLink="/" class="kicker">← TruckGo</a>
      <h1>Đăng nhập</h1>
      <p class="sub">Dùng email hoặc số điện thoại đã đăng ký.</p>
      @if (notice()) { <div class="alert alert-info" role="status">{{ notice() }}</div> }
      @if (error()) { <div class="alert alert-error" role="alert">{{ error() }}</div> }
      <form [formGroup]="form" (ngSubmit)="submit()" novalidate>
        <div class="field" [class.invalid]="err('identifier')">
          <label for="identifier">Email hoặc số điện thoại</label>
          <input id="identifier" formControlName="identifier" autocomplete="username" />
          @if (err('identifier'); as e) { <span class="error">{{ e }}</span> }
        </div>
        <div class="field" [class.invalid]="err('password')">
          <label for="password">Mật khẩu</label>
          <input id="password" type="password" formControlName="password" autocomplete="current-password" />
          @if (err('password'); as e) { <span class="error">{{ e }}</span> }
        </div>
        <button class="btn btn-primary btn-block" type="submit" [disabled]="loading()">{{ loading() ? 'Đang đăng nhập…' : 'Đăng nhập' }}</button>
      </form>
      <p class="auth-foot">Chưa có tài khoản? <a routerLink="/register">Đăng ký</a></p>
    </section>
  </main>`,
})
export class LoginComponent {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly notice = signal<string | null>(this.initialNotice());
  protected readonly form = inject(FormBuilder).nonNullable.group({
    identifier: ['', [Validators.required, Validators.maxLength(254)]],
    password: ['', [Validators.required, Validators.maxLength(200)]],
  });

  err(name: 'identifier' | 'password'): string | null {
    return fieldError(this.form.controls[name], { required: name === 'identifier' ? 'Vui lòng nhập email hoặc số điện thoại' : 'Vui lòng nhập mật khẩu', maxlength: 'Giá trị quá dài' });
  }

  submit(): void {
    if (this.loading()) return;
    this.form.markAllAsTouched();
    if (this.form.invalid) return;
    this.loading.set(true);
    this.error.set(null);
    const { identifier, password } = this.form.getRawValue();
    this.auth.login(identifier.trim(), password).subscribe({
      next: user => {
        const returnUrl = this.route.snapshot.queryParamMap.get('returnUrl');
        const target = returnUrl && returnUrl.startsWith('/') && !returnUrl.startsWith('//') ? returnUrl : HOME_BY_ROLE[user.role];
        void this.router.navigateByUrl(target);
      },
      error: (e: unknown) => {
        const apiError = toApiError(e);
        this.error.set(apiError.status === 400 ? 'Vui lòng kiểm tra lại thông tin đăng nhập' : apiError.message);
        this.form.controls.password.reset('');
        this.loading.set(false);
      },
    });
  }

  private initialNotice(): string | null {
    const params = inject(ActivatedRoute).snapshot.queryParamMap;
    if (params.get('registered')) return 'Đăng ký thành công. Vui lòng đăng nhập để tiếp tục.';
    if (params.get('expired')) return 'Phiên đăng nhập đã hết hạn, vui lòng đăng nhập lại.';
    return null;
  }
}
