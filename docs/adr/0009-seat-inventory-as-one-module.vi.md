# Seat Inventory là một module

[English](0009-seat-inventory-as-one-module.md) | Tiếng Việt

Sửa đổi [ADR 0005](0005-showtime-cancellation-in-one-adapter-transaction.vi.md). Giữ nguyên [ADR 0001](0001-framework-free-business-layer.vi.md) và [ADR 0004](0004-seat-contention-via-unique-constraint-and-lazy-holds.vi.md).

Trước đây, Seat Inventory của một suất chiếu nằm rải rác ở cả hai module business lẫn hai adapter: adapter của suất chiếu xóa và hoàn tiền các dòng đơn đặt vé khi hủy hoặc xóa suất chiếu, adapter của đơn đặt vé khóa dòng suất chiếu khi tạo Hold, port của sơ đồ ghế được khai báo trong `business.showtime` nhưng cài đặt trong `persistence.booking`, và các fake in-memory dùng chung một map các dòng đơn đặt vé. Mỗi chiến lược tranh chấp ghế của phase 2 cần một chỗ duy nhất thấy được mọi thay đổi lên một ghế, nên giờ mọi thay đổi đó đi qua một port, `SeatInventory`, trong `business.showtime.inventory`:

| Thao tác | Làm gì trong một transaction | Kết quả |
|---|---|---|
| `hold` | `SELECT … FOR SHARE` trên suất chiếu trước tiên, xóa các Hold hết hạn, insert đơn đặt vé; thua deadlock thì thử lại một lần | `Held`, `SeatsTaken`, `ShowtimeCancelled`, `ShowtimeGone`, `CustomerHasPending`, `Busy` |
| `pay` | conditional update sang Confirmed | `Paid`, `AlreadyPaid`, `NotPayable`, `Gone` |
| `abandon` | conditional delete một đơn Pending | `Abandoned`, `NotPending`, `Gone` |
| `statuses` | đọc các ghế HELD và SOLD | map từ ghế sang trạng thái |
| `cancel` | ba câu lệnh của ADR 0005, đúng thứ tự của nó | `Cancelled`, `NotCancellable` |
| `delete` | xóa các Hold hết hạn, rồi xóa suất chiếu | `Deleted`, `HasBookings` |

Quy tắc vẫn là một use case, một method của repository, một transaction. Các kết quả là sealed interface gồm các record, nên business layer vẫn không phụ thuộc framework, và các service chuyển mỗi kết quả thành đúng exception và message mà client vẫn nhận. Khi một conditional update hoặc delete không thay đổi dòng nào, adapter đọc lại dòng đó **trong cùng transaction** và trả về lý do. Với READ COMMITTED, lần đọc này thấy được một lượt thanh toán đồng thời mà câu update đã phải chờ. Một câu lệnh duy nhất dùng data-modifying CTE thì không thấy: câu select bên ngoài dùng chung snapshot được lấy trước lúc chờ. Ngoại lệ là xung đột ghế của `hold`: PostgreSQL hủy transaction khi vi phạm unique, nên các ghế đã bị lấy vẫn được đọc sau khi rollback, như trước.

`Booking`, `BookedSeat`, `BookingStatus` và `SeatStatus` được chuyển vào package inventory. `BookingRepository` chỉ còn các thao tác đọc, `ShowtimeRepository` bỏ `cancel` và `deleteById`, và `SeatOccupancyReader` bị xóa. Phía sau port là một adapter duy nhất, `JpaSeatInventory`, nằm cạnh `BookingEntity`. Fake duy nhất của nó, `InMemorySeatInventory`, sở hữu map đơn đặt vé duy nhất và thay đổi suất chiếu qua hai helper dành cho test của `InMemoryShowtimes`.

Quy tắc Hold còn hiệu lực giờ chỉ được viết một lần bằng Java (`Booking.statusAt`, `Booking.occupiesSeatsAt`) và một lần bằng SQL (`HoldSql.LIVE_HOLD`, `LAPSED_HOLD`, `OCCUPIES`), mọi câu truy vấn đều ghép từ các đoạn này. Ghế của một đơn Refunded được tính là SOLD, đúng với việc unique constraint vẫn không cho ai khác đặt chúng. Trước đây chúng được tính là HELD cho đến khi hết thời gian Hold, rồi thành trống (mục B2 trong sổ vấn đề).

**Sửa đổi ADR 0005.** Transaction của việc hủy suất chiếu chuyển từ `ShowtimeRepository.cancel` sang `SeatInventory.cancel` (`JpaSeatInventory.cancel`). Ba câu lệnh và thứ tự của chúng không đổi, và mọi lượt `hold` vẫn bắt đầu bằng câu `FOR SHARE`. Những gì còn lại ADR 0005 nói vẫn đúng.

## Hệ quả

- Mỗi chiến lược tranh chấp ghế sau này là một cách cài đặt khác của `hold`, hoặc một decorator bọc port. `pay`, `abandon`, `statuses`, `cancel` và `delete` dùng chung. Unique constraint vẫn là lớp bảo vệ cuối cùng (ADR 0004). Xem [ADR 0010](0010-seat-contention-strategies-benchmark-comparison.vi.md) về kết quả so sánh thực nghiệm giữa `unique`, `showtime_lock`, `seat_rows` và `single_writer`.
- Giữa các module business: `business.booking` phụ thuộc `business.showtime`, không bao giờ theo chiều ngược lại. Bên trong module suất chiếu, `inventory` dùng `Cancellation` còn `ShowtimeService` dùng port, nên hai package phụ thuộc lẫn nhau. Một quy tắc kiểm tra vòng phụ thuộc phải coi `business.showtime..` là một slice.
- Xóa suất chiếu giờ là một câu native `delete from showtime`, không còn câu select mà `deleteById` của Spring Data chạy trước. Lớp bảo vệ `fk_booking_showtime` và mã `409` của nó không đổi.
- Port không giữ trạng thái in-memory nào, nên quy tắc 8 không áp dụng cho nó.
