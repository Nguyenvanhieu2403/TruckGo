import { Component, input } from '@angular/core';
import { DatePipe } from '@angular/common';
import { INCIDENT_STATUS_LABELS, INCIDENT_TYPE_LABELS, Incident } from '../../core/api/trip.api';
import { PhotoStripComponent } from '../../shared/photo-strip.component';

export function incidentBadge(status: Incident['status']): string {
  return status === 'RESOLVED' ? 'badge badge--approved' : status === 'OPEN' ? 'badge badge--rejected' : status === 'IN_PROGRESS' ? 'badge badge--pending_review' : 'badge';
}

@Component({
  selector: 'app-incident-list',
  standalone: true,
  imports: [DatePipe, PhotoStripComponent],
  template: `
  @for (i of incidents(); track i.id) {
    <article class="incident" data-testid="incident">
      <div class="row"><strong>{{ types[i.type] }}</strong><span [class]="badge(i.status)">{{ statuses[i.status] }}</span></div>
      <p>{{ i.description }}</p>
      <div class="muted small">{{ i.occurredAt | date: 'HH:mm dd/MM/yyyy' }}@if (i.reportedByName) { · {{ i.reportedByName }} }</div>
      @if (i.resolutionNote) { <div class="small"><strong>Xử lý:</strong> {{ i.resolutionNote }}</div> }
      @if (i.photos.length) { <app-photo-strip [photos]="i.photos" /> }
    </article>
  } @empty { <p class="empty">Chưa có sự cố nào.</p> }`,
  styles: [`.incident{padding:12px 0;border-bottom:1px solid #eef1ea;display:flex;flex-direction:column;gap:4px}.row{display:flex;justify-content:space-between;gap:8px;align-items:center}
  .incident p{margin:2px 0}.small{font-size:12px}`],
})
export class IncidentListComponent {
  readonly incidents = input<Incident[]>([]);
  protected readonly types = INCIDENT_TYPE_LABELS;
  protected readonly statuses = INCIDENT_STATUS_LABELS;
  protected readonly badge = incidentBadge;
}
