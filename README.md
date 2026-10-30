# TruckGo — Sprint 1

Nền tảng kỹ thuật khởi đầu gồm Angular SPA, REST API Spring Boot, PostgreSQL, Redis và Flyway. Sprint này chưa có đăng nhập/đăng ký, tài xế, xe, đơn hàng, báo giá hay thanh toán.

## Kiến trúc

- `frontend/`: Angular standalone, TypeScript strict, Router, HttpClient, SCSS; landing page tiếng Việt và trang trạng thái API.
- `backend/`: Java 21, Spring Boot 3.5, Actuator, JPA, Redis, Validation, Flyway; REST prefix `/api/v1`.
- PostgreSQL là nguồn dữ liệu chính, dùng named volume; Hibernate `validate`, schema do Flyway quản lý.
- Redis dùng cho cache/dữ liệu tạm trong các sprint sau, AOF được bật local.
- Nginx phục vụ SPA và proxy `/api` cùng `/actuator` về backend same-origin.

## Yêu cầu

- Docker Engine/Desktop và Docker Compose v2.
- Chạy frontend riêng: Node.js 24+ và npm.
- Chạy backend riêng: JDK 21 và Maven 3.9+. Môi trường khảo sát hiện có Java 25 nhưng chưa có Maven; Docker build dùng toolchain Java 21.

## Chạy toàn bộ bằng Docker Compose

```bash
cp .env.example .env
# Thay POSTGRES_PASSWORD trước khi dùng ngoài máy cá nhân.
docker compose up --build
```

- Frontend: http://localhost:4200
- API: http://localhost:8080/api/v1/health
- Actuator health (PostgreSQL và Redis): http://localhost:8080/actuator/health
- Swagger UI: http://localhost:8080/swagger-ui.html (hoặc http://localhost:4200/swagger-ui.html)
- OpenAPI JSON: http://localhost:8080/v3/api-docs

Compose chờ PostgreSQL và Redis healthy trước khi chạy backend; frontend đợi backend healthy. PostgreSQL và Redis dùng named volumes. Dừng bằng `docker compose down`; tránh `-v` nếu cần giữ dữ liệu.

## Chạy frontend/backend riêng

Khởi chạy PostgreSQL và Redis trước:

```bash
docker compose up -d postgres redis
```

Backend từ `backend/` (cần Maven):

```bash
export APP_ENV=dev POSTGRES_HOST=localhost POSTGRES_PORT=5432 POSTGRES_DB=truckgo
export POSTGRES_USER=truckgo_app POSTGRES_PASSWORD=change_me_local_only REDIS_HOST=localhost REDIS_PORT=6379
mvn spring-boot:run
```

Frontend từ `frontend/`:

```bash
npm install
npm start
```

Angular dev server proxy `/api` và `/actuator` tới `localhost:8080`. API base path được gom tại `ApiService` (`/api/v1`).

## API, health và migration

`GET /api/v1/health` trả response envelope chung gồm `success`, `message`, `data`, `timestamp` (ISO 8601 UTC), `path`. `GET /actuator/health` giữ định dạng Actuator. Chỉ công khai `health` và `info`; không hiển thị chi tiết dependency. Health tổng thể bao gồm DB check mặc định và Redis ping riêng, nên DOWN nếu dependency lỗi.

Swagger UI được cung cấp bằng springdoc-openapi 2.10.0, chỉ quét controller trong `com.truckgo.common.web` và các đường dẫn `/api/v1/**`. Tài liệu bật theo mặc định ở profile dev, tắt trong profile prod. Nginx Compose proxy Swagger UI và OpenAPI JSON về backend.

Mỗi request có `X-Request-ID` (tiếp nhận giá trị hợp lệ hoặc tạo mới), được ghi vào log MDC. Migration `V1__init_schema.sql` là baseline, chưa tạo bảng nghiệp vụ; Flyway chạy khi ứng dụng khởi động trên database mới.

## Cấu hình

Sao chép `.env.example` thành `.env` cho Compose. Compose truyền biến rõ ràng và backend trong container dùng hostname `postgres`/`redis`; chạy backend trực tiếp thì dùng `localhost`. Spring không tự nạp `.env` ngoài Compose. Không commit `.env`. Nếu cấu hình `REDIS_PASSWORD`, Compose áp dụng cùng mật khẩu cho Redis server và backend client.

## Lệnh kiểm tra

```bash
cd backend && mvn test
cd frontend && npm install && npm test
cd frontend && npm run build
cd .. && docker compose config
```

Backend unit test không yêu cầu service ngoài. Kiểm tra migration/health thực tế cần PostgreSQL, Redis và backend chạy; xác minh qua Actuator sau Compose startup. Kết quả lệnh đã thực thi được nêu trong bàn giao.

## Định hướng Sprint 2

- Thiết kế tài khoản, đăng ký/đăng nhập và phân quyền khách hàng/tài xế.
- Chốt schema nghiệp vụ và migration mới cho người dùng, tài xế, hồ sơ.
- Xây dựng xác thực/token, quyền truy cập và onboarding.
- Bổ sung integration test với PostgreSQL/Redis cho migration và health.
