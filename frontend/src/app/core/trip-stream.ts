import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { AuthService } from './auth/auth.service';

export interface TripEvent { type: 'status' | 'location'; data: unknown; }
export type StreamStatus = 'connecting' | 'open' | 'closed';

/** Injectable so tests can replace the network call. */
export const FETCH = { fetch: (input: RequestInfo, init?: RequestInit) => fetch(input, init) };

/**
 * Server-sent events over fetch (EventSource cannot send the Authorization header). Reconnects with backoff when the
 * stream drops or the server closes it (it does so periodically so a fresh token is used). The caller keeps polling
 * as a fallback while the status is not 'open'.
 */
@Injectable({ providedIn: 'root' })
export class TripStreamClient {
  private readonly auth = inject(AuthService);

  connect(url: string, onStatus: (s: StreamStatus) => void): Observable<TripEvent> {
    return new Observable<TripEvent>(subscriber => {
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
          const response = await FETCH.fetch(url, {
            headers: { Accept: 'text/event-stream', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
            signal: controller.signal, cache: 'no-store',
          });
          if (!response.ok || !response.body) { schedule(); return; }   // 401: the polling request refreshes the token first
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
              const event = parseEvent(buffer.slice(0, split));
              buffer = buffer.slice(split + 2);
              if (event) subscriber.next(event);
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

/** Parses one SSE block ("event:" + "data:" lines); comments (heartbeats) yield null. */
export function parseEvent(block: string): TripEvent | null {
  let type: string | null = null;
  const data: string[] = [];
  for (const line of block.split('\n')) {
    if (line.startsWith(':')) continue;
    if (line.startsWith('event:')) type = line.slice(6).trim();
    else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''));
  }
  if ((type !== 'status' && type !== 'location') || data.length === 0) return null;
  try { return { type, data: JSON.parse(data.join('\n')) }; } catch { return null; }
}
