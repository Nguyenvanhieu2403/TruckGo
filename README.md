# TruckGo — Sprint 2

Nền tảng kết nối khách hàng thuê xe tải với tài xế. Sprint 1 dựng nền tảng kỹ thuật (Angular SPA, Spring Boot, PostgreSQL, Redis, Flyway). Sprint 2 bổ sung **tài khoản, xác thực/phân quyền, hồ sơ người dùng, hồ sơ tài xế, phương tiện và quy trình duyệt của quản trị viên**.

Chưa có: tạo đơn vận chuyển, tính giá, nhận đơn, GPS, thanh toán, đánh giá (các sprint sau).

## Kiến trúc

- `frontend/`: Angular 22 standalone, TypeScript strict, Router, Reactive Forms, HttpClient, Signals; functional interceptor/guard. Không dùng UI library — dùng SCSS tự viết như Sprint 1 (UI kit chung trong `src/styles.scss`).
- `backend/`: Java 21, Spring Boot 3.5, Spring Security 6 (OAuth2 Resource Server + Nimbus JOSE cho JWT), JPA, Redis, Validation, Flyway; REST prefix `/api/v1`.
- PostgreSQL là nguồn dữ liệu chính (tài khoản, hồ sơ, xe, phiên refresh, audit log). Hibernate `validate`, schema do Flyway quản lý.
- Redis dùng cho giới hạn số lần đăng nhập.
- Nginx phục vụ SPA và proxy `/api` về backend **cùng origin**, nên cookie refresh `SameSite=Strict` hoạt động không cần CORS.

Package backend: `auth` (đăng ký/đăng nhập/refresh/logout, rate limit), `security` (JWT, SecurityFilterChain, CORS), `user`, `driver`, `vehicle`, `admin`, `common`.

## Kiến trúc xác thực

```
Đăng nhập ──► access token (JWT HS256, 15 phút) trong JSON body ──► Angular giữ trong bộ nhớ
          └─► refresh token (ngẫu nhiên 256-bit, 7 ngày) trong cookie HttpOnly, Path=/api/v1/auth
                 DB chỉ lưu SHA-256 của token (bảng auth_sessions)
```

- **Access token** — claims: `sub` (user UUID), `roles`, `sid` (session family), `iat`, `exp`, `iss`, `jti`. Không chứa email/phone/mật khẩu. Backend kiểm tra chữ ký, `iss`, `exp`; sau đó **đọc lại role và trạng thái tài khoản từ DB ở mỗi request** — khóa tài khoản có hiệu lực ngay, không chờ token hết hạn.
- **Refresh token rotation** — mỗi lần `/auth/refresh` thành công: token cũ bị đánh dấu `ROTATED` (lưu `replaced_by_session_id`), cấp token mới cùng *family*. Dòng session bị khóa `SELECT … FOR UPDATE` nên các request đồng thời với cùng token được tuần tự hóa: đúng một request thắng.
- **Phát hiện tái sử dụng** — dùng lại token đã `ROTATED`:
  - trong khoảng ân hạn `REFRESH_REUSE_GRACE` (mặc định 10 giây — trường hợp hai tab refresh cùng lúc): trả 401, không làm gì thêm; frontend thử lại một lần sau 300ms với cookie mới;
  - sau khoảng ân hạn: coi là token bị đánh cắp → thu hồi **toàn bộ family** (`REUSE_DETECTED`), log cảnh báo.
- **Logout** — thu hồi family của refresh token hiện tại và xóa cookie. **Access token đã cấp vẫn hợp lệ tới khi hết hạn (tối đa 15 phút)** vì hệ thống không dùng denylist cho access token. Frontend bỏ access token khỏi bộ nhớ ngay. Riêng tài khoản bị khóa/tạm ngưng thì access token bị từ chối ngay nhờ kiểm tra trạng thái theo từng request.
- **Đổi mật khẩu** — thu hồi mọi phiên khác (`PASSWORD_CHANGED`), giữ phiên hiện tại (nhận diện qua claim `sid`).
- **Admin khóa/tạm ngưng tài khoản** — thu hồi mọi phiên (`ACCOUNT_DISABLED`).

### CSRF và CORS

- API nghiệp vụ chỉ nhận `Authorization: Bearer`, trình duyệt không tự gửi header này → không có bề mặt CSRF; CSRF của Spring được tắt có chủ đích.
- Hai endpoint dùng cookie (`/auth/refresh`, `/auth/logout`) được bảo vệ bằng: cookie `SameSite=Strict` + path-scoped; **bắt buộc header `X-Requested-With`** (form cross-site không gửi được, script cross-origin phải qua preflight CORS); header `Origin` nếu có phải cùng origin hoặc nằm trong `CORS_ALLOWED_ORIGINS`.
- CORS chỉ cho phép origin cấu hình rõ ràng (cấm `*`), `allowCredentials=true`, header cho phép: `Authorization, Content-Type, X-Request-ID, X-Requested-With`.

### Chống dò mật khẩu

- Redis đếm (Lua `INCR`+`PEXPIRE`, atomic) theo cửa sổ cố định: **5 lần sai / identifier / 15 phút** và **30 lần thử / IP / 15 phút** → `429` + `Retry-After`. Khóa Redis dùng hash của identifier, không lưu email/phone.
- **Redis không khả dụng → fail-open** (cho phép đăng nhập, log cảnh báo): lỗi hạ tầng không khóa người dùng; BCrypt vẫn giới hạn tốc độ thử.
- Sai mật khẩu và tài khoản không tồn tại trả **cùng thông báo 401**; khi không tìm thấy tài khoản vẫn chạy BCrypt với hash giả để thời gian phản hồi tương đương. Trạng thái khóa (403) chỉ tiết lộ sau khi mật khẩu đúng.

## Vai trò và phân quyền

| Nhóm API | Quyền |
|---|---|
| `POST /auth/register/*`, `/auth/login`, `/auth/refresh`, `/auth/logout`, `GET /health`, `/actuator/health`, `/actuator/info` | Công khai |
| `/users/me/**` | Mọi người dùng đã xác thực (chỉ dữ liệu của chính mình) |
| `/drivers/me/**`, `/vehicles/**` | `DRIVER` + kiểm tra quyền sở hữu |
| `/admin/**` | `ADMIN` |

- Kiểm tra hai lớp: rule URL trong `SecurityFilterChain` và `@PreAuthorize` trên controller.
- Danh tính luôn lấy từ principal đã xác thực; request không có trường `userId`/`driverId`/`role`/`status`/`verificationStatus` (Jackson bỏ qua nếu client gửi).
- Truy cập xe của tài xế khác trả **404** (không tiết lộ UUID có tồn tại).
- `401` khi chưa xác thực/token sai/hết hạn, `403` khi thiếu quyền; luôn theo envelope chuẩn.
- Route guard ở Angular chỉ phục vụ UX; mọi quyền được backend kiểm tra độc lập.

## Danh sách API (`/api/v1`)

| Method | Path | Mô tả |
|---|---|---|
| POST | `/auth/register/customer` | Đăng ký khách hàng (201) |
| POST | `/auth/register/driver` | Đăng ký tài xế + tạo hồ sơ `NOT_SUBMITTED` trong cùng transaction |
| POST | `/auth/login` | `{identifier, password}` — email hoặc SĐT |
| POST | `/auth/refresh` | Cookie + `X-Requested-With`; xoay vòng refresh token |
| POST | `/auth/logout` | Thu hồi phiên, xóa cookie |
| GET / PATCH | `/users/me` | Xem / cập nhật họ tên, email, SĐT |
| POST | `/users/me/change-password` | Đổi mật khẩu |
| GET / PATCH | `/drivers/me` | Xem / sửa số và hạng GPLX |
| POST | `/drivers/me/submit-verification` | Gửi hồ sơ duyệt |
| POST | `/vehicles` | Đăng ký xe |
| GET | `/vehicles/my` | Xe của tôi |
| GET / PATCH | `/vehicles/{id}` | Chi tiết / cập nhật một phần |
| POST | `/vehicles/{id}/submit-verification` | Gửi xe duyệt |
| GET | `/admin/drivers/pending?page=&size=` | Hồ sơ chờ duyệt (phân trang, cũ nhất trước, size ≤ 100) |
| POST | `/admin/drivers/{id}/approve` · `/reject` | Duyệt / từ chối (`{reason}` 5–500 ký tự) |
| GET | `/admin/vehicles/pending?page=&size=` | Xe chờ duyệt |
| POST | `/admin/vehicles/{id}/approve` · `/reject` | Duyệt / từ chối |
| GET | `/admin/users?role=&status=&page=` | Danh sách người dùng |
| POST | `/admin/users/{id}/status` | `{status, reason}` — ACTIVE/SUSPENDED/LOCKED (không áp dụng cho chính mình hay ADMIN khác) |

Lỗi validation/nghiệp vụ theo trường trả `data: { "field": "thông báo" }` (400/409). Swagger UI (`/swagger-ui.html`) có scheme `bearerAuth`, chỉ bật ở profile dev.

### Quy tắc nghiệp vụ chính

- **Liên hệ**: cần ít nhất email hoặc SĐT. Email chuẩn hóa chữ thường; SĐT di động Việt Nam chuẩn hóa về E.164 (`0912 345 678` → `+84912345678`).
- **Mật khẩu**: 8–72 byte (giới hạn của BCrypt), có chữ và số; hash BCrypt (strength 10, cấu hình `BCRYPT_STRENGTH`).
- **Trạng thái tài khoản**: `ACTIVE`, `SUSPENDED`, `LOCKED`, `PENDING_VERIFICATION`. Chỉ `ACTIVE` được đăng nhập và gọi API. *Quyết định*: chưa có kênh OTP/email nên tài khoản mới là `ACTIVE` với `email_verified=false`, `phone_verified=false`; `PENDING_VERIFICATION` được dành cho luồng xác minh ở sprint sau (đổi `AuthService.INITIAL_STATUS`). Đổi email/SĐT luôn đặt lại cờ xác minh về `false`.
- **GPLX**: số 12 chữ số (`DRIVER_LICENSE_NUMBER_PATTERN`), hạng thuộc `B,B2,C1,C,CE,D,FC` (`DRIVER_ALLOWED_LICENSE_CLASSES`), unique.
- **Hồ sơ tài xế / xe**: `NOT_SUBMITTED → PENDING_REVIEW → APPROVED | REJECTED`. Không tự duyệt khi đăng ký. Đang chờ duyệt thì không sửa được (409). Sửa thông tin quan trọng của hồ sơ/xe đã duyệt → tự quay về `PENDING_REVIEW`. Admin chỉ quyết định được mục đang `PENDING_REVIEW`; lưu `reviewed_by`, `reviewed_at`, lý do; ghi audit trong cùng transaction.
- **Xe**: biển số chuẩn hóa (`51C-123.45` → `51C12345`), unique; tải trọng 1–60.000 kg, kích thước > 0 (dài ≤ 2000, rộng ≤ 400, cao ≤ 500 cm); hạn đăng kiểm phải sau hôm nay (khi tạo, sửa và gửi duyệt); `vehicle_type` là enum (`VAN, SMALL_TRUCK, MEDIUM_TRUCK, LARGE_TRUCK, CONTAINER_TRUCK, REFRIGERATED_TRUCK`). Một tài xế có nhiều xe.
- Chỉ tài xế `APPROVED` có xe `APPROVED` mới đủ điều kiện nhận đơn (áp dụng khi Sprint 3 xây nghiệp vụ đơn).

## Cơ sở dữ liệu

Migration mới: `backend/src/main/resources/db/migration/V2__create_auth_user_driver_vehicle_tables.sql` (V1 giữ nguyên). Flyway tự chạy khi backend khởi động; database Sprint 1 chỉ có V1 nên V2 được áp dụng tiếp.

| Bảng | Nội dung chính |
|---|---|
| `users` | UUID, email/phone (unique, nullable, `CHECK` ít nhất một), `password_hash`, `role`, `status`, cờ xác minh, `last_login_at`, `version` (optimistic lock) |
| `auth_sessions` | `token_hash` (SHA-256, unique), `family_id`, `expires_at`, `revoked_at`, `revoke_reason`, `replaced_by_session_id`, `last_used_at`, `user_agent` |
| `driver_profiles` | `user_id` unique, `license_number` unique, `license_class`, `verification_status`, `rejection_reason`, `submitted_at`, `reviewed_at`, `reviewed_by` |
| `vehicles` | `driver_id` → `driver_profiles`, `plate_number` unique, kích thước/tải trọng `CHECK > 0`, `registration_expiry_date`, trạng thái duyệt |
| `admin_audit_logs` | `actor_id`, `action`, `target_type`, `target_id`, `details`, `created_at` |

Mọi thời điểm là `TIMESTAMPTZ` (UTC). Có index cho email, phone, role, status, driver_id, trạng thái duyệt + `submitted_at`. Không có xóa vật lý tài khoản; vô hiệu hóa bằng `SUSPENDED/LOCKED`.

## Cấu hình

Sao chép `.env.example` → `.env`. Biến mới của Sprint 2:

| Biến | Mặc định | Ghi chú |
|---|---|---|
| `JWT_SECRET` | rỗng | Base64, ≥ 32 byte: `openssl rand -base64 48`. **Bắt buộc ở prod** (không có thì không khởi động). Ở dev để rỗng → khóa ngẫu nhiên mỗi lần khởi động (mọi phiên mất khi restart). |
| `JWT_ISSUER` | `truckgo-api` | Claim `iss`, được kiểm tra |
| `JWT_ACCESS_TOKEN_TTL` | `15m` | |
| `REFRESH_TOKEN_TTL` | `7d` | |
| `REFRESH_REUSE_GRACE` | `10s` | Khoảng ân hạn cho refresh đồng thời |
| `REFRESH_COOKIE_SECURE` | `true` (dev: `false`) | **Prod từ chối khởi động nếu `false`** |
| `REFRESH_COOKIE_SAMESITE` | `Strict` | |
| `LOGIN_MAX_FAILURES` / `LOGIN_MAX_ATTEMPTS_PER_IP` / `LOGIN_RATE_LIMIT_WINDOW` | `5` / `30` / `15m` | |
| `LOGIN_RATE_LIMIT_ENABLED` | `true` | |
| `DRIVER_LICENSE_NUMBER_PATTERN`, `DRIVER_ALLOWED_LICENSE_CLASSES` | xem trên | |
| `ADMIN_BOOTSTRAP_EMAIL` / `_PASSWORD` / `_FULL_NAME` | rỗng | Chỉ dùng cho lệnh tạo admin |

Không commit `.env` hay secret thật.

### Cookie refresh token

- **Local HTTP (dev)**: `REFRESH_COOKIE_SECURE=false`, `SameSite=Strict`. Angular dev server (`npm start`) proxy `/api` sang `localhost:8080`, Compose dùng nginx — cả hai đều cùng origin với trình duyệt nên cookie Strict được gửi bình thường.
- **Production (HTTPS)**: `APP_ENV=prod`, `REFRESH_COOKIE_SECURE=true`, giữ `SameSite=Strict`, phục vụ SPA và API cùng domain (khuyến nghị, như nginx hiện tại).
- Nếu buộc phải tách domain (ví dụ `app.example.com` và `api.example.com` — vẫn *same-site*): giữ `Strict`/`Lax`, thêm origin SPA vào `CORS_ALLOWED_ORIGINS`. Khác site hoàn toàn mới cần `SameSite=None` + `Secure=true`. **Không tắt `Secure` ở production để chữa lỗi CORS.**

## Tạo tài khoản admin đầu tiên

Không có đăng ký công khai cho ADMIN và không có admin mặc định. Dùng lệnh one-shot (không khởi động web server, idempotent, không bao giờ "nâng quyền" tài khoản không phải admin đã dùng email đó; mật khẩu ≥ 12 ký tự, có chữ và số):

```bash
# Docker Compose (database đã chạy)
docker compose run --rm \
  -e ADMIN_BOOTSTRAP_EMAIL=admin@your-domain.vn \
  -e ADMIN_BOOTSTRAP_PASSWORD='<mật khẩu mạnh>' \
  -e ADMIN_BOOTSTRAP_FULL_NAME='Quản trị viên' \
  backend --bootstrap-admin

# Hoặc chạy trực tiếp từ backend/
ADMIN_BOOTSTRAP_EMAIL=... ADMIN_BOOTSTRAP_PASSWORD=... mvn spring-boot:run -Dspring-boot.run.arguments=--bootstrap-admin
```

Nên nhập mật khẩu từ secret manager/biến shell thay vì để trong lịch sử lệnh, và đổi mật khẩu sau lần đăng nhập đầu (`/profile`).

## Chạy ứng dụng

### Docker Compose

```bash
cp .env.example .env          # đặt POSTGRES_PASSWORD; với prod đặt JWT_SECRET, APP_ENV=prod, REFRESH_COOKIE_SECURE=true
docker compose up --build
```

- Frontend: http://localhost:4200 (`/login`, `/register`)
- API: http://localhost:8080/api/v1/health · Actuator: `/actuator/health` · Swagger (dev): `/swagger-ui.html`

Dừng bằng `docker compose down` (tránh `-v` nếu cần giữ dữ liệu).

### Chạy riêng frontend/backend

```bash
docker compose up -d postgres redis

# backend/ (JDK 21 + Maven 3.9+)
export APP_ENV=dev POSTGRES_HOST=localhost POSTGRES_PORT=5432 POSTGRES_DB=truckgo
export POSTGRES_USER=truckgo_app POSTGRES_PASSWORD=change_me_local_only REDIS_HOST=localhost REDIS_PORT=6379
mvn spring-boot:run

# frontend/ (Node.js 24+)
npm install
npm start      # http://localhost:4200, proxy /api → localhost:8080
```

### Frontend

| Route | Quyền | Nội dung |
|---|---|---|
| `/register` (`?role=driver`) | khách | Đăng ký khách hàng/tài xế |
| `/login` | khách | Đăng nhập; điều hướng theo vai trò |
| `/profile` | đã đăng nhập | Hồ sơ, cập nhật, đổi mật khẩu |
| `/customer` | CUSTOMER | Khu vực khách hàng (đặt xe ở sprint sau) |
| `/driver/verification`, `/driver/profile`, `/driver/vehicles` | DRIVER | Trạng thái duyệt, GPLX, quản lý xe |
| `/admin/drivers`, `/admin/vehicles`, `/admin/users` | ADMIN | Duyệt/từ chối có bước xác nhận; danh sách người dùng |

Access token chỉ ở bộ nhớ (không localStorage/sessionStorage). Khi tải lại trang, `provideAppInitializer` gọi `/auth/refresh` một lần để khôi phục phiên trước khi guard chạy. Interceptor gắn Bearer token, khi gặp 401 thì refresh **một lần dùng chung** cho mọi request đồng thời, phát lại request đúng một lần; refresh thất bại → xóa trạng thái và về `/login`. Không refresh cho endpoint `/auth/*` hay request đã phát lại → không có vòng lặp.

## Kiểm thử

```bash
cd backend && mvn test          # unit + integration (Testcontainers PostgreSQL 17 + Redis 7, cần Docker)
cd frontend && npm test         # Vitest
cd frontend && npm run build    # production build
docker compose config -q
```

Integration test tự bỏ qua nếu không có Docker (`@Testcontainers(disabledWithoutDocker = true)`). Unit test mock Redis; integration test dùng Redis thật.

Phạm vi đã kiểm thử: hash mật khẩu; đăng ký CUSTOMER/DRIVER; bỏ qua `role: ADMIN` từ client; trùng email/phone (409) và unique/check constraint ở DB; rollback user khi insert hồ sơ tài xế thất bại (trigger lỗi giả lập); Flyway V1→V2; đăng nhập email/SĐT, sai thông tin, tài khoản khóa; rate limit 429 qua Redis; cookie `HttpOnly/Secure/SameSite/Path`; refresh rotation, token đã thu hồi, phát hiện tái sử dụng, 4 luồng refresh đồng thời chỉ 1 thành công; logout; đổi mật khẩu thu hồi phiên khác; CSRF header/Origin; CORS preflight; 401/403; CUSTOMER/DRIVER bị chặn ở `/admin/**`; tài xế không đọc/sửa/gửi duyệt xe của người khác; tài xế không tự duyệt hồ sơ/xe; luồng duyệt/từ chối + audit; admin khóa người dùng. Frontend: validate form đăng ký, đăng nhập thành công/thất bại theo vai trò, interceptor gắn token / refresh dùng chung / không lặp, route guard, trang profile tải từ API, thao tác admin bị backend từ chối (403) không làm thay đổi danh sách.

## Giới hạn còn tồn tại

- Chưa xác minh email/SĐT (OTP/link) và chưa có quên mật khẩu; đăng ký trả 409 khi email/SĐT đã tồn tại (có thể bị dùng để dò tài khoản — chấp nhận cho đến khi có luồng xác minh).
- Access token còn hiệu lực tới 15 phút sau logout (không có denylist). Tài khoản bị khóa thì bị chặn ngay.
- Rate limit theo IP dựa trên `X-Forwarded-For` do nginx đặt (`$remote_addr`). Cổng 8080 của backend đang được publish trong Compose; nếu backend lộ trực tiếp ra ngoài, header này có thể bị giả mạo — production chỉ nên mở backend sau reverse proxy tin cậy. Giới hạn theo identifier không bị ảnh hưởng.
- Chưa có upload ảnh GPLX/đăng kiểm (chờ hạ tầng lưu trữ tệp); chưa có danh mục loại xe cấu hình được.
- Bảng `auth_sessions` chưa có job dọn session hết hạn; chưa giới hạn số phiên đồng thời mỗi người dùng.
- Giao diện admin chưa có nút đổi trạng thái người dùng (API `POST /admin/users/{id}/status` đã có).
- Khóa JWT là HMAC đối xứng; nếu sau này có nhiều dịch vụ cần xác minh token, nên chuyển sang RS256/JWKS.
