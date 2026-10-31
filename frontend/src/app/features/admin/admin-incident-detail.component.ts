import { Component, OnInit, inject, signal } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { toApiError } from '../../core/api.service';
import { AdminTripApi, INCIDENT_STATUS_LABELS, INCIDENT_TYPE_LABELS, Incident, IncidentStatus } from '../../core/api/trip.api';
import { PhotoStripComponent } from '../../shared/photo-strip.component';
import { ToastService } from '../../shared/toast.service';
import { incidentBadge } from '../trips/incident-list.component';

const NEXT: Record<IncidentStatus, IncidentStatus[]> = { OPEN: ['IN_PROGRESS', 'RESOLVED', 'CANCELLED'], IN_PROGRESS: ['RESOLVED', 'CANCELLED'], RESOLVED: [], CANCELLED: [] };

@Component({
  selector: 'app-admin-incident-detail',
  standalone: true,
  imports: [DatePipe, DecimalPipe, FormsModule, RouterLink, PhotoStripComponent],
  template: `
  <main class="container">
    <a routerLink="/admin/incidents" class="kicker">← Sự cố</a>
    @if (error()) { <div class="alert alert-error" role="alert" style="margin-top:14px">{{ error() }}</div> }
    @if (incident(); as i) {
      <div class="page-head"><div><h1>{{ types[i.type] }}</h1><span class="muted">Đơn <a [routerLink]="['/admin/shipments', i.shipmentId]">{{ i.shipmentCode }}</a> · {{ i.occurredAt | date: 'HH:mm dd/MM/yyyy' }}</span></div>
        <span [class]="badge(i.status)" data-testid="incident-status">{{ statuses[i.status] }}</span></div>
      <div class="detail-grid">
        <section class="card"><h2>Nội dung</h2><p>{{ i.description }}</p>
          <dl class="dl compact"><dt>Người báo</dt><dd>{{ i.reportedByName || '—' }}</dd>
            @if (i.latitude !== null) { <dt>Vị trí</dt><dd>{{ i.latitude | number: '1.5-5' }}, {{ i.longitude | number: '1.5-5' }}</dd> }
            @if (i.resolutionNote) { <dt>Xử lý</dt><dd>{{ i.resolutionNote }}</dd> }
            @if (i.resolvedAt) { <dt>Đóng lúc</dt><dd>{{ i.resolvedAt | date: 'HH:mm dd/MM/yyyy' }}</dd> }</dl>
          <app-photo-strip [photos]="i.photos" /></section>
        <section class="card"><h2>Cập nhật xử lý</h2>
          @if (next(i).length === 0) { <p class="muted">Sự cố đã đóng.</p> } @else {
            <div class="field"><label for="ai-next">Chuyển sang</label><select id="ai-next" [(ngModel)]="target">
              @for (s of next(i); track s) { <option [value]="s">{{ statuses[s] }}</option> }</select></div>
            <div class="field"><label for="ai-note">Ghi chú@if (target === 'RESOLVED' || target === 'CANCELLED') { (bắt buộc) }</label>
              <textarea id="ai-note" rows="3" maxlength="1000" [(ngModel)]="note"></textarea></div>
            <button class="btn btn-primary" type="button" (click)="save(i)" [disabled]="busy() || ((target === 'RESOLVED' || target === 'CANCELLED') && !note.trim())">Lưu</button>
            <p class="muted small">Khách hàng và tài xế sẽ được thông báo. Thao tác này không hủy đơn hay đổi tài xế.</p>
          }
        </section>
      </div>
    } @else if (!error()) { <div class="card" aria-busy="true">@for (n of [1, 2]; track n) { <div class="skeleton-row"></div> }</div> }
  </main>`,
  styles: [`.detail-grid{display:grid;grid-template-columns:minmax(0,1.4fr) minmax(0,1fr);gap:20px}.dl.compact{grid-template-columns:100px minmax(0,1fr)}.small{font-size:12px}
  @media(max-width:900px){.detail-grid{grid-template-columns:minmax(0,1fr)}}`],
})
export class AdminIncidentDetailComponent implements OnInit {
  private readonly api = inject(AdminTripApi);
  private readonly toast = inject(ToastService);
  private readonly id = inject(ActivatedRoute).snapshot.paramMap.get('id') ?? '';
  protected readonly incident = signal<Incident | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected target: IncidentStatus = 'IN_PROGRESS';
  protected note = '';
  protected readonly types = INCIDENT_TYPE_LABELS;
  protected readonly statuses = INCIDENT_STATUS_LABELS;
  protected readonly badge = incidentBadge;

  ngOnInit(): void {
    this.api.incident(this.id).subscribe({ next: i => this.show(i), error: (e: unknown) => this.error.set(toApiError(e).message) });
  }

  next(i: Incident): IncidentStatus[] { return NEXT[i.status]; }

  save(i: Incident): void {
    this.busy.set(true);
    this.error.set(null);
    this.api.update(i.id, this.target, this.note.trim() || null).subscribe({
      next: updated => { this.busy.set(false); this.note = ''; this.show(updated); this.toast.success('Đã cập nhật sự cố.'); },
      error: (e: unknown) => {
        const err = toApiError(e);
        this.busy.set(false);
        this.error.set(Object.values(err.fieldErrors).length ? Object.values(err.fieldErrors).join(' ') : err.message);
      },
    });
  }

  private show(i: Incident): void {
    this.incident.set(i);
    this.target = NEXT[i.status][0] ?? i.status;
  }
}
