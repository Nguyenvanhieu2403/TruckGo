import { Component, computed, input } from '@angular/core';
import { VerificationStatus } from '../core/auth/auth.models';

const LABELS: Record<VerificationStatus, string> = {
  NOT_SUBMITTED: 'Chưa gửi duyệt', PENDING_REVIEW: 'Chờ duyệt', APPROVED: 'Đã duyệt', REJECTED: 'Bị từ chối',
};

@Component({
  selector: 'app-status-badge',
  standalone: true,
  template: `<span class="badge" [class]="'badge badge--' + status().toLowerCase()">{{ label() }}</span>`,
})
export class StatusBadgeComponent {
  readonly status = input.required<VerificationStatus>();
  readonly label = computed(() => LABELS[this.status()]);
}
