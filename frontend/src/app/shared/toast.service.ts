import { Injectable, signal } from '@angular/core';

export interface Toast { id: number; kind: 'success' | 'error' | 'info'; text: string; }

/** App-wide transient notifications; rendered by ToastContainerComponent in the app shell. */
@Injectable({ providedIn: 'root' })
export class ToastService {
  private seq = 0;
  readonly toasts = signal<Toast[]>([]);

  success(text: string): void { this.push('success', text); }
  error(text: string): void { this.push('error', text, 7000); }
  info(text: string): void { this.push('info', text); }

  dismiss(id: number): void { this.toasts.update(list => list.filter(t => t.id !== id)); }

  private push(kind: Toast['kind'], text: string, ms = 4000): void {
    const id = ++this.seq;
    this.toasts.update(list => [...list.slice(-3), { id, kind, text }]);
    setTimeout(() => this.dismiss(id), ms);
  }
}
