import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router, provideRouter } from '@angular/router';
import { signal } from '@angular/core';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { NotificationBellComponent } from './notification-bell.component';
import { AuthService } from '../core/auth/auth.service';
import { AppNotification } from '../core/api/notification.api';
import { ok } from '../features/shipments/shipment-test-data';
import { NotificationStreamClient, parseNotification } from '../core/notification-stream';
import { StreamStatus } from '../core/trip-stream';
import { Subject } from 'rxjs';

const note = (overrides: Partial<AppNotification> = {}): AppNotification => ({
  id: 'n-1', type: 'APPLICATION_ACCEPTED', title: 'Bạn đã được chọn cho đơn TG1', content: 'Khách hàng đã chọn bạn.', referenceType: 'APPLICATION',
  referenceId: 'app-1', read: false, readAt: null, createdAt: '2026-11-01T00:00:00Z', ...overrides,
});

describe('NotificationBellComponent', () => {
  let http: HttpTestingController;
  let pushed: Subject<AppNotification>;
  let status: (s: StreamStatus) => void;
  let lastEventId: () => string | null;
  beforeEach(() => {
    pushed = new Subject<AppNotification>();
    const stream = { connect: (onStatus: (s: StreamStatus) => void, last: () => string | null) => { status = onStatus; lastEventId = last; return pushed.asObservable(); } };
    TestBed.configureTestingModule({
      imports: [NotificationBellComponent],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting(), { provide: AuthService, useValue: { role: signal('DRIVER') } },
        { provide: NotificationStreamClient, useValue: stream }],
    });
    http = TestBed.inject(HttpTestingController);
  });

  function render(unread: number) {
    const fixture = TestBed.createComponent(NotificationBellComponent);
    fixture.detectChanges();
    http.expectOne('/api/v1/notifications/unread-count').flush(ok({ count: unread }));
    fixture.detectChanges();
    return { fixture, el: fixture.nativeElement as HTMLElement };
  }

  it('shows the unread count and the latest notifications', () => {
    const { fixture, el } = render(2);
    expect(el.querySelector('[data-testid=unread-count]')?.textContent).toBe('2');
    el.querySelector<HTMLButtonElement>('.bell-btn')!.click();
    fixture.detectChanges();
    http.expectOne(r => r.url === '/api/v1/notifications').flush(ok({ items: [note(), note({ id: 'n-2', read: true, title: 'Cũ' })], page: 0, size: 10, totalElements: 2, totalPages: 1 }));
    http.expectOne('/api/v1/notifications/unread-count').flush(ok({ count: 1 }));
    fixture.detectChanges();
    expect(el.textContent).toContain('Bạn đã được chọn cho đơn TG1');
    expect(el.querySelectorAll('.item.unread').length).toBe(1);
    expect(el.querySelector('[data-testid=unread-count]')?.textContent).toBe('1');
  });

  it('marks a notification read and opens what it refers to', () => {
    const { fixture, el } = render(1);
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    el.querySelector<HTMLButtonElement>('.bell-btn')!.click();
    fixture.detectChanges();
    http.expectOne(r => r.url === '/api/v1/notifications').flush(ok({ items: [note()], page: 0, size: 10, totalElements: 1, totalPages: 1 }));
    http.expectOne('/api/v1/notifications/unread-count').flush(ok({ count: 1 }));
    fixture.detectChanges();
    el.querySelector<HTMLButtonElement>('.item')!.click();
    http.expectOne('/api/v1/notifications/n-1/read').flush(ok(note({ read: true, readAt: '2026-11-01T01:00:00Z' })));
    fixture.detectChanges();
    expect(navigate).toHaveBeenCalledWith(['/driver/applications', 'app-1']);
    expect(el.querySelector('[data-testid=unread-count]')).toBeNull();
  });

  it('marks everything read', () => {
    const { fixture, el } = render(3);
    el.querySelector<HTMLButtonElement>('.bell-btn')!.click();
    fixture.detectChanges();
    http.expectOne(r => r.url === '/api/v1/notifications').flush(ok({ items: [note()], page: 0, size: 10, totalElements: 1, totalPages: 1 }));
    http.expectOne('/api/v1/notifications/unread-count').flush(ok({ count: 3 }));
    fixture.detectChanges();
    [...el.querySelectorAll('button')].find(b => b.textContent?.includes('Đánh dấu đã đọc hết'))!.click();
    http.expectOne('/api/v1/notifications/read-all').flush(ok({ updated: 3 }));
    fixture.detectChanges();
    expect(el.querySelector('[data-testid=unread-count]')).toBeNull();
    expect(el.querySelectorAll('.item.unread').length).toBe(0);
  });

  it('adds pushed notifications once, even when a reconnect replays them', () => {
    const { fixture, el } = render(1);
    status('open');
    pushed.next(note({ id: 'n-9', title: 'Có tài xế muốn nhận đơn TG9' }));
    pushed.next(note({ id: 'n-9', title: 'Có tài xế muốn nhận đơn TG9' }));   // replayed after reconnect
    fixture.detectChanges();
    expect(el.querySelector('[data-testid=unread-count]')?.textContent).toBe('2');
    expect(lastEventId()).toBe('n-9');      // sent as Last-Event-ID on the next connection
    el.querySelector<HTMLButtonElement>('.bell-btn')!.click();
    fixture.detectChanges();
    http.expectOne(r => r.url === '/api/v1/notifications').flush(ok({ items: [note({ id: 'n-9', title: 'Có tài xế muốn nhận đơn TG9' })], page: 0, size: 10, totalElements: 1, totalPages: 1 }));
    http.expectOne('/api/v1/notifications/unread-count').flush(ok({ count: 2 }));
    fixture.detectChanges();
    pushed.next(note({ id: 'n-10', title: 'Đơn TG9 đã đủ tài xế', type: 'SHIPMENT_FULLY_ASSIGNED' }));
    fixture.detectChanges();
    expect(el.querySelectorAll('.item').length).toBe(2);
    expect(el.querySelector('.item .title')?.textContent).toContain('Đơn TG9 đã đủ tài xế');
  });

  it('re-reads the unread count after the stream comes back', () => {
    render(0);
    status('open');
    pushed.next(note({ id: 'n-1' }));
    status('closed');
    status('open');
    http.expectOne('/api/v1/notifications/unread-count').flush(ok({ count: 4 }));
  });
});

describe('parseNotification', () => {
  it('reads notification events and ignores heartbeats and other events', () => {
    expect(parseNotification('id:n-1\nevent:notification\ndata:{"id":"n-1","title":"A"}')?.id).toBe('n-1');
    expect(parseNotification(':hb')).toBeNull();
    expect(parseNotification('event:ready\ndata:{"replayed":0}')).toBeNull();
    expect(parseNotification('event:notification\ndata:not json')).toBeNull();
  });
});
