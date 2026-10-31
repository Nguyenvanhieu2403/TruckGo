import { APIRequestContext, Page, expect, test } from '@playwright/test';
import { PASSWORD, apiToken, button, horizontalOverflow, isolatePriceTables, signIn, uniqueEmail } from './support';

const adminEmail = process.env['E2E_ADMIN_EMAIL'];
const adminPassword = process.env['E2E_ADMIN_PASSWORD'];
const VEHICLE = 'TRUCK_2_5T';
const PICKUP_DETAIL = `Số 9 ngõ E2E ${Date.now().toString(36)}`;

const auth = (token: string) => ({ Authorization: `Bearer ${token}` });
const plate = () => `29C${String(Math.floor(Math.random() * 100_000)).padStart(5, '0')}`;
const license = () => String(Math.floor(Math.random() * 1e12)).padStart(12, '0');

/** Registers a driver and gets the profile and a 3-tonne truck approved; returns the login email. */
async function approvedDriver(request: APIRequestContext, adminToken: string, name: string): Promise<string> {
  const email = uniqueEmail('driver');
  const reg = await request.post('/api/v1/auth/register/driver', { data: { fullName: name, email, password: PASSWORD, confirmPassword: PASSWORD,
    licenseNumber: license(), licenseClass: 'C' } });
  expect(reg.status(), await reg.text()).toBe(201);
  const token = await apiToken(request, email, PASSWORD);
  const profile = (await (await request.post('/api/v1/drivers/me/submit-verification', { headers: auth(token) })).json()).data.id;
  expect((await request.post(`/api/v1/admin/drivers/${profile}/approve`, { headers: auth(adminToken) })).status()).toBe(200);
  const vehicle = await request.post('/api/v1/vehicles', { headers: auth(token), data: { plateNumber: plate(), vehicleType: 'MEDIUM_TRUCK', maxPayloadKg: 3000,
    lengthCm: 450, widthCm: 200, heightCm: 200, registrationExpiryDate: new Date(Date.now() + 365 * 86_400_000).toISOString().slice(0, 10) } });
  expect(vehicle.status(), await vehicle.text()).toBe(201);
  const vehicleId = (await vehicle.json()).data.id;
  expect((await request.post(`/api/v1/vehicles/${vehicleId}/submit-verification`, { headers: auth(token) })).status()).toBe(200);
  expect((await request.post(`/api/v1/admin/vehicles/${vehicleId}/approve`, { headers: auth(adminToken) })).status()).toBe(200);
  return email;
}

test.describe.serial('marketplace: drivers apply, the customer chooses one, everyone is notified', () => {
  test.skip(!adminEmail || !adminPassword, 'Set E2E_ADMIN_EMAIL and E2E_ADMIN_PASSWORD to run the marketplace tests');
  let customer: Page;
  let driverA: Page;
  let driverB: Page;
  let admin: Page;
  let shipmentId = '';
  let shipmentCode = '';
  let restore: () => Promise<void> = async () => undefined;

  test.beforeAll(async ({ browser, request }) => {
    const adminToken = await apiToken(request, adminEmail!, adminPassword!);
    restore = await isolatePriceTables(adminToken, VEHICLE);
    expect((await request.post('/api/v1/admin/pricing-configs', { headers: auth(adminToken), data: {
      name: `E2E marketplace ${Date.now().toString(36)}`, vehicleTypeCode: VEHICLE, basePrice: 300000, pricePerKm: 10000, includedDistanceKm: 0,
      includedWeightKg: 2500, pricePerExtraKg: 0, includedVolumeM3: 20, pricePerExtraM3: 0, additionalStopPrice: 0, minimumPrice: 0,
      effectiveFrom: new Date(Date.now() - 3_600_000).toISOString(), timeRules: [], extraFees: [],
    } })).status()).toBe(201);

    // A published order with an accepted price: 50 km entered by the admin (no map provider on the E2E stack).
    const customerEmail = uniqueEmail('mk-customer');
    await request.post('/api/v1/auth/register/customer', { data: { fullName: 'Khách Marketplace', email: customerEmail, password: PASSWORD, confirmPassword: PASSWORD } });
    const customerToken = await apiToken(request, customerEmail, PASSWORD);
    const stop = (type: string, seq: number, province: string, district: string, detail: string) =>
      ({ stopType: type, sequenceNo: seq, contactName: `Liên hệ ${seq}`, contactPhone: `091234567${seq}`, province, district, addressDetail: detail });
    const draft = await request.post('/api/v1/shipments/draft', { headers: auth(customerToken), data: {
      pickupTime: new Date(Date.now() + 2 * 86_400_000).toISOString(), vehicleTypeCode: VEHICLE,
      stops: [stop('PICKUP', 1, 'Hà Nội', 'Cầu Giấy', PICKUP_DETAIL), stop('DELIVERY', 2, 'Hải Phòng', 'Lê Chân', 'Số 2 Lạch Tray')],
      items: [{ itemName: 'Tủ lạnh', category: 'ELECTRONICS', quantity: 10, weightPerUnitKg: 80, lengthCm: 70, widthCm: 70, heightCm: 180 }],
    } });
    expect(draft.status(), await draft.text()).toBe(201);
    ({ id: shipmentId, shipmentCode } = (await draft.json()).data);
    const quote = (await (await request.post(`/api/v1/shipments/${shipmentId}/pricing/preview`, { headers: auth(customerToken) })).json()).data;
    if (quote.status === 'AWAITING_MANUAL_REVIEW') {
      const adjusted = await request.post(`/api/v1/admin/quotes/${quote.id}/manual-adjustment`, { headers: auth(adminToken),
        data: { distanceKm: 50, reason: 'Quãng đường kiểm tra cho E2E' } });
      expect(adjusted.status(), await adjusted.text()).toBe(200);
    }
    expect((await request.post(`/api/v1/shipments/${shipmentId}/pricing/accept`, { headers: auth(customerToken) })).status()).toBe(200);
    expect((await request.post(`/api/v1/shipments/${shipmentId}/publish`, { headers: auth(customerToken) })).status()).toBe(200);

    const emailA = await approvedDriver(request, adminToken, 'Tài Xế An');
    const emailB = await approvedDriver(request, adminToken, 'Tài Xế Bình');
    customer = await browser.newPage();
    await signIn(customer, customerEmail, PASSWORD);
    driverA = await browser.newPage();
    await signIn(driverA, emailA, PASSWORD);
    driverB = await browser.newPage();
    await signIn(driverB, emailB, PASSWORD);
    admin = await browser.newPage();
    await signIn(admin, adminEmail!, adminPassword!);
  });

  test.afterAll(async () => {
    for (const p of [customer, driverA, driverB, admin]) await p?.close();
    await restore();
  });

  test('a driver finds the order by area without seeing the exact address', async () => {
    await driverA.goto('/driver/marketplace');
    await driverA.fill('#mk-pp', 'ha noi');
    await driverA.fill('#mk-dp', 'hai phong');
    await button(driverA, 'Lọc').click();
    const card = driverA.getByTestId('marketplace-item').filter({ hasText: shipmentCode });
    await expect(card).toContainText('Cầu Giấy, Hà Nội');
    await expect(card).toContainText('800.000 VNĐ');   // 300,000 + 50 km × 10,000
    await card.click();
    await expect(driverA.getByRole('heading', { name: shipmentCode })).toBeVisible();
    await expect(driverA.getByText('Địa chỉ chi tiết và số điện thoại liên hệ sẽ hiển thị khi khách hàng chọn bạn')).toBeVisible();
    await expect(driverA.getByText(PICKUP_DETAIL)).toHaveCount(0);
  });

  test('drivers apply after confirming', async () => {
    await button(driverA, 'Gửi yêu cầu nhận đơn').click();
    await expect(driverA.getByRole('dialog')).toContainText('Gửi yêu cầu nhận đơn?');
    await driverA.getByRole('dialog').getByRole('button', { name: 'Gửi yêu cầu', exact: true }).click();
    await expect(driverA).toHaveURL(/\/driver\/applications\/[0-9a-f-]+$/);
    await expect(driverA.getByTestId('application-status')).toHaveText('Chờ khách chọn');

    await driverB.goto(`/driver/marketplace/${shipmentId}`);
    await driverB.fill('#driver-message', 'Có thể đến sớm 30 phút');
    await button(driverB, 'Gửi yêu cầu nhận đơn').click();
    await driverB.getByRole('dialog').getByRole('button', { name: 'Gửi yêu cầu', exact: true }).click();
    await expect(driverB.getByTestId('application-status')).toHaveText('Chờ khách chọn');
  });

  test('the customer is notified and chooses one driver', async () => {
    await customer.goto(`/customer/shipments/${shipmentId}`);
    await expect(customer.getByTestId('unread-count')).toHaveText('2');
    await expect(customer.getByTestId('applicant')).toHaveCount(2);
    const an = customer.getByTestId('applicant').filter({ hasText: 'Tài Xế An' });
    await expect(customer.getByTestId('applicant').filter({ hasText: 'Tài Xế Bình' })).toContainText('Có thể đến sớm 30 phút');
    await an.getByRole('button', { name: 'Chọn tài xế này' }).click();
    await expect(customer.getByRole('dialog')).toContainText('các yêu cầu khác sẽ tự động bị từ chối');
    await button(customer, 'Xác nhận chọn').click();
    await expect(customer.getByText('Đã chọn Tài Xế An cho đơn.')).toBeVisible();
    await expect(customer.getByTestId('assigned-driver')).toContainText('Tài Xế An');
    await expect(customer.locator('app-shipment-status').first()).toContainText('Đã có tài xế');
    await expect(customer.getByRole('button', { name: 'Chọn tài xế này' })).toHaveCount(0);
    await expect(customer.getByRole('button', { name: 'Hủy đơn' })).toHaveCount(0);
  });

  test('the chosen driver gets the address, the other one is told', async () => {
    await driverA.reload();
    await expect(driverA.getByTestId('application-status')).toHaveText('Được chọn');
    await expect(driverA.getByText(PICKUP_DETAIL, { exact: false })).toBeVisible();
    await driverA.getByRole('button', { name: /Thông báo/ }).click();
    await expect(driverA.getByRole('dialog', { name: 'Thông báo' })).toContainText(`Bạn đã được chọn cho đơn ${shipmentCode}`);

    await driverB.goto('/driver/applications');
    await expect(driverB.getByTestId('application-row').filter({ hasText: shipmentCode })).toContainText('Không được chọn');
    await driverB.goto('/driver/marketplace');
    await expect(driverB.getByTestId('marketplace-item').filter({ hasText: shipmentCode })).toHaveCount(0);
  });

  test('the admin sees the assignment', async () => {
    await admin.goto('/admin/marketplace');
    await admin.selectOption('#am-status', 'ASSIGNED');
    await expect(admin.getByRole('row').filter({ hasText: shipmentCode })).toContainText('Tài Xế An');
    await admin.getByRole('tab', { name: 'Lịch sử phân công' }).click();
    await expect(admin.getByRole('row').filter({ hasText: shipmentCode })).toContainText('Giao cho tài xế');
  });

  test('the marketplace fits a 390 px phone', async () => {
    await driverB.setViewportSize({ width: 390, height: 844 });
    await driverB.goto('/driver/marketplace');
    await expect(driverB.getByRole('heading', { name: 'Tìm đơn vận chuyển' })).toBeVisible();
    expect(await horizontalOverflow(driverB)).toBe(0);
  });
});
