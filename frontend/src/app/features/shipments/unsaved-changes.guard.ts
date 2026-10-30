import { CanDeactivateFn } from '@angular/router';

export interface LeaveConfirmable { canLeave(): boolean; }

/** Asks before leaving a page with unsaved input; the component decides what "unsaved" means. */
export const unsavedChangesGuard: CanDeactivateFn<LeaveConfirmable> = component =>
  component.canLeave() || globalThis.confirm('Bạn có thay đổi chưa lưu. Rời trang và bỏ các thay đổi này?');
