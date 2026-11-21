# Cache sơ đồ ghế theo từng phòng chiếu

[English](0008-seat-layouts-cached-per-auditorium.md) | Tiếng Việt

Mỗi lần xem sơ đồ ghế và mỗi lần đặt chỗ đều phải tính giá các ghế trong phòng chiếu của suất chiếu. Ở phase 1, phòng chiếu và danh sách ghế được đọc lại từ database mỗi lần, và lần đọc đó là câu lệnh tốn nhất trong load test browse, đứng đầu cả trong load test book. Giờ `ShowtimeService` giữ danh sách ghế của từng phòng chiếu trong bộ nhớ sau lần đọc đầu tiên, trong một `ConcurrentHashMap` có khóa là id phòng chiếu. Giá vẫn được tính riêng cho từng suất chiếu theo giá gốc của nó.

Cache không bao giờ bị xóa, vì sơ đồ ghế phía sau một suất chiếu còn tồn tại thì không thể thay đổi. Phòng chiếu không có thao tác sửa, `AuditoriumService.create` luôn lưu với một id ngẫu nhiên mới, và phòng chiếu đã có suất chiếu thì không xóa được ([ADR 0006](0006-delete-guards-in-business-and-database.vi.md)). Cache chỉ được nạp từ một suất chiếu, nên mọi mục trong cache đều thuộc về một phòng chiếu đã có suất chiếu và chưa từng bị sửa. Điều này đúng với bất kỳ số instance nào: giữa các instance không có gì cần đồng bộ.

## Hệ quả

- Một tính năng sau này cho sửa danh sách ghế của phòng chiếu, hoặc dùng lại id phòng chiếu, phải xóa phòng chiếu đó khỏi cache ngay trong cùng thay đổi.
- Các bước kiểm tra sự tồn tại (`ShowtimeService.create`, `AuditoriumService.get`) vẫn đọc database, nên vẫn thấy được việc xóa.
- Một mục có thể tồn tại lâu hơn phòng chiếu của nó. Một request đã đọc suất chiếu ngay trước khi suất chiếu rồi đến phòng chiếu bị xóa có thể đọc mục đó thêm một lần: nếu là đặt chỗ thì bị foreign key của suất chiếu chặn lại (409), nếu là xem sơ đồ ghế thì trả về đúng kết quả như khi đọc ngay trước lúc xóa.
- Bộ nhớ tăng thêm một mục cho mỗi phòng chiếu từng được tính giá. Với số phòng chiếu ít ỏi của một chuỗi rạp, không đáng để dọn các mục của phòng đã xóa.
