import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it } from 'vitest';
import { AdminIncidentDetailComponent } from './admin-incident-detail.component';
import { ok } from '../trips/trip-test-data';
import { Incident } from '../../core/api/trip.api';

const incident = (o: Partial<Incident> = {}): Incident => ({
  id: 'i-1', shipmentId: 'sh-1', shipmentCode: 'TG1', type: 'GOODS_DAMAGED', description: 'Vỡ 2 thùng', status: 'IN_PROGRESS', latitude: null, longitude: null,
  occurredAt: '2026-11-01T03:00:00Z', reportedBy: 'u', reportedByName: 'Tài Xế An', resolutionNote: null, resolvedAt: null, createdAt: '2026-11-01T03:00:00Z', photos: [], ...o,
});

describe('AdminIncidentDetailComponent', () => {
  let http: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [AdminIncidentDetailComponent],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting(),
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ id: 'i-1' }) } } }],
    });
    http = TestBed.inject(HttpTestingController);
  });

  it('requires a note to close an incident and shows the new state', async () => {
    const fixture = TestBed.createComponent(AdminIncidentDetailComponent);
    fixture.detectChanges();
    http.expectOne('/api/v1/admin/incidents/i-1').flush(ok(incident()));
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const save = () => [...el.querySelectorAll('button')].find(b => b.textContent?.trim() === 'Lưu') as HTMLButtonElement;
    expect(el.querySelector<HTMLSelectElement>('#ai-next')!.value).toBe('RESOLVED');
    expect(save().disabled).toBe(true);
    const note = el.querySelector<HTMLTextAreaElement>('#ai-note')!;
    note.value = 'Đã bồi thường 2 thùng';
    note.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    save().click();
    const req = http.expectOne('/api/v1/admin/incidents/i-1');
    expect(req.request.body).toEqual({ status: 'RESOLVED', note: 'Đã bồi thường 2 thùng' });
    req.flush(ok(incident({ status: 'RESOLVED', resolutionNote: 'Đã bồi thường 2 thùng', resolvedAt: '2026-11-01T05:00:00Z' })));
    fixture.detectChanges();
    expect(el.querySelector('[data-testid=incident-status]')?.textContent).toContain('Đã xử lý');
    expect(el.textContent).toContain('Sự cố đã đóng');
  });
});
