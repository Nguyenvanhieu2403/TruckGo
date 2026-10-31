import { Component, OnInit, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { PageResponse, toApiError } from '../../core/api.service';
import { AdminTripApi, INCIDENT_STATUS_LABELS, INCIDENT_TYPE_LABELS, Incident, IncidentStatus, IncidentType } from '../../core/api/trip.api';
import { incidentBadge } from '../trips/incident-list.component';

@Component({
  selector: 'app-admin-incidents',
  standalone: true,
  imports: [DatePipe, FormsModule, RouterLink],
  template: `
  <main class="container">
    <div class="page-head"><div><div class="kicker">Quản trị</div><h1>Sự cố vận chuyển</h1></div></div>
    <form class="card filters" (ngSubmit)="load(0)">
      <div class="field"><label for="ai-type">Loại</label><select id="ai-type" name="type" [(ngModel)]="type"><option value="">Tất cả</option>
        @for (t of typeList; track t) { <option [value]="t">{{ types[t] }}</option> }</select></div>
      <div class="field"><label for="ai-status">Trạng thái</label><select id="ai-status" name="status" [(ngModel)]="status"><option value="">Tất cả</option>
        @for (s of statusList; track s) { <option [value]="s">{{ statuses[s] }}</option> }</select></div>
      <div class="field"><label for="ai-from">Từ ngày</label><input id="ai-from" name="from" type="date" [(ngModel)]="from" /></div>
      <div class="field"><label for="ai-to">Đến ngày</label><input id="ai-to" name="to" type="date" [(ngModel)]="to" /></div>
      <div class="filter-actions"><button class="btn btn-primary" type="submit" [disabled]="loading()">Lọc</button></div>
    </form>
    @if (error()) { <div class="alert alert-error" role="alert">{{ error() }}</div> }
    <section class="card">
      @if (loading()) { @for (n of [1, 2, 3]; track n) { <div class="skeleton-row"></div> } }
      @else if (data(); as d) {
        @if (d.items.length === 0) { <p class="empty">Không có sự cố nào.</p> } @else {
          <div class="table-wrap"><table class="table"><thead><tr><th>Thời điểm</th><th>Đơn</th><th>Loại</th><th>Mô tả</th><th>Người báo</th><th>Trạng thái</th></tr></thead>
            <tbody>@for (i of d.items; track i.id) {
              <tr><td>{{ i.occurredAt | date: 'HH:mm dd/MM/yyyy' }}</td><td><a [routerLink]="['/admin/shipments', i.shipmentId]">{{ i.shipmentCode }}</a></td>
                <td><a [routerLink]="['/admin/incidents', i.id]">{{ types[i.type] }}</a></td><td class="desc">{{ i.description }}</td><td>{{ i.reportedByName }}</td>
                <td><span [class]="badge(i.status)">{{ statuses[i.status] }}</span></td></tr>
            }</tbody></table></div>
          <div class="pager">
            <button class="btn btn-ghost" type="button" (click)="load(d.page - 1)" [disabled]="d.page === 0">‹ Trước</button>
            <span>Trang {{ d.page + 1 }} / {{ d.totalPages || 1 }} · {{ d.totalElements }} sự cố</span>
            <button class="btn btn-ghost" type="button" (click)="load(d.page + 1)" [disabled]="d.page + 1 >= d.totalPages">Sau ›</button>
          </div>
        }
      }
    </section>
  </main>`,
  styles: [`.filters{display:grid;grid-template-columns:repeat(4,minmax(0,1fr)) auto;gap:0 12px;align-items:end}.filter-actions{padding-bottom:14px}
  .desc{max-width:320px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}@media(max-width:900px){.filters{grid-template-columns:1fr 1fr}}`],
})
export class AdminIncidentsComponent implements OnInit {
  private readonly api = inject(AdminTripApi);
  protected readonly data = signal<PageResponse<Incident> | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected type: IncidentType | '' = '';
  protected status: IncidentStatus | '' = 'OPEN';
  protected from = '';
  protected to = '';
  protected readonly types = INCIDENT_TYPE_LABELS;
  protected readonly statuses = INCIDENT_STATUS_LABELS;
  protected readonly typeList = Object.keys(INCIDENT_TYPE_LABELS) as IncidentType[];
  protected readonly statusList = Object.keys(INCIDENT_STATUS_LABELS) as IncidentStatus[];
  protected readonly badge = incidentBadge;

  ngOnInit(): void { this.load(0); }

  load(page: number): void {
    this.loading.set(true);
    this.error.set(null);
    const day = (d: string, end = false) => (d ? new Date(`${d}T00:00:00+07:00`).getTime() + (end ? 86_400_000 : 0) : null);
    const from = day(this.from), to = day(this.to, true);
    this.api.incidents({ type: this.type, status: this.status, from: from ? new Date(from).toISOString() : '', to: to ? new Date(to).toISOString() : '', page: Math.max(page, 0) })
      .subscribe({ next: d => { this.data.set(d); this.loading.set(false); }, error: (e: unknown) => { this.error.set(toApiError(e).message); this.loading.set(false); } });
  }
}
