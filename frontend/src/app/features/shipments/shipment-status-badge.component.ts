import { Component, computed, input } from '@angular/core';
import { ShipmentStatus } from '../../core/api/shipment.api';
import { SHIPMENT_STATUS_LABELS } from './shipment-labels';

@Component({
  selector: 'app-shipment-status',
  standalone: true,
  template: `<span class="badge" [class]="'badge shipment-status shipment-status--' + status().toLowerCase()">{{ label() }}</span>`,
})
export class ShipmentStatusBadgeComponent {
  readonly status = input.required<ShipmentStatus>();
  readonly label = computed(() => SHIPMENT_STATUS_LABELS[this.status()]);
}
