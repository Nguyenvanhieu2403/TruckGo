import { Page, expect, test } from '@playwright/test';
import { PASSWORD, apiToken, routeProvider, button, horizontalOverflow, isolatePriceTables, signIn, uniqueEmail } from './support';

const adminEmail = process.env['E2E_ADMIN_EMAIL'];
const adminPassword = process.env['E2E_ADMIN_PASSWORD'];
const VEHICLE = 'TRUCK_1_5T';
/** Unique per run so tables left by earlier runs on the same database never match. */
const TABLE = `E2E bảng giá 1,5 tấn ${Date.now().toString(36)}`;

const vnd = (n: number) => `${new Intl.NumberFormat('vi-VN').format(n)} VNĐ`;
const amount = (text: string) => Number(text.replace(/[^\d]/g, ''));

/** Next Saturday at least two days ahead, 23:00 Vietnam time, so both the night and weekend surcharges apply. */
function saturdayNight(): Date {
  const d = new Date(Date.now() + 2 * 86_400_000);
  while (new Date(d.getTime() + 7 * 3_600_000).getUTCDay() !== 6) d.setTime(d.getTime() + 86_400_000);
  const local = new Date(d.getTime() + 7 * 3_600_000);
  return new Date(Date.UTC(local.getUTCFullYear(), local.getUTCMonth(), local.getUTCDate(), 16, 0));
}

/*
 * Expected price for 600 kg (10 × 60 kg, 3.24 m³), 3 stops, loading by two people, fragile goods, 18.5 km verified,
 * pickup Saturday 23:00, with the table created below:
 *   base 200,000 · distance (18.5 − 3) × 11,000 = 170,500 · transport 370,500
 *   loading 110,000 + fragile 5% 18,525 = 128,525 · extra stop 30,000
 *   night 20% 74,100 + weekend 10% 37,050 = 111,150 · total 640,175 (600 kg is within the 1,000 kg included)
 */
const EXPECTED = { base: 200_000, distance: 170_500, service: 128_525, stop: 30_000, time: 111_150, total: 640_175 };

test.describe.serial('pricing UI: admin price tables, quote breakdown and lifecycle', () => {
  test.skip(!adminEmail || !adminPassword, 'Set E2E_ADMIN_EMAIL and E2E_ADMIN_PASSWORD to run the pricing UI tests');
  let admin: Page;
  let customer: Page;
  let shipmentUrl = '';
  let configId = '';
  let restore: () => Promise<void> = async () => undefined;

  test.beforeAll(async ({ browser, request }) => {
    test.skip(await routeProvider(await apiToken(request, adminEmail!, adminPassword!)) !== 'none',
      'These tests cover the manual-distance flow; the stack routes quotes automatically (MAPS_ROUTE_PROVIDER is set)');
    const adminToken = await apiToken(request, adminEmail!, adminPassword!);
    restore = await isolatePriceTables(adminToken, VEHICLE);
    const headers = { Authorization: `Bearer ${adminToken}` };
    const created = await request.post('/api/v1/admin/pricing-configs', { headers, data: {
      name: TABLE, vehicleTypeCode: VEHICLE, basePrice: 200000, pricePerKm: 11000, includedDistanceKm: 3, includedWeightKg: 1000,
      pricePerExtraKg: 350, includedVolumeM3: 5, pricePerExtraM3: 30000, additionalStopPrice: 30000, minimumPrice: 250000,
      effectiveFrom: new Date(Date.now() - 3_600_000).toISOString(),
      timeRules: [
        { name: 'Ban đêm (22:00–06:00)', daysOfWeek: null, startTime: '22:00', endTime: '06:00', feeType: 'PERCENTAGE', amount: 0, percentage: 20 },
        { name: 'Cuối tuần', daysOfWeek: 'SAT,SUN', startTime: '00:00', endTime: '23:59', feeType: 'PERCENTAGE', amount: 0, percentage: 10 },
      ],
      extraFees: [],
    } });
    expect(created.status(), await created.text()).toBe(201);
    configId = (await created.json()).data.id;
    for (const fee of [{ serviceCode: 'LOADING_SUPPORT', serviceOptionValue: 'TWO_PEOPLE', pricingType: 'FIXED', amount: 110000, percentage: 0 },
      { serviceCode: 'FRAGILE_GOODS', serviceOptionValue: null, pricingType: 'PERCENTAGE', amount: 0, percentage: 5 }])
      expect((await request.post('/api/v1/admin/pricing-service-fees', { headers, data: { pricingConfigId: configId, ...fee } })).status()).toBe(201);

    const email = uniqueEmail('pricing-ui');
    await request.post('/api/v1/auth/register/customer', { data: { fullName: 'Khách Giao Diện Giá', email, password: PASSWORD, confirmPassword: PASSWORD } });
    const customerToken = await apiToken(request, email, PASSWORD);
    const stop = (type: string, seq: number, name: string, phone: string, detail: string) =>
      ({ stopType: type, sequenceNo: seq, contactName: name, contactPhone: phone, province: 'Hà Nội', district: 'Cầu Giấy', addressDetail: detail });
    const draft = await request.post('/api/v1/shipments/draft', { headers: { Authorization: `Bearer ${customerToken}` }, data: {
      pickupTime: saturdayNight().toISOString(), vehicleTypeCode: VEHICLE,
      stops: [stop('PICKUP', 1, 'Kho Cầu Giấy', '0912345678', 'Số 1 Trần Thái Tông E2E'), stop('DELIVERY', 2, 'Cửa hàng A', '0987654321', 'Số 20 Xuân Thủy'),
        stop('DELIVERY', 3, 'Cửa hàng B', '0987654322', 'Số 5 Hoàng Quốc Việt')],
      items: [{ itemName: 'Máy giặt', category: 'ELECTRONICS', quantity: 10, weightPerUnitKg: 60, lengthCm: 60, widthCm: 60, heightCm: 90 }],
      services: [{ serviceCode: 'LOADING_SUPPORT', serviceValue: 'TWO_PEOPLE' }, { serviceCode: 'FRAGILE_GOODS', serviceValue: 'true' }],
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

  const panel = () => customer.locator('section.quote');

  async function adjust(distance: string, reason: string, adjustment?: string): Promise<void> {
    await admin.goto('/admin/quotes');
    await admin.getByRole('row').filter({ hasText: 'Số 1 Trần Thái Tông E2E' }).last().getByRole('button', { name: 'Xử lý' }).click();
    await admin.fill('#distanceKm', distance);
    if (adjustment) await admin.fill('#adjustmentAmount', adjustment);
    await admin.fill('#reason', reason);
    await button(admin, 'Tạo báo giá đã điều chỉnh').click();
  }

  test('admin filters price tables and sees time windows, service fees and history', async () => {
    await admin.getByRole('link', { name: 'Bảng giá', exact: true }).click();
    await admin.selectOption('#vehicle', VEHICLE);
    const row = admin.getByRole('row').filter({ hasText: TABLE });
    await expect(row).toContainText('Đang áp dụng');
    await expect(admin.locator('tbody tr').filter({ hasText: 'Đang áp dụng' })).toHaveCount(1);
    await row.getByRole('link', { name: TABLE }).click();
    await expect(admin.locator('#basePrice')).toHaveValue('200000');
    await expect(admin.locator('#tr-start0')).toHaveValue('22:00');
    await expect(admin.locator('#tr-end0')).toHaveValue('06:00');
    await expect(admin.locator('app-pricing-service-fees tbody tr')).toHaveCount(2);
    await expect(admin.getByRole('cell', { name: 'Cần hai người hỗ trợ' })).toBeVisible();
    await expect(admin.locator('details.history summary', { hasText: 'Thêm phí dịch vụ' })).toHaveCount(2);
  });

  test('admin form rejects a negative price and an inverted period without saving', async () => {
    await admin.fill('#pricePerKm', '-5');
    await admin.fill('#effectiveTo', '2020-01-01T00:00');
    await button(admin, 'Lưu thay đổi').click();
    await expect(admin.getByText('Vui lòng kiểm tra các trường được đánh dấu.')).toBeVisible();
    await expect(admin.getByText('Không được âm').first()).toBeVisible();
    await expect(admin.getByText('Thời điểm kết thúc phải sau thời điểm bắt đầu')).toBeVisible();
    await admin.reload();
    await expect(admin.locator('#pricePerKm')).toHaveValue('11000');
  });

  test('without a map provider the quote waits for the admin, with no price shown', async () => {
    await customer.goto(shipmentUrl);
    await expect(panel()).toContainText('Đơn chưa có báo giá');
    await button(customer, 'Tính giá').click();
    await expect(panel()).toContainText('Chờ quản trị viên xử lý');
    await expect(customer.locator('.toast').first()).toBeVisible();
    await expect(customer.getByText('Đang chờ quản trị viên xác nhận')).toBeVisible();
    expect(await panel().innerText()).not.toMatch(/\d\s?VNĐ/);
  });

  test('admin must give a reason, then the verified distance prices the order', async () => {
    await admin.goto('/admin/quotes');
    await admin.getByRole('row').filter({ hasText: 'Số 1 Trần Thái Tông E2E' }).last().getByRole('button', { name: 'Xử lý' }).click();
    await expect(admin.getByText('khách hàng sẽ thấy')).toBeVisible();
    await button(admin, 'Tạo báo giá đã điều chỉnh').click();
    await expect(admin.getByText('Bắt buộc')).toBeVisible();
    await adjust('18.5', 'Đã xác minh tuyến Cầu Giấy qua 3 điểm');
    await expect(admin.getByText(new RegExp(`-Q2: ${vnd(EXPECTED.total).replace('.', '\\.')}`))).toBeVisible();
  });

  test('the breakdown matches the hand-computed price, including night and weekend surcharges', async () => {
    await customer.reload();
    await expect(panel()).toContainText('Đã báo giá');
    for (const [label, value] of [['Giá cơ bản', EXPECTED.base], ['Phí vận chuyển', EXPECTED.distance], ['Phụ phí dịch vụ', EXPECTED.service],
      ['Phụ phí điểm dừng', EXPECTED.stop], ['Phụ phí khung giờ', EXPECTED.time], ['Tổng giá', EXPECTED.total]] as const) {
      await expect(panel()).toContainText(label);
      await expect(panel()).toContainText(vnd(value));
    }
    await expect(panel()).not.toContainText('Phụ phí khối lượng');
    await expect(panel()).toContainText('18,5 km');
    await expect(panel()).toContainText('đã được quản trị viên xác minh');
    const rows = (await panel().locator('table.breakdown tbody td.num').allInnerTexts()).map(amount);
    expect(rows.reduce((a, b) => a + b, 0)).toBe(EXPECTED.total);
    await panel().locator('details.lines summary').click();
    const lines = await panel().locator('details.lines li').allInnerTexts();
    expect(lines.some(l => l.includes('Ban đêm'))).toBe(true);
    expect(lines.some(l => l.includes('Cuối tuần'))).toBe(true);
    expect(lines.some(l => l.includes('18,5 km'))).toBe(true);
    const lineSum = (await panel().locator('details.lines .line-main .num').allInnerTexts()).map(amount).reduce((a, b) => a + b, 0);
    expect(lineSum).toBe(EXPECTED.total);
    await expect(customer.getByText(`${vnd(EXPECTED.total)} (chờ xác nhận)`)).toBeVisible();
  });

  test('rejecting with a reason keeps the version history', async () => {
    await button(customer, 'Từ chối báo giá').click();
    await customer.fill('#reject-reason', 'Muốn so sánh thêm');
    await panel().getByRole('button', { name: 'Từ chối báo giá', exact: true }).click();
    await expect(panel()).toContainText('Bạn đã từ chối báo giá: Muốn so sánh thêm');
    await panel().locator('details.history summary').click();
    const history = panel().locator('.history-row');
    await expect(history.nth(0)).toContainText('Q2');
    await expect(history.nth(0)).toContainText('Đã từ chối');
    await expect(history.nth(1)).toContainText('Q1');
    await expect(history.nth(1)).toContainText('Đã được thay thế');
  });

  test('a re-quote adjusted by the admin is accepted through the confirmation dialog', async () => {
    await customer.getByRole('button', { name: 'Tính lại giá' }).click();
    await expect(panel()).toContainText('Chờ quản trị viên xử lý');
    await adjust('18.5', 'Khách quen, làm tròn giá', '-40175');
    await expect(admin.getByText(/-Q4: 600\.000 VNĐ/)).toBeVisible();
    await customer.reload();
    await expect(panel()).toContainText(vnd(600_000));
    await expect(panel()).toContainText('Khách quen, làm tròn giá');
    await customer.getByRole('button', { name: 'Xác nhận báo giá' }).click();
    await expect(panel()).toContainText('Sau khi xác nhận, báo giá không thể thay đổi');
    await panel().getByRole('button', { name: 'Xác nhận', exact: true }).click();
    await expect(panel()).toContainText('Bạn đã xác nhận báo giá');
    await expect(customer.getByText(`${vnd(600_000)} (đã xác nhận)`)).toBeVisible();
    await expect(customer.getByRole('button', { name: 'Tính lại giá' })).toHaveCount(0);
  });

  test('editing the draft after acceptance supersedes the quote', async () => {
    await customer.getByRole('link', { name: 'Sửa đơn' }).click();
    await expect(customer).toHaveURL(/\/edit$/);
    await customer.fill('#addressDetail2', 'Số 7 Hoàng Quốc Việt');
    await button(customer, 'Lưu nháp').click();
    await expect(customer).toHaveURL(/\/customer\/shipments\/[0-9a-f-]+$/);
    await expect(panel()).toContainText('Đơn đã thay đổi nên báo giá này không còn hiệu lực');
    await expect(customer.getByText(/Chưa có báo giá/).first()).toBeVisible();
    await expect(customer.getByRole('button', { name: 'Tính lại giá' })).toBeVisible();
  });

  test('quote panel and price tables fit a 390px phone screen', async () => {
    for (const [page, url, ready] of [[customer, shipmentUrl, 'section.quote'], [admin, `/admin/pricing/${configId}`, '#basePrice']] as const) {
      await page.setViewportSize({ width: 390, height: 844 });
      await page.goto(url);
      await expect(page.locator(ready)).toBeVisible();
      expect(await horizontalOverflow(page)).toBeLessThanOrEqual(1);
    }
  });
});
