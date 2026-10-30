import { Component, DestroyRef, OnInit, inject, input, signal } from '@angular/core';
import { AbstractControl, ReactiveFormsModule } from '@angular/forms';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { startWith } from 'rxjs';
import { ItemCategory } from '../../../core/api/catalog.api';
import { fieldError } from '../../../shared/form-utils';
import { ShipmentForm, Totals, computeTotals, itemForm } from '../shipment-form';
import { formatKg, formatM3 } from '../shipment-labels';

const MESSAGES = { required: 'Trường này là bắt buộc', min: 'Giá trị phải lớn hơn 0', max: 'Giá trị quá lớn', maxlength: 'Giá trị quá dài' };

@Component({
  selector: 'app-items-step',
  standalone: true,
  imports: [ReactiveFormsModule],
  template: `
  <div [formGroup]="form()">
    <div class="totals" aria-live="polite">
      <div><span class="kicker">Tổng số kiện</span><strong>{{ totals().quantity }}</strong></div>
      <div><span class="kicker">Tổng trọng lượng</span><strong>{{ kg(totals().weightKg) }}</strong></div>
      <div><span class="kicker">Tổng thể tích</span><strong>{{ m3(totals().volumeM3) }}</strong></div>
    </div>
    <p class="hint muted">Số liệu tạm tính để tham khảo; hệ thống tính lại khi lưu đơn.@if (totals().itemsWithoutSize) { {{ ' ' + totals().itemsWithoutSize }} dòng chưa có kích thước (cần bổ sung trước khi đăng đơn). }</p>
    <div formArrayName="items">
      @for (item of form().controls.items.controls; track item; let i = $index) {
        <section class="card" [formGroupName]="i">
          <div class="item-head"><h2>Hàng hóa {{ i + 1 }}</h2>
            @if (form().controls.items.length > 1) { <button class="btn btn-ghost" type="button" (click)="remove(i)">Xóa</button> }
          </div>
          <div class="grid-2">
            <div class="field" [class.invalid]="err(item.controls.itemName)">
              <label [for]="'itemName' + i">Tên hàng</label><input [id]="'itemName' + i" formControlName="itemName" />
              @if (err(item.controls.itemName); as e) { <span class="error">{{ e }}</span> }
            </div>
            <div class="field" [class.invalid]="err(item.controls.category)">
              <label [for]="'category' + i">Loại hàng</label>
              <select [id]="'category' + i" formControlName="category">
                <option value="">— Chọn loại hàng —</option>
                @for (c of categories(); track c.code) { <option [value]="c.code">{{ c.name }}</option> }
              </select>
              @if (err(item.controls.category); as e) { <span class="error">{{ e }}</span> }
            </div>
          </div>
          <div class="field"><label [for]="'description' + i">Mô tả</label><input [id]="'description' + i" formControlName="description" /></div>
          <div class="grid-5">
            <div class="field" [class.invalid]="err(item.controls.quantity)">
              <label [for]="'quantity' + i">Số kiện</label><input [id]="'quantity' + i" type="number" min="1" step="1" formControlName="quantity" />
              @if (err(item.controls.quantity); as e) { <span class="error">{{ e }}</span> }
            </div>
            <div class="field" [class.invalid]="err(item.controls.weightPerUnitKg)">
              <label [for]="'weight' + i">Kg/kiện</label><input [id]="'weight' + i" type="number" min="0.01" step="0.01" formControlName="weightPerUnitKg" />
              @if (err(item.controls.weightPerUnitKg); as e) { <span class="error">{{ e }}</span> }
            </div>
            @for (dim of dims; track dim.key) {
              <div class="field" [class.invalid]="err(item.get(dim.key)) || item.hasError('partialDimensions')">
                <label [for]="dim.key + i">{{ dim.label }} (cm)</label><input [id]="dim.key + i" type="number" min="0.01" step="0.01" [formControlName]="dim.key" />
                @if (err(item.get(dim.key)); as e) { <span class="error">{{ e }}</span> }
              </div>
            }
          </div>
          @if (item.hasError('partialDimensions')) { <p class="error-text">Nhập đủ dài, rộng, cao hoặc để trống cả ba.</p> }
        </section>
      }
    </div>
    <button class="btn btn-ghost" type="button" (click)="add()" [disabled]="form().controls.items.length >= 50">+ Thêm hàng hóa</button>
  </div>`,
  styles: [`.totals{display:grid;grid-template-columns:repeat(3,1fr);gap:12px;margin-bottom:8px}.totals>div{background:#fff;border:1px solid var(--tg-line);border-radius:6px;padding:14px}.totals strong{display:block;font-size:20px;margin-top:6px}
  .item-head{display:flex;justify-content:space-between;align-items:center}.item-head h2{margin:0 0 16px}.grid-5{display:grid;grid-template-columns:repeat(5,minmax(0,1fr));gap:0 12px}.error-text{color:var(--tg-danger);font-size:12px;margin:0}
  @media(max-width:700px){.totals{grid-template-columns:1fr}.grid-5{grid-template-columns:repeat(2,minmax(0,1fr))}}`],
})
export class ItemsStepComponent implements OnInit {
  readonly form = input.required<ShipmentForm>();
  readonly categories = input<ItemCategory[]>([]);
  protected readonly dims = [{ key: 'lengthCm', label: 'Dài' }, { key: 'widthCm', label: 'Rộng' }, { key: 'heightCm', label: 'Cao' }] as const;
  protected readonly totals = signal<Totals>({ quantity: 0, weightKg: 0, volumeM3: 0, itemsWithoutSize: 0 });
  private readonly destroyRef = inject(DestroyRef);
  protected readonly kg = formatKg;
  protected readonly m3 = formatM3;

  ngOnInit(): void {
    const items = this.form().controls.items;
    items.valueChanges.pipe(startWith(null), takeUntilDestroyed(this.destroyRef)).subscribe(() => this.totals.set(computeTotals(items.getRawValue())));
  }

  err(control: AbstractControl | null): string | null { return fieldError(control, MESSAGES); }
  add(): void { this.form().controls.items.push(itemForm()); this.form().controls.items.markAsDirty(); }
  remove(index: number): void { this.form().controls.items.removeAt(index); this.form().controls.items.markAsDirty(); }
}
