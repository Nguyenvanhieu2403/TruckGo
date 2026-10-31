import { Component, OnDestroy, effect, inject, input, signal, untracked } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Photo } from '../core/api/trip.api';

const LABELS: Record<string, string> = { PICKUP_PHOTO: 'Nhận hàng', DELIVERY_PHOTO: 'Giao hàng', INCIDENT_PHOTO: 'Sự cố', GOODS_PHOTO: 'Hàng hóa' };

/** Thumbnails of private photos: fetched with the user's token and shown from object URLs. */
@Component({
  selector: 'app-photo-strip',
  standalone: true,
  template: `
  <div class="strip">
    @for (p of photos(); track p.id) {
      <figure>@if (urls()[p.id]; as url) { <img [src]="url" [alt]="label(p)" /> } @else { <div class="ph" aria-busy="true"></div> }
        <figcaption>{{ label(p) }}</figcaption></figure>
    } @empty { <p class="muted">Chưa có ảnh.</p> }
  </div>`,
  styles: [`.strip{display:flex;gap:10px;flex-wrap:wrap}figure{margin:0;width:96px}img,.ph{width:96px;height:96px;object-fit:cover;border-radius:6px;background:#eef1ea;display:block}
  figcaption{font-size:11px;color:var(--tg-muted);margin-top:2px}`],
})
export class PhotoStripComponent implements OnDestroy {
  readonly photos = input<Photo[]>([]);
  private readonly http = inject(HttpClient);
  protected readonly urls = signal<Record<string, string>>({});
  private readonly requested = new Set<string>();

  constructor() {
    effect(() => {
      const photos = this.photos();
      untracked(() => this.load(photos));
    });
  }

  private load(photos: Photo[]): void {
    {
      for (const p of photos) {
        if (this.requested.has(p.id)) continue;
        this.requested.add(p.id);
        this.http.get(p.contentUrl, { responseType: 'blob' }).subscribe({
          next: blob => this.urls.update(m => ({ ...m, [p.id]: URL.createObjectURL(blob) })),
          error: () => undefined,
        });
      }
    }
  }

  label(p: Photo): string { return LABELS[p.fileType] ?? 'Ảnh'; }

  ngOnDestroy(): void { Object.values(this.urls()).forEach(u => URL.revokeObjectURL(u)); }
}
