import { Component, OnDestroy, effect, inject, input, signal } from '@angular/core';
import { DomSanitizer, SafeUrl } from '@angular/platform-browser';
import { Attachment, ShipmentApi } from '../../core/api/shipment.api';
import { toApiError } from '../../core/api.service';

const ALLOWED = ['image/jpeg', 'image/png', 'image/webp'];
const MAX_BYTES = 5 * 1024 * 1024;

/**
 * Goods photos. The browser checks type/size only for quick feedback; the server re-checks the real content.
 * Images are private, so they are fetched with the access token and displayed from object URLs.
 */
@Component({
  selector: 'app-attachment-panel',
  standalone: true,
  template: `
  <section class="card">
    <h2>Ảnh hàng hóa</h2>
    @if (error()) { <div class="alert alert-error" role="alert">{{ error() }}</div> }
    @if (editable()) {
      <label class="upload" [class.disabled]="uploading()">
        <input type="file" accept="image/jpeg,image/png,image/webp" multiple (change)="pick($event)" [disabled]="uploading()" />
        {{ uploading() ? 'Đang tải lên…' : '+ Chọn ảnh (JPEG, PNG, WebP; tối đa 5 MB/ảnh)' }}
      </label>
    }
    @if (items().length === 0) { <p class="muted">Chưa có ảnh nào.</p> }
    <div class="thumbs">
      @for (a of items(); track a.id) {
        <figure>
          @if (previews()[a.id]; as url) { <img [src]="url" [alt]="a.fileName" /> } @else { <div class="ph">Đang tải…</div> }
          <figcaption><span title="{{ a.fileName }}">{{ a.fileName }}</span>
            @if (editable()) { <button class="btn btn-ghost" type="button" (click)="remove(a)" [disabled]="busyId() === a.id">Xóa</button> }
          </figcaption>
        </figure>
      }
    </div>
  </section>`,
  styles: [`.upload{display:block;border:1px dashed #b9c6ad;border-radius:6px;padding:18px;text-align:center;cursor:pointer;font-size:13px;color:var(--tg-dark);margin-bottom:14px}.upload input{display:none}.upload.disabled{opacity:.6;cursor:wait}
  .thumbs{display:grid;grid-template-columns:repeat(auto-fill,minmax(140px,1fr));gap:12px}figure{margin:0;border:1px solid var(--tg-line);border-radius:6px;overflow:hidden;background:#fafbf8}
  img,.ph{width:100%;aspect-ratio:4/3;object-fit:cover;display:grid;place-items:center;color:var(--tg-muted);font-size:12px}figcaption{display:flex;gap:6px;align-items:center;justify-content:space-between;padding:6px 8px;font-size:12px}figcaption span{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}figcaption .btn{padding:5px 8px}`],
})
export class AttachmentPanelComponent implements OnDestroy {
  readonly shipmentId = input.required<string>();
  readonly initial = input<Attachment[]>([]);
  readonly editable = input(false);
  private readonly api = inject(ShipmentApi);
  private readonly sanitizer = inject(DomSanitizer);
  protected readonly items = signal<Attachment[]>([]);
  protected readonly previews = signal<Record<string, SafeUrl>>({});
  protected readonly uploading = signal(false);
  protected readonly busyId = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);
  private readonly objectUrls: string[] = [];

  constructor() {
    effect(() => { this.items.set(this.initial()); for (const a of this.initial()) this.preview(a); });
  }

  pick(event: Event): void {
    const input = event.target as HTMLInputElement;
    const files = Array.from(input.files ?? []);
    input.value = '';
    const rejected = files.filter(f => !ALLOWED.includes(f.type) || f.size > MAX_BYTES);
    if (rejected.length) this.error.set(`Bỏ qua ${rejected.map(f => f.name).join(', ')}: chỉ nhận JPEG/PNG/WebP tối đa 5 MB.`);
    else this.error.set(null);
    this.uploadNext(files.filter(f => !rejected.includes(f)));
  }

  remove(a: Attachment): void {
    this.busyId.set(a.id);
    this.api.deleteAttachment(this.shipmentId(), a.id).subscribe({
      next: () => { this.items.update(list => list.filter(x => x.id !== a.id)); this.busyId.set(null); },
      error: (e: unknown) => { this.error.set(toApiError(e).message); this.busyId.set(null); },
    });
  }

  ngOnDestroy(): void { this.objectUrls.forEach(url => URL.revokeObjectURL(url)); }

  private uploadNext(queue: File[]): void {
    const [file, ...rest] = queue;
    if (!file) { this.uploading.set(false); return; }
    this.uploading.set(true);
    this.api.upload(this.shipmentId(), file).subscribe({
      next: a => { this.items.update(list => [...list, a]); this.preview(a); this.uploadNext(rest); },
      error: (e: unknown) => { this.error.set(`${file.name}: ${toApiError(e).message}`); this.uploadNext(rest); },
    });
  }

  private preview(a: Attachment): void {
    if (this.previews()[a.id]) return;
    this.api.attachmentBlob(a.contentUrl).subscribe({
      next: blob => {
        const url = URL.createObjectURL(blob);
        this.objectUrls.push(url);
        this.previews.update(p => ({ ...p, [a.id]: this.sanitizer.bypassSecurityTrustUrl(url) }));
      },
      error: () => undefined,
    });
  }
}
