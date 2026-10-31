import { Page, expect, test } from '@playwright/test';
import { PASSWORD, apiToken, button, isolatePriceTables, signIn, uniqueEmail } from './support';

const adminEmail = process.env['E2E_ADMIN_EMAIL'];
const adminPassword = process.env['E2E_ADMIN_PASSWORD'];
const VEHICLE = 'TRUCK_1T';

/**
 * Full pricing flow with no map provider configured (the default): the quote waits for an admin, who enters a
 * verified distance; the customer then sees and accepts the spec example price of 360,000 VND.
 */
test.describe.serial('pricing: price table, manual review, accept', () => {
  test.skip(!adminEmail || !adminPassword, 'Set E2E_ADMIN_EMAIL and E2E_ADMIN_PASSWORD to run the pricing flow');
  let admin: Page;
  let customer: Page;
  let shipmentUrl = '';
  let restore: () => Promise<void> = async () => undefined;

  test.beforeAll(async ({ browser, request }) => {
    // Exclusive use of this vehicle type's price tables; the originals are re-activated afterwards.
    restore = await isolatePriceTables(await apiToken(request, adminEmail!, adminPassword!), VEHICLE);

    // A customer with a priced-ready draft: 10 × 60 kg (600 kg), 3 stops, loading support, 1-tonne truck.
    const email = uniqueEmail('quote');
    await request.post('/api/v1/auth/register/customer', { data: { fullName: 'Khách Báo Giá', email, password: PASSWORD, confirmPassword: PASSWORD } });
    const customerToken = await apiToken(request, email, PASSWORD);
    const pickup = new Date(Date.now() + 2 * 86_400_000);
    pickup.setUTCHours(3, 0, 0, 0);
    const stop = (type: string, seq: number, name: string, phone: string) => ({ stopType: type, sequenceNo: seq, contactName: name, contactPhone: phone, province: 'Hà Nội', addressDetail: `Số ${seq}` });
    const draft = await request.post('/api/v1/shipments/draft', { headers: { Authorization: `Bearer ${customerToken}` }, data: {
      pickupTime: pickup.toISOString(), vehicleTypeCode: VEHICLE,
      stops: [stop('PICKUP', 1, 'Kho', '0912345678'), stop('DELIVERY', 2, 'Khách 1', '0987654321'), stop('DELIVERY', 3, 'Khách 2', '0987654322')],
      items: [{ itemName: 'Máy giặt', category: 'ELECTRONICS', quantity: 10, weightPerUnitKg: 60, lengthCm: 60, widthCm: 60, heightCm: 90 }],
      services: [{ serviceCode: 'LOADING_SUPPORT', serviceValue: 'TWO_PEOPLE' }],
    } });
    expect(draft.status(), await draft.text()).toBe(201);
    shipmentUrl = `/customer/shipments/${(await draft.json()).data.id}`;

    admin = await browser.newPage();
    await signIn(admin, adminEmail!, adminPassword!);
    await expect(admin).toHaveURL(/\/admin\/drivers/);
    customer = await browser.newPage();
    await signIn(customer, email, PASSWORD);
    await expect(customer).toHaveURL(/\/customer$/);
  });

  test.afterAll(async () => { await admin?.close(); await customer?.close(); await restore(); });

  test('admin creates a price table with a service fee (form rejects negative prices)', async () => {
    await admin.getByRole('link', { name: 'Bảng giá', exact: true }).click();
    await admin.getByRole('link', { name: '+ Tạo bảng giá' }).click();
    await admin.fill('#name', 'E2E xe 1 tấn');
    await admin.selectOption('#vehicleTypeCode', VEHICLE);
    const from = new Date(Date.now() - 3_600_000);
    const p = (n: number) => String(n).padStart(2, '0');
    await admin.fill('#effectiveFrom', `${from.getFullYear()}-${p(from.getMonth() + 1)}-${p(from.getDate())}T${p(from.getHours())}:${p(from.getMinutes())}`);
    await admin.fill('#basePrice', '-1');
    await admin.fill('#pricePerKm', '8000');
    await button(admin, 'Tạo bảng giá').click();
    await expect(admin.getByText('Không được âm').first()).toBeVisible();
    await admin.fill('#basePrice', '100000');
    await admin.fill('#includedWeightKg', '500');
    await admin.fill('#pricePerExtraKg', '500');
    await admin.fill('#includedVolumeM3', '5');
    await admin.fill('#additionalStopPrice', '20000');
    await button(admin, 'Tạo bảng giá').click();
    await expect(admin).toHaveURL(/\/admin\/pricing\/[0-9a-f-]+$/);
    await expect(admin.getByText('Đang áp dụng')).toBeVisible();

    await admin.selectOption('#fee-service', 'LOADING_SUPPORT');
    await admin.fill('#fee-amount', '30000');
    await button(admin, 'Thêm phí').click();
    await expect(admin.getByRole('cell', { name: 'Hỗ trợ bốc hàng' })).toBeVisible();
    await admin.reload();
    await expect(admin.getByText('Thêm phí dịch vụ').first()).toBeVisible(); // history entry
  });

  test('without a map provider the quote waits for an admin and shows no price', async () => {
    await customer.goto(shipmentUrl);
    await button(customer, 'Tính giá').click();
    const panel = customer.locator('section.quote');
    await expect(panel).toContainText('Chờ quản trị viên xử lý');
    await expect(panel).toContainText('Chưa cấu hình dịch vụ bản đồ');
    expect(await panel.innerText()).not.toMatch(/\d\s?VNĐ/);
    await expect(button(customer, 'Xác nhận báo giá')).toHaveCount(0);
  });

  test('admin enters the verified distance with a reason', async () => {
    await admin.getByRole('link', { name: 'Báo giá chờ xử lý' }).click();
    const row = admin.getByRole('row').filter({ hasText: 'Chưa cấu hình dịch vụ bản đồ' }).last();
    await row.getByRole('button', { name: 'Xử lý' }).click();
    await admin.fill('#distanceKm', '20');
    await admin.fill('#reason', 'Đã xác minh tuyến trên bản đồ');
    await button(admin, 'Tạo báo giá đã điều chỉnh').click();
    await expect(admin.getByText(/Đã tạo .*-Q2: 360\.000 VNĐ/)).toBeVisible();
  });

  test('customer sees the 360,000 VNĐ breakdown and accepts it', async () => {
    await customer.reload();
    const panel = customer.locator('section.quote');
    await expect(panel).toContainText('Đã báo giá');
    for (const text of ['Giá cơ bản', '100.000 VNĐ', 'Phí vận chuyển', '160.000 VNĐ', 'Phụ phí khối lượng', '50.000 VNĐ', 'Phụ phí dịch vụ', '30.000 VNĐ',
      'Phụ phí điểm dừng', '20.000 VNĐ', '360.000 VNĐ', 'đã được quản trị viên xác minh', 'Báo giá có hiệu lực đến']) await expect(panel).toContainText(text);
    await button(customer, 'Xác nhận báo giá').click();
    await expect(panel).toContainText('Sau khi xác nhận, báo giá không thể thay đổi');
    await button(customer, 'Xác nhận').click();
    await expect(panel).toContainText('Bạn đã xác nhận báo giá');
    await expect(customer.getByText('360.000 VNĐ (đã xác nhận)')).toBeVisible();
    await expect(button(customer, 'Tính lại giá')).toHaveCount(0);
  });
});
