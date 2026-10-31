import { Component, OnInit, computed, inject, input, signal } from '@angular/core';
import { AbstractControl, FormControl, FormGroup, ReactiveFormsModule, ValidationErrors, Validators } from '@angular/forms';
import { CatalogApi, ServiceDefinition } from '../../core/api/catalog.api';
import { AdminPricingApi, FeeType, ServiceFee } from '../../core/api/pricing.api';
import { toApiError } from '../../core/api.service';
import { formatVnd } from '../../shared/money';
import { applyServerErrors } from '../../shared/form-utils';
import { ToastService } from '../../shared/toast.service';

const TYPE_LABELS: Record<FeeType, string> = { FIXED: 'Cố định', PERCENTAGE: '% cước vận chuyển', PER_UNIT: 'Theo số lượng', PER_STOP: 'Theo điểm dừng', FREE: 'Miễn phí' };

/** Fees of the Sprint 3 service catalog under one price table. */
@Component({
  selector: 'app-pricing-service-fees',
  standalone: true,
  imports: [ReactiveFormsModule],
  template: `
  <section class="card"><h2>Phí dịch vụ bổ sung</h2>
    <p class="muted small">Dịch vụ khách chọn nhưng chưa có phí sẽ được tính 0 đ và hiển thị "Miễn phí" trong báo giá. Phí cho một lựa chọn cụ thể (vd. "Cần đội bốc xếp") được ưu tiên hơn phí chung.</p>
    @if (loadError()) { <div class="alert alert-error" role="alert">{{ loadError() }}</div> }
    <div class="table-wrap"><table class="table">
      <thead><tr><th>Dịch vụ</th><th>Lựa chọn</th><th>Cách tính</th><th>Giá trị</th><th></th></tr></thead>
      <tbody>@for (f of fees(); track f.id) {
        <tr><td>{{ f.serviceName }}</td><td>{{ f.serviceOptionLabel || 'Mọi lựa chọn' }}</td><td>{{ typeLabels[f.pricingType] }}</td>
          <td>{{ f.pricingType === 'PERCENTAGE' ? f.percentage + '%' : f.pricingType === 'FREE' ? '—' : vnd(f.amount) }}</td>
          <td><div class="actions-row"><button class="btn btn-ghost" type="button" (click)="edit(f)">Sửa</button>
            @if (deleteId() === f.id) { <button class="btn btn-danger" type="button" (click)="remove(f)">Xác nhận xóa</button>
              <button class="btn btn-ghost" type="button" (click)="deleteId.set(null)">Hủy</button> }
            @else { <button class="btn btn-ghost" type="button" (click)="deleteId.set(f.id)">Xóa</button> }</div></td></tr>
      } @empty { <tr><td colspan="5" class="muted">Chưa có phí dịch vụ.</td></tr> }</tbody>
    </table></div>
    <form class="fee-form" [formGroup]="form" (ngSubmit)="save()" novalidate>
      <h3>{{ editing() ? 'Sửa phí dịch vụ' : 'Thêm phí dịch vụ' }}</h3>
      <div class="grid-3">
        <div class="field"><label for="fee-service">Dịch vụ</label>
          <select id="fee-service" formControlName="serviceCode" (change)="form.controls.serviceOptionValue.setValue('')"><option value="">— Chọn dịch vụ —</option>
            @for (s of services(); track s.code) { <option [value]="s.code">{{ s.name }}</option> }</select>
          @if (form.controls.serviceCode.errors?.['server']; as e) { <span class="error">{{ e }}</span> }</div>
        @if (selected()?.valueType === 'OPTION') {
          <div class="field"><label for="fee-option">Lựa chọn</label>
            <select id="fee-option" formControlName="serviceOptionValue"><option value="">Mọi lựa chọn</option>
              @for (o of selected()!.options; track o.value) { <option [value]="o.value">{{ o.label }}</option> }</select></div>
        }
        <div class="field"><label for="fee-type">Cách tính</label>
          <select id="fee-type" formControlName="pricingType">@for (t of types; track t) { <option [value]="t">{{ typeLabels[t] }}</option> }</select></div>
        @if (form.controls.pricingType.value === 'PERCENTAGE') {
          <div class="field" [class.invalid]="form.touched && form.hasError('value')"><label for="fee-pct">Phần trăm</label><input id="fee-pct" type="number" min="0" max="1000" step="any" formControlName="percentage" /></div>
        } @else if (form.controls.pricingType.value !== 'FREE') {
          <div class="field" [class.invalid]="form.touched && form.hasError('value')"><label for="fee-amount">Số tiền (VNĐ)</label><input id="fee-amount" type="number" min="0" step="any" formControlName="amount" /></div>
        }
      </div>
      @if (form.touched && form.hasError('value')) { <p class="error-text">Nhập giá trị lớn hơn 0 phù hợp với cách tính (không âm).</p> }
      @if (formError()) { <div class="alert alert-error" role="alert">{{ formError() }}</div> }
      <div class="actions-row"><button class="btn btn-primary" type="submit" [disabled]="saving()">{{ saving() ? 'Đang lưu…' : editing() ? 'Lưu phí' : 'Thêm phí' }}</button>
        @if (editing()) { <button class="btn btn-ghost" type="button" (click)="reset()">Hủy</button> }</div>
    </form>
  </section>`,
  styles: [`.small{font-size:12px}.fee-form{margin-top:18px;border-top:1px solid var(--tg-line);padding-top:12px}.fee-form h3{font-size:15px;margin:0 0 12px}.error-text{color:var(--tg-danger);font-size:12px;margin:0 0 10px}`],
})
export class PricingServiceFeesComponent implements OnInit {
  readonly configId = input.required<string>();
  private readonly api = inject(AdminPricingApi);
  private readonly catalogs = inject(CatalogApi);
  private readonly toast = inject(ToastService);
  protected readonly typeLabels = TYPE_LABELS;
  protected readonly types = Object.keys(TYPE_LABELS) as FeeType[];
  protected readonly fees = signal<ServiceFee[]>([]);
  protected readonly services = signal<ServiceDefinition[]>([]);
  protected readonly editing = signal<ServiceFee | null>(null);
  protected readonly deleteId = signal<string | null>(null);
  protected readonly saving = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly formError = signal<string | null>(null);
  protected readonly vnd = formatVnd;
  protected readonly form = new FormGroup({
    serviceCode: new FormControl('', { nonNullable: true, validators: Validators.required }),
    serviceOptionValue: new FormControl('', { nonNullable: true }),
    pricingType: new FormControl<FeeType>('FIXED', { nonNullable: true }),
    amount: new FormControl<number | null>(null, { validators: Validators.min(0) }),
    percentage: new FormControl<number | null>(null, { validators: [Validators.min(0), Validators.max(1000)] }),
  }, { validators: (g: AbstractControl): ValidationErrors | null => {
    const type = g.get('pricingType')?.value as FeeType;
    const amount = Number(g.get('amount')?.value) || 0;
    const pct = Number(g.get('percentage')?.value) || 0;
    if (type === 'FREE') return null;
    return (type === 'PERCENTAGE' ? pct : amount) > 0 ? null : { value: true };
  } });
  private readonly code = signal('');
  protected readonly selected = computed(() => this.services().find(s => s.code === this.code()) ?? null);

  ngOnInit(): void {
    this.catalogs.services().subscribe({ next: s => this.services.set(s), error: () => undefined });
    this.form.controls.serviceCode.valueChanges.subscribe(v => this.code.set(v));
    this.load();
  }

  edit(f: ServiceFee): void {
    this.editing.set(f);
    this.form.reset({ serviceCode: f.serviceCode, serviceOptionValue: f.serviceOptionValue ?? '', pricingType: f.pricingType, amount: f.amount, percentage: f.percentage });
  }

  reset(): void { this.editing.set(null); this.formError.set(null); this.form.reset({ serviceCode: '', serviceOptionValue: '', pricingType: 'FIXED', amount: null, percentage: null }); }

  save(): void {
    if (this.saving()) return;
    this.form.markAllAsTouched();
    if (this.form.invalid) return;
    const v = this.form.getRawValue();
    const body = { pricingConfigId: this.configId(), serviceCode: v.serviceCode, serviceOptionValue: v.serviceOptionValue || null, pricingType: v.pricingType,
      amount: v.pricingType === 'PERCENTAGE' || v.pricingType === 'FREE' ? 0 : Number(v.amount), percentage: v.pricingType === 'PERCENTAGE' ? Number(v.percentage) : 0,
      ...(this.editing() ? { version: this.editing()!.version } : {}) };
    this.saving.set(true);
    this.formError.set(null);
    const edit = this.editing();
    (edit ? this.api.updateServiceFee(edit.id, body) : this.api.createServiceFee(body)).subscribe({
      next: () => { this.saving.set(false); this.toast.success('Đã lưu phí dịch vụ.'); this.reset(); this.load(); },
      error: (e: unknown) => {
        const err = toApiError(e);
        const unmatched = applyServerErrors(this.form, err.fieldErrors);
        this.formError.set(unmatched.join(' ') || Object.values(err.fieldErrors).join(' ') || err.message);
        this.saving.set(false);
      },
    });
  }

  remove(f: ServiceFee): void {
    this.api.deleteServiceFee(f.id).subscribe({
      next: () => { this.toast.success('Đã xóa phí dịch vụ.'); this.deleteId.set(null); this.load(); },
      error: (e: unknown) => { this.toast.error(toApiError(e).message); this.deleteId.set(null); },
    });
  }

  private load(): void {
    this.api.serviceFees(this.configId()).subscribe({ next: f => this.fees.set(f), error: (e: unknown) => this.loadError.set(toApiError(e).message) });
  }
}
