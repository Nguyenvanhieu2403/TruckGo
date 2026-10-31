/** Formats VND amounts returned by the API (whole đồng). Display only: the UI never computes prices. */
const VND = new Intl.NumberFormat('vi-VN', { maximumFractionDigits: 0 });

export function formatVnd(value: number | null | undefined): string {
  return value === null || value === undefined ? '—' : `${VND.format(value)} VNĐ`;
}
