import { Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';

@Component({
  selector: 'app-customer-home',
  standalone: true,
  imports: [RouterLink],
  template: `
  <main class="container">
    <div class="page-head"><div><div class="kicker">Khu vực khách hàng</div><h1>Xin chào, {{ auth.user()?.fullName }}</h1></div></div>
    <section class="card">
      <h2>Đặt xe vận chuyển</h2>
      <p class="muted">Chức năng tạo đơn vận chuyển và báo giá đang được phát triển và sẽ có mặt trong bản cập nhật tiếp theo.</p>
      <a class="btn btn-ghost" routerLink="/profile">Quản lý hồ sơ cá nhân</a>
    </section>
  </main>`,
})
export class CustomerHomeComponent { protected readonly auth = inject(AuthService); }
