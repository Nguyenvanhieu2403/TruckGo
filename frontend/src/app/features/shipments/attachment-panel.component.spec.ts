import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { AttachmentPanelComponent } from './attachment-panel.component';
import { fail, ok } from './shipment-test-data';

describe('AttachmentPanelComponent', () => {
  let http: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [AttachmentPanelComponent], providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
    globalThis.URL.createObjectURL ??= () => 'blob:test';
    globalThis.URL.revokeObjectURL ??= () => undefined;
  });

  function render() {
    const fixture = TestBed.createComponent(AttachmentPanelComponent);
    fixture.componentRef.setInput('shipmentId', 'sh-1');
    fixture.componentRef.setInput('editable', true);
    fixture.detectChanges();
    return { fixture, panel: fixture.componentInstance, el: fixture.nativeElement as HTMLElement };
  }

  function pick(panel: AttachmentPanelComponent, files: File[]) {
    const input = document.createElement('input');
    Object.defineProperty(input, 'files', { value: files });
    panel.pick({ target: input } as unknown as Event);
  }

  it('uploads images as multipart and shows them', () => {
    const { panel, fixture, el } = render();
    pick(panel, [new File([new Uint8Array([0x89, 0x50])], 'hang.png', { type: 'image/png' })]);
    const upload = http.expectOne('/api/v1/shipments/sh-1/attachments');
    expect(upload.request.body instanceof FormData).toBe(true);
    upload.flush(ok({ id: 'a1', fileName: 'hang.png', contentType: 'image/png', fileSize: 2, fileType: 'GOODS_PHOTO', createdAt: '', contentUrl: '/api/v1/shipments/sh-1/attachments/a1/content' }));
    http.expectOne('/api/v1/shipments/sh-1/attachments/a1/content').flush(new Blob(['x']));
    fixture.detectChanges();
    expect(el.textContent).toContain('hang.png');
  });

  it('rejects wrong types and large files before uploading and shows server errors', () => {
    const { panel, fixture, el } = render();
    pick(panel, [new File(['%PDF'], 'hoa-don.pdf', { type: 'application/pdf' }), new File([new Uint8Array(5 * 1024 * 1024 + 1)], 'lon.png', { type: 'image/png' })]);
    http.expectNone('/api/v1/shipments/sh-1/attachments');
    fixture.detectChanges();
    expect(el.textContent).toContain('hoa-don.pdf, lon.png');
    pick(panel, [new File(['x'], 'gia.png', { type: 'image/png' })]);
    http.expectOne('/api/v1/shipments/sh-1/attachments').flush(fail('Định dạng tệp không được hỗ trợ'), { status: 415, statusText: 'Unsupported' });
    fixture.detectChanges();
    expect(el.querySelector('[role=alert]')?.textContent).toContain('gia.png: Định dạng tệp không được hỗ trợ');
  });
});
