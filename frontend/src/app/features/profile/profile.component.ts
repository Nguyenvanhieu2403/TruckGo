import { Component, OnInit, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { UserApi } from '../../core/api/account.api';
import { toApiError } from '../../core/api.service';
import { User } from '../../core/auth/auth.models';
import { AuthService } from '../../core/auth/auth.service';
import { VN_PHONE_PATTERN, applyServerErrors, fieldError, matchFields, passwordPolicy } from '../../shared/form-utils';

const ROLE_LABEL: Record<string, string> = { CUSTOMER: 'Khách hàng', DRIVER: 'Tài xế', ADMIN: 'Quản trị viên' };
const STATUS_LABEL: Record<string, string> = { ACTIVE: 'Đang hoạt động', PENDING_VERIFICATION: 'Chờ xác minh', SUSPENDED: 'Tạm ngưng', LOCKED: 'Đã khóa' };

@Component({
  selector: 'app-profile',
  standalone: true,
  imports: [ReactiveFormsModule, DatePipe],
  template: `
  <main class="container">
    <div class="page-head"><div><div class="kicker">Tài khoản</div><h1>Hồ sơ cá nhân</h1></div></div>
    @if (loadError()) { <div class="alert alert-error" role="alert">{{ loadError() }}</div> }
    @if (user(); as u) {
      <section class="card">
        <h2>Thông tin tài khoản</h2>
        <dl class="dl">
          <dt>Vai trò</dt><dd>{{ roleLabel[u.role] }}</dd>
          <dt>Trạng thái</dt><dd>{{ statusLabel[u.status] }}</dd>
          <dt>Email</dt><dd>{{ u.email || '—' }} @if (u.email) { <span class="muted">({{ u.emailVerified ? 'đã xác minh' : 'chưa xác minh' }})</span> }</dd>
          <dt>Số điện thoại</dt><dd>{{ u.phone || '—' }} @if (u.phone) { <span class="muted">({{ u.phoneVerified ? 'đã xác minh' : 'chưa xác minh' }})</span> }</dd>
          <dt>Ngày tạo</dt><dd>{{ u.createdAt | date: 'dd/MM/yyyy HH:mm' }}</dd>
        </dl>
      </section>
      <section class="card">
        <h2>Cập nhật thông tin</h2>
        @if (profileMessage()) { <div class="alert" [class.alert-success]="profileOk()" [class.alert-error]="!profileOk()" role="status">{{ profileMessage() }}</div> }
        <form [formGroup]="profileForm" (ngSubmit)="saveProfile()" novalidate>
          <div class="field" [class.invalid]="perr('fullName')">
            <label for="fullName">Họ và tên</label><input id="fullName" formControlName="fullName" autocomplete="name" />
            @if (perr('fullName'); as e) { <span class="error">{{ e }}</span> }
          </div>
          <div class="grid-2">
            <div class="field" [class.invalid]="perr('email')">
              <label for="email">Email</label><input id="email" type="email" formControlName="email" autocomplete="email" />
              @if (perr('email'); as e) { <span class="error">{{ e }}</span> }
            </div>
            <div class="field" [class.invalid]="perr('phone')">
              <label for="phone">Số điện thoại</label><input id="phone" type="tel" formControlName="phone" autocomplete="tel" />
              @if (perr('phone'); as e) { <span class="error">{{ e }}</span> }
            </div>
          </div>
          <p class="hint muted">Email/số điện thoại mới sẽ ở trạng thái chưa xác minh.</p>
          <button class="btn btn-primary" type="submit" [disabled]="savingProfile() || profileForm.pristine">{{ savingProfile() ? 'Đang lưu…' : 'Lưu thay đổi' }}</button>
        </form>
      </section>
      <section class="card">
        <h2>Đổi mật khẩu</h2>
        @if (passwordMessage()) { <div class="alert" [class.alert-success]="passwordOk()" [class.alert-error]="!passwordOk()" role="status">{{ passwordMessage() }}</div> }
        <form [formGroup]="passwordForm" (ngSubmit)="changePassword()" novalidate>
          <div class="field" [class.invalid]="werr('currentPassword')">
            <label for="currentPassword">Mật khẩu hiện tại</label><input id="currentPassword" type="password" formControlName="currentPassword" autocomplete="current-password" />
            @if (werr('currentPassword'); as e) { <span class="error">{{ e }}</span> }
          </div>
          <div class="grid-2">
            <div class="field" [class.invalid]="werr('newPassword')">
              <label for="newPassword">Mật khẩu mới</label><input id="newPassword" type="password" formControlName="newPassword" autocomplete="new-password" />
              @if (werr('newPassword'); as e) { <span class="error">{{ e }}</span> }
            </div>
            <div class="field" [class.invalid]="werr('confirmNewPassword') || pwMismatch()">
              <label for="confirmNewPassword">Xác nhận mật khẩu mới</label><input id="confirmNewPassword" type="password" formControlName="confirmNewPassword" autocomplete="new-password" />
              @if (werr('confirmNewPassword'); as e) { <span class="error">{{ e }}</span> } @else if (pwMismatch()) { <span class="error">Mật khẩu xác nhận không khớp</span> }
            </div>
          </div>
          <p class="hint muted">Các phiên đăng nhập trên thiết bị khác sẽ bị đăng xuất.</p>
          <button class="btn btn-primary" type="submit" [disabled]="savingPassword()">{{ savingPassword() ? 'Đang đổi…' : 'Đổi mật khẩu' }}</button>
        </form>
      </section>
    } @else if (!loadError()) { <p class="muted">Đang tải hồ sơ…</p> }
  </main>`,
})
export class ProfileComponent implements OnInit {
  private readonly api = inject(UserApi);
  private readonly auth = inject(AuthService);
  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly roleLabel = ROLE_LABEL;
  protected readonly statusLabel = STATUS_LABEL;
  protected readonly user = signal<User | null>(null);
  protected readonly loadError = signal<string | null>(null);
  protected readonly savingProfile = signal(false);
  protected readonly profileMessage = signal<string | null>(null);
  protected readonly profileOk = signal(false);
  protected readonly savingPassword = signal(false);
  protected readonly passwordMessage = signal<string | null>(null);
  protected readonly passwordOk = signal(false);

  protected readonly profileForm = this.fb.group({
    fullName: ['', [Validators.required, Validators.minLength(2), Validators.maxLength(100)]],
    email: ['', [Validators.email, Validators.maxLength(254)]],
    phone: ['', [Validators.pattern(VN_PHONE_PATTERN)]],
  });
  protected readonly passwordForm = this.fb.group({
    currentPassword: ['', Validators.required],
    newPassword: ['', [Validators.required, passwordPolicy]],
    confirmNewPassword: ['', Validators.required],
  }, { validators: matchFields('newPassword', 'confirmNewPassword') });

  ngOnInit(): void {
    this.api.me().subscribe({ next: user => this.setUser(user), error: (e: unknown) => this.loadError.set(toApiError(e).message) });
  }

  perr(name: 'fullName' | 'email' | 'phone'): string | null {
    return fieldError(this.profileForm.controls[name], { required: 'Trường này là bắt buộc', minlength: 'Họ tên dài 2–100 ký tự', maxlength: 'Giá trị quá dài', email: 'Email không hợp lệ', pattern: 'Số điện thoại không hợp lệ' });
  }
  werr(name: 'currentPassword' | 'newPassword' | 'confirmNewPassword'): string | null {
    return fieldError(this.passwordForm.controls[name], { required: 'Trường này là bắt buộc', passwordPolicy: 'Mật khẩu phải dài 8–72 ký tự và chứa cả chữ lẫn số' });
  }
  pwMismatch(): boolean { return this.passwordForm.hasError('mismatch') && this.passwordForm.controls.confirmNewPassword.touched; }

  saveProfile(): void {
    if (this.savingProfile()) return;
    this.profileForm.markAllAsTouched();
    if (this.profileForm.invalid) return;
    const v = this.profileForm.getRawValue();
    this.savingProfile.set(true);
    this.profileMessage.set(null);
    this.api.update({ fullName: v.fullName.trim(), email: v.email.trim(), phone: v.phone.trim() }).subscribe({
      next: user => { this.setUser(user); this.auth.updateUser(user); this.profileOk.set(true); this.profileMessage.set('Đã cập nhật hồ sơ.'); this.savingProfile.set(false); },
      error: (e: unknown) => {
        const err = toApiError(e);
        const unmatched = applyServerErrors(this.profileForm, err.fieldErrors);
        this.profileOk.set(false); this.profileMessage.set(unmatched.join(' ') || err.message); this.savingProfile.set(false);
      },
    });
  }

  changePassword(): void {
    if (this.savingPassword()) return;
    this.passwordForm.markAllAsTouched();
    if (this.passwordForm.invalid) return;
    this.savingPassword.set(true);
    this.passwordMessage.set(null);
    this.api.changePassword(this.passwordForm.getRawValue()).subscribe({
      next: () => { this.passwordForm.reset(); this.passwordOk.set(true); this.passwordMessage.set('Đã đổi mật khẩu. Các phiên khác đã bị đăng xuất.'); this.savingPassword.set(false); },
      error: (e: unknown) => {
        const err = toApiError(e);
        const unmatched = applyServerErrors(this.passwordForm, err.fieldErrors);
        this.passwordOk.set(false); this.passwordMessage.set(unmatched.join(' ') || err.message); this.savingPassword.set(false);
      },
    });
  }

  private setUser(user: User): void {
    this.user.set(user);
    this.profileForm.reset({ fullName: user.fullName, email: user.email ?? '', phone: user.phone ?? '' });
  }
}
