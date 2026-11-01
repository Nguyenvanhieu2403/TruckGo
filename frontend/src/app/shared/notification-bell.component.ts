import { Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { Router } from '@angular/router';
import { Subscription, interval, startWith, switchMap, catchError, of, filter } from 'rxjs';
import { AppNotification, NotificationApi } from '../core/api/notification.api';
import { AuthService } from '../core/auth/auth.service';
import { toApiError } from '../core/api.service';
import { NotificationStreamClient } from '../core/notification-stream';
import { StreamStatus } from '../core/trip-stream';
import { ToastService } from './toast.service';

/**
 * Unread badge and a dropdown with the latest notifications. New notifications arrive in realtime over the
 * notification stream; while the stream is down the badge is polled every 60 s (and every 5 minutes as a safety net
 * while it is up). Notifications are de-duplicated by id, so a replay after reconnecting never counts twice.
 */
@Component({
  selector: 'app-notification-bell',
  standalone: true,
  imports: [DatePipe],
  template: `
  <div class="bell">
    <button class="bell-btn" type="button" (click)="toggle()" [attr.aria-expanded]="open()" aria-controls="notification-panel"
            [attr.aria-label]="unread() ? 'Thông báo, ' + unread() + ' chưa đọc' : 'Thông báo'">
      <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true"><path fill="currentColor" d="M12 22a2.5 2.5 0 0 0 2.45-2h-4.9A2.5 2.5 0 0 0 12 22Zm7-6V11a7 7 0 0 0-5-6.71V3.5a2 2 0 0 0-4 0v.79A7 7 0 0 0 5 11v5l-2 2v1h18v-1Z"/></svg>
      @if (unread()) { <span class="count" data-testid="unread-count">{{ unread() > 99 ? '99+' : unread() }}</span> }
    </button>
    @if (open()) {
      <div class="backdrop-clear" (click)="open.set(false)"></div>
      <div id="notification-panel" class="panel card" role="dialog" aria-label="Thông báo">
        <div class="panel-head"><strong>Thông báo</strong>
          <button class="btn btn-ghost" type="button" (click)="readAll()" [disabled]="!unread() || busy()">Đánh dấu đã đọc hết</button></div>
        @if (error()) { <div class="alert alert-error" role="alert">{{ error() }}</div> }
        @if (loading()) { @for (n of [1, 2, 3]; track n) { <div class="skeleton-row"></div> } }
        @else {
          @for (n of items(); track n.id) {
            <button type="button" class="item" [class.unread]="!n.read" (click)="openItem(n)">
              <span class="title">{{ n.title }}</span><span class="content">{{ n.content }}</span>
              <span class="time">{{ n.createdAt | date: 'HH:mm dd/MM/yyyy' }}</span></button>
          } @empty { <p class="empty">Chưa có thông báo.</p> }
          @if (hasMore()) { <button class="btn btn-ghost more" type="button" (click)="more()">Xem thêm</button> }
        }
      </div>
    }
  </div>`,
  styles: [`.bell{position:relative}.bell-btn{position:relative;display:grid;place-items:center;width:38px;height:38px;border:1px solid var(--tg-line);border-radius:50%;background:#fff;color:#4c5a4f;cursor:pointer}
  .count{position:absolute;top:-4px;right:-4px;min-width:18px;height:18px;padding:0 4px;border-radius:9px;background:#c4452f;color:#fff;font-size:11px;font-weight:700;line-height:18px}
  .backdrop-clear{position:fixed;inset:0;z-index:29}
  .panel{position:absolute;right:0;top:46px;z-index:30;width:min(380px,calc(100vw - 32px));max-height:70vh;overflow:auto;margin:0;padding:12px}
  .panel-head{display:flex;justify-content:space-between;align-items:center;gap:8px;margin-bottom:8px}
  .item{display:flex;flex-direction:column;gap:2px;width:100%;text-align:left;padding:10px;border:0;border-bottom:1px solid #eef1ea;background:none;cursor:pointer;font:inherit}
  .item:hover{background:#f7f8f4}.item.unread{background:var(--tg-accent-soft)}.title{font-weight:700;font-size:13px}.content{font-size:13px;color:#4c5a4f}.time{font-size:11px;color:var(--tg-muted)}
  .more{width:100%;margin-top:6px}@media(max-width:820px){.panel{position:fixed;left:16px;right:16px;top:72px;width:auto}}`],
})
export class NotificationBellComponent implements OnInit {
  private readonly api = inject(NotificationApi);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly open = signal(false);
  protected readonly unread = signal(0);
  protected readonly items = signal<AppNotification[]>([]);
  protected readonly loading = signal(false);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  private readonly page = signal(0);
  private readonly totalPages = signal(0);
  protected readonly hasMore = computed(() => this.page() + 1 < this.totalPages());
  private readonly stream = inject(NotificationStreamClient);
  private readonly toasts = inject(ToastService);
  /** 'open' while realtime notifications are flowing. */
  readonly live = signal<StreamStatus>('closed');
  private readonly seen = new Set<string>();
  private lastId: string | null = null;
  private poll?: Subscription;
  private push?: Subscription;
  private ticks = 0;

  ngOnInit(): void {
    // Fallback polling: every minute while the stream is down, every fifth minute while it is up.
    this.poll = interval(60_000).pipe(startWith(0), filter(() => this.live() !== 'open' || this.ticks++ % 5 === 0),
      switchMap(() => this.api.unreadCount().pipe(catchError(() => of(null)))))
      .subscribe(count => { if (count !== null) this.unread.set(count); });
    this.push = this.stream.connect(s => {
      const reconnected = s === 'open' && this.live() !== 'open' && this.lastId !== null;
      this.live.set(s);
      if (reconnected) this.api.unreadCount().subscribe({ next: c => this.unread.set(c), error: () => undefined });
    }, () => this.lastId).subscribe(n => this.receive(n));
    this.destroyRef.onDestroy(() => { this.poll?.unsubscribe(); this.push?.unsubscribe(); });
  }

  /** A notification pushed by the server (live or replayed after a reconnect). */
  receive(n: AppNotification): void {
    this.lastId = n.id;
    if (this.seen.has(n.id) || this.items().some(x => x.id === n.id)) return;
    this.seen.add(n.id);
    if (!n.read) this.unread.update(c => c + 1);
    if (this.items().length || this.open()) this.items.update(list => [n, ...list]);
    this.toasts.info(n.title);
  }

  toggle(): void {
    this.open.update(v => !v);
    if (this.open()) {
      this.load(0);
      this.api.unreadCount().subscribe({ next: count => this.unread.set(count), error: () => undefined });
    }
  }

  more(): void { this.load(this.page() + 1); }

  openItem(n: AppNotification): void {
    if (!n.read) {
      this.api.markRead(n.id).subscribe({ next: updated => { this.replace(updated); this.unread.update(c => Math.max(c - 1, 0)); } });
    }
    const link = this.link(n);
    this.open.set(false);
    if (link) void this.router.navigate(link);
  }

  readAll(): void {
    this.busy.set(true);
    this.api.markAllRead().subscribe({
      next: () => { this.busy.set(false); this.unread.set(0); this.items.update(list => list.map(n => ({ ...n, read: true }))); },
      error: (e: unknown) => { this.busy.set(false); this.error.set(toApiError(e).message); },
    });
  }

  /** Where a notification leads depends on who receives it. */
  link(n: AppNotification): string[] | null {
    if (!n.referenceId) return null;
    const role = this.auth.role();
    if (n.referenceType === 'APPLICATION' && role === 'DRIVER') return ['/driver/applications', n.referenceId];
    if (n.referenceType === 'SHIPMENT' && role === 'CUSTOMER')
      return n.type.startsWith('APPLICATION') || n.type.startsWith('SHIPMENT_') || n.type === 'ASSIGNMENT_RELEASED'
        ? ['/customer/shipments', n.referenceId] : ['/customer/shipments', n.referenceId, 'tracking'];
    // A driver removed from an order no longer has its trip.
    if (n.referenceType === 'SHIPMENT' && role === 'DRIVER') return n.type === 'ASSIGNMENT_RELEASED' ? ['/driver/applications'] : ['/driver/trips', n.referenceId];
    if (n.referenceType === 'SHIPMENT' && role === 'ADMIN') return ['/admin/shipments', n.referenceId];
    return null;
  }

  private load(page: number): void {
    this.loading.set(page === 0);
    this.error.set(null);
    this.api.list(page).subscribe({
      next: data => {
        this.items.update(list => (page === 0 ? data.items : [...list, ...data.items]));
        data.items.forEach(n => this.seen.add(n.id));
        if (page === 0 && data.items.length && !this.lastId) this.lastId = data.items[0].id;
        this.page.set(data.page);
        this.totalPages.set(data.totalPages);
        this.loading.set(false);
      },
      error: (e: unknown) => { this.loading.set(false); this.error.set(toApiError(e).message); },
    });
  }

  private replace(updated: AppNotification): void { this.items.update(list => list.map(n => (n.id === updated.id ? updated : n))); }
}
