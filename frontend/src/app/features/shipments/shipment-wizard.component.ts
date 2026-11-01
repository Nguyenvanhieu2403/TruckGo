import { Component, HostListener, OnInit, inject, signal } from '@angular/core';
import { Location } from '@angular/common';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Observable, forkJoin, switchMap, tap } from 'rxjs';
import { BodyType, CatalogApi, CatalogVehicleType, ItemCategory, ServiceDefinition } from '../../core/api/catalog.api';
import { DraftPayload, ShipmentApi, ShipmentDetail } from '../../core/api/shipment.api';
import { toApiError } from '../../core/api.service';
import { AttachmentPanelComponent } from './attachment-panel.component';
import { STEP_LABELS, ShipmentForm, applyServerErrors, ensureServiceControls, patchFromDetail, shipmentForm, toPayload } from './shipment-form';
import { ItemsStepComponent } from './steps/items-step.component';
import { ReviewStepComponent } from './steps/review-step.component';
import { ServicesStepComponent } from './steps/services-step.component';
import { StopsStepComponent } from './steps/stops-step.component';
import { VehicleStepComponent } from './steps/vehicle-step.component';
import { LeaveConfirmable } from './unsaved-changes.guard';

/** Container for the 5-step order wizard: owns the form, catalogs and save/publish; each step renders one part. */
@Component({
  selector: 'app-shipment-wizard',
  standalone: true,
  imports: [RouterLink, StopsStepComponent, ItemsStepComponent, VehicleStepComponent, ServicesStepComponent, ReviewStepComponent, AttachmentPanelComponent],
  template: `
  <main class="container">
    <div class="page-head"><div><a routerLink="/customer/shipments" class="kicker">← Đơn vận chuyển</a>
      <h1>{{ shipmentId() ? 'Sửa đơn nháp ' + (code() ?? '') : 'Tạo đơn vận chuyển' }}</h1></div></div>
    <ol class="steps" aria-label="Các bước tạo đơn">
      @for (label of labels; track label; let i = $index) {
        <li [class.active]="step() === i" [class.done]="i < step()">
          <button type="button" (click)="goTo(i)" [disabled]="i > step() || busy()" [attr.aria-current]="step() === i ? 'step' : null"><span>{{ i + 1 }}</span>{{ label }}</button>
        </li>
      }
    </ol>
    @if (loadError()) { <div class="alert alert-error" role="alert">{{ loadError() }}</div> }
    @else if (!ready()) { <div class="card skeleton" aria-busy="true">Đang tải dữ liệu…</div> }
    @else {
      @if (error()) { <div class="alert alert-error" role="alert">{{ error() }}</div> }
      @if (notice()) { <div class="alert alert-success" role="status">{{ notice() }}</div> }
      @switch (step()) {
        @case (0) { <app-stops-step [form]="form" [catalog]="services()" /> }
        @case (1) {
          <app-items-step [form]="form" [categories]="categories()" />
          @if (shipmentId(); as id) { <app-attachment-panel [shipmentId]="id" [initial]="attachments()" [editable]="true" /> }
          @else { <div class="card"><h2>Ảnh hàng hóa</h2><p class="muted">Lưu nháp để tải ảnh hàng hóa lên.</p>
            <button class="btn btn-ghost" type="button" (click)="saveDraft(true)" [disabled]="busy()">Lưu nháp để thêm ảnh</button></div> }
        }
        @case (2) { <app-vehicle-step [form]="form" [vehicleTypes]="vehicleTypes()" [bodyTypes]="bodyTypes()" /> }
        @case (3) { <app-services-step [form]="form" [catalog]="services()" /> }
        @case (4) { <app-review-step [payload]="preview()!" [categories]="categories()" [vehicleTypes]="vehicleTypes()" [bodyTypes]="bodyTypes()" [services]="services()" /> }
      }
      <div class="wizard-nav">
        <button class="btn btn-ghost" type="button" (click)="back()" [disabled]="step() === 0 || busy()">← Quay lại</button>
        <div class="actions-row">
          <button class="btn btn-ghost" type="button" (click)="saveDraft(false)" [disabled]="busy()">{{ saving() === 'draft' ? 'Đang lưu…' : 'Lưu nháp' }}</button>
          @if (step() < labels.length - 1) { <button class="btn btn-primary" type="button" (click)="next()" [disabled]="busy()">Tiếp tục →</button> }
          @else { <button class="btn btn-primary" type="button" (click)="publish()" [disabled]="busy()">{{ saving() === 'publish' ? 'Đang đăng…' : 'Đăng đơn' }}</button> }
        </div>
      </div>
    }
  </main>`,
  styles: [`.steps{display:grid;grid-template-columns:repeat(5,1fr);gap:8px;list-style:none;padding:0;margin:0 0 22px}
  .steps button{width:100%;display:flex;align-items:center;gap:8px;font:inherit;font-size:13px;font-weight:600;padding:10px;border:1px solid var(--tg-line);background:#fff;border-radius:6px;color:var(--tg-muted);cursor:pointer}
  .steps button:disabled{cursor:default}.steps span{display:grid;place-items:center;width:24px;height:24px;border-radius:50%;background:#eceee9;font-size:12px;flex:none}
  .steps .active button{border-color:var(--tg-dark);color:var(--tg-ink)}.steps .active span{background:var(--tg-dark);color:#fff}.steps .done span{background:#8eb85a;color:#fff}
  .wizard-nav{display:flex;justify-content:space-between;gap:12px;flex-wrap:wrap;position:sticky;bottom:0;background:var(--tg-bg);padding:14px 0;border-top:1px solid var(--tg-line);margin-top:8px}
  .skeleton{color:var(--tg-muted)}@media(max-width:700px){.steps{grid-template-columns:repeat(5,minmax(0,1fr))}.steps button{flex-direction:column;font-size:10px;padding:8px 4px}}`],
})
export class ShipmentWizardComponent implements OnInit, LeaveConfirmable {
  private readonly catalogApi = inject(CatalogApi);
  private readonly api = inject(ShipmentApi);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly location = inject(Location);
  protected readonly labels = STEP_LABELS;
  protected readonly form: ShipmentForm = shipmentForm();
  protected readonly step = signal(0);
  protected readonly ready = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly saving = signal<'draft' | 'publish' | null>(null);
  protected readonly shipmentId = signal<string | null>(null);
  protected readonly code = signal<string | null>(null);
  protected readonly attachments = signal<ShipmentDetail['attachments']>([]);
  protected readonly preview = signal<DraftPayload | null>(null);
  protected readonly categories = signal<ItemCategory[]>([]);
  protected readonly vehicleTypes = signal<CatalogVehicleType[]>([]);
  protected readonly bodyTypes = signal<BodyType[]>([]);
  protected readonly services = signal<ServiceDefinition[]>([]);
  private version: number | undefined;
  private leaving = false;

  busy(): boolean { return this.saving() !== null; }

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id');
    forkJoin({ categories: this.catalogApi.itemCategories(), vehicleTypes: this.catalogApi.vehicleTypes(), bodyTypes: this.catalogApi.bodyTypes(), services: this.catalogApi.services() })
      .subscribe({
        next: c => {
          this.categories.set(c.categories); this.vehicleTypes.set(c.vehicleTypes); this.bodyTypes.set(c.bodyTypes); this.services.set(c.services);
          ensureServiceControls(this.form, c.services);
          if (!id) { this.ready.set(true); return; }
          this.api.get(id).subscribe({
            next: detail => {
              if (!detail.actions.editable) { this.leaving = true; void this.router.navigate(['/customer/shipments', id]); return; }
              this.adopt(detail);
              patchFromDetail(this.form, detail, c.services);
              this.ready.set(true);
            },
            error: (e: unknown) => this.loadError.set(toApiError(e).message),
          });
        },
        error: (e: unknown) => this.loadError.set(toApiError(e).message),
      });
  }

  canLeave(): boolean { return this.leaving || !this.form.dirty; }

  @HostListener('window:beforeunload', ['$event'])
  protected beforeUnload(event: BeforeUnloadEvent): void { if (!this.canLeave()) event.preventDefault(); }

  goTo(index: number): void { if (index <= this.step()) this.show(index); }
  back(): void { this.show(Math.max(0, this.step() - 1)); }

  /** Moves forward only when the current step is valid; earlier steps stay editable. */
  next(): void {
    const group = this.stepControl(this.step());
    group.forEach(c => c.markAllAsTouched());
    if (group.some(c => c.invalid)) { this.error.set('Vui lòng kiểm tra các trường được đánh dấu.'); return; }
    this.error.set(null);
    this.show(this.step() + 1);
  }

  saveDraft(stay: boolean): void {
    if (this.busy()) return;
    if (!this.validateAll()) return;
    this.saving.set('draft');
    this.persist().subscribe({
      next: detail => {
        this.saving.set(null);
        this.notice.set(stay ? 'Đã lưu nháp. Bạn có thể thêm ảnh hàng hóa.' : 'Đã lưu nháp.');
        // Stay in the wizard (state and photos panel intact) and make the URL point at the saved draft.
        if (stay) this.location.replaceState(`/customer/shipments/${detail.id}/edit`);
        else { this.leaving = true; void this.router.navigate(['/customer/shipments', detail.id]); }
      },
      error: (e: unknown) => this.fail(e),
    });
  }

  publish(): void {
    if (this.busy()) return;
    if (!this.validateAll()) return;
    this.saving.set('publish');
    this.persist().pipe(switchMap(detail => this.api.publish(detail.id))).subscribe({
      next: detail => { this.saving.set(null); this.leaving = true; void this.router.navigate(['/customer/shipments', detail.id], { queryParams: { published: 1 } }); },
      error: (e: unknown) => this.fail(e),
    });
  }

  private persist(): Observable<ShipmentDetail> {
    const id = this.shipmentId();
    const payload = toPayload(this.form, this.services(), id ? this.version : undefined);
    this.preview.set(payload);
    return (id ? this.api.updateDraft(id, payload) : this.api.createDraft(payload)).pipe(tap(detail => { this.adopt(detail); this.form.markAsPristine(); }));
  }

  private adopt(detail: ShipmentDetail): void {
    this.shipmentId.set(detail.id);
    this.code.set(detail.shipmentCode);
    this.version = detail.version;
    this.attachments.set(detail.attachments);
  }

  private fail(e: unknown): void {
    this.saving.set(null);
    const apiError = toApiError(e);
    const payload = this.preview() ?? toPayload(this.form, this.services());
    const { step, unmatched } = applyServerErrors(this.form, apiError.fieldErrors, payload);
    this.error.set(unmatched.length ? unmatched.join(' ') : apiError.message);
    if (step !== null && step < this.step()) this.show(step, false);
  }

  private validateAll(): boolean {
    for (let i = 0; i < this.labels.length - 1; i++) {
      const group = this.stepControl(i);
      group.forEach(c => c.markAllAsTouched());
      if (group.some(c => c.invalid)) { this.error.set(`Bước "${this.labels[i]}" còn thông tin chưa hợp lệ.`); this.show(i, false); return false; }
    }
    this.error.set(null);
    return true;
  }

  private stepControl(index: number) {
    const c = this.form.controls;
    return [[c.schedule, c.stops], [c.items], [c.vehicle], [c.services, c.customerNote], []][index] ?? [];
  }

  private show(index: number, clearMessages = true): void {
    if (clearMessages) { this.error.set(null); this.notice.set(null); }
    if (index === this.labels.length - 1) this.preview.set(toPayload(this.form, this.services()));
    this.step.set(index);
    globalThis.scrollTo?.({ top: 0, behavior: 'smooth' });
  }
}
