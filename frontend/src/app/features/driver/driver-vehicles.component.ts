import { Component, OnInit, inject, signal } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { VEHICLE_TYPE_LABELS, Vehicle, VehicleApi, VehiclePayload, VehicleType } from '../../core/api/account.api';
import { toApiError } from '../../core/api.service';
import { StatusBadgeComponent } from '../../shared/status-badge.component';
import { applyServerErrors, fieldError } from '../../shared/form-utils';

@Component({
  selector: 'app-driver-vehicles',
  standalone: true,
  imports: [ReactiveFormsModule, DatePipe, DecimalPipe, StatusBadgeComponent],
  template: `
  <main class="container">
    <div class="page-head"><div><div class="kicker">Khu vực tài xế</div><h1>Phương tiện</h1></div></div>
    @if (message()) { <div class="alert" [class.alert-success]="ok()" [class.alert-error]="!ok()" role="status">{{ message() }}</div> }
    <section class="card">
      <h2>{{ editingId() ? 'Cập nhật phương tiện' : 'Đăng ký phương tiện mới' }}</h2>
      <form [formGroup]="form" (ngSubmit)="save()" novalidate>
        <div class="grid-3">
          <div class="field" [class.invalid]="err('plateNumber')">
            <label for="plateNumber">Biển số</label><input id="plateNumber" formControlName="plateNumber" placeholder="51C-123.45" />
            @if (err('plateNumber'); as e) { <span class="error">{{ e }}</span> }
          </div>
          <div class="field" [class.invalid]="err('vehicleType')">
            <label for="vehicleType">Loại xe</label>
            <select id="vehicleType" formControlName="vehicleType">@for (t of types; track t) { <option [value]="t">{{ typeLabels[t] }}</option> }</select>
          </div>
          <div class="field" [class.invalid]="err('registrationExpiryDate')">
            <label for="registrationExpiryDate">Hạn đăng kiểm</label><input id="registrationExpiryDate" type="date" formControlName="registrationExpiryDate" [min]="tomorrow" />
            @if (err('registrationExpiryDate'); as e) { <span class="error">{{ e }}</span> }
          </div>
        </div>
        <div class="grid-2">
          <div class="field" [class.invalid]="err('maxPayloadKg')">
            <label for="maxPayloadKg">Tải trọng tối đa (kg)</label><input id="maxPayloadKg" type="number" min="1" formControlName="maxPayloadKg" />
            @if (err('maxPayloadKg'); as e) { <span class="error">{{ e }}</span> }
          </div>
          <div class="grid-3">
            <div class="field" [class.invalid]="err('lengthCm')"><label for="lengthCm">Dài (cm)</label><input id="lengthCm" type="number" min="1" formControlName="lengthCm" />
              @if (err('lengthCm'); as e) { <span class="error">{{ e }}</span> }</div>
            <div class="field" [class.invalid]="err('widthCm')"><label for="widthCm">Rộng (cm)</label><input id="widthCm" type="number" min="1" formControlName="widthCm" />
              @if (err('widthCm'); as e) { <span class="error">{{ e }}</span> }</div>
            <div class="field" [class.invalid]="err('heightCm')"><label for="heightCm">Cao (cm)</label><input id="heightCm" type="number" min="1" formControlName="heightCm" />
              @if (err('heightCm'); as e) { <span class="error">{{ e }}</span> }</div>
          </div>
        </div>
        @if (editingApproved()) { <div class="alert alert-info">Phương tiện đã được duyệt; thay đổi sẽ đưa phương tiện về trạng thái chờ duyệt.</div> }
        <div class="actions-row">
          <button class="btn btn-primary" type="submit" [disabled]="saving()">{{ saving() ? 'Đang lưu…' : editingId() ? 'Lưu thay đổi' : 'Thêm phương tiện' }}</button>
          @if (editingId()) { <button class="btn btn-ghost" type="button" (click)="cancelEdit()">Hủy</button> }
        </div>
      </form>
    </section>
    <section class="card">
      <h2>Phương tiện của tôi</h2>
      @if (loading()) { <p class="muted">Đang tải…</p> }
      @else if (vehicles().length === 0) { <div class="empty">Chưa có phương tiện nào.</div> }
      @else {
        <div class="table-wrap"><table class="table">
          <thead><tr><th>Biển số</th><th>Loại</th><th>Tải trọng</th><th>Kích thước (D×R×C)</th><th>Hạn đăng kiểm</th><th>Trạng thái</th><th></th></tr></thead>
          <tbody>
            @for (v of vehicles(); track v.id) {
              <tr>
                <td><strong>{{ v.plateNumber }}</strong></td>
                <td>{{ typeLabels[v.vehicleType] }}</td>
                <td>{{ v.maxPayloadKg | number }} kg</td>
                <td>{{ v.lengthCm }}×{{ v.widthCm }}×{{ v.heightCm }} cm</td>
                <td>{{ v.registrationExpiryDate | date: 'dd/MM/yyyy' }}</td>
                <td><app-status-badge [status]="v.verificationStatus" />
                  @if (v.rejectionReason) { <div class="muted" style="margin-top:6px">{{ v.rejectionReason }}</div> }</td>
                <td><div class="actions-row">
                  @if (v.verificationStatus !== 'PENDING_REVIEW') { <button class="btn btn-ghost" type="button" (click)="edit(v)">Sửa</button> }
                  @if (v.verificationStatus === 'NOT_SUBMITTED' || v.verificationStatus === 'REJECTED') {
                    <button class="btn btn-primary" type="button" (click)="submit(v)" [disabled]="busyId() === v.id">Gửi duyệt</button>
                  }
                </div></td>
              </tr>
            }
          </tbody>
        </table></div>
      }
    </section>
  </main>`,
})
export class DriverVehiclesComponent implements OnInit {
  private readonly api = inject(VehicleApi);
  protected readonly types = Object.keys(VEHICLE_TYPE_LABELS) as VehicleType[];
  protected readonly typeLabels = VEHICLE_TYPE_LABELS;
  protected readonly tomorrow = new Date(Date.now() + 86_400_000).toISOString().slice(0, 10);
  protected readonly vehicles = signal<Vehicle[]>([]);
  protected readonly loading = signal(true);
  protected readonly saving = signal(false);
  protected readonly busyId = signal<string | null>(null);
  protected readonly editingId = signal<string | null>(null);
  protected readonly editingApproved = signal(false);
  protected readonly message = signal<string | null>(null);
  protected readonly ok = signal(false);
  protected readonly form = inject(FormBuilder).nonNullable.group({
    plateNumber: ['', [Validators.required, Validators.maxLength(15)]],
    vehicleType: ['SMALL_TRUCK' as VehicleType, Validators.required],
    maxPayloadKg: [0, [Validators.required, Validators.min(1), Validators.max(60000)]],
    lengthCm: [0, [Validators.required, Validators.min(1), Validators.max(2000)]],
    widthCm: [0, [Validators.required, Validators.min(1), Validators.max(400)]],
    heightCm: [0, [Validators.required, Validators.min(1), Validators.max(500)]],
    registrationExpiryDate: ['', Validators.required],
  });

  ngOnInit(): void { this.load(); }

  err(name: keyof VehiclePayload): string | null {
    return fieldError(this.form.controls[name], { required: 'Trường này là bắt buộc', min: 'Giá trị phải lớn hơn 0', max: 'Giá trị vượt giới hạn cho phép', maxlength: 'Giá trị quá dài' });
  }

  save(): void {
    if (this.saving()) return;
    this.form.markAllAsTouched();
    if (this.form.invalid) return;
    this.saving.set(true);
    this.message.set(null);
    const payload = this.form.getRawValue();
    const id = this.editingId();
    (id ? this.api.update(id, payload) : this.api.create(payload)).subscribe({
      next: () => { this.ok.set(true); this.message.set(id ? 'Đã cập nhật phương tiện.' : 'Đã thêm phương tiện. Hãy gửi duyệt khi sẵn sàng.'); this.cancelEdit(); this.saving.set(false); this.load(); },
      error: (e: unknown) => {
        const err = toApiError(e);
        const unmatched = applyServerErrors(this.form, err.fieldErrors);
        this.ok.set(false); this.message.set(unmatched.join(' ') || err.message); this.saving.set(false);
      },
    });
  }

  edit(v: Vehicle): void {
    this.editingId.set(v.id);
    this.editingApproved.set(v.verificationStatus === 'APPROVED');
    this.form.reset({ plateNumber: v.plateNumber, vehicleType: v.vehicleType, maxPayloadKg: v.maxPayloadKg, lengthCm: v.lengthCm, widthCm: v.widthCm, heightCm: v.heightCm, registrationExpiryDate: v.registrationExpiryDate });
  }

  cancelEdit(): void {
    this.editingId.set(null);
    this.editingApproved.set(false);
    this.form.reset();
  }

  submit(v: Vehicle): void {
    this.busyId.set(v.id);
    this.message.set(null);
    this.api.submit(v.id).subscribe({
      next: updated => { this.vehicles.update(list => list.map(x => x.id === updated.id ? updated : x)); this.busyId.set(null); },
      error: (e: unknown) => { this.ok.set(false); this.message.set(toApiError(e).message); this.busyId.set(null); },
    });
  }

  private load(): void {
    this.api.mine().subscribe({
      next: list => { this.vehicles.set(list); this.loading.set(false); },
      error: (e: unknown) => { this.ok.set(false); this.message.set(toApiError(e).message); this.loading.set(false); },
    });
  }
}
