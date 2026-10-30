import { Page, expect, test } from '@playwright/test';
import { PNG, button, fillItem, fillStop, pickupLocal, signInAsNewCustomer } from './support';

/** One order goes through the whole wizard, gets published, found in the list and cancelled. */
test.describe.serial('shipment wizard: create, publish, find, cancel', () => {
  let page: Page;
  let code = '';

  test.beforeAll(async ({ browser }) => {
    page = await browser.newPage();
    await signInAsNewCustomer(page, 'Khách Wizard');
  });
  test.afterAll(async () => { await page.close(); });

  test('empty list offers to create the first order', async () => {
    await page.getByRole('link', { name: 'Đơn vận chuyển', exact: true }).click();
    await expect(page.getByText('Bạn chưa có đơn vận chuyển nào')).toBeVisible();
    await page.getByRole('link', { name: 'Tạo đơn đầu tiên' }).click();
    await expect(page).toHaveURL(/\/customer\/shipments\/create$/);
  });

  test('step 1 blocks until valid; deliveries can be added and removed', async () => {
    await button(page, 'Tiếp tục →').click();
    await expect(page.getByText('Vui lòng kiểm tra các trường được đánh dấu')).toBeVisible();
    await expect(page.locator('.field.invalid').nth(3)).toBeVisible();

    await page.fill('#pickupTime', pickupLocal());
    await fillStop(page, 0, { name: 'Kho Mỹ Đình', phone: '0912345678', province: 'Hà Nội', district: 'Nam Từ Liêm', detail: 'Số 10, đường Lê Đức Thọ' });
    await fillStop(page, 1, { name: 'Cửa hàng Cầu Giấy', phone: '0987 654 321', province: 'Hà Nội', detail: 'Số 20, đường Xuân Thủy' });
    await button(page, '+ Thêm điểm giao').click();
    await expect(page.locator('.stop-card')).toHaveCount(3);
    await page.getByRole('button', { name: 'Xóa điểm' }).last().click();
    await expect(page.locator('.stop-card')).toHaveCount(2);
    await expect(page.locator('#contactName1')).toHaveValue('Cửa hàng Cầu Giấy');
    await button(page, 'Tiếp tục →').click();
  });

  test('step 2 totals follow the spec example (13 parcels, 330 kg, 2.562 m³)', async () => {
    await fillItem(page, 0, { name: 'Thùng hàng A', category: 'CONSUMER_GOODS', qty: 10, kg: 20, size: [50, 40, 30] });
    await button(page, '+ Thêm hàng hóa').click();
    await fillItem(page, 1, { name: 'Bàn làm việc', category: 'FURNITURE', qty: 2, kg: 35, size: [120, 60, 75] });
    await button(page, '+ Thêm hàng hóa').click();
    await fillItem(page, 2, { name: 'Tủ lạnh', category: 'ELECTRONICS', qty: 1, kg: 60, size: [70, 70, 180] });
    const totals = page.locator('.totals');
    await expect(totals).toContainText('13');
    await expect(totals).toContainText('330 kg');
    await expect(totals).toContainText('2,562 m³');
  });

  test('photos upload after saving a draft; invalid files are rejected', async () => {
    await button(page, 'Lưu nháp để thêm ảnh').click();
    await expect(page.getByText('Đã lưu nháp. Bạn có thể thêm ảnh hàng hóa.')).toBeVisible();
    await expect(page).toHaveURL(/\/customer\/shipments\/[0-9a-f-]+\/edit$/);
    const input = page.locator('.upload input[type=file]');
    await input.setInputFiles({ name: 'thung-hang.png', mimeType: 'image/png', buffer: PNG });
    await expect(page.locator('figure img')).toHaveCount(1);
    await input.setInputFiles({ name: 'hoa-don.pdf', mimeType: 'application/pdf', buffer: Buffer.from('%PDF-1.7') });
    await expect(page.getByText(/Bỏ qua hoa-don.pdf/)).toBeVisible();
    // Passes the browser check (declared PNG) but the server sniffs the bytes.
    await input.setInputFiles({ name: 'gia-mao.png', mimeType: 'image/png', buffer: Buffer.from('<svg onload=alert(1)>') });
    await expect(page.getByText(/gia-mao.png: Định dạng tệp không được hỗ trợ/)).toBeVisible();
    await expect(page.locator('figure')).toHaveCount(1);
    await button(page, 'Tiếp tục →').click();
  });

  test('step 3 warns about an inconsistent payload requirement', async () => {
    await page.getByText('Xe tải 500 kg', { exact: true }).click();
    await expect(page.getByText(/vượt tải trọng danh nghĩa/)).toHaveCount(0);
    await page.fill('#minimumPayloadKg', '200');
    await expect(page.getByText(/Tải trọng tối thiểu 200 kg thấp hơn tổng trọng lượng/)).toBeVisible();
    await page.fill('#minimumPayloadKg', '');
    await page.getByText('Xe tải 1 tấn', { exact: true }).click();
    await page.selectOption('#bodyType', 'CLOSED_BOX');
    await button(page, 'Tiếp tục →').click();
  });

  test('step 4 renders services from the catalog with exclusive choices', async () => {
    await page.selectOption('#LOADING_SUPPORT', 'TWO_PEOPLE');
    await page.getByLabel('Có thang máy', { exact: true }).check();
    await page.getByLabel('Không có thang máy', { exact: true }).check();
    await expect(page.getByLabel('Có thang máy', { exact: true })).not.toBeChecked();
    await page.getByLabel('Hàng dễ vỡ', { exact: true }).check();
    await page.fill('#customerNote', 'Gọi trước 30 phút');
    await button(page, 'Tiếp tục →').click();
  });

  test('step 5 reviews everything and shows no price', async () => {
    await expect(page.getByRole('heading', { name: 'Giá cước' })).toBeVisible();
    const main = page.locator('main');
    for (const text of ['Kho Mỹ Đình', 'Cửa hàng Cầu Giấy', 'Tủ lạnh', '330 kg', '2,562 m³', 'Xe tải 1 tấn', 'Thùng kín',
      'Hỗ trợ bốc hàng: Cần hai người hỗ trợ', 'Không có thang máy', 'Hàng dễ vỡ', 'Gọi trước 30 phút',
      'Chưa tính giá – sẽ được hệ thống báo giá ở bước tiếp theo']) await expect(main).toContainText(text);
    expect(await main.innerText()).not.toMatch(/\d[\d.]*\s?(đ|₫|VND)/);
  });

  test('publishing shows the order with history and without edit actions', async () => {
    await button(page, 'Đăng đơn').click();
    await expect(page).toHaveURL(/\/customer\/shipments\/[0-9a-f-]+\?published=1/);
    await expect(page.getByText('Đã đăng đơn thành công.')).toBeVisible();
    code = (await page.locator('h1').innerText()).trim();
    expect(code).toMatch(/^TG\d{6}-[2-9A-HJKMNP-Z]{6}$/);
    const main = page.locator('main');
    await expect(main).toContainText('Đã đăng');
    await expect(main).toContainText('Khách hàng đăng đơn');
    await expect(main).toContainText('thung-hang.png');
    await expect(page.locator('figure img')).toHaveCount(1);
    await expect(button(page, 'Đăng đơn')).toHaveCount(0);
    await expect(page.getByRole('link', { name: 'Sửa đơn' })).toHaveCount(0);
  });

  test('the list finds the order by code and filters by status', async () => {
    await page.getByRole('link', { name: 'Đơn vận chuyển', exact: true }).click();
    await expect(page.getByRole('cell', { name: new RegExp(code) })).toBeVisible();
    await page.selectOption('#status', 'DRAFT');
    await button(page, 'Lọc').click();
    await expect(page.getByText('Không có đơn nào phù hợp bộ lọc')).toBeVisible();
    await button(page, 'Xóa lọc').click();
    await page.fill('#keyword', code.toLowerCase());
    await button(page, 'Lọc').click();
    await expect(page.getByRole('cell', { name: new RegExp(code) })).toBeVisible();
  });

  test('cancelling requires a reason', async () => {
    await button(page, 'Hủy').click();
    await button(page, 'Xác nhận hủy').click();
    await expect(page.getByText('Lý do dài 5–500 ký tự')).toBeVisible();
    await page.fill('#cancel-reason', 'Khách hàng thay đổi kế hoạch');
    await button(page, 'Xác nhận hủy').click();
    await expect(page.getByText(`Đã hủy đơn ${code}.`)).toBeVisible();
    await expect(page.locator('.shipment-status--cancelled').first()).toBeVisible();
  });
});
