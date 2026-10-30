import { expect, test } from '@playwright/test';
import { PASSWORD, button, signIn, uniqueEmail } from './support';

test('customer signs up from the landing page, signs in and stays signed in after reload', async ({ page }) => {
  const email = uniqueEmail('signup');
  await page.goto('/');
  await page.getByRole('link', { name: /Tôi cần thuê xe/ }).click();
  await expect(page).toHaveURL(/\/register\?role=customer/);
  await page.fill('#fullName', 'Khách Đăng Ký');
  await page.fill('#email', email);
  await page.fill('#password', PASSWORD);
  await page.fill('#confirmPassword', PASSWORD);
  await button(page, 'Đăng ký').click();
  await expect(page).toHaveURL(/\/login\?registered=1/);
  await expect(page.getByText('Đăng ký thành công')).toBeVisible();

  await signIn(page, email, PASSWORD);
  await expect(page).toHaveURL(/\/customer$/);
  await expect(page.getByRole('link', { name: '+ Tạo đơn vận chuyển' })).toBeVisible();

  // The access token lives in memory only; a reload must restore the session from the HttpOnly refresh cookie.
  await page.reload();
  await expect(page.getByRole('heading', { name: /Xin chào, Khách Đăng Ký/ })).toBeVisible();
  expect(await page.evaluate(() => JSON.stringify({ ...localStorage, ...sessionStorage }))).not.toContain('eyJ');

  await button(page, 'Đăng xuất').click();
  await expect(page).toHaveURL(/\/login/);
  await page.goto('/customer');
  await expect(page).toHaveURL(/\/login\?returnUrl=%2Fcustomer/);
});

test('wrong password shows an error and clears the field', async ({ page }) => {
  await signIn(page, uniqueEmail('nobody'), 'Wrong-Password-1');
  await expect(page.getByRole('alert')).toContainText('Thông tin đăng nhập không chính xác');
  await expect(page.locator('#password')).toHaveValue('');
});
