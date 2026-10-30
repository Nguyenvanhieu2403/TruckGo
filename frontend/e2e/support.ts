import { Locator, Page, expect } from '@playwright/test';

export const PASSWORD = 'Example-Password-123';

export function uniqueEmail(prefix = 'e2e'): string {
  return `${prefix}.${Date.now()}.${Math.random().toString(36).slice(2, 7)}@example.com`;
}

/** `datetime-local` value two days ahead at 08:00 (browser local time). */
export function pickupLocal(daysAhead = 2): string {
  const d = new Date(Date.now() + daysAhead * 86_400_000);
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T08:00`;
}

/** A valid 1×1 PNG. */
export const PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==', 'base64');

export const button = (page: Page, name: string): Locator => page.getByRole('button', { name, exact: true });

/** Creates a customer through the API (fast) and signs in through the UI. */
export async function signInAsNewCustomer(page: Page, name = 'Khách E2E'): Promise<string> {
  const email = uniqueEmail('customer');
  const response = await page.request.post('/api/v1/auth/register/customer', {
    data: { fullName: name, email, password: PASSWORD, confirmPassword: PASSWORD },
  });
  expect(response.status(), await response.text()).toBe(201);
  await signIn(page, email, PASSWORD);
  await page.waitForURL(/\/customer$/);
  return email;
}

export async function signIn(page: Page, identifier: string, password: string): Promise<void> {
  await page.goto('/login');
  await page.fill('#identifier', identifier);
  await page.fill('#password', password);
  await button(page, 'Đăng nhập').click();
}

export async function fillStop(page: Page, index: number, stop: { name: string; phone: string; province: string; detail: string; district?: string }): Promise<void> {
  await page.fill(`#contactName${index}`, stop.name);
  await page.fill(`#contactPhone${index}`, stop.phone);
  await page.fill(`#province${index}`, stop.province);
  if (stop.district) await page.fill(`#district${index}`, stop.district);
  await page.fill(`#addressDetail${index}`, stop.detail);
}

export async function fillItem(page: Page, index: number, item: { name: string; category: string; qty: number; kg: number; size?: [number, number, number] }): Promise<void> {
  await page.fill(`#itemName${index}`, item.name);
  await page.selectOption(`#category${index}`, item.category);
  await page.fill(`#quantity${index}`, String(item.qty));
  await page.fill(`#weight${index}`, String(item.kg));
  if (item.size) {
    await page.fill(`#lengthCm${index}`, String(item.size[0]));
    await page.fill(`#widthCm${index}`, String(item.size[1]));
    await page.fill(`#heightCm${index}`, String(item.size[2]));
  }
}
