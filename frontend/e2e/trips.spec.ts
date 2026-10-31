import { APIRequestContext, Browser, BrowserContext, Page, expect, test } from '@playwright/test';
import { PASSWORD, apiToken, button, isolatePriceTables, signIn, uniqueEmail } from './support';

const adminEmail = process.env['E2E_ADMIN_EMAIL'];
const adminPassword = process.env['E2E_ADMIN_PASSWORD'];
const VEHICLE = 'TRUCK_3_5T';
const auth = (token: string) => ({ Authorization: `Bearer ${token}` });
const plate = () => `30C${String(Math.floor(Math.random() * 100_000)).padStart(5, '0')}`;
const license = () => String(Math.floor(Math.random() * 1e12)).padStart(12, '0');
const HANOI = { latitude: 21.0285, longitude: 105.8542 };

async function approvedDriver(request: APIRequestContext, adminToken: string, name: string): Promise<string> {
  const email = uniqueEmail('trip-driver');
  expect((await request.post('/api/v1/auth/register/driver', { data: { fullName: name, email, password: PASSWORD, confirmPassword: PASSWORD,
    licenseNumber: license(), licenseClass: 'C' } })).status()).toBe(201);
  const token = await apiToken(request, email, PASSWORD);
  const profile = (await (await request.post('/api/v1/drivers/me/submit-verification', { headers: auth(token) })).json()).data.id;
  expect((await request.post(`/api/v1/admin/drivers/${profile}/approve`, { headers: auth(adminToken) })).status()).toBe(200);
  const vehicle = await request.post('/api/v1/vehicles', { headers: auth(token), data: { plateNumber: plate(), vehicleType: 'MEDIUM_TRUCK', maxPayloadKg: 4000,
    lengthCm: 500, widthCm: 210, heightCm: 210, registrationExpiryDate: new Date(Date.now() + 365 * 86_400_000).toISOString().slice(0, 10) } });
  expect(vehicle.status(), await vehicle.text()).toBe(201);
  const vehicleId = (await vehicle.json()).data.id;
  expect((await request.post(`/api/v1/vehicles/${vehicleId}/submit-verification`, { headers: auth(token) })).status()).toBe(200);
  expect((await request.post(`/api/v1/admin/vehicles/${vehicleId}/approve`, { headers: auth(adminToken) })).status()).toBe(200);
  return email;
}

async function page(browser: Browser, email: string, password: string, geo = false): Promise<{ context: BrowserContext; page: Page }> {
  const context = await browser.newContext(geo ? { permissions: ['geolocation'], geolocation: HANOI } : {});
  const p = await context.newPage();
  await signIn(p, email, password);
  return { context, page: p };
}

test.describe.serial('trip execution: milestones, live GPS, OTP confirmation and incidents', () => {
  test.skip(!adminEmail || !adminPassword, 'Set E2E_ADMIN_EMAIL and E2E_ADMIN_PASSWORD to run the trip tests');
  let driver: { context: BrowserContext; page: Page };
  let customer: { context: BrowserContext; page: Page };
  let admin: { context: BrowserContext; page: Page };
  let shipmentId = '';
  let shipmentCode = '';
  let restore: () => Promise<void> = async () => undefined;

  test.beforeAll(async ({ browser, request }) => {
    const adminToken = await apiToken(request, adminEmail!, adminPassword!);
    restore = await isolatePriceTables(adminToken, VEHICLE);
    expect((await request.post('/api/v1/admin/pricing-configs', { headers: auth(adminToken), data: {
      name: `E2E chuyến ${Date.now().toString(36)}`, vehicleTypeCode: VEHICLE, basePrice: 400000, pricePerKm: 12000, includedDistanceKm: 0,
      includedWeightKg: 3500, pricePerExtraKg: 0, includedVolumeM3: 30, pricePerExtraM3: 0, additionalStopPrice: 0, minimumPrice: 0,
      effectiveFrom: new Date(Date.now() - 3_600_000).toISOString(), timeRules: [], extraFees: [],
    } })).status()).toBe(201);

    const customerEmail = uniqueEmail('trip-customer');
    await request.post('/api/v1/auth/register/customer', { data: { fullName: 'Khách Theo Dõi', email: customerEmail, password: PASSWORD, confirmPassword: PASSWORD } });
    const customerToken = await apiToken(request, customerEmail, PASSWORD);
    const stop = (type: string, seq: number, province: string, detail: string) =>
      ({ stopType: type, sequenceNo: seq, contactName: `Liên hệ ${seq}`, contactPhone: `091234567${seq}`, province, district: 'Trung tâm', addressDetail: detail });
    const draft = await request.post('/api/v1/shipments/draft', { headers: auth(customerToken), data: {
      pickupTime: new Date(Date.now() + 2 * 86_400_000).toISOString(), vehicleTypeCode: VEHICLE,
      stops: [stop('PICKUP', 1, 'Hà Nội', 'Kho số 1'), stop('DELIVERY', 2, 'Hải Phòng', 'Cửa hàng số 2')],
      items: [{ itemName: 'Điều hòa', category: 'ELECTRONICS', quantity: 20, weightPerUnitKg: 50, lengthCm: 80, widthCm: 40, heightCm: 60 }],
    } });
    expect(draft.status(), await draft.text()).toBe(201);
    ({ id: shipmentId, shipmentCode } = (await draft.json()).data);
    const quote = (await (await request.post(`/api/v1/shipments/${shipmentId}/pricing/preview`, { headers: auth(customerToken) })).json()).data;
    if (quote.status === 'AWAITING_MANUAL_REVIEW')
      expect((await request.post(`/api/v1/admin/quotes/${quote.id}/manual-adjustment`, { headers: auth(adminToken),
        data: { distanceKm: 120, reason: 'Quãng đường kiểm tra cho E2E' } })).status()).toBe(200);
    expect((await request.post(`/api/v1/shipments/${shipmentId}/pricing/accept`, { headers: auth(customerToken) })).status()).toBe(200);
    expect((await request.post(`/api/v1/shipments/${shipmentId}/publish`, { headers: auth(customerToken) })).status()).toBe(200);

    const driverEmail = await approvedDriver(request, adminToken, 'Tài Xế Chuyến');
    const driverToken = await apiToken(request, driverEmail, PASSWORD);
    const detail = (await (await request.get(`/api/v1/driver/marketplace/shipments/${shipmentId}`, { headers: auth(driverToken) })).json()).data;
    const applied = await request.post(`/api/v1/driver/marketplace/shipments/${shipmentId}/applications`, { headers: auth(driverToken), data: { vehicleId: detail.vehicles[0].id } });
    expect(applied.status(), await applied.text()).toBe(201);
    const accept = await request.post(`/api/v1/customer/shipments/${shipmentId}/applications/${(await applied.json()).data.id}/accept`, { headers: auth(customerToken) });
    expect(accept.status(), await accept.text()).toBe(200);

    driver = await page(browser, driverEmail, PASSWORD, true);
    customer = await page(browser, customerEmail, PASSWORD);
    admin = await page(browser, adminEmail!, adminPassword!);
  });

  test.afterAll(async () => {
    for (const p of [driver, customer, admin]) await p?.context.close();
    await restore();
  });

  async function step(label: string): Promise<void> {
    await button(driver.page, label).click();
    await driver.page.getByRole('dialog').getByRole('button', { name: label, exact: true }).click();
    await expect(driver.page.getByText(`Đã cập nhật: ${label}.`)).toBeVisible();
  }

  test('the driver sees the assigned trip and advances it step by step', async () => {
    await driver.page.goto('/driver/trips');
    await driver.page.getByRole('tab', { name: 'Sắp thực hiện' }).click();
    await driver.page.getByTestId('trip-item').filter({ hasText: shipmentCode }).click();
    await expect(driver.page.getByRole('heading', { name: shipmentCode })).toBeVisible();
    await expect(driver.page.getByText('Kho số 1')).toBeVisible();
    await expect(driver.page.getByText('Chia sẻ vị trí')).toHaveCount(0);   // nothing location-related before transit
    await step('Đã đến điểm lấy hàng');
    await step('Xác nhận đã nhận hàng');
    await step('Bắt đầu vận chuyển');
    await expect(driver.page.locator('app-shipment-status').first()).toContainText('Đang vận chuyển');
  });

  test('the customer follows the real browser position live', async () => {
    await customer.page.goto(`/customer/shipments/${shipmentId}/tracking`);
    await expect(customer.page.getByTestId('tracking-state')).toHaveText('Mất tín hiệu');   // in transit, no fix yet
    await button(driver.page, 'Bắt đầu chia sẻ vị trí').click();
    await expect(driver.page.getByTestId('gps-state')).toContainText('Đang chia sẻ vị trí');
    await expect(customer.page.getByTestId('tracking-state')).toHaveText('Đang cập nhật', { timeout: 20_000 });
    await expect(customer.page.getByText('21.02850, 105.85420')).toBeVisible();
    await expect(customer.page.getByTestId('stream-status')).toContainText('trực tiếp');

    // The vehicle moves ~150 m (about 60 km/h); after the send interval the new fix reaches the customer through the stream.
    await driver.page.waitForTimeout(9_000);
    await driver.context.setGeolocation({ latitude: 21.0299, longitude: 105.8542 });
    await expect(customer.page.getByText('21.02990, 105.85420')).toBeVisible({ timeout: 20_000 });
  });

  test('an incident is reported during the trip and resolved by the admin', async () => {
    await driver.page.goto(`/driver/trips/${shipmentId}/incidents`);
    await driver.page.selectOption('#inc-type', 'TRAFFIC_JAM');
    await driver.page.fill('#inc-desc', 'Kẹt xe trên cầu Thanh Trì, chậm khoảng 30 phút');
    await button(driver.page, 'Gửi báo cáo').click();
    await expect(driver.page.getByText('Đã gửi báo cáo sự cố.')).toBeVisible();
    await expect(driver.page.getByTestId('incident')).toContainText('Kẹt xe');

    await customer.page.reload();
    await expect(customer.page.getByTestId('incident')).toContainText('Kẹt xe trên cầu Thanh Trì');

    await admin.page.goto('/admin/incidents');
    await admin.page.getByRole('row').filter({ hasText: shipmentCode }).getByRole('link', { name: 'Kẹt xe' }).click();
    await admin.page.selectOption('#ai-next', 'RESOLVED');
    await expect(button(admin.page, 'Lưu')).toBeDisabled();
    await admin.page.fill('#ai-note', 'Đã báo khách, xe tiếp tục hành trình');
    await button(admin.page, 'Lưu').click();
    await expect(admin.page.getByTestId('incident-status')).toHaveText('Đã xử lý');
    await customer.page.reload();
    await expect(customer.page.getByTestId('incident')).toContainText('Đã báo khách');
    await expect(customer.page.locator('app-shipment-status').first()).toContainText('Đang vận chuyển');   // the order itself is untouched
  });

  test('delivery is completed only with the customer’s code', async () => {
    await driver.page.goto(`/driver/trips/${shipmentId}`);
    await step('Xác nhận đã giao hàng');
    await expect(driver.page.getByText('Đang chờ khách hàng xác nhận bằng mã OTP')).toBeVisible();

    await customer.page.reload();
    await expect(customer.page.getByTestId('confirm-delivery')).toBeVisible();
    await expect(customer.page.getByTestId('tracking-state')).toHaveText('Đã kết thúc');
    await customer.page.getByRole('button', { name: /Thông báo/ }).click();
    const message = await customer.page.getByRole('dialog', { name: 'Thông báo' }).getByText(/Mã xác nhận của bạn là \d{6}/).textContent();
    const code = /(\d{6})/.exec(message ?? '')![1];
    await customer.page.keyboard.press('Escape');
    await customer.page.mouse.click(5, 5);

    await customer.page.fill('#otp', code === '000000' ? '111111' : '000000');
    await button(customer.page, 'Xác nhận đã nhận hàng').click();
    await expect(customer.page.getByRole('alert')).toContainText('Mã xác nhận không đúng');
    await expect(customer.page.getByText('Còn 4 lần nhập')).toBeVisible();
    await customer.page.fill('#otp', code);
    await button(customer.page, 'Xác nhận đã nhận hàng').click();
    await expect(customer.page.getByText('Đã xác nhận nhận hàng. Chuyến đã hoàn thành.')).toBeVisible();
    await expect(customer.page.locator('app-shipment-status').first()).toContainText('Hoàn tất');
    await expect(customer.page.getByText('21.02990')).toHaveCount(0);

    await driver.page.reload();
    await expect(driver.page.getByText(/Chuyến đã hoàn thành lúc/)).toBeVisible();
  });
});
