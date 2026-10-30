import { Component, OnInit, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { DriverApi, DriverProfile } from '../../core/api/account.api';
import { toApiError } from '../../core/api.service';
import { StatusBadgeComponent } from '../../shared/status-badge.component';
import { applyServerErrors, fieldError } from '../../shared/form-utils';
import { LICENSE_CLASSES } from '../auth/register.component';

@Component({
  selector: 'app-driver-profile',
  standalone: true,
  imports: [ReactiveFormsModule, StatusBadgeComponent],
  template: `
  <main class="container">
    <div class="page-head"><div><div class="kicker">Khu vực tài xế</div><h1>Giấy phép lái xe</h1></div>
      @if (profile(); as p) { <app-status-badge [status]="p.verificationStatus" /> }</div>
    @if (message()) { <div class="alert" [class.alert-success]="ok()" [class.alert-error]="!ok()" role="status">{{ message() }}</div> }
    @if (profile(); as p) {
      @if (p.verificationStatus === 'PENDING_REVIEW') { <div class="alert alert-warn">Hồ sơ đang chờ duyệt nên tạm thời không thể chỉnh sửa.</div> }
      @if (p.verificationStatus === 'APPROVED') { <div class="alert alert-info">Hồ sơ đã được duyệt. Nếu thay đổi giấy phép, hồ sơ sẽ quay lại trạng thái chờ duyệt.</div> }
      <section class="card">
        <form [formGroup]="form" (ngSubmit)="save()" novalidate>
          <div class="grid-2">
            <div class="field" [class.invalid]="err('licenseNumber')">
              <label for="licenseNumber">Số giấy phép lái xe</label><input id="licenseNumber" formControlName="licenseNumber" inputmode="numeric" />
              @if (err('licenseNumber'); as e) { <span class="error">{{ e }}</span> }
            </div>
            <div class="field" [class.invalid]="err('licenseClass')">
              <label for="licenseClass">Hạng giấy phép</label>
              <select id="licenseClass" formControlName="licenseClass">@for (c of classes; track c) { <option [value]="c">{{ c }}</option> }</select>
              @if (err('licenseClass'); as e) { <span class="error">{{ e }}</span> }
            </div>
          </div>
          <button class="btn btn-primary" type="submit" [disabled]="saving() || form.disabled || form.pristine">{{ saving() ? 'Đang lưu…' : 'Lưu thay đổi' }}</button>
        </form>
      </section>
      <p class="muted">Tải ảnh giấy tờ sẽ được bổ sung khi hạ tầng lưu trữ tệp sẵn sàng.</p>
    } @else if (!message()) { <p class="muted">Đang tải…</p> }
  </main>`,
})
export class DriverProfileComponent implements OnInit {
  private readonly api = inject(DriverApi);
  protected readonly classes = LICENSE_CLASSES;
  protected readonly profile = signal<DriverProfile | null>(null);
  protected readonly saving = signal(false);
  protected readonly message = signal<string | null>(null);
  protected readonly ok = signal(false);
  protected readonly form = inject(FormBuilder).nonNullable.group({
    licenseNumber: ['', [Validators.required, Validators.pattern(/^[0-9\s.-]{12,20}$/)]],
    licenseClass: ['', Validators.required],
  });

  ngOnInit(): void {
    this.api.me().subscribe({ next: p => this.setProfile(p), error: (e: unknown) => { this.ok.set(false); this.message.set(toApiError(e).message); } });
  }

  err(name: 'licenseNumber' | 'licenseClass'): string | null {
    return fieldError(this.form.controls[name], { required: 'Trường này là bắt buộc', pattern: 'Số giấy phép gồm 12 chữ số' });
  }

  save(): void {
    if (this.saving()) return;
    this.form.markAllAsTouched();
    if (this.form.invalid) return;
    this.saving.set(true);
    this.message.set(null);
    this.api.update(this.form.getRawValue()).subscribe({
      next: p => {
        this.setProfile(p);
        this.ok.set(true);
        this.message.set(p.verificationStatus === 'PENDING_REVIEW' ? 'Đã lưu. Hồ sơ đã được gửi duyệt lại.' : 'Đã lưu thông tin giấy phép.');
        this.saving.set(false);
      },
      error: (e: unknown) => {
        const err = toApiError(e);
        const unmatched = applyServerErrors(this.form, err.fieldErrors);
        this.ok.set(false); this.message.set(unmatched.join(' ') || err.message); this.saving.set(false);
      },
    });
  }

  private setProfile(p: DriverProfile): void {
    this.profile.set(p);
    this.form.reset({ licenseNumber: p.licenseNumber ?? '', licenseClass: p.licenseClass ?? '' });
    if (p.verificationStatus === 'PENDING_REVIEW') this.form.disable(); else this.form.enable();
  }
}
