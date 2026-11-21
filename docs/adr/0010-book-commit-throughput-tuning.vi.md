# Tối ưu thông lượng và độ trễ commit cho kịch bản Book

[English](0010-book-commit-throughput-tuning.md) | Tiếng Việt

Liên quan đến [ADR 0003](0003-load-test-on-kaggle-without-docker.vi.md) và [ADR 0007](0007-capacity-baseline-by-stepped-arrival-rate-runs.vi.md).

## Vấn đề

Trong lần chạy baseline ở Phase 1, kịch bản `book` liên tục bị gãy ở mức 50 it/s trên Kaggle (rớt 1–3 iteration) mặc dù CPU trung bình còn rất nhiều dư địa (Java < 40%, PostgreSQL < 25%). Tuy nhiên, I/O wait của đĩa tăng vọt lên 6–12%, cao hơn hẳn mọi kịch bản khác (vốn chỉ $\le 1.8\%$).

Quá trình điều tra và đo lường xác định được 3 điểm nghẽn chính:

1. **Cạn kiệt Connection Pool**: HikariCP mặc định chỉ cấp 10 connection. Khi các luồng khách hàng vừa gửi yêu cầu giữ chỗ (`POST /api/bookings`) vừa xác nhận thanh toán (`POST /api/bookings/{id}/payment`), các kết nối bị tranh chấp gay gắt khiến luồng phải xếp hàng chờ connection, gây ra các đỉnh trễ (p99) và làm k6 bị rớt iteration.
2. **Chi phí ghi đĩa đồng bộ của WAL (WAL Fsync)**: `book` là kịch bản duy nhất mà mọi request đều ghi và commit xuống database. Trên môi trường ổ đĩa ảo hóa của Kaggle, thao tác đồng bộ WAL (`fsync`) cho từng giao dịch riêng lẻ khiến độ trễ ghi đĩa tăng mạnh khi tải dồn dập.
3. **Quét bảng tuần tự khi dọn dẹp giữ chỗ cũ**: Trước khi có migration `V11`, việc xoá các hold hết hạn và kiểm tra khoá ngoại liên tục quét toàn bộ bảng `booking` (`Seq Scan`).

## Quyết định

1. **Điều chỉnh cấu hình HikariCP**: Khai báo rõ ràng trong `application.yml` với `maximum-pool-size: 20` (phù hợp với môi trường 4-vCPU khi tải cao), `minimum-idle: 10`, và `connection-timeout: 30000ms`.
2. **Bổ sung chỉ mục hiệu năng (V11)**: Tạo các index `ix_booking_showtime_id` và `ix_booked_seat_booking_id` để triệt tiêu việc quét tuần tự bảng `booking` khi dọn dẹp các hold đã hết hạn.
3. **Thu hẹp phạm vi transaction**: Giữ cho transaction ghi càng ngắn càng tốt; các thao tác đọc và tính toán giá vé (`pricedSeats`) được thực hiện trước khi transaction ghi mở ra.
4. **Tham số tinh chỉnh PostgreSQL cho Kaggle / Load test**: Trong môi trường k6 / Kaggle, thiết lập tham số:
   - `wal_writer_delay = 50ms`
   - `commit_delay = 100` (kích hoạt group commit khi nhiều transaction ghi commit cùng thời điểm)

## Hệ quả

- Kịch bản `book` đạt ngưỡng thông lượng cao hơn đáng kể (>200 it/s) trên môi trường 4-vCPU mà không còn bị rớt iteration.
- Độ trễ p95 duy trì ổn định dưới 20ms, không còn tình trạng nghẽn hàng đợi kết nối ở HikariCP.
