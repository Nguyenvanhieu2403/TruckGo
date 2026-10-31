import { Component, OnInit, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { toApiError } from '../../core/api.service';
import { DriverTripApi, INCIDENT_TYPE_LABELS, Incident, IncidentType, Photo } from '../../core/api/trip.api';
import { GEOLOCATION, currentPosition } from '../../core/geo/geolocation';
import { PhotoStripComponent } from '../../shared/photo-strip.component';
import { ToastService } from '../../shared/toast.service';
import { IncidentListComponent } from './incident-list.component';

/** Report a problem on the trip. Reporting informs the customer and admins; it does not cancel or reassign the order. */
@Component({
  selector: 'app-driver-trip-incidents',
  standalone: true,
  imports: [ReactiveFormsModule, RouterLink, PhotoStripComponent, IncidentListComponent],
  template: `
  <main class="container">
    <a [routerLink]="['/driver/trips', id]" class="kicker">← Chi tiết chuyến</a>
    <div class="page-head"><div><h1>Sự cố trên chuyến</h1></div></div>
    <div class="detail-grid">
      <section class="card" aria-labelledby="report-title"><h2 id="report-title">Báo cáo sự cố</h2>
        <p class="muted small">Khách hàng và bộ phận điều hành sẽ được thông báo. Đơn hàng không bị hủy hay đổi tài xế tự động.</p>
        @if (error()) { <div class="alert alert-error" role="alert">{{ error() }}</div> }
        <form [formGroup]="form" (ngSubmit)="submit()">
          <div class="field"><label for="inc-type">Loại sự cố</label>
            <select id="inc-type" formControlName="type"><option value="" disabled>Chọn loại</option>
              @for (t of typeList; track t) { <option [value]="t">{{ types[t] }}</option> }</select></div>
          <div class="field" [class.invalid]="form.controls.description.invalid && form.controls.description.touched"><label for="inc-desc">Mô tả</label>
            <textarea id="inc-desc" rows="4" maxlength="1000" formControlName="description" placeholder="Chuyện gì đã xảy ra, ảnh hưởng thế nào đến chuyến"></textarea>
            @if (form.controls.description.invalid && form.controls.description.touched) { <span class="error">Mô tả dài 5–1000 ký tự</span> }</div>
          <div class="field"><label for="inc-photo">Ảnh (không bắt buộc)</label>
            <input id="inc-photo" type="file" accept="image/jpeg,image/png,image/webp" (change)="upload($event)" [disabled]="uploading()" /></div>
          @if (photos().length) { <app-photo-strip [photos]="photos()" /> }
          <label class="checkbox"><input type="checkbox" formControlName="withLocation" /> Đính kèm vị trí hiện tại</label>
          <button class="btn btn-danger" type="submit" [disabled]="busy() || uploading()">{{ busy() ? 'Đang gửi…' : 'Gửi báo cáo' }}</button>
        </form>
      </section>
      <section class="card"><h2>Đã báo cáo</h2>
        @if (loading()) { <div class="skeleton-row"></div> } @else { <app-incident-list [incidents]="incidents()" /> }
      </section>
    </div>
  </main>`,
  styles: [`.detail-grid{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1fr);gap:20px}.small{font-size:12px}
  .checkbox{display:flex;gap:8px;align-items:center;font-size:13px;margin:6px 0 12px}@media(max-width:900px){.detail-grid{grid-template-columns:minmax(0,1fr)}}`],
})
export class DriverTripIncidentsComponent implements OnInit {
  private readonly api = inject(DriverTripApi);
  private readonly toast = inject(ToastService);
  private readonly geo = inject(GEOLOCATION);
  protected readonly id = inject(ActivatedRoute).snapshot.paramMap.get('shipmentId') ?? '';
  protected readonly incidents = signal<Incident[]>([]);
  protected readonly photos = signal<Photo[]>([]);
  protected readonly loading = signal(true);
  protected readonly busy = signal(false);
  protected readonly uploading = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly types = INCIDENT_TYPE_LABELS;
  protected readonly typeList = Object.keys(INCIDENT_TYPE_LABELS) as IncidentType[];
  protected readonly form = inject(FormBuilder).nonNullable.group({
    type: ['' as IncidentType | '', Validators.required],
    description: ['', [Validators.required, Validators.minLength(5), Validators.maxLength(1000)]],
    withLocation: false,
  });

  ngOnInit(): void { this.load(); }

  load(): void {
    this.api.incidents(this.id).subscribe({
      next: list => { this.incidents.set(list); this.loading.set(false); },
      error: (e: unknown) => { this.error.set(toApiError(e).message); this.loading.set(false); },
    });
  }

  upload(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) return;
    this.uploading.set(true);
    this.api.upload(this.id, 'INCIDENT_PHOTO', file).subscribe({
      next: p => { this.uploading.set(false); this.photos.update(l => [...l, p]); input.value = ''; },
      error: (e: unknown) => { this.uploading.set(false); this.toast.error(toApiError(e).message); input.value = ''; },
    });
  }

  async submit(): Promise<void> {
    this.form.markAllAsTouched();
    const v = this.form.getRawValue();
    if (this.form.invalid || !v.type || v.description.trim().length < 5) return;
    this.busy.set(true);
    this.error.set(null);
    let latitude: number | null = null, longitude: number | null = null;
    if (v.withLocation) {
      const p = await currentPosition(this.geo);
      if (p) { latitude = Math.round(p.coords.latitude * 1e6) / 1e6; longitude = Math.round(p.coords.longitude * 1e6) / 1e6; }
    }
    this.api.report(this.id, { type: v.type, description: v.description.trim(), latitude, longitude, attachments: this.photos().map(p => p.id) }).subscribe({
      next: incident => {
        this.busy.set(false);
        this.incidents.update(l => [incident, ...l]);
        this.photos.set([]);
        this.form.reset({ type: '', description: '', withLocation: false });
        this.toast.success('Đã gửi báo cáo sự cố.');
      },
      error: (e: unknown) => {
        const err = toApiError(e);
        this.busy.set(false);
        this.error.set(Object.values(err.fieldErrors).length ? `${err.message}: ${Object.values(err.fieldErrors).join(' ')}` : err.message);
      },
    });
  }
}
