import { expect, test } from '@playwright/test';
import { button, fillItem, fillStop, pickupLocal, signInAsNewCustomer } from './support';

test('a draft can be saved, edited and deleted', async ({ page }) => {
  await signInAsNewCustomer(page, 'Khách Nháp');
  await page.goto('/customer/shipments/create');
  await page.fill('#pickupTime', pickupLocal());
  await fillStop(page, 0, { name: 'Kho B', phone: '0912000111', province: 'Hà Nội', detail: 'Số 1' });
  await fillStop(page, 1, { name: 'Nhà khách', phone: '0987000222', province: 'Hà Nội', detail: 'Số 2' });
  await button(page, 'Tiếp tục →').click();
  await fillItem(page, 0, { name: 'Bao gạo', category: 'AGRICULTURAL', qty: 5, kg: 50 });
  await button(page, 'Lưu nháp').click();

  await expect(page).toHaveURL(/\/customer\/shipments\/[0-9a-f-]+$/);
  await expect(page.getByText('Một số hàng chưa có kích thước')).toBeVisible();
  await page.getByRole('link', { name: 'Sửa đơn' }).click();
  await expect(page).toHaveURL(/\/edit$/);
  await button(page, 'Tiếp tục →').click();
  await expect(page.locator('#itemName0')).toHaveValue('Bao gạo');
  await fillItem(page, 0, { name: 'Bao gạo', category: 'AGRICULTURAL', qty: 5, kg: 50, size: [60, 40, 15] });
  await button(page, 'Lưu nháp').click();
  await expect(page).toHaveURL(/\/customer\/shipments\/[0-9a-f-]+$/);
  await expect(page.getByText('Một số hàng chưa có kích thước')).toHaveCount(0);

  await button(page, 'Xóa nháp').click();
  await button(page, 'Xác nhận xóa nháp').click();
  await expect(page).toHaveURL(/\/customer\/shipments$/);
  await expect(page.getByText('Bạn chưa có đơn vận chuyển nào')).toBeVisible();
});

test('leaving the wizard with unsaved input asks for confirmation', async ({ page }) => {
  await signInAsNewCustomer(page, 'Khách Rời Trang');
  await page.goto('/customer/shipments/create');
  await page.fill('#contactName0', 'Đang nhập dở');

  const dismissed = page.waitForEvent('dialog').then(async d => { expect(d.message()).toContain('chưa lưu'); await d.dismiss(); });
  await page.getByRole('link', { name: 'Tổng quan' }).click();
  await dismissed;
  await expect(page).toHaveURL(/\/create$/);
  await expect(page.locator('#contactName0')).toHaveValue('Đang nhập dở');

  page.once('dialog', d => d.accept());
  await page.getByRole('link', { name: 'Tổng quan' }).click();
  await expect(page).toHaveURL(/\/customer$/);
});

test('the wizard fits a 390px phone screen', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await signInAsNewCustomer(page, 'Khách Mobile');
  await page.goto('/customer/shipments/create');
  await expect(page.locator('#contactName0')).toBeVisible();
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  expect(overflow).toBeLessThanOrEqual(1);
});
