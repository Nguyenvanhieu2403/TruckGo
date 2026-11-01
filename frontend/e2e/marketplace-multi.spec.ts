import { APIRequestContext, Page, expect, test } from '@playwright/test';
import { PASSWORD, apiToken, button, horizontalOverflow, isolatePriceTables, signIn, uniqueEmail } from './support';

const adminEmail = process.env['E2E_ADMIN_EMAIL'];
const adminPassword = process.env['E2E_ADMIN_PASSWORD'];
const VEHICLE = 'TRUCK_2_5T';

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
  const vehicle = await request.post('/api/v1/vehicles', { headers: auth(token), data: { plateNumber: plate(), vehicleType: 'MEDIUM_TRUCK', bodyTypeCode: 'CLOSED_BOX', maxPayloadKg: 3000,
    lengthCm: 450, widthCm: 200, heightCm: 200, registrationExpiryDate: new Date(Date.now() + 365 * 86_400_000).toISOString().slice(0, 10) } });
  expect(vehicle.status(), await vehicle.text()).toBe(201);
  const vehicleId = (await vehicle.json()).data.id;
  expect((await request.post(`/api/v1/vehicles/${vehicleId}/submit-verification`, { headers: auth(token) })).status()).toBe(200);
  expect((await request.post(`/api/v1/admin/vehicles/${vehicleId}/approve`, { headers: auth(adminToken) })).status()).toBe(200);
  return email;
}

test.describe.serial('marketplace: an order needing two vehicles is filled slot by slot, a driver can be removed before the trip', () => {
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
      name: `E2E multi ${Date.now().toString(36)}`, vehicleTypeCode: VEHICLE, basePrice: 300000, pricePerKm: 10000, includedDistanceKm: 0,
      includedWeightKg: 2500, pricePerExtraKg: 0, includedVolumeM3: 20, pricePerExtraM3: 0, additionalStopPrice: 0, minimumPrice: 0,
      effectiveFrom: new Date(Date.now() - 3_600_000).toISOString(), timeRules: [], extraFees: [],
    } })).status()).toBe(201);

    const customerEmail = uniqueEmail('mk-multi');
    await request.post('/api/v1/auth/register/customer', { data: { fullName: 'Khách Hai Xe', email: customerEmail, password: PASSWORD, confirmPassword: PASSWORD } });
    const customerToken = await apiToken(request, customerEmail, PASSWORD);
    const stop = (type: string, seq: number, province: string, district: string, detail: string) =>
      ({ stopType: type, sequenceNo: seq, contactName: `Liên hệ ${seq}`, contactPhone: `091234567${seq}`, province, district, addressDetail: detail });
    const draft = await request.post('/api/v1/shipments/draft', { headers: auth(customerToken), data: {
      pickupTime: new Date(Date.now() + 3 * 86_400_000).toISOString(), vehicleTypeCode: VEHICLE, vehicleCount: 2,
      stops: [stop('PICKUP', 1, 'Hà Nội', 'Đống Đa', 'Số 5 Tây Sơn'), stop('DELIVERY', 2, 'Hải Phòng', 'Ngô Quyền', 'Số 8 Lạch Tray')],
      items: [{ itemName: 'Máy lạnh', category: 'ELECTRONICS', quantity: 10, weightPerUnitKg: 80, lengthCm: 70, widthCm: 70, heightCm: 180 }],
    } });
    expect(draft.status(), await draft.text()).toBe(201);
    ({ id: shipmentId, shipmentCode } = (await draft.json()).data);
    const quote = (await (await request.post(`/api/v1/shipments/${shipmentId}/pricing/preview`, { headers: auth(customerToken) })).json()).data;
    if (quote.status === 'AWAITING_MANUAL_REVIEW') {
      expect((await request.post(`/api/v1/admin/quotes/${quote.id}/manual-adjustment`, { headers: auth(adminToken),
        data: { distanceKm: 50, reason: 'Quãng đường kiểm tra cho E2E' } })).status()).toBe(200);
    }
    expect((await request.post(`/api/v1/shipments/${shipmentId}/pricing/accept`, { headers: auth(customerToken) })).status()).toBe(200);
    expect((await request.post(`/api/v1/shipments/${shipmentId}/publish`, { headers: auth(customerToken) })).status()).toBe(200);

    const emailA = await approvedDriver(request, adminToken, 'Tài Xế Một');
    const emailB = await approvedDriver(request, adminToken, 'Tài Xế Hai');
    for (const email of [emailA, emailB]) {
      const token = await apiToken(request, email, PASSWORD);
      const vehicles = (await (await request.get('/api/v1/vehicles/my', { headers: auth(token) })).json()).data;
      const applied = await request.post(`/api/v1/driver/marketplace/shipments/${shipmentId}/applications`, { headers: auth(token), data: { vehicleId: vehicles[0].id } });
      expect(applied.status(), await applied.text()).toBe(201);
    }
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

  test('drivers see how many vehicles the order needs', async () => {
    await driverA.goto('/driver/marketplace');
    await expect(driverA.getByTestId('marketplace-item').filter({ hasText: shipmentCode }).getByTestId('vehicle-progress')).toHaveText('Cần 2 xe · đã có 0');
  });

  test('the customer chooses two drivers one after the other', async () => {
    await customer.goto(`/customer/shipments/${shipmentId}`);
    await expect(customer.getByTestId('applicants-progress')).toHaveText('Đã chọn 0/2 xe');
    await customer.getByTestId('applicant').filter({ hasText: 'Tài Xế Một' }).getByRole('button', { name: 'Chọn tài xế này' }).click();
    await expect(customer.getByRole('dialog')).toContainText('nhận 1 trong 2 xe');
    await button(customer, 'Xác nhận chọn').click();
    await expect(customer.getByTestId('assignment-progress')).toContainText('1/2 xe đã có tài xế');
    await expect(customer.getByTestId('assignment-progress')).toContainText('Còn thiếu 1 xe');

    await customer.getByTestId('applicant').filter({ hasText: 'Tài Xế Hai' }).getByRole('button', { name: 'Chọn tài xế này' }).click();
    await expect(customer.getByRole('dialog')).toContainText('xe cuối cùng');
    await button(customer, 'Xác nhận chọn').click();
    await expect(customer.getByTestId('assignment-progress')).toContainText('2/2 xe đã có tài xế');
    await expect(customer.getByTestId('assignment')).toHaveCount(2);
    await expect(customer.locator('app-shipment-status').first()).toContainText('Đã có tài xế');
  });

  test('a driver removed before the trip is told in realtime and the order needs a driver again', async () => {
    await driverB.goto('/driver/applications');
    await expect(driverB.getByTestId('application-row').filter({ hasText: shipmentCode })).toContainText('Được chọn');
    const before = Number((await driverB.getByTestId('unread-count').textContent()) ?? '0');

    const slot = customer.getByTestId('assignment').filter({ hasText: 'Tài Xế Hai' });
    await slot.getByRole('button', { name: 'Gỡ tài xế' }).click();
    const dialog = customer.getByRole('dialog');
    await dialog.getByRole('button', { name: 'Gỡ tài xế' }).click();
    await expect(dialog).toContainText('Vui lòng nhập lý do');
    await customer.fill('#change-reason', 'Xe của tài xế cần bảo dưỡng');
    await dialog.getByRole('button', { name: 'Gỡ tài xế' }).click();
    await expect(customer.getByText('Đã gỡ tài xế khỏi đơn.')).toBeVisible();
    await expect(customer.getByTestId('assignment-progress')).toContainText('1/2 xe đã có tài xế');
    await customer.getByText(/Lịch sử phân công/).click();
    await expect(customer.getByTestId('assignment-history').last()).toContainText('Xe của tài xế cần bảo dưỡng');

    // Pushed over the notification stream: no reload needed.
    await expect(driverB.getByTestId('unread-count')).toHaveText(String(before + 1));
    await driverB.reload();
    await expect(driverB.getByTestId('application-row').filter({ hasText: shipmentCode })).toContainText('Đã bị gỡ khỏi đơn');
  });

  test('the admin sees the order among those still missing drivers', async () => {
    await admin.goto('/admin/marketplace');
    await admin.getByTestId('understaffed').check();
    await expect(admin.getByRole('row').filter({ hasText: shipmentCode }).getByTestId('admin-vehicle-progress')).toHaveText('1/2 xe đã có tài xế');
    await admin.getByRole('tab', { name: 'Lịch sử phân công' }).click();
    await expect(admin.getByRole('row').filter({ hasText: shipmentCode }).filter({ hasText: 'Gỡ tài xế' })).toContainText('Xe của tài xế cần bảo dưỡng');
    await admin.getByRole('tab', { name: 'Lịch tài xế & xe' }).click();
    await expect(admin.getByTestId('schedule-row').filter({ hasText: shipmentCode })).toHaveCount(1);
  });
});
