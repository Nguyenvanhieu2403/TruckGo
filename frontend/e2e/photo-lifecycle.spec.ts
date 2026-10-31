import { expect, test } from '@playwright/test';
import { PASSWORD, PNG, apiToken, button, signIn, uniqueEmail } from './support';

const auth = (token: string) => ({ Authorization: `Bearer ${token}` });

test.describe('order photos and addresses without a map provider', () => {
  test('cancelling an order removes its photos for good', async ({ page, request }) => {
    const email = uniqueEmail('photos');
    await request.post('/api/v1/auth/register/customer', { data: { fullName: 'Khách Ảnh', email, password: PASSWORD, confirmPassword: PASSWORD } });
    const token = await apiToken(request, email, PASSWORD);
    const stop = (type: string, seq: number) => ({ stopType: type, sequenceNo: seq, contactName: `Liên hệ ${seq}`, contactPhone: `091234567${seq}`, province: 'Hà Nội', addressDetail: `Số ${seq}` });
    const draft = await request.post('/api/v1/shipments/draft', { headers: auth(token), data: {
      pickupTime: new Date(Date.now() + 2 * 86_400_000).toISOString(), stops: [stop('PICKUP', 1), stop('DELIVERY', 2)],
      items: [{ itemName: 'Thùng sách', category: 'ELECTRONICS', quantity: 2, weightPerUnitKg: 15 }],
    } });
    expect(draft.status(), await draft.text()).toBe(201);
    const id = (await draft.json()).data.id;
    const uploaded = await request.post(`/api/v1/shipments/${id}/attachments`, { headers: auth(token), multipart: { file: { name: 'thung.png', mimeType: 'image/png', buffer: PNG } } });
    expect(uploaded.status(), await uploaded.text()).toBe(201);
    const contentUrl = (await uploaded.json()).data.contentUrl;
    expect((await request.get(contentUrl, { headers: auth(token) })).status()).toBe(200);

    await signIn(page, email, PASSWORD);
    await page.waitForURL(/\/customer$/);
    await page.goto(`/customer/shipments/${id}`);
    await expect(page.locator('figure img')).toHaveCount(1);
    await button(page, 'Hủy đơn').click();
    await page.fill('#cancel-reason', 'Không cần vận chuyển nữa');
    await button(page, 'Xác nhận hủy').click();
    await expect(page.getByText('Đã hủy đơn.')).toBeVisible();
    await expect(page.locator('figure img')).toHaveCount(0);

    // Metadata and object are both gone: the old link no longer serves the file.
    const list = await request.get(`/api/v1/shipments/${id}/attachments`, { headers: auth(token) });
    expect((await list.json()).data).toEqual([]);
    expect((await request.get(contentUrl, { headers: auth(token) })).status()).toBe(404);
  });

  test('the stops step explains that addresses are entered manually', async ({ page, request }) => {
    const email = uniqueEmail('geo');
    await request.post('/api/v1/auth/register/customer', { data: { fullName: 'Khách Địa Chỉ', email, password: PASSWORD, confirmPassword: PASSWORD } });
    await signIn(page, email, PASSWORD);
    await page.waitForURL(/\/customer$/);
    await page.goto('/customer/shipments/create');
    await expect(page.getByTestId('geo-disabled').first()).toContainText('nhập địa chỉ thủ công');
    const status = await page.evaluate(async () => (await fetch('/api/v1/geo/status')).status);
    expect(status).toBe(401);   // the lookup API is never open to anonymous callers
  });
});
