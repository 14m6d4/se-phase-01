# Giới hạn tần suất bằng Token Bucket và Đo lường chi phí trên kịch bản Account

[English](0011-token-bucket-rate-limiting.md) | Tiếng Việt

Bổ sung cho [ADR 0001](0001-framework-free-business-layer.vi.md) và [ADR 0007](0007-capacity-baseline-by-stepped-arrival-rate-runs.vi.md).

## Bối cảnh

Nếu không có cơ chế giới hạn tần suất request (Issue #15 / S1), API sẽ đứng trước nguy cơ bị dò quét mật khẩu (brute-force) tại `/api/auth/login` và bị dội request hàng loạt gây từ chối dịch vụ (DDoS / flooding) ở cả các endpoint công khai lẫn bảo vệ.

Để bảo vệ hệ thống mà không làm suy giảm trải nghiệm của khách hàng hợp lệ, bộ lọc giới hạn tần suất theo thuật toán Token Bucket đã được triển khai tại tầng API (`RateLimitingFilter`, `TokenBucket`), tuân thủ triệt để ADR 0001 (giữ tầng business độc lập với framework).

Một tiêu chí quan trọng của S1 là đo lường chi phí suy hao (overhead) của bộ lọc dưới kịch bản tải cao `account` (1.200–1.600 it/s) nhằm chứng minh bộ lọc không làm suy giảm năng lực xử lý bền vững của hệ thống.

---

## Kiến trúc & Cấu hình

1. **Thuật toán**: Token Bucket với tốc độ nạp lười (`refillRatePerNano = refillTokens / refillDuration.toNanos()`). Tính toán lượng token khả dụng dựa trên khoảng thời gian trôi qua tính theo nano giây khi có request đến, loại bỏ hoàn toàn nhu cầu dùng background thread hẹn giờ.
2. **Khóa định danh (Key Resolution)**:
   - Endpoint xác thực (`/api/auth/**`): Phân loại theo IP của client (`auth:<ip>`), cấu hình sức chứa tối đa 200 rpm (nạp 200 token mỗi 60s) để chặn tấn công brute-force.
   - Endpoint API bảo vệ (`/api/**`): Phân loại theo tài khoản khách hàng (`user:<email>`) hoặc theo IP đối với request ẩn danh (`api:ip:<ip>`), cấu hình sức chứa tối đa 3.000 rpm (nạp 3.000 token mỗi 60s = 50 req/s duy trì cho mỗi tài khoản).
3. **Phản hồi khi vượt ngưỡng**:
   - HTTP status `429 Too Many Requests`.
   - Header `Retry-After: <giây>` được tính toán động dựa trên lượng token còn thiếu và tốc độ nạp.
   - Body chuẩn RFC 7807 `application/problem+json`.

---

## Kết quả đo lường đối chứng chi phí (Kịch bản Account)

Kịch bản `account` thực hiện `GET /api/account` trên 2.000 khách hàng đã đăng nhập độc lập. Đo lường được tiến hành trên môi trường chuẩn Kaggle (4 vCPU, 31 GiB RAM, PostgreSQL 14.24, HikariCP 10).

### Bảng so sánh: Trước và sau khi bật Rate Limiter

| Chỉ số | Baseline (Không có Filter) | Bật RateLimitingFilter | Chênh lệch / Chi phí suy hao |
|---|---|---|---|
| **Thông lượng bền vững tối đa** | 1.200 it/s (Held) | 1.200 it/s (Held) | **0% sụt giảm thông lượng** |
| **p95 Latency @ 1.200 it/s** | 18,4 ms | 19,1 ms | **+0,7 ms (+3,8% overhead)** |
| **p99 Latency @ 1.200 it/s** | 26,2 ms | 27,5 ms | +1,3 ms |
| **Bước đầu tiên bị vỡ (Broken step)** | 1.600 it/s | 1.600 it/s | Ngưỡng vỡ đồng nhất (hàng đợi Tomcat/Hikari) |
| **CPU ứng dụng @ 1.200 it/s** | 78% của 1 core | 80% của 1 core | ~2% tải CPU phụ trội cho nanoTime/CAS |
| **Tỷ lệ lỗi @ 1.200 it/s** | 0,00% | 0,00% | Không có request hợp lệ nào bị chặn nhầm |

### Vi kiểm thử hiệu năng (`TokenBucket.tryConsume`)
- Đánh giá thông lượng của `TokenBucket.tryConsume(1)` trên đa luồng:
  - Thông lượng: > 12.000.000 phép tính / giây trên kiến trúc x86-64.
  - Thời gian xử lý mỗi request: ~80 nano giây.
  - Tiêu tốn bộ nhớ: < 64 bytes cho mỗi entry bucket đang hoạt động trong `ConcurrentHashMap`.

---

## Kết luận & Quyết định

1. **Chi phí suy hao không đáng kể**: Độ trễ gia tăng dưới 1 ms (< 4%) và hoàn toàn không làm suy giảm thông lượng bền vững tối đa 1.200 it/s của hệ thống.
2. **Ngưỡng chặn an toàn**:
   - `auth: 200 rpm`: Đủ chặt để ngăn chặn vét cạn mật khẩu, đồng thời đủ rộng để người dùng và các retry mạng hoạt động bình thường.
   - `api: 3000 rpm`: Cho phép người dùng thao tác nhanh (lên tới 50 request/giây trên một tài khoản) mà không bị từ chối sai (false positive).
