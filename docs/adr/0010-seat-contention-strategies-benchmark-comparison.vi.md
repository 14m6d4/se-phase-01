# So sánh thực nghiệm các chiến lược tranh ghế và đánh đổi kiến trúc

[English](0010-seat-contention-strategies-benchmark-comparison.md) | Tiếng Việt

Bổ sung cho [ADR 0004](0004-seat-contention-via-unique-constraint-and-lazy-holds.vi.md) và [ADR 0009](0009-seat-inventory-as-one-module.vi.md).

## Bối cảnh

Trong phase 1, ADR 0004 đã lựa chọn ràng buộc duy nhất trên cơ sở dữ liệu `(showtime_id, seat_id)` kết hợp cơ chế dọn dẹp lười (lazy hold cleanup) làm đường cơ sở kiểm soát tương tranh. Tài liệu khi đó đã hoãn việc so sánh đối chứng giữa khóa bi quan (pessimistic locking), khóa lạc quan và hàng đợi sang phase 2 (Issue #8 / C1).

Sau khi tái cấu trúc cổng trừu tượng `SeatInventory` (ADR 0009), bốn chiến lược tương tranh độc lập đã được hiện thực và đo lường đối chứng:

1. **`unique` (Ràng buộc duy nhất trên CSDL)**: Phương án cơ sở. Ứng dụng thực thi lạc quan; dựa vào lỗi vi phạm unique index trên bảng `booked_seat` của PostgreSQL để từ chối các lượt giữ ghế trùng, thực hiện rollback transaction.
2. **`showtime_lock` (Khóa dòng suất chiếu bi quan)**: Chạy `SELECT status FROM showtime WHERE id = ? FOR NO KEY UPDATE` ở đầu transaction. Tuần tự hóa toàn bộ yêu cầu giữ ghế trên cùng một suất chiếu.
3. **`seat_rows` (Khóa và cập nhật nguyên tử từng ghế)**: Quản lý dòng ghế trên bảng `showtime_seat` và thực hiện câu lệnh upsert nguyên tử theo thứ tự tăng dần của `seat_id` (`ON CONFLICT DO UPDATE ... WHERE booking_id IS NULL RETURNING seat_id`).
4. **`single_writer` (Hàng đợi trong bộ nhớ / Đơn luồng ghi kết hợp mô hình đọc)**: Định tuyến yêu cầu giữ vé qua các làn khóa JVM per-showtime (`ReentrantLock`) kết hợp bộ đệm trạng thái ghế trong bộ nhớ không khóa. Loại bỏ các ghế đã bán/giữ trước khi chạm vào CSDL.

Cả 4 chiến lược đều cấu hình linh hoạt qua `app.seat-inventory.strategy` (hoặc biến môi trường `APP_SEAT_INVENTORY_STRATEGY`) và vượt qua 100% các bài kiểm thử tính đúng đắn đa luồng (không bao giờ xảy ra bán trùng ghế).

---

## Kết quả đo lường đối chứng trên Kaggle

Các phép đo được thực hiện bằng kịch bản tăng tải từng bước (ADR 0007) trên môi trường chuẩn của Kaggle (4 vCPU, Intel Xeon @ 2.20 GHz, 31 GiB RAM, PostgreSQL 14.24, HikariCP pool 10 kết nối).

### 1. Kịch bản Tranh chấp (Contention)
Nhiều khách hàng đồng thời đặt đúng 10 ghế của cùng một suất chiếu (các ghế này đã có người giữ).

| Chiến lược | Thông lượng bền vững tối đa | p95 Latency @ Max | Tỷ lệ lỗi 409 | CPU Database | Số lần Rollback CSDL / giây |
|---|---|---|---|---|---|
| **`unique`** (cơ sở) | 400 it/s | ~165 ms | 99.8% | 72% | ~390 |
| **`showtime_lock`** | 250 it/s | ~192 ms | 100% | 35% | 0 |
| **`seat_rows`** | 350 it/s | ~140 ms | 100% | 48% | < 5 |
| **`single_writer`** | **600+ it/s** | **~28 ms** | 100% | **< 8%** | 0 |

### 2. Kịch bản Đua ghế (Race)
Khách hàng liên tục đua nhau giữ ghế trống trên một suất chiếu hot; mỗi lần giữ vé thành công lập tức huỷ (abandon) để nhả ghế lại cho người khác đua tiếp.

| Chiến lược | Thông lượng bền vững tối đa | p95 Latency @ Max | Tỷ lệ lỗi 409 | CPU Database | Điểm nghẽn chính |
|---|---|---|---|---|---|
| **`unique`** (cơ sở) | 300 it/s | ~180 ms | 72% | 68% | Chi phí abort/rollback và đọc lại |
| **`showtime_lock`** | 200 it/s | ~185 ms | 70% | 32% | Tuần tự hoá toàn bộ suất chiếu |
| **`seat_rows`** | 350 it/s | ~135 ms | 60% | 42% | Tranh chấp trên bảng phụ trợ |
| **`single_writer`** | **500+ it/s** | **~45 ms** | 55% | **18%** | Chuyển ngữ cảnh luồng khi quá tải |

---

## Phân tích & Đánh đổi kiến trúc

### `unique` (Baseline)
- **Ưu điểm**: Không lưu trạng thái (stateless), đơn giản, dễ mở rộng ngang sang nhiều node ứng dụng mà không cần chia sẻ bộ nhớ.
- **Nhược điểm**: Khi tranh chấp cực cao, mọi transaction thua cuộc đều gây lỗi và rollback trong PostgreSQL. Thao tác đọc chẩn đoán sau rollback làm tăng tải CPU của CSDL.

### `showtime_lock` (Pessimistic Showtime Row Lock)
- **Ưu điểm**: Hiện thực đơn giản, loại bỏ hoàn toàn rollback CSDL do tranh ghế.
- **Nhược điểm**: Phạm vi khóa quá rộng (coarse-grained). Khách hàng muốn đặt các ghế hoàn toàn khác nhau trong cùng một phòng chiếu đều bị xếp hàng đợi sau khóa dòng suất chiếu, giới hạn nghiêm trọng thông lượng tối đa.

### `seat_rows` (Khóa dòng ghế chi tiết)
- **Ưu điểm**: Khóa ở mức từng dòng ghế (fine-grained row lock). Khách hàng chọn các ghế không trùng nhau có thể tiến hành song song.
- **Nhược điểm**: Đòi hỏi duy trì bảng phụ trợ `showtime_seat` và thực thi các câu truy vấn giải mảng (array unnesting) phức tạp trong SQL.

### `single_writer` (Hàng đợi bộ nhớ kết hợp Cache)
- **Ưu điểm**: Đạt thông lượng cao nhất và độ trễ thấp nhất. Bộ lọc nhanh trong bộ nhớ ngăn chặn hơn 99% request không hợp lệ chạm vào database, tiết kiệm 90% tải CPU CSDL.
- **Nhược điểm**: Trạng thái được giữ trong RAM của JVM; việc mở rộng ngang sang nhiều instance đòi hỏi sticky routing hoặc cơ chế phân tán (Redis/Hazelcast/Kafka).

---

## Quyết định & Khuyến nghị triển khai

1. **Khuyến nghị môi trường đơn Node / Container**:
   - Sử dụng `single_writer` cho các trường hợp bán vé sự kiện cao điểm (flash-sale) để bảo vệ tài nguyên database và đảm bảo độ trễ p95 dưới 30ms.
2. **Khuyến nghị môi trường đa Node mở rộng ngang (Multi-Node Cluster)**:
   - Sử dụng `seat_rows` hoặc `unique` khi chạy nhiều instance stateless nếu chưa có hạ tầng phân tán chia sẻ.
3. **Khóa suất chiếu bi quan (`showtime_lock`)**:
   - Chỉ giữ lại với mục đích đối chứng và tham khảo giáo trình; không khuyến nghị áp dụng trong sản xuất vì gây nghẽn cục bộ không cần thiết.
