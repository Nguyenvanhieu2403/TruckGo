import { Component, inject } from '@angular/core';
import { ToastService } from './toast.service';

@Component({
  selector: 'app-toasts',
  standalone: true,
  template: `
  <div class="toasts" aria-live="polite">
    @for (t of toast.toasts(); track t.id) {
      <div class="toast" [class]="'toast toast--' + t.kind" role="status">
        <span>{{ t.text }}</span><button type="button" aria-label="Đóng" (click)="toast.dismiss(t.id)">×</button>
      </div>
    }
  </div>`,
  styles: [`.toasts{position:fixed;right:16px;bottom:16px;z-index:40;display:flex;flex-direction:column;gap:8px;max-width:min(380px,calc(100vw - 32px))}
  .toast{display:flex;gap:12px;align-items:flex-start;justify-content:space-between;padding:12px 14px;border-radius:6px;font-size:13px;box-shadow:0 8px 24px #18251f26;background:#fff;border-left:4px solid var(--tg-accent)}
  .toast--success{border-left-color:var(--tg-ok)}.toast--error{border-left-color:var(--tg-danger)}
  .toast button{border:0;background:none;font-size:18px;line-height:1;cursor:pointer;color:var(--tg-muted)}`],
})
export class ToastContainerComponent { protected readonly toast = inject(ToastService); }
