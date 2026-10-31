import { Component, computed, inject, signal } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { AuthService } from '../core/auth/auth.service';
import { ToastContainerComponent } from './toast-container.component';
import { NotificationBellComponent } from './notification-bell.component';

interface NavItem { path: string; label: string; exact?: boolean; }

const NAV: Record<string, NavItem[]> = {
  CUSTOMER: [{ path: '/customer', label: 'Tổng quan', exact: true }, { path: '/customer/shipments', label: 'Đơn vận chuyển' }, { path: '/profile', label: 'Hồ sơ' }],
  DRIVER: [{ path: '/driver/marketplace', label: 'Tìm đơn' }, { path: '/driver/applications', label: 'Yêu cầu của tôi' }, { path: '/driver/verification', label: 'Trạng thái duyệt' }, { path: '/driver/profile', label: 'Giấy phép' }, { path: '/driver/vehicles', label: 'Phương tiện' }, { path: '/profile', label: 'Tài khoản' }],
  ADMIN: [{ path: '/admin/drivers', label: 'Duyệt tài xế' }, { path: '/admin/vehicles', label: 'Duyệt phương tiện' }, { path: '/admin/users', label: 'Người dùng' }, { path: '/admin/shipments', label: 'Đơn hàng' }, { path: '/admin/marketplace', label: 'Marketplace' }, { path: '/admin/pricing', label: 'Bảng giá' }, { path: '/admin/quotes', label: 'Báo giá chờ xử lý' }, { path: '/profile', label: 'Tài khoản' }],
};

/** Layout for signed-in areas. Menu items depend on role but every API call is still authorised server-side. */
@Component({
  selector: 'app-shell',
  standalone: true,
  imports: [RouterOutlet, RouterLink, RouterLinkActive, ToastContainerComponent, NotificationBellComponent],
  template: `
  <div class="page">
    <header class="shell-bar">
      <a class="shell-brand" routerLink="/"><span class="mark">T</span>truck<span class="go">go</span></a>
      <button class="menu-toggle" type="button" (click)="open.set(!open())" [attr.aria-expanded]="open()" aria-controls="shell-nav">Menu</button>
      <nav id="shell-nav" [class.open]="open()" aria-label="Điều hướng tài khoản">
        @for (item of nav(); track item.path) {
          <a [routerLink]="item.path" routerLinkActive="active" [routerLinkActiveOptions]="{ exact: item.exact ?? false }" (click)="open.set(false)">{{ item.label }}</a>
        }
      </nav>
      <div class="shell-user">
        <app-notification-bell />
        <span class="who">{{ auth.user()?.fullName }}<small>{{ roleLabel() }}</small></span>
        <button class="btn btn-ghost" type="button" (click)="logout()" [disabled]="loggingOut()">Đăng xuất</button>
      </div>
    </header>
    <router-outlet />
    <app-toasts />
  </div>`,
  styles: [`.shell-bar{display:flex;align-items:center;gap:24px;padding:0 24px;min-height:68px;background:#fff;border-bottom:1px solid var(--tg-line);flex-wrap:wrap}
  .shell-brand{display:flex;align-items:center;gap:8px;font-weight:800;font-size:21px;letter-spacing:-1px;color:var(--tg-ink);text-decoration:none}.go{color:#78a648}
  .mark{display:grid;place-items:center;width:28px;height:28px;background:#8eb85a;border-radius:50% 50% 50% 8px;color:#fff;font-size:15px;font-family:Georgia,serif;font-style:italic}
  nav{display:flex;gap:4px;flex:1}nav a{padding:8px 12px;border-radius:4px;color:#4c5a4f;text-decoration:none;font-size:13px;font-weight:600}nav a.active,nav a:hover{background:var(--tg-accent-soft);color:var(--tg-dark)}
  .shell-user{display:flex;align-items:center;gap:12px}.who{display:flex;flex-direction:column;font-size:13px;font-weight:600;text-align:right}.who small{font-size:11px;color:var(--tg-muted);font-weight:500}
  .menu-toggle{display:none;margin-left:auto;font:inherit;font-size:13px;padding:8px 12px;border:1px solid var(--tg-line);background:#fff;border-radius:4px}
  @media(max-width:820px){.menu-toggle{display:block}nav{display:none;flex-basis:100%;flex-direction:column;padding-bottom:10px}nav.open{display:flex}.shell-user{flex-basis:100%;justify-content:space-between;padding-bottom:12px}.who{text-align:left}}`],
})
export class AppShellComponent {
  protected readonly auth = inject(AuthService);
  protected readonly open = signal(false);
  protected readonly loggingOut = signal(false);
  protected readonly nav = computed(() => NAV[this.auth.role() ?? ''] ?? []);
  protected readonly roleLabel = computed(() => ({ CUSTOMER: 'Khách hàng', DRIVER: 'Tài xế', ADMIN: 'Quản trị viên' } as Record<string, string>)[this.auth.role() ?? ''] ?? '');

  logout(): void {
    this.loggingOut.set(true);
    this.auth.logout().subscribe({ complete: () => this.loggingOut.set(false) });
  }
}
