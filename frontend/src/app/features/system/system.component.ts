import { Component, OnInit, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApiService } from '../../core/api.service';
type HealthState = 'checking' | 'up' | 'down';
@Component({ selector: 'app-system', standalone: true, imports: [RouterLink], template: `<main class="system"><a routerLink="/" class="brand">← TruckGo</a><div class="panel"><div class="eyebrow">TRUCKGO / SYSTEM STATUS</div><h1>Trạng thái hệ thống</h1><p>Theo dõi khả năng kết nối tới API nền tảng.</p><div class="status" [class.up]="state() === 'up'" [class.down]="state() === 'down'" [class.checking]="state() === 'checking'"><i></i><span>{{ label() }}</span></div><div class="details">API nền tảng <span>{{ detail() }}</span></div><button type="button" (click)="check()" [disabled]="state() === 'checking'">Kiểm tra lại</button></div></main>`, styles: [`.system{min-height:100vh;background:#f3f6ef;padding:8vh 9vw;box-sizing:border-box;font-family:Arial,sans-serif;color:#29372d}.brand{color:#506b41;text-decoration:none}.panel{background:#fff;max-width:600px;margin:12vh auto;padding:48px;box-shadow:0 18px 65px #2435220c}.eyebrow{letter-spacing:1.5px;font-size:10px;color:#819077}.panel h1{font-size:34px;margin:17px 0 8px}.panel>p{font-size:13px;color:#7c867d}.status{margin:36px 0 24px;padding:15px;background:#f1f2ed;display:flex;align-items:center;gap:10px;font-size:13px}.status i{width:8px;height:8px;border-radius:50%;background:#b1b8ad}.status.up i{background:#6da64e}.status.down i{background:#c75b4c}.status.checking i{background:#d5a94d}.details{border-top:1px solid #edf0ea;padding-top:15px;font-size:12px;color:#69746b;display:flex;justify-content:space-between}.details span{color:#344438}button{margin-top:28px;border:0;background:#293a2e;color:white;padding:13px 18px;cursor:pointer}button:disabled{opacity:.55}`] })
export class SystemComponent implements OnInit {
  private readonly api = inject(ApiService);
  readonly state = signal<HealthState>('checking');
  readonly label = () => ({ checking: 'Đang kiểm tra', up: 'Backend hoạt động', down: 'Backend không kết nối được' })[this.state()];
  readonly detail = () => this.state() === 'up' ? 'Đang phản hồi' : this.state() === 'checking' ? 'Đang gửi yêu cầu…' : 'Không thể kết nối';
  ngOnInit(): void { this.check(); }
  check(): void { this.state.set('checking'); this.api.getHealth().subscribe({ next: response => this.state.set(response.success && response.data.status === 'UP' ? 'up' : 'down'), error: () => this.state.set('down') }); }
}
