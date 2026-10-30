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
      <p class="muted">Khai báo điểm lấy/giao, hàng hóa và yêu cầu xe trong 5 bước. Giá cước sẽ được báo ở bước tiếp theo khi tính năng báo giá ra mắt.</p>
      <div class="actions-row"><a class="btn btn-primary" routerLink="/customer/shipments/create">+ Tạo đơn vận chuyển</a>
        <a class="btn btn-ghost" routerLink="/customer/shipments">Xem đơn của tôi</a>
        <a class="btn btn-ghost" routerLink="/profile">Hồ sơ cá nhân</a></div>
    </section>
  </main>`,
})
export class CustomerHomeComponent { protected readonly auth = inject(AuthService); }
