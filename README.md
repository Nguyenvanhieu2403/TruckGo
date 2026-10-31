# TruckGo — Sprint 4

Nền tảng kết nối khách hàng thuê xe tải với tài xế.

- Sprint 1: nền tảng kỹ thuật (Angular SPA, Spring Boot, PostgreSQL, Redis, Flyway).
- Sprint 2: tài khoản, xác thực/phân quyền, hồ sơ người dùng, hồ sơ tài xế, phương tiện, duyệt của quản trị viên.
- Sprint 3: **quản lý đơn vận chuyển** — wizard 5 bước, nhiều điểm giao, nhiều dòng hàng, tổng trọng lượng/thể tích do máy chủ tính, yêu cầu xe, dịch vụ bổ sung, ảnh hàng hóa trên MinIO, lưu nháp/đăng/sửa/hủy/xóa nháp, lịch sử trạng thái.
- Sprint 4: **Pricing Engine** — bảng giá theo loại xe do ADMIN quản lý, tính giá theo quãng đường thực tế, khối lượng, thể tích, dịch vụ, điểm dừng, khung giờ và phụ phí khác; báo giá có phiên bản và snapshot, khách xác nhận/từ chối, ADMIN xử lý báo giá thủ công.

Chưa có: tài xế tìm/nhận đơn (Sprint 5), GPS, thanh toán, ví, đối soát, hoa hồng, chat, đánh giá.

## Kiến trúc

- `frontend/`: Angular 22 standalone, TypeScript strict, Router, Reactive Forms, HttpClient, Signals; functional interceptor/guard. Không dùng UI library — dùng SCSS tự viết như Sprint 1 (UI kit chung trong `src/styles.scss`).
- `backend/`: Java 21, Spring Boot 3.5, Spring Security 6 (OAuth2 Resource Server + Nimbus JOSE cho JWT), JPA, Redis, Validation, Flyway; REST prefix `/api/v1`.
- PostgreSQL là nguồn dữ liệu chính (tài khoản, hồ sơ, xe, phiên refresh, audit log). Hibernate `validate`, schema do Flyway quản lý.
- Redis dùng cho giới hạn số lần đăng nhập và cache danh mục đặt đơn (không bao giờ cache đơn hàng).
- MinIO (S3) lưu ảnh hàng hóa trong bucket riêng tư; PostgreSQL chỉ lưu metadata.
- Nginx phục vụ SPA và proxy `/api` về backend **cùng origin**, nên cookie refresh `SameSite=Strict` hoạt động không cần CORS.

Package backend (package-by-feature): `auth`, `security`, `user`, `driver`, `vehicle`, `admin`, `catalog` (danh mục + cache), `shipment` (đơn vận chuyển), `storage` (`StorageService` + adapter MinIO), `geo` (abstraction bản đồ), `pricing` (`engine` tính giá thuần, `route` quãng đường, `config` bảng giá, `quote` báo giá), `common`.

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

## Quản lý đơn vận chuyển (Sprint 3)

### Quy trình tạo đơn (`/customer/shipments/create`)

| Bước | Nội dung |
|---|---|
| 1. Địa điểm | Thời gian lấy hàng, hạn giao (tuỳ chọn); điểm lấy và một hoặc nhiều điểm giao: người liên hệ, SĐT, tỉnh/quận/phường, địa chỉ chi tiết, GPS tuỳ chọn, ghi chú |
| 2. Hàng hóa | Nhiều dòng hàng: tên, loại, mô tả, số kiện, kg/kiện, dài × rộng × cao; tổng tạm tính; ảnh hàng hóa (sau khi lưu nháp) |
| 3. Phương tiện | Loại xe theo tải trọng danh nghĩa (hoặc để hệ thống gợi ý), loại thùng, số xe, tải trọng/kích thước thùng tối thiểu; cảnh báo nếu hàng nặng hơn tải trọng |
| 4. Dịch vụ | Bốc/dỡ hàng, điều kiện địa điểm, đặc điểm hàng, yêu cầu khác, ghi chú — sinh tự động từ danh mục |
| 5. Xác nhận | Tổng hợp toàn bộ; giá cước hiển thị *“Chưa tính giá – sẽ được hệ thống báo giá ở bước tiếp theo”*; **Lưu nháp** hoặc **Đăng đơn** |

Wizard kiểm tra từng bước trước khi đi tiếp, giữ dữ liệu khi quay lại, chặn bấm lặp, ánh xạ lỗi từ máy chủ về đúng trường và bước, hỏi xác nhận khi rời trang có thay đổi chưa lưu.

### Trạng thái

`DRAFT, PUBLISHED, ASSIGNED, DRIVER_ARRIVED, PICKED_UP, IN_TRANSIT, DELIVERED, COMPLETED, CANCELLED, EXPIRED` được định nghĩa cùng bảng chuyển trạng thái (`ShipmentStatus`). Sprint 3 chỉ thực hiện `DRAFT → PUBLISHED`, `DRAFT → CANCELLED`, `PUBLISHED → CANCELLED`. Mọi chuyển trạng thái khác (vd. `CANCELLED → PUBLISHED`, `COMPLETED → CANCELLED`, `PUBLISHED → COMPLETED`) bị từ chối 409. Mỗi lần đổi trạng thái ghi một dòng `shipment_status_histories` (người đổi, lý do); bảng này chỉ thêm, không sửa/xóa.

- **Sửa**: chỉ đơn `DRAFT` của chính mình; `PUT` thay toàn bộ điểm dừng, hàng hóa, dịch vụ (ảnh giữ nguyên) và yêu cầu `version` đã đọc (optimistic locking, lệch phiên bản → 409). *Quyết định*: đơn đã đăng không sửa được trong Sprint 3 (theo mục 6.2); khách hủy rồi tạo đơn mới.
- **Đăng**: cần ≥ 1 điểm lấy, ≥ 1 điểm giao (điểm đầu là điểm lấy), ≥ 1 dòng hàng có đủ kích thước, tổng trọng lượng > 0, thời gian lấy hàng sau hiện tại ít nhất `SHIPMENT_MIN_PICKUP_LEAD` (15 phút), danh mục còn hiệu lực. Không gán tài xế, không tạo giá. Database cũng chặn `pickup_time < published_at`.
- **Hủy**: chỉ `DRAFT`/`PUBLISHED`, lý do 5–500 ký tự.
- **Xóa**: chỉ `DRAFT`, xóa mềm (`deleted_at`) và xóa ảnh trên MinIO sau khi transaction commit.
- Đơn của người khác luôn trả **404**; tài xế bị chặn **403** khỏi `/shipments/**` (Sprint 5 sẽ có API riêng chỉ lộ các trường cần thiết); admin xem được, không thao tác thay khách.

### Công thức hàng hóa (BigDecimal, máy chủ tính lại mỗi lần lưu)

```
Tổng trọng lượng dòng  = Số kiện × Kg/kiện                       (kg, 2 chữ số thập phân)
Thể tích 1 kiện        = Dài × Rộng × Cao / 1.000.000             (cm → m³)
Tổng thể tích dòng     = Số kiện × Thể tích 1 kiện                 (m³, 6 chữ số, làm tròn HALF_UP)
Tổng đơn               = Σ các dòng
```

Các giá trị `totalWeightKg`, `totalVolumeM3`, `customerId`, `status`, `estimatedPrice` do client gửi đều bị bỏ qua. Dòng hàng chưa có kích thước không cộng vào thể tích và phải bổ sung trước khi đăng. Ví dụ trong đề (10×20 kg 50×40×30, 2×35 kg 120×60×75, 1×60 kg 70×70×180) cho **330 kg, 2,562 m³** — có test cả backend và frontend.

### Cảnh báo phương tiện

Phản hồi đơn có `warnings` (chỉ cảnh báo, không chặn): `VEHICLE_PAYLOAD_INSUFFICIENT`, `MINIMUM_PAYLOAD_BELOW_WEIGHT`, `VEHICLE_BELOW_MINIMUM_PAYLOAD`, `VOLUME_EXCEEDS_CARGO`, `ITEM_EXCEEDS_CARGO_SIZE`, `DIMENSIONS_MISSING`. Tải trọng trong danh mục là **danh nghĩa**; hệ thống không bao giờ khẳng định một xe phù hợp hay hợp lệ. Việc ghép xe ở Sprint 5 phải kiểm tra tải trọng thực tế của xe đã duyệt và quy định pháp luật.

### Danh mục (`/catalogs/*`) và Redis

Bảng `item_categories`, `vehicle_types` (500 kg … trên 15 tấn), `vehicle_body_types`, `shipment_service_definitions` (+ `shipment_service_options`) được seed trong V3. Frontend lấy danh mục từ API, không giữ danh sách riêng; backend validate theo cùng dữ liệu. Dịch vụ có kiểu giá trị `BOOLEAN | OPTION | NUMBER | TEXT` và `exclusive_group` (vd. có/không có thang máy). Tên dịch vụ lưu vào đơn được lấy từ danh mục, không từ request.

Cache: JSON trong Redis, key `catalog:v1:*`, TTL `CATALOG_CACHE_TTL` (mặc định 1h). Admin bật/tắt mục qua `PATCH /admin/catalogs/{catalog}/{code}` → cache bị xóa **sau khi commit**; `POST /admin/catalogs/cache/evict` dùng khi sửa danh mục trực tiếp trong DB. Redis lỗi → đọc thẳng PostgreSQL, đơn hàng không bị ảnh hưởng.

### Ảnh hàng hóa và lưu trữ

- `StorageService` (put/get/delete) với `MinioStorageService` (MinIO Java SDK, mọi endpoint S3) và `DisabledStorageService` khi `STORAGE_PROVIDER=none` — lúc đó upload trả **503**, không giả thành công.
- Chỉ chủ đơn, chỉ khi `DRAFT`, tối đa 10 ảnh/đơn, 5 MB/ảnh (413). Định dạng xác định bằng **magic bytes** (JPEG/PNG/WebP), không dựa vào tên hay Content-Type (sai → 415).
- Object key do máy chủ sinh: `shipments/{shipmentId}/{uuid}.{ext}`; tên tệp gốc chỉ làm tên hiển thị đã làm sạch, không bao giờ dùng làm đường dẫn.
- Bucket riêng tư, tạo tự động; ảnh được trả qua `GET /shipments/{id}/attachments/{aid}/content` sau khi kiểm tra quyền (`Cache-Control: private`, `nosniff`). Client không thấy endpoint, bucket, key hay credential MinIO.
- Upload: ghi object rồi lưu metadata; lưu metadata lỗi → xóa object. Xóa: xóa metadata, xóa object sau commit; lỗi xóa object được log kèm key để dọn sau.
- nginx cho phép body tới 6 MB trên `/api/` (mặc định 1 MB sẽ chặn ảnh).
- Image MinIO chính thức không còn tải được từ Docker Hub/quay.io; Compose và Testcontainers dùng bản build MinIO của Chainguard, pin theo digest.

### Bản đồ

`GeocodingProvider` là điểm nối cho Google Maps/Mapbox/OpenStreetMap. Mặc định `app.maps.provider=none`: nhập địa chỉ thủ công, tọa độ chỉ lưu khi người dùng nhập (cả vĩ độ và kinh độ, trong phạm vi hợp lệ) — **không tự đoán tọa độ**. `full_address` được ghép từ các phần có cấu trúc; tọa độ lưu riêng cho Sprint 4.

### API đơn vận chuyển (`/api/v1`)

| Method | Path | Quyền | Mô tả |
|---|---|---|---|
| POST | `/shipments/draft` | CUSTOMER | Tạo đơn nháp (201) |
| PUT | `/shipments/{id}/draft` | Chủ đơn | Cập nhật nháp, cần `version` |
| POST | `/shipments/{id}/publish` | Chủ đơn | Đăng đơn |
| POST | `/shipments/{id}/cancel` | Chủ đơn | `{reason}` |
| DELETE | `/shipments/{id}` | Chủ đơn | Xóa mềm đơn nháp |
| GET | `/shipments/my` | CUSTOMER | `page, size, status, keyword, fromDate, toDate, sortBy (createdAt, pickupTime, shipmentCode, totalWeightKg, status), sortDirection` |
| GET | `/shipments/{id}` | Chủ đơn, ADMIN | Chi tiết: điểm dừng, hàng, dịch vụ, ảnh, lịch sử, cảnh báo, `actions` |
| POST/GET | `/shipments/{id}/attachments` | Chủ đơn (POST), chủ đơn/ADMIN (GET) | Multipart `file` |
| DELETE | `/shipments/{id}/attachments/{aid}` | Chủ đơn | |
| GET | `/shipments/{id}/attachments/{aid}/content` | Chủ đơn, ADMIN | Nội dung ảnh |
| GET | `/catalogs/item-categories`, `/vehicle-types`, `/vehicle-body-types`, `/shipment-services` | Đã đăng nhập | Danh mục |
| GET | `/admin/shipments` | ADMIN | Tất cả đơn, cùng bộ lọc |
| PATCH | `/admin/catalogs/{catalog}/{code}` | ADMIN | `{active}` |
| POST | `/admin/catalogs/cache/evict` | ADMIN | Xóa cache danh mục |

Mã đơn dạng `TG<yyMMdd>-<6 ký tự>` theo ngày giờ Việt Nam, không dùng ký tự dễ nhầm (0/O, 1/I/L), unique trong DB, sinh lại khi trùng. Thời điểm lưu UTC (`TIMESTAMPTZ`), API trả ISO-8601 `Z`; bộ lọc ngày hiểu theo `SHIPMENT_BUSINESS_ZONE` (Asia/Ho_Chi_Minh).

Frontend: `/customer/shipments` (danh sách có tìm kiếm, lọc trạng thái/ngày, sắp xếp, phân trang, skeleton, trạng thái rỗng/lỗi), `/customer/shipments/create`, `/customer/shipments/:id`, `/customer/shipments/:id/edit`; admin `/admin/shipments`, `/admin/shipments/:id` (chỉ xem).

## Định giá vận chuyển (Sprint 4)

Mọi số tiền do **backend** tính và lưu; frontend chỉ hiển thị kết quả API và không bao giờ gửi giá lên. Nếu không đủ dữ liệu tin cậy, hệ thống **không bịa** quãng đường hay giá.

### Công thức

```
Tổng = Giá mở cửa + Phí quãng đường + Phụ phí khối lượng + Phụ phí thể tích + Phụ phí dịch vụ
     + Phụ phí điểm dừng + Phụ phí khung giờ + Phụ phí khác (+ bù giá tối thiểu, + điều chỉnh của ADMIN)

Giá mở cửa        = basePrice × số xe
Phí quãng đường   = max(0, km − includedDistanceKm) × số xe × pricePerKm
Phụ phí khối lượng = max(0, tổng kg − includedWeightKg × số xe) × pricePerExtraKg
Phụ phí thể tích  = max(0, tổng m³ − includedVolumeM3 × số xe) × pricePerExtraM3
Phụ phí điểm dừng = max(0, số điểm dừng − 2) × additionalStopPrice      (ngoài 1 điểm lấy + 1 điểm giao)
Phí dạng %        = % của (giá mở cửa + phí quãng đường)
```

Phí dịch vụ dùng **danh mục dịch vụ của Sprint 3** (không tạo danh sách mới): `FIXED` (cố định), `PERCENTAGE` (% cước vận chuyển), `PER_UNIT` (× giá trị số của dịch vụ, vd. số tầng; với dịch vụ khác là × số kiện), `PER_STOP` (× số điểm dừng), `FREE`. Phí cho một lựa chọn cụ thể (vd. `LOADING_SUPPORT=TEAM`) được ưu tiên hơn phí chung; dịch vụ chưa cấu hình phí hiển thị "Miễn phí". Khung giờ áp theo giờ lấy hàng (giờ Việt Nam), hỗ trợ ngày trong tuần và khung qua nửa đêm.

**Làm tròn**: chỉ dùng `BigDecimal`; tiền tệ VND; **mỗi dòng làm tròn tới đồng (HALF_UP)** và tổng = tổng các dòng đã làm tròn, nên tổng luôn khớp chi tiết (DB còn có `CHECK` tổng = tổng các thành phần). Ví dụ đề bài (100.000 + 20 km × 8.000 + 50.000 + 30.000 + 20.000) = **360.000 VNĐ** có test ở cả unit, integration và E2E.

### Quãng đường

`RouteDistanceService.calculateDistance(stops)`:
- `MAPS_ROUTE_PROVIDER=none` (mặc định) — `ManualRouteDistanceService`: không có quãng đường → báo giá `AWAITING_MANUAL_REVIEW`; ADMIN nhập quãng đường đã xác minh.
- `MAPS_ROUTE_PROVIDER=google` — Google Directions API, chế độ lái xe, đi qua các điểm theo thứ tự (origin → waypoints → destination), cộng mọi chặng; dùng tọa độ nếu khách nhập, nếu không dùng địa chỉ có cấu trúc. Timeout `MAPS_CONNECT_TIMEOUT`/`MAPS_READ_TIMEOUT`; lỗi, không có tuyến, key bị từ chối hoặc quãng đường bất hợp lý (> `MAPS_MAX_REASONABLE_KM`) → chờ ADMIN. Bật Directions API trong Google Cloud, giới hạn key theo API và IP máy chủ, đặt key qua biến môi trường `GOOGLE_MAPS_API_KEY`; key không bao giờ được ghi log.
- Không dùng khoảng cách đường chim bay để tính giá.

### Báo giá

Trạng thái `PENDING, CALCULATED, AWAITING_MANUAL_REVIEW, ACCEPTED, REJECTED, EXPIRED, SUPERSEDED` — tách biệt với trạng thái đơn.
- Mỗi lần tính giá tạo **phiên bản mới bất biến** (`<mã đơn>-Q<n>`) kèm các dòng chi tiết và **snapshot JSON** (đơn giá, dữ liệu đầu vào, tuyến đường). Phiên bản hiện hành trước đó chuyển `SUPERSEDED`; DB bảo đảm mỗi đơn chỉ có một báo giá hiện hành. Đổi bảng giá sau đó không ảnh hưởng báo giá cũ.
- Tạo phiên bản khóa dòng shipment (`SELECT … FOR UPDATE`) nên request đồng thời không tạo trùng phiên bản.
- Cần ADMIN khi: không có quãng đường tin cậy, không có bảng giá đang hiệu lực cho loại xe, hoặc xe khách chọn có tải trọng danh nghĩa nhỏ hơn hàng. Nếu khách để "hệ thống gợi ý", chọn loại xe nhỏ nhất có bảng giá và đủ tải trọng danh nghĩa.
- Đơn phải có kích thước mọi kiện hàng và thời gian lấy hàng mới tính giá được. Chỉ đơn `DRAFT`/`PUBLISHED` được báo giá.
- Báo giá `CALCULATED` hết hạn sau `QUOTE_VALIDITY` (mặc định 24h); kiểm tra khi đọc/xác nhận/từ chối và lưu `EXPIRED`.
- `ACCEPTED`: không tính lại, không điều chỉnh; tổng được ghi vào `shipments.estimated_price` (dùng cho Sprint 5). Nếu khách **sửa đơn nháp**, báo giá hiện hành (kể cả đã xác nhận) chuyển `SUPERSEDED` — số liệu cũ giữ nguyên — và khách phải báo giá lại.
- ADMIN `manual-adjustment`: quãng đường đã xác minh và/hoặc khoản điều chỉnh có dấu (giảm giá là số âm, tổng không được âm), **lý do bắt buộc**; tạo phiên bản mới, lưu người/thời điểm/lý do trên báo giá và trong `admin_audit_logs`. `recalculate` dùng bảng giá hiện hành và giữ quãng đường đã xác minh.

### Bảng giá

Mỗi bảng giá thuộc một **loại xe** (`vehicle_types` của Sprint 3) với thời gian hiệu lực; tại một thời điểm chỉ một bảng giá đang bật cho mỗi loại xe (kiểm tra ở service và exclusion constraint `btree_gist`). Bảng giá hết hạn hoặc chưa tới hạn không được dùng. Không có giá nào được seed sẵn — ADMIN tạo tại `/admin/pricing`. Mọi thay đổi (kể cả phí dịch vụ) ghi `pricing_config_histories` với JSON trước/sau và người thực hiện. Bảng giá đang bật được cache Redis (`pricing:v1:active-configs`, TTL `PRICING_CACHE_TTL`), xóa cache sau khi commit thay đổi; Redis lỗi thì đọc PostgreSQL. Báo giá không bao giờ được cache.

### API định giá (`/api/v1`)

| Method | Path | Quyền | Mô tả |
|---|---|---|---|
| POST | `/shipments/{id}/pricing/preview` | Chủ đơn | Tính giá, tạo phiên bản mới |
| GET | `/shipments/{id}/pricing` | Chủ đơn, ADMIN | Báo giá mới nhất (`data: null` nếu chưa có) |
| GET | `/shipments/{id}/pricing/history` | Chủ đơn, ADMIN | Các phiên bản |
| POST | `/shipments/{id}/pricing/accept` · `/reject` | Chủ đơn | `reject` nhận `{reason}` tuỳ chọn |
| GET/POST | `/admin/pricing-configs` | ADMIN | Danh sách (`vehicleTypeCode`, `active`, phân trang) / tạo |
| GET/PUT | `/admin/pricing-configs/{id}` | ADMIN | Chi tiết / cập nhật (cần `version`) |
| PATCH | `/admin/pricing-configs/{id}/activate` · `/deactivate` | ADMIN | |
| GET | `/admin/pricing-configs/{id}/history` | ADMIN | Lịch sử |
| GET/POST | `/admin/pricing-service-fees` | ADMIN | `?pricingConfigId=` / tạo |
| PUT/DELETE | `/admin/pricing-service-fees/{id}` | ADMIN | Cập nhật (cần `version`) / xóa mềm |
| GET | `/admin/quotes/pending`, `/admin/quotes/{id}` | ADMIN | Báo giá chờ xử lý / chi tiết kèm snapshot |
| POST | `/admin/quotes/{id}/manual-adjustment` · `/recalculate` | ADMIN | `{distanceKm?, adjustmentAmount?, reason}` |

Frontend: mục **Báo giá** trong chi tiết đơn (tính giá, phân tích chi tiết, hạn hiệu lực, xác nhận/từ chối có hộp thoại xác nhận, lịch sử, giải thích khi chờ ADMIN, không hiển thị giá khi API lỗi); ADMIN: `/admin/pricing`, `/admin/pricing/new`, `/admin/pricing/:id` (form có validation, phí dịch vụ, lịch sử), `/admin/quotes`.

### Cấu hình bảng giá lần đầu

1. Đăng nhập ADMIN → **Bảng giá** → **+ Tạo bảng giá**: chọn loại xe, thời gian hiệu lực, đơn giá; thêm khung giờ/phụ phí khác nếu cần.
2. Trong trang bảng giá vừa tạo, thêm **phí dịch vụ** cho các dịch vụ thu phí.
3. Lặp lại cho các loại xe đang phục vụ. Loại xe chưa có bảng giá → báo giá của khách vào **Báo giá chờ xử lý**.

## Cơ sở dữ liệu

Migration mới: `backend/src/main/resources/db/migration/V2__create_auth_user_driver_vehicle_tables.sql` (V1 giữ nguyên). Flyway tự chạy khi backend khởi động; database Sprint 1 chỉ có V1 nên V2 được áp dụng tiếp.

| Bảng | Nội dung chính |
|---|---|
| `users` | UUID, email/phone (unique, nullable, `CHECK` ít nhất một), `password_hash`, `role`, `status`, cờ xác minh, `last_login_at`, `version` (optimistic lock) |
| `auth_sessions` | `token_hash` (SHA-256, unique), `family_id`, `expires_at`, `revoked_at`, `revoke_reason`, `replaced_by_session_id`, `last_used_at`, `user_agent` |
| `driver_profiles` | `user_id` unique, `license_number` unique, `license_class`, `verification_status`, `rejection_reason`, `submitted_at`, `reviewed_at`, `reviewed_by` |
| `vehicles` | `driver_id` → `driver_profiles`, `plate_number` unique, kích thước/tải trọng `CHECK > 0`, `registration_expiry_date`, trạng thái duyệt |
| `admin_audit_logs` | `actor_id`, `action`, `target_type`, `target_id`, `details`, `created_at` |

**V3** `V3__create_shipment_management_tables.sql`: `shipments` (mã đơn unique, chủ đơn, trạng thái, lịch, yêu cầu xe, tổng `NUMERIC`, giá nullable, publish/cancel/soft delete, `version`), `shipment_stops` (PICKUP/DELIVERY, `sequence_no` unique theo đơn — không giới hạn 2 điểm), `shipment_items`, `shipment_services`, `shipment_attachments` (metadata), `shipment_status_histories`, cùng các bảng danh mục và dữ liệu seed. Ràng buộc: số kiện > 0, trọng lượng ≥ 0, kích thước > 0 khi có, vĩ độ ±90, kinh độ ±180, tổng không âm, hạn giao sau giờ lấy, đơn đã đăng phải có `pickup_time ≥ published_at`, đơn hủy phải có lý do, chỉ xóa mềm đơn nháp; index theo `customer_id, created_at`, `status, pickup_time`.

**V4** `V4__create_pricing_engine_tables.sql`: `pricing_configs` (theo `vehicle_types`, thời gian hiệu lực, exclusion constraint), `pricing_service_fees` (theo `shipment_service_definitions`/options), `pricing_time_rules`, `pricing_extra_fees`, `pricing_config_histories` (JSONB), `shipment_quotes` (unique `quote_code` và `(shipment_id, quote_version)`, unique index một báo giá hiện hành/đơn, `CHECK` tổng = tổng thành phần, snapshot JSONB), `shipment_quote_items` (số tiền nguyên đồng). Tiền dùng `NUMERIC`, thời gian `TIMESTAMPTZ`; báo giá không bao giờ bị xóa vật lý.

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
| `STORAGE_PROVIDER` | `none` (Compose: `minio`) | `minio` hoặc `none` |
| `MINIO_ENDPOINT`, `MINIO_ACCESS_KEY`, `MINIO_SECRET_KEY`, `MINIO_BUCKET`, `MINIO_REGION` | — / `truckgo-private` | Backend dùng; Compose lấy từ `MINIO_ROOT_USER`/`MINIO_ROOT_PASSWORD` |
| `MINIO_ROOT_USER`, `MINIO_ROOT_PASSWORD`, `MINIO_PORT` | `truckgo_minio`, `change_me_minio_local`, `9000` | Compose; cổng chỉ bind `127.0.0.1`. Production nên tạo user riêng chỉ có quyền trên bucket thay vì dùng root |
| `SHIPMENT_BUSINESS_ZONE` | `Asia/Ho_Chi_Minh` | Ngày trong mã đơn và bộ lọc |
| `SHIPMENT_MIN_PICKUP_LEAD` | `15m` | Khoảng cách tối thiểu từ lúc đăng tới giờ lấy hàng |
| `SHIPMENT_MAX_ATTACHMENT_SIZE`, `UPLOAD_MAX_FILE_SIZE`, `UPLOAD_MAX_REQUEST_SIZE` | `5MB`, `5MB`, `6MB` | Giới hạn ảnh |
| `CATALOG_CACHE_TTL` | `1h` | TTL cache danh mục |
| `MAPS_ROUTE_PROVIDER` | `none` | `none` (ADMIN xác minh quãng đường) hoặc `google` |
| `GOOGLE_MAPS_API_KEY` | rỗng | Bắt buộc khi `google`; không commit |
| `GOOGLE_DIRECTIONS_URL`, `MAPS_CONNECT_TIMEOUT`, `MAPS_READ_TIMEOUT`, `MAPS_MAX_REASONABLE_KM` | Google mặc định, `3s`, `5s`, `3000` | |
| `QUOTE_VALIDITY` | `24h` | Thời hạn báo giá đã tính |
| `PRICING_CACHE_TTL` | `10m` | TTL cache bảng giá đang bật |

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

- Frontend: http://localhost:4200 (`/login`, `/register`, `/customer/shipments`)
- MinIO API (chỉ localhost, dùng cho công cụ): http://127.0.0.1:9000 — bucket riêng tư, ảnh chỉ đọc được qua API
- API: http://localhost:8080/api/v1/health · Actuator: `/actuator/health` · Swagger (dev): `/swagger-ui.html`

Dừng bằng `docker compose down` (tránh `-v` nếu cần giữ dữ liệu).

### Chạy riêng frontend/backend

```bash
docker compose up -d postgres redis minio

# backend/ (JDK 21 + Maven 3.9+)
export APP_ENV=dev POSTGRES_HOST=localhost POSTGRES_PORT=5432 POSTGRES_DB=truckgo
export POSTGRES_USER=truckgo_app POSTGRES_PASSWORD=change_me_local_only REDIS_HOST=localhost REDIS_PORT=6379
export STORAGE_PROVIDER=minio MINIO_ENDPOINT=http://127.0.0.1:9000 MINIO_ACCESS_KEY=truckgo_minio MINIO_SECRET_KEY=change_me_minio_local
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
cd backend && mvn test          # unit + integration (Testcontainers PostgreSQL 17 + Redis 7 + MinIO, cần Docker)
cd frontend && npm test         # Vitest
cd frontend && npm run build    # production build
docker compose config -q
```

### E2E giao diện (Playwright + Chromium)

Bộ `frontend/e2e/` điều khiển trình duyệt trên **stack thật** (nginx, API, PostgreSQL, Redis, MinIO) — không mock. Gồm: đăng ký từ trang chủ, đăng nhập, giữ phiên khi tải lại, sai mật khẩu; wizard 5 bước (chặn bước thiếu dữ liệu, thêm/xóa điểm giao, tổng 330 kg / 2,562 m³, tải ảnh và từ chối tệp giả, cảnh báo tải trọng, dịch vụ loại trừ nhau, xác nhận không có giá), đăng đơn, tìm/lọc, hủy có lý do; sửa và xóa nháp; hỏi xác nhận khi rời trang; màn hình 390px; khách khác không xem được đơn; admin chỉ xem.

```bash
docker compose up -d --build
# Admin cho test quyền quản trị (tuỳ chọn; thiếu thì test admin được skip)
docker compose run --rm -e ADMIN_BOOTSTRAP_EMAIL=admin@truckgo.local -e ADMIN_BOOTSTRAP_PASSWORD='<mật khẩu>' backend --bootstrap-admin

cd frontend
npm run e2e:install          # tải Chromium cho Playwright (một lần)
E2E_BASE_URL=http://localhost:4200 \
E2E_ADMIN_EMAIL=admin@truckgo.local E2E_ADMIN_PASSWORD='<mật khẩu>' \
npm run e2e                  # báo cáo HTML: npx playwright show-report
```

Test tự tạo tài khoản khách hàng với email ngẫu nhiên nên chạy lặp lại được trên cùng database; không nên chạy trên môi trường production.

- Định giá (`pricing.spec.ts`, `pricing-ui.spec.ts`): cần tài khoản admin. Mỗi spec tạm tắt bảng giá đang bật của một loại xe (1 tấn, 1,5 tấn), tự tạo bảng giá riêng và **bật lại bảng giá gốc khi kết thúc**, nên chạy được cả trên stack có bảng giá thật/mẫu. `pricing-ui.spec.ts` kiểm tra phân tích giá với phụ phí đêm và cuối tuần khớp số tính tay (640.175 VNĐ), từ chối/lịch sử, điều chỉnh giảm giá và xác nhận, sửa đơn sau khi xác nhận, định dạng số tiếng Việt và hiển thị 390px.
- Mỗi lượt chạy đăng nhập khoảng 30 lần từ cùng một IP. Với stack dành cho E2E, đặt `LOGIN_MAX_ATTEMPTS_PER_IP` cao (CI dùng 1000) để không bị chặn 429 khi chạy lặp; giữ giá trị mặc định cho môi trường thật.

Integration test tự bỏ qua nếu không có Docker (`@Testcontainers(disabledWithoutDocker = true)`). Unit test mock Redis; integration test dùng Redis thật.

Phạm vi đã kiểm thử: hash mật khẩu; đăng ký CUSTOMER/DRIVER; bỏ qua `role: ADMIN` từ client; trùng email/phone (409) và unique/check constraint ở DB; rollback user khi insert hồ sơ tài xế thất bại (trigger lỗi giả lập); Flyway V1→V2; đăng nhập email/SĐT, sai thông tin, tài khoản khóa; rate limit 429 qua Redis; cookie `HttpOnly/Secure/SameSite/Path`; refresh rotation, token đã thu hồi, phát hiện tái sử dụng, 4 luồng refresh đồng thời chỉ 1 thành công; logout; đổi mật khẩu thu hồi phiên khác; CSRF header/Origin; CORS preflight; 401/403; CUSTOMER/DRIVER bị chặn ở `/admin/**`; tài xế không đọc/sửa/gửi duyệt xe của người khác; tài xế không tự duyệt hồ sơ/xe; luồng duyệt/từ chối + audit; admin khóa người dùng. Frontend: validate form đăng ký, đăng nhập thành công/thất bại theo vai trò, interceptor gắn token / refresh dùng chung / không lặp, route guard, trang profile tải từ API, thao tác admin bị backend từ chối (403) không làm thay đổi danh sách.

Sprint 3 thêm (backend): tạo nháp; thiếu điểm lấy/giao/hàng bị từ chối; tổng trọng lượng/thể tích chính xác và bỏ qua giá trị client; khách A không xem/sửa/đăng/hủy/xóa đơn khách B (404); tài xế 403; admin chỉ xem; đăng thiếu dữ liệu; không đăng lại; không hủy đơn `COMPLETED`/`ASSIGNED`; không xóa đơn đã đăng; cập nhật nháp thay đúng dữ liệu con, giữ ảnh, xung đột `version`; lịch sử trạng thái; mã đơn unique (DB + retry); UTC và bộ lọc ngày giờ Việt Nam; upload sai định dạng (415), quá dung lượng (413), người khác xóa ảnh (404); object bị xóa khỏi MinIO; cache danh mục trong Redis và xóa cache; Flyway V3; các ràng buộc DB. Frontend: model form và tính tổng, wizard (kiểm tra từng bước, thêm/xóa điểm và hàng, lưu nháp, đăng đơn, sửa nháp, ánh xạ lỗi), danh sách (lọc, phân trang, lỗi, hủy), trang chi tiết, upload ảnh và lỗi.

Sprint 4 thêm: unit test Pricing Engine (từng thành phần, kết hợp, khung giờ qua đêm, làm tròn, giá tối thiểu, điều chỉnh âm, dữ liệu không hợp lệ) và provider Google (nhiều chặng, lỗi, timeout, không lộ key); integration test định giá (ví dụ 360.000, tổng khớp chi tiết, không có/hết hạn bảng giá, provider lỗi → ADMIN nhập quãng đường có audit, quyền truy cập, hết hạn, đã xác nhận không đổi, sửa đơn thay thế báo giá, đồng thời, đổi bảng giá không ảnh hưởng báo giá cũ, validation/trùng thời gian, cache); frontend (panel báo giá, form bảng giá, xử lý báo giá) và E2E luồng định giá.

## Giới hạn còn tồn tại

**Sprint 4**

- Chưa có job định kỳ chuyển báo giá sang `EXPIRED`; trạng thái hết hạn được áp dụng khi báo giá được đọc/xác nhận/từ chối.
- Mới có Google Directions; Mapbox/OSRM cần thêm adapter cho `RouteDistanceService`. Không có quota/cache kết quả tuyến đường.
- Mỗi lần khách bấm "Tính giá" tạo một phiên bản mới; chưa giới hạn tần suất.
- Đăng đơn chưa bắt buộc có báo giá đã xác nhận (giữ hành vi Sprint 3); Sprint 5 nên dùng `ACCEPTED` làm điều kiện ghép tài xế.
- Phí dịch vụ áp theo đơn, chưa phân biệt điểm lấy/điểm giao (vd. bốc ở điểm lấy, dỡ ở điểm giao).


**Sprint 3**

- Chưa tính giá cước, phụ phí dịch vụ, khoảng cách (Sprint 4); `estimated_price`/`final_price` luôn null.
- Chưa tích hợp bản đồ/geocoding; tọa độ do khách tự nhập hoặc để trống.
- Đơn đã đăng không sửa được; chưa có hết hạn tự động (`EXPIRED`) — cần job ở Sprint 5.
- Ảnh tải lên được lưu khi đơn còn nháp; ảnh của đơn đã hủy vẫn được giữ. Chưa có job dọn object mồ côi (lỗi xóa đã được log).
- Upload đọc cả tệp vào bộ nhớ (≤ 5 MB) và giữ transaction trong lúc ghi MinIO; nếu tăng giới hạn nên chuyển sang stream/presigned upload.
- Compose dùng tài khoản root của MinIO cho backend; production cần user/policy riêng cho bucket.
- E2E giao diện chỉ chạy trên Chromium và cần stack đang chạy; chưa đưa vào CI.

**Sprint 2**

- Chưa xác minh email/SĐT (OTP/link) và chưa có quên mật khẩu; đăng ký trả 409 khi email/SĐT đã tồn tại (có thể bị dùng để dò tài khoản — chấp nhận cho đến khi có luồng xác minh).
- Access token còn hiệu lực tới 15 phút sau logout (không có denylist). Tài khoản bị khóa thì bị chặn ngay.
- Rate limit theo IP dựa trên `X-Forwarded-For` do nginx đặt (`$remote_addr`). Cổng 8080 của backend đang được publish trong Compose; nếu backend lộ trực tiếp ra ngoài, header này có thể bị giả mạo — production chỉ nên mở backend sau reverse proxy tin cậy. Giới hạn theo identifier không bị ảnh hưởng.
- Chưa có upload ảnh GPLX/đăng kiểm (chờ hạ tầng lưu trữ tệp); chưa có danh mục loại xe cấu hình được.
- Bảng `auth_sessions` chưa có job dọn session hết hạn; chưa giới hạn số phiên đồng thời mỗi người dùng.
- Giao diện admin chưa có nút đổi trạng thái người dùng (API `POST /admin/users/{id}/status` đã có).
- Khóa JWT là HMAC đối xứng; nếu sau này có nhiều dịch vụ cần xác minh token, nên chuyển sang RS256/JWKS.
