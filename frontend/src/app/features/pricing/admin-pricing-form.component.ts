import { Component, OnInit, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { AbstractControl, ReactiveFormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { CatalogApi, CatalogVehicleType } from '../../core/api/catalog.api';
import { AdminPricingApi, ConfigHistory, PricingConfig } from '../../core/api/pricing.api';
import { toApiError } from '../../core/api.service';
import { applyServerErrors, fieldError } from '../../shared/form-utils';
import { ToastService } from '../../shared/toast.service';
import { PricingServiceFeesComponent } from './pricing-service-fees.component';
import { extraFeeForm, patchConfig, pricingConfigForm, timeRuleForm, toConfigPayload } from './pricing-config-form';

const MESSAGES = { required: 'Trường này là bắt buộc', min: 'Không được âm', max: 'Giá trị quá lớn', maxlength: 'Giá trị quá dài', pattern: 'Định dạng không hợp lệ' };

const MONEY_FIELDS = [
  ['basePrice', 'Giá mở cửa (VNĐ)'], ['pricePerKm', 'Giá mỗi km (VNĐ)'], ['includedDistanceKm', 'Số km đã gồm trong giá mở cửa'],
  ['includedWeightKg', 'Khối lượng miễn phụ phí (kg)'], ['pricePerExtraKg', 'Giá mỗi kg vượt (VNĐ)'], ['includedVolumeM3', 'Thể tích miễn phụ phí (m³)'],
  ['pricePerExtraM3', 'Giá mỗi m³ vượt (VNĐ)'], ['additionalStopPrice', 'Giá mỗi điểm dừng bổ sung (VNĐ)'], ['minimumPrice', 'Giá tối thiểu (VNĐ)'],
] as const;

@Component({
  selector: 'app-admin-pricing-form',
  standalone: true,
  imports: [DatePipe, ReactiveFormsModule, RouterLink, PricingServiceFeesComponent],
  template: `
  <main class="container">
    <div class="page-head"><div><a routerLink="/admin/pricing" class="kicker">← Bảng giá</a><h1>{{ id ? 'Sửa bảng giá' : 'Tạo bảng giá' }}</h1></div>
      @if (config(); as c) { <span class="badge" [class.badge--approved]="c.effectiveNow">{{ !c.active ? 'Đã tắt' : c.effectiveNow ? 'Đang áp dụng' : 'Bật, ngoài thời gian hiệu lực' }}</span> }</div>
    @if (loadError()) { <div class="alert alert-error" role="alert">{{ loadError() }}</div> }
    @else {
      <div class="alert alert-info">Thay đổi bảng giá chỉ áp dụng cho báo giá tạo sau đó; báo giá đã tạo giữ nguyên số liệu tại thời điểm tính.</div>
      @if (error()) { <div class="alert alert-error" role="alert">{{ error() }}</div> }
      <form [formGroup]="form" (ngSubmit)="save()" novalidate>
        <section class="card"><h2>Thông tin chung</h2>
          <div class="grid-2">
            <div class="field" [class.invalid]="err(form.controls.name)"><label for="name">Tên bảng giá</label><input id="name" formControlName="name" />
              @if (err(form.controls.name); as e) { <span class="error">{{ e }}</span> }</div>
            <div class="field" [class.invalid]="err(form.controls.vehicleTypeCode)"><label for="vehicleTypeCode">Loại xe</label>
              <select id="vehicleTypeCode" formControlName="vehicleTypeCode"><option value="">— Chọn loại xe —</option>
                @for (v of vehicleTypes(); track v.code) { <option [value]="v.code">{{ v.name }}</option> }</select>
              @if (err(form.controls.vehicleTypeCode); as e) { <span class="error">{{ e }}</span> }</div>
          </div>
          <div class="grid-2">
            <div class="field" [class.invalid]="err(form.controls.effectiveFrom)"><label for="effectiveFrom">Hiệu lực từ</label>
              <input id="effectiveFrom" type="datetime-local" formControlName="effectiveFrom" />
              @if (err(form.controls.effectiveFrom); as e) { <span class="error">{{ e }}</span> }</div>
            <div class="field" [class.invalid]="err(form.controls.effectiveTo) || periodInvalid()"><label for="effectiveTo">Hiệu lực đến (bỏ trống = không thời hạn)</label>
              <input id="effectiveTo" type="datetime-local" formControlName="effectiveTo" />
              @if (err(form.controls.effectiveTo); as e) { <span class="error">{{ e }}</span> }
              @else if (periodInvalid()) { <span class="error">Thời điểm kết thúc phải sau thời điểm bắt đầu</span> }</div>
          </div>
        </section>
        <section class="card"><h2>Đơn giá</h2>
          <div class="grid-3">
            @for (f of moneyFields; track f[0]) {
              <div class="field" [class.invalid]="err(form.get(f[0]))"><label [for]="f[0]">{{ f[1] }}</label>
                <input [id]="f[0]" type="number" min="0" step="any" [formControlName]="f[0]" />
                @if (err(form.get(f[0])); as e) { <span class="error">{{ e }}</span> }</div>
            }
          </div>
        </section>
        <section class="card"><h2>Phụ phí theo khung giờ</h2>
          <p class="muted small">Tính theo thời gian lấy hàng (giờ Việt Nam). Khung qua nửa đêm: giờ bắt đầu lớn hơn giờ kết thúc. Ngày: MON,TUE,…; bỏ trống = mọi ngày. Phần trăm tính trên giá mở cửa + phí quãng đường.</p>
          <div formArrayName="timeRules">
            @for (r of form.controls.timeRules.controls; track r; let i = $index) {
              <div class="rule" [formGroupName]="i">
                <div class="field"><label [for]="'tr-name' + i">Tên</label><input [id]="'tr-name' + i" formControlName="name" /></div>
                <div class="field"><label [for]="'tr-days' + i">Ngày</label><input [id]="'tr-days' + i" formControlName="daysOfWeek" placeholder="SAT,SUN" /></div>
                <div class="field"><label [for]="'tr-start' + i">Từ</label><input [id]="'tr-start' + i" type="time" formControlName="startTime" /></div>
                <div class="field"><label [for]="'tr-end' + i">Đến</label><input [id]="'tr-end' + i" type="time" formControlName="endTime" /></div>
                <div class="field"><label [for]="'tr-type' + i">Cách tính</label><select [id]="'tr-type' + i" formControlName="feeType"><option value="PERCENTAGE">%</option><option value="FIXED">Cố định</option></select></div>
                <div class="field"><label [for]="'tr-val' + i">{{ r.controls.feeType.value === 'PERCENTAGE' ? 'Phần trăm' : 'Số tiền' }}</label>
                  @if (r.controls.feeType.value === 'PERCENTAGE') { <input [id]="'tr-val' + i" type="number" min="0" step="any" formControlName="percentage" /> }
                  @else { <input [id]="'tr-val' + i" type="number" min="0" step="any" formControlName="amount" /> }</div>
                <button class="btn btn-ghost" type="button" (click)="form.controls.timeRules.removeAt(i)">Xóa</button>
                @if (r.invalid && r.touched) { <span class="error rule-error">Kiểm tra tên, ngày, giờ (khác nhau) và giá trị lớn hơn 0.</span> }
              </div>
            }
          </div>
          <button class="btn btn-ghost" type="button" (click)="form.controls.timeRules.push(newTimeRule())">+ Thêm khung giờ</button>
        </section>
        <section class="card"><h2>Phụ phí khác</h2>
          <div formArrayName="extraFees">
            @for (x of form.controls.extraFees.controls; track x; let i = $index) {
              <div class="rule" [formGroupName]="i">
                <div class="field"><label [for]="'xf-code' + i">Mã</label><input [id]="'xf-code' + i" formControlName="code" placeholder="FUEL" /></div>
                <div class="field"><label [for]="'xf-name' + i">Tên</label><input [id]="'xf-name' + i" formControlName="name" /></div>
                <div class="field"><label [for]="'xf-type' + i">Cách tính</label><select [id]="'xf-type' + i" formControlName="feeType"><option value="FIXED">Cố định</option><option value="PERCENTAGE">%</option></select></div>
                <div class="field"><label [for]="'xf-val' + i">{{ x.controls.feeType.value === 'PERCENTAGE' ? 'Phần trăm' : 'Số tiền' }}</label>
                  @if (x.controls.feeType.value === 'PERCENTAGE') { <input [id]="'xf-val' + i" type="number" min="0" step="any" formControlName="percentage" /> }
                  @else { <input [id]="'xf-val' + i" type="number" min="0" step="any" formControlName="amount" /> }</div>
                <button class="btn btn-ghost" type="button" (click)="form.controls.extraFees.removeAt(i)">Xóa</button>
                @if (x.invalid && x.touched) { <span class="error rule-error">Mã gồm CHỮ_IN_HOA/số, tên bắt buộc, giá trị lớn hơn 0.</span> }
              </div>
            }
          </div>
          <button class="btn btn-ghost" type="button" (click)="form.controls.extraFees.push(newExtraFee())">+ Thêm phụ phí</button>
        </section>
        <div class="actions-row"><button class="btn btn-primary" type="submit" [disabled]="saving()">{{ saving() ? 'Đang lưu…' : id ? 'Lưu thay đổi' : 'Tạo bảng giá' }}</button>
          <a class="btn btn-ghost" routerLink="/admin/pricing">Hủy</a></div>
      </form>
      @if (id) {
        <app-pricing-service-fees [configId]="id" />
        <section class="card"><h2>Lịch sử thay đổi</h2>
          @for (h of history(); track h.id) {
            <details class="history"><summary><strong>{{ actionLabel(h.action) }}</strong> · {{ h.changedAt | date: 'HH:mm dd/MM/yyyy' }}</summary>
              <div class="grid-2"><pre>{{ pretty(h.oldValue) }}</pre><pre>{{ pretty(h.newValue) }}</pre></div></details>
          } @empty { <p class="muted">Chưa có lịch sử.</p> }
        </section>
      }
    }
  </main>`,
  styles: [`.rule{display:grid;grid-template-columns:1.4fr 1fr .8fr .8fr .8fr 1fr auto;gap:0 10px;align-items:end;border-bottom:1px solid #eef1ea;margin-bottom:10px}
  .rule .btn{margin-bottom:16px}.rule-error{grid-column:1/-1;margin:-8px 0 10px;font-size:12px;color:var(--tg-danger)}.small{font-size:12px}
  .history{border-bottom:1px solid #eef1ea;padding:8px 0;font-size:13px}.history summary{cursor:pointer}pre{white-space:pre-wrap;word-break:break-word;font-size:11px;background:#fafbf8;padding:8px;border-radius:4px;max-height:260px;overflow:auto}
  @media(max-width:900px){.rule{grid-template-columns:1fr 1fr}}`],
})
export class AdminPricingFormComponent implements OnInit {
  private readonly api = inject(AdminPricingApi);
  private readonly catalogs = inject(CatalogApi);
  private readonly router = inject(Router);
  private readonly toast = inject(ToastService);
  protected readonly id = inject(ActivatedRoute).snapshot.paramMap.get('id');
  protected readonly form = pricingConfigForm();
  protected readonly moneyFields = MONEY_FIELDS;
  protected readonly vehicleTypes = signal<CatalogVehicleType[]>([]);
  protected readonly config = signal<PricingConfig | null>(null);
  protected readonly history = signal<ConfigHistory[]>([]);
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly loadError = signal<string | null>(null);
  protected readonly newTimeRule = timeRuleForm;
  protected readonly newExtraFee = extraFeeForm;

  ngOnInit(): void {
    this.catalogs.vehicleTypes().subscribe({ next: v => this.vehicleTypes.set(v), error: () => undefined });
    if (this.id) this.reload();
  }

  err(control: AbstractControl | null): string | null { return fieldError(control, MESSAGES); }

  periodInvalid(): boolean { return this.form.hasError('period') && (this.form.controls.effectiveTo.touched || this.form.controls.effectiveTo.dirty); }

  save(): void {
    if (this.saving()) return;
    this.form.markAllAsTouched();
    if (this.form.invalid) { this.error.set('Vui lòng kiểm tra các trường được đánh dấu.'); return; }
    this.saving.set(true);
    this.error.set(null);
    const payload = toConfigPayload(this.form, this.config()?.version);
    (this.id ? this.api.update(this.id, payload) : this.api.create(payload)).subscribe({
      next: c => {
        this.saving.set(false);
        this.toast.success(this.id ? 'Đã lưu bảng giá.' : 'Đã tạo bảng giá. Bạn có thể thêm phí dịch vụ.');
        if (this.id) { this.config.set(c); patchConfig(this.form, c); this.loadHistory(); }
        else void this.router.navigate(['/admin/pricing', c.id]);
      },
      error: (e: unknown) => {
        const err = toApiError(e);
        const unmatched = applyServerErrors(this.form, err.fieldErrors);
        this.error.set(unmatched.length ? unmatched.join(' ') : err.message);
        this.toast.error(err.message);
        this.saving.set(false);
      },
    });
  }

  actionLabel(action: string): string {
    return ({ CREATED: 'Tạo bảng giá', UPDATED: 'Cập nhật', ACTIVATED: 'Bật', DEACTIVATED: 'Tắt', SERVICE_FEE_CREATED: 'Thêm phí dịch vụ',
      SERVICE_FEE_UPDATED: 'Sửa phí dịch vụ', SERVICE_FEE_DELETED: 'Xóa phí dịch vụ' } as Record<string, string>)[action] ?? action;
  }

  pretty(raw: string | null): string {
    if (!raw) return '—';
    try { return JSON.stringify(JSON.parse(raw), null, 1); } catch { return raw; }
  }

  private reload(): void {
    this.api.get(this.id!).subscribe({
      next: c => { this.config.set(c); patchConfig(this.form, c); },
      error: (e: unknown) => this.loadError.set(toApiError(e).message),
    });
    this.loadHistory();
  }

  private loadHistory(): void { this.api.history(this.id!).subscribe({ next: h => this.history.set(h), error: () => this.history.set([]) }); }
}
