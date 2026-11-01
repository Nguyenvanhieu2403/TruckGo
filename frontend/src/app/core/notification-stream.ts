import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { AuthService } from './auth/auth.service';
import { FETCH, StreamStatus } from './trip-stream';
import { AppNotification, NOTIFICATION_STREAM_URL } from './api/notification.api';

/**
 * The current user's notifications as server-sent events. Reconnects with backoff (the server also closes streams
 * periodically so a fresh token is used) and sends the id of the last notification it saw, so the server replays
 * anything stored while the stream was down. PostgreSQL stays the source: callers keep a slower poll as a fallback.
 */
@Injectable({ providedIn: 'root' })
export class NotificationStreamClient {
  private readonly auth = inject(AuthService);

  connect(onStatus: (s: StreamStatus) => void, lastEventId: () => string | null): Observable<AppNotification> {
    return new Observable<AppNotification>(subscriber => {
      let stopped = false;
      let controller: AbortController | null = null;
      let attempt = 0;
      let timer: ReturnType<typeof setTimeout> | null = null;

      const schedule = () => {
        if (stopped) return;
        onStatus('closed');
        const delay = Math.min(30_000, 2000 * 2 ** Math.min(attempt++, 4));
        timer = setTimeout(open, delay);
      };

      const open = async () => {
        if (stopped) return;
        onStatus('connecting');
        controller = new AbortController();
        try {
          const token = this.auth.accessToken();
          const last = lastEventId();
          const response = await FETCH.fetch(NOTIFICATION_STREAM_URL, {
            headers: { Accept: 'text/event-stream', ...(token ? { Authorization: `Bearer ${token}` } : {}), ...(last ? { 'Last-Event-ID': last } : {}) },
            signal: controller.signal, cache: 'no-store',
          });
          if (!response.ok || !response.body) { schedule(); return; }
          attempt = 0;
          onStatus('open');
          const reader = response.body.pipeThrough(new TextDecoderStream()).getReader();
          let buffer = '';
          for (;;) {
            const { value, done } = await reader.read();
            if (done) break;
            buffer += value;
            let split: number;
            while ((split = buffer.indexOf('\n\n')) >= 0) {
              const n = parseNotification(buffer.slice(0, split));
              buffer = buffer.slice(split + 2);
              if (n) subscriber.next(n);
            }
          }
          schedule();
        } catch {
          if (!stopped) schedule();
        }
      };

      void open();
      return () => {
        stopped = true;
        if (timer) clearTimeout(timer);
        controller?.abort();
        onStatus('closed');
      };
    });
  }
}

/** One SSE block of type "notification"; heartbeats, "ready" and malformed blocks yield null. */
export function parseNotification(block: string): AppNotification | null {
  let type: string | null = null;
  const data: string[] = [];
  for (const line of block.split('\n')) {
    if (line.startsWith(':')) continue;
    if (line.startsWith('event:')) type = line.slice(6).trim();
    else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''));
  }
  if (type !== 'notification' || data.length === 0) return null;
  try {
    const n = JSON.parse(data.join('\n')) as AppNotification;
    return n && typeof n.id === 'string' ? n : null;
  } catch { return null; }
}
