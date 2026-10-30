import { expect, test } from '@playwright/test';
import { button, fillItem, fillStop, pickupLocal, signIn, signInAsNewCustomer } from './support';

/** Creates a draft for the signed-in customer and returns its detail URL and code. */
async function createDraft(page: import('@playwright/test').Page): Promise<{ url: string; code: string }> {
  await page.goto('/customer/shipments/create');
  await page.fill('#pickupTime', pickupLocal());
  await fillStop(page, 0, { name: 'Kho Bí Mật', phone: '0912111222', province: 'Hà Nội', detail: 'Số 9' });
  await fillStop(page, 1, { name: 'Người nhận', phone: '0987111222', province: 'Hà Nội', detail: 'Số 8' });
  await button(page, 'Tiếp tục →').click();
  await fillItem(page, 0, { name: 'Hàng riêng tư', category: 'OTHER', qty: 1, kg: 10, size: [10, 10, 10] });
  await button(page, 'Lưu nháp').click();
  await expect(page).toHaveURL(/\/customer\/shipments\/[0-9a-f-]+$/);
  return { url: page.url(), code: (await page.locator('h1').innerText()).trim() };
}

test('another customer cannot open someone else\'s order', async ({ browser }) => {
  const owner = await browser.newPage();
  await signInAsNewCustomer(owner, 'Chủ Đơn');
  const { url } = await createDraft(owner);
  await owner.close();

  const intruder = await browser.newPage();
  await signInAsNewCustomer(intruder, 'Người Khác');
  await intruder.goto(url);
  await expect(intruder.getByText('Không tìm thấy đơn vận chuyển')).toBeVisible();
  await expect(intruder.getByText('Kho Bí Mật')).toHaveCount(0);
  await intruder.close();
});

test('admin sees orders read-only', async ({ browser }) => {
  const adminEmail = process.env['E2E_ADMIN_EMAIL'];
  const adminPassword = process.env['E2E_ADMIN_PASSWORD'];
  test.skip(!adminEmail || !adminPassword, 'Set E2E_ADMIN_EMAIL and E2E_ADMIN_PASSWORD (see README) to run the admin check');

  const customer = await browser.newPage();
  await signInAsNewCustomer(customer, 'Khách Cho Admin');
  const { code } = await createDraft(customer);
  await customer.close();

  const admin = await browser.newPage();
  await signIn(admin, adminEmail!, adminPassword!);
  await expect(admin).toHaveURL(/\/admin\/drivers/);
  await admin.getByRole('link', { name: 'Đơn hàng' }).click();
  await admin.fill('#keyword', code);
  await button(admin, 'Lọc').click();
  await admin.getByRole('link', { name: code }).click();
  await expect(admin.locator('h1')).toHaveText(code);
  for (const label of ['Sửa đơn', 'Đăng đơn', 'Hủy đơn', 'Xóa nháp'])
    await expect(admin.getByRole('button', { name: label }).or(admin.getByRole('link', { name: label }))).toHaveCount(0);
  await expect(admin.locator('.upload')).toHaveCount(0);
  await admin.close();
});
