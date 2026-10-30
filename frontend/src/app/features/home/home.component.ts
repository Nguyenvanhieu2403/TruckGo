import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';

@Component({ selector: 'app-home', standalone: true, imports: [RouterLink], template: `
<main>
  <header class="topbar"><a class="brand" routerLink="/" aria-label="TruckGo trang chủ"><span class="brand-mark">T</span><span>truck<span>go</span></span></a>
    <nav aria-label="Điều hướng chính"><a href="#cach-hoat-dong">Cách hoạt động</a><a href="#loai-xe">Loại xe</a><a routerLink="/he-thong">Trạng thái hệ thống</a></nav>
    <a class="nav-cta" routerLink="/dang-phat-trien">Bắt đầu <span>↗</span></a>
  </header>
  <section class="hero">
    <div class="hero-copy"><div class="eyebrow"><i></i> NỀN TẢNG VẬN TẢI THẾ HỆ MỚI</div>
      <h1>Kết nối nhu cầu vận chuyển <em>với tài xế xe tải.</em></h1>
      <p>Chuyến hàng của bạn xứng đáng có một hành trình dễ dàng hơn. TruckGo kết nối người cần vận chuyển với tài xế phù hợp, nhanh chóng và minh bạch.</p>
      <div class="actions"><a class="button button-dark" routerLink="/dang-phat-trien">Tôi cần thuê xe <span>↗</span></a><a class="button button-light" routerLink="/dang-phat-trien">Tôi là tài xế <span>↗</span></a></div>
      <div class="hero-note"><span class="note-icon">✳</span><span>Đang hoàn thiện nền tảng<br><strong>Sẵn sàng đồng hành cùng bạn</strong></span></div>
    </div>
    <div class="hero-art" aria-label="Minh họa xe tải trên cung đường vận chuyển"><div class="sun"></div><div class="route route-one"></div><div class="route route-two"></div><div class="road"></div><div class="truck"><div class="truck-box"><span>TRUCKGO</span></div><div class="truck-cab"><div class="window"></div><div class="headlight"></div></div><div class="wheel wheel-one"></div><div class="wheel wheel-two"></div></div><div class="art-label"><span>01 / 03</span><span>VẬN CHUYỂN DỄ DÀNG</span></div><div class="floating-pin">⌖ <span>Hành trình mới<br><b>Bắt đầu tại đây</b></span></div></div>
  </section>
  <section class="intro" id="cach-hoat-dong"><div class="section-kicker">01 — ĐƠN GIẢN HÓA VẬN CHUYỂN</div><div class="intro-body"><h2>Một kết nối.<br><em>Trọn hành trình.</em></h2><div><p>TruckGo giúp việc tìm xe và tìm chuyến trở nên thuận tiện hơn — để bạn tập trung vào điều quan trọng nhất.</p><a class="text-link" routerLink="/dang-phat-trien">Khám phá cách hoạt động <span>↗</span></a></div></div>
    <div class="steps"><article><span>01</span><div class="step-icon">⌕</div><h3>Chia sẻ nhu cầu</h3><p>Mô tả chuyến hàng và điểm đến của bạn.</p></article><article><span>02</span><div class="step-icon">⇄</div><h3>Kết nối phù hợp</h3><p>Tìm tài xế và loại xe phù hợp với hành trình.</p></article><article><span>03</span><div class="step-icon">↗</div><h3>Cùng lăn bánh</h3><p>Sẵn sàng cho hành trình vận chuyển thuận tiện.</p></article></div>
  </section>
  <section class="fleet" id="loai-xe"><div class="fleet-head"><div><div class="section-kicker">02 — LỰA CHỌN LINH HOẠT</div><h2>Mỗi chuyến hàng<br><em>một lựa chọn phù hợp.</em></h2></div><a class="text-link" routerLink="/dang-phat-trien">Xem các loại xe <span>↗</span></a></div><div class="vehicle-grid"><article class="vehicle"><div class="vehicle-visual pickup"><span class="vehicle-number">01</span><div class="mini-truck"><i></i></div></div><div class="vehicle-info"><div><h3>Xe tải nhỏ</h3><p>Gọn nhẹ trong phố</p></div><span>↗</span></div></article><article class="vehicle"><div class="vehicle-visual medium"><span class="vehicle-number">02</span><div class="mini-truck"><i></i></div></div><div class="vehicle-info"><div><h3>Xe tải vừa</h3><p>Linh hoạt mỗi chuyến</p></div><span>↗</span></div></article><article class="vehicle"><div class="vehicle-visual large"><span class="vehicle-number">03</span><div class="mini-truck"><i></i></div></div><div class="vehicle-info"><div><h3>Xe tải lớn</h3><p>Chở hàng quy mô lớn</p></div><span>↗</span></div></article></div></section>
  <section class="closing"><div><div class="section-kicker">TRUCKGO — VẬN CHUYỂN THÔNG MINH</div><h2>Chuyến đi tiếp theo<br>bắt đầu từ <em>một kết nối.</em></h2></div><a class="button button-dark" routerLink="/dang-phat-trien">Khám phá TruckGo <span>↗</span></a></section>
  <footer><a class="brand" routerLink="/"><span class="brand-mark">T</span><span>truck<span>go</span></span></a><span>Kết nối hành trình. Chuyển động tương lai.</span><a routerLink="/he-thong">Trạng thái hệ thống ↗</a><span>© 2026 TruckGo</span></footer>
</main>` , styleUrl: './home.component.scss' })
export class HomeComponent {}
