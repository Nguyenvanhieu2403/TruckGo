import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, ValidationErrors, Validators, AbstractControl } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Observable } from 'rxjs';
import { toApiError } from '../../core/api.service';
import { RegistrationResponse } from '../../core/auth/auth.models';
import { AuthService } from '../../core/auth/auth.service';
import { VN_PHONE_PATTERN, applyServerErrors, fieldError, matchFields, passwordPolicy } from '../../shared/form-utils';

type AccountType = 'customer' | 'driver';

/** Driver licence classes accepted by the backend default configuration (app.driver.allowed-license-classes). */
export const LICENSE_CLASSES = ['B', 'B2', 'C1', 'C', 'CE', 'D', 'FC'];

function contactRequired(group: AbstractControl): ValidationErrors | null {
  return group.get('email')?.value?.trim() || group.get('phone')?.value?.trim() ? null : { contactRequired: true };
}

@Component({
  selector: 'app-register',
  standalone: true,
  imports: [ReactiveFormsModule, RouterLink],
  template: `
  <main class="auth-page">
    <section class="auth-card">
      <a routerLink="/" class="kicker">← TruckGo</a>
      <h1>Tạo tài khoản</h1>
      <p class="sub">Chọn loại tài khoản phù hợp với bạn.</p>
      <div class="segmented" role="group" aria-label="Loại tài khoản">
        <button type="button" [attr.aria-pressed]="type() === 'customer'" (click)="setType('customer')">Khách hàng thuê xe</button>
        <button type="button" [attr.aria-pressed]="type() === 'driver'" (click)="setType('driver')">Tài xế</button>
      </div>
      @if (error()) { <div class="alert alert-error" role="alert">{{ error() }}</div> }
      <form [formGroup]="form" (ngSubmit)="submit()" novalidate>
        <div class="field" [class.invalid]="err('fullName')">
          <label for="fullName">Họ và tên</label>
          <input id="fullName" formControlName="fullName" autocomplete="name" />
          @if (err('fullName'); as e) { <span class="error">{{ e }}</span> }
        </div>
        <div class="grid-2">
          <div class="field" [class.invalid]="err('email')">
            <label for="email">Email</label>
            <input id="email" type="email" formControlName="email" autocomplete="email" />
            @if (err('email'); as e) { <span class="error">{{ e }}</span> }
          </div>
          <div class="field" [class.invalid]="err('phone')">
            <label for="phone">Số điện thoại</label>
            <input id="phone" type="tel" formControlName="phone" autocomplete="tel" placeholder="09xx xxx xxx" />
            @if (err('phone'); as e) { <span class="error">{{ e }}</span> }
          </div>
        </div>
        @if (form.hasError('contactRequired') && (form.controls.email.touched || form.controls.phone.touched)) {
          <p class="field error" role="alert">Cần nhập ít nhất email hoặc số điện thoại.</p>
        }
        <div class="grid-2">
          <div class="field" [class.invalid]="err('password')">
            <label for="password">Mật khẩu</label>
            <input id="password" type="password" formControlName="password" autocomplete="new-password" />
            @if (err('password'); as e) { <span class="error">{{ e }}</span> } @else { <span class="hint">8–72 ký tự, gồm chữ và số</span> }
          </div>
          <div class="field" [class.invalid]="err('confirmPassword') || mismatch()">
            <label for="confirmPassword">Xác nhận mật khẩu</label>
            <input id="confirmPassword" type="password" formControlName="confirmPassword" autocomplete="new-password" />
            @if (err('confirmPassword'); as e) { <span class="error">{{ e }}</span> }
            @else if (mismatch()) { <span class="error">Mật khẩu xác nhận không khớp</span> }
          </div>
        </div>
        @if (type() === 'driver') {
          <div class="grid-2">
            <div class="field" [class.invalid]="err('licenseNumber')">
              <label for="licenseNumber">Số giấy phép lái xe</label>
              <input id="licenseNumber" formControlName="licenseNumber" inputmode="numeric" />
              @if (err('licenseNumber'); as e) { <span class="error">{{ e }}</span> } @else { <span class="hint">12 chữ số in trên GPLX</span> }
            </div>
            <div class="field" [class.invalid]="err('licenseClass')">
              <label for="licenseClass">Hạng giấy phép</label>
              <select id="licenseClass" formControlName="licenseClass">
                <option value="">— Chọn hạng —</option>
                @for (c of licenseClasses; track c) { <option [value]="c">{{ c }}</option> }
              </select>
              @if (err('licenseClass'); as e) { <span class="error">{{ e }}</span> }
            </div>
          </div>
          <div class="alert alert-info">Sau khi đăng ký, hồ sơ tài xế cần được quản trị viên duyệt trước khi có thể nhận đơn.</div>
        }
        <button class="btn btn-primary btn-block" type="submit" [disabled]="loading()">{{ loading() ? 'Đang tạo tài khoản…' : 'Đăng ký' }}</button>
      </form>
      <p class="auth-foot">Đã có tài khoản? <a routerLink="/login">Đăng nhập</a></p>
    </section>
  </main>`,
})
export class RegisterComponent {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  protected readonly licenseClasses = LICENSE_CLASSES;
  protected readonly type = signal<AccountType>(inject(ActivatedRoute).snapshot.queryParamMap.get('role') === 'driver' ? 'driver' : 'customer');
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly form = inject(FormBuilder).nonNullable.group({
    fullName: ['', [Validators.required, Validators.minLength(2), Validators.maxLength(100)]],
    email: ['', [Validators.email, Validators.maxLength(254)]],
    phone: ['', [Validators.pattern(VN_PHONE_PATTERN)]],
    password: ['', [Validators.required, passwordPolicy]],
    confirmPassword: ['', [Validators.required]],
    licenseNumber: ['', [Validators.required, Validators.pattern(/^[0-9\s.-]{12,20}$/)]],
    licenseClass: ['', [Validators.required]],
  }, { validators: [contactRequired, matchFields('password', 'confirmPassword')] });

  constructor() { this.syncDriverFields(); }

  setType(type: AccountType): void {
    this.type.set(type);
    this.syncDriverFields();
  }

  mismatch(): boolean { return this.form.hasError('mismatch') && this.form.controls.confirmPassword.touched; }

  err(name: keyof typeof this.form.controls): string | null {
    return fieldError(this.form.controls[name], {
      required: 'Trường này là bắt buộc', minlength: 'Họ tên dài 2–100 ký tự', maxlength: 'Giá trị quá dài', email: 'Email không hợp lệ',
      pattern: name === 'phone' ? 'Số điện thoại di động Việt Nam không hợp lệ' : 'Số giấy phép gồm 12 chữ số', passwordPolicy: 'Mật khẩu phải dài 8–72 ký tự và chứa cả chữ lẫn số',
    });
  }

  submit(): void {
    if (this.loading()) return;
    this.form.markAllAsTouched();
    if (this.form.invalid) return;
    this.loading.set(true);
    this.error.set(null);
    const v = this.form.getRawValue();
    const base = { fullName: v.fullName.trim(), email: v.email.trim() || undefined, phone: v.phone.trim() || undefined, password: v.password, confirmPassword: v.confirmPassword };
    const request$: Observable<RegistrationResponse> = this.type() === 'driver'
      ? this.auth.registerDriver({ ...base, licenseNumber: v.licenseNumber, licenseClass: v.licenseClass })
      : this.auth.registerCustomer(base);
    request$.subscribe({
      next: () => void this.router.navigate(['/login'], { queryParams: { registered: 1 } }),
      error: (e: unknown) => {
        const apiError = toApiError(e);
        const unmatched = applyServerErrors(this.form, apiError.fieldErrors);
        this.error.set(unmatched.length ? unmatched.join(' ') : apiError.message);
        this.loading.set(false);
      },
    });
  }

  private syncDriverFields(): void {
    for (const name of ['licenseNumber', 'licenseClass'] as const) {
      const control = this.form.controls[name];
      if (this.type() === 'driver') control.enable(); else control.disable();
    }
  }
}
