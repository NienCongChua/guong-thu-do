# Nhật ký kiểm tra Gương Thử Đồ

Ngày: 10/11/2026. Lần bàn giao đầu chưa có key. Lần chẩn đoán tiếp theo, người dùng đã cấu hình key trên server; đã kiểm tra cấp token Decart thật thành công.
Không dùng ảnh mẫu/khung hình cũ/camera gốc để đánh dấu thử đồ thành công.

## Đã hoàn tất

| Hạng mục | Bằng chứng |
| --- | --- |
| Build debug | `:app:assembleDebug` thành công; APK tại `app/build/outputs/apk/debug/app-debug.apk` |
| Phân tích Android | `:app:lintDebug` thành công; còn cảnh báo về phiên bản dependency và tài nguyên mẫu không dùng |
| Backend | Node 24.14.1; kiểm tra cú pháp server thành công |
| Test backend | `npm test`: 6/6 qua, provider giả lập, không gọi API tính phí |
| Kiểm tra lỗi SDK và bảo mật log | `:app:testDebugUnitTest`: 12/12 qua, gồm trường hợp metadata đã gặp, các lỗi bắt buộc vẫn bị từ chối và kiểm tra che token/key |
| Dependency backend | `npm audit --omit=dev --audit-level=high`: 0 lỗ hổng được báo tại thời điểm chạy |
| SDK Android | Đã tải AAR chính thức 0.7.3; POM xác nhận LiveKit 2.25.3; đọc ví dụ Kotlin đúng tag |
| Cài trên HONOR | `adb install -r`: Success, thiết bị HONOR AAP-AN00, API 37 |
| Mở ứng dụng | Activity khởi chạy; quan sát được giao diện Compose tiếng Việt, ô trang phục, hộp cấu hình với bàn phím |
| Kiểm tra trên HONOR | 2/2 instrumentation test qua: APK không yêu cầu RECORD_AUDIO; điện thoại đăng nhập vào backend laptop qua adb reverse và đọc capabilities đúng (cả hai key chưa cấu hình) |

Các test backend xác nhận: endpoint bắt buộc bearer; thiếu key từng dịch vụ có lỗi riêng;
OpenAI nhận ảnh người trước, trang phục sau; body/prompt đúng; trả chính bytes PNG response;
quota và quyền lỗi riêng; không có ảnh fallback khi response rỗng; token Decart chỉ dùng
lucy-vton-3.6, hạn kết nối 60 giây và ràng buộc phiên 120 giây. Provider của các test là
giả lập, nên không coi đây là thành công thử đồ thật.

Backend kiểm tra cục bộ (không key, bộ đếm trong RAM) đã dừng sau khi kiểm tra.
Để demo tiếp, tạo backend/.env từ .env.example, đặt mật khẩu/key riêng rồi chạy npm start theo README.

## Kiểm tra API thật và các bước còn chờ

| Hạng mục | Trạng thái / điều kiện cần |
| --- | --- |
| Cấp token Decart thật | Đã đăng nhập backend hiện chạy và gọi endpoint realtime-token: HTTP 200, nhận token có hạn và maxSessionSeconds=120; không in giá trị token. Việc cấp token chưa chứng minh phiên video có quyền/số dư để chạy |
| Video mặc thử thật / ACK ảnh | Đã tái hiện lỗi SDK: WEBRTC_SIGNALING_ERROR, SessionIdMessage thiếu server_ip/server_port. Đã xử lý tương thích metadata cũ khi CONNECTING. Sau khi cài bản sửa, người dùng xác nhận “Đã thấy video mặc thử” trên HONOR. Kết nối video API thật đã được người dùng xác nhận; chất lượng chi tiết/chuyển động vẫn cần các bài kiểm tra riêng bên dưới |
| Đổi áo trong cùng phiên | Chờ video thật, hai ảnh trang phục khác nhau |
| Chất lượng khuôn mặt, bàn tay, cảnh phòng | Chưa có video thật để quan sát |
| Thử ảnh chỉ với OpenAI key | Đã kiểm tra hợp đồng độc lập bằng provider giả lập; chờ key có quyền GPT Image và hai ảnh thật |
| Lưu kết quả OpenAI thật | Chờ API thật trả PNG để kiểm tra trước/sau và thư viện |
| Đúng chiều / màu sắc nhiều loại ảnh thực tế | Có xử lý EXIF, sRGB, PNG không mất dữ liệu; cần thử ảnh có EXIF xoay, HEIC, JPEG và PNG trên máy |
| Camera / giải phóng sau Stop và nền | DecartCameraTest qua trên HONOR: SDK nhận khung hình camera trước, không microphone, dispose sau test; không kết nối cloud. Vẫn cần kiểm tra Stop/vào nền trong phiên Decart thật |
| Hết hạn mức / mất mạng nhà cung cấp | Backend đã qua test phản hồi lỗi giả lập; cần thử tích hợp thật |

## Bài kiểm tra chấp nhận trên HONOR WIN

Chuẩn bị: dựng laptop/backend theo README, đặt key trên server, đăng nhập trong app.
Dùng ảnh trang phục thật, không dùng ảnh minh họa từ bộ test. Cố định điện thoại, phòng
đủ sáng, đứng sao cho vai/thân/váy cần thử nằm trong khung hình.
Trước khi bắt đầu phải đồng ý gửi ảnh/video tới dịch vụ được nêu trên màn hình.

### 1. Ảnh dán / rời khỏi khung hình

1. Chọn **Áo**, chọn ảnh áo sọc; đối chiếu thumbnail đúng chiều/màu.
2. Mở gương, chờ **Đang thử trực tiếp** trên video remote.
3. Bước sang trái rồi bước hẳn ra khỏi khung hình.
4. Thất bại nếu chiếc áo còn lơ lửng ở vị trí cũ, hoặc sản phẩm thu nhỏ bị vẽ lên camera.
5. Ghi thời điểm, độ trễ và lỗi quan sát được; không đánh dấu qua chỉ vì không có overlay trong mã.

Kết quả thực tế: **CHƯA THỬ — thiếu key Decart**.

### 2. Thay đúng trang phục trong cùng phiên

1. Trước khi mở gương, chọn lần lượt hai ảnh thật để chúng vào tủ đồ gần đây.
2. Mở phiên với áo sọc; trong phiên chạm ảnh áo trơn khác màu trong tủ đồ.
3. Xác nhận nhãn **Đang đổi trang phục** xuất hiện trong lúc chờ ACK.
4. Sau khi trở lại **Đang thử trực tiếp**, so sánh cổ áo, độ dài tay và họa tiết trong video.
5. Giữ **Xem bản gốc** để đối chiếu áo thực tế; thả nút phải trở về video Decart.
6. Tủ đồ đổi ảnh không khởi tạo phiên mới. Dùng camera ngoài/đưa app vào nền thì phiên phải dừng.

Kết quả thực tế: **CHƯA THỬ — thiếu key Decart**.

### 3. Chuyển động, che khuất, biến dạng, nhấp nháy

1. Nghiêng vai, xoay thân, bước trái/phải.
2. Đưa bàn tay qua ngực theo cả hai chiều; tay phải đi trước vải trong video remote.
3. Bước gần rồi lùi xa camera; theo dõi cổ áo, tay áo, đường viền và họa tiết.
4. Quan sát khuôn mặt, tóc, dáng người, đồ vật trong phòng và ánh sáng.
5. Ghi lỗi cụ thể: vùng lỗi, chuyển động gây lỗi, biến dạng/nhấp nháy, độ trễ cảm nhận.
6. Bấm Dừng, kiểm tra camera tắt và theo dõi Usage Decart để xác nhận phiên kết thúc;
   lặp lại với Home/khóa màn hình. Không dùng bật lại app để tự nối phiên trả phí.

Kết quả thực tế: **CHƯA THỬ — không có video kết quả để ghi nhận lỗi**.

### 4. Chỉ có OpenAI key

1. Để DECART_API_KEY trống, chỉ điền OPENAI_API_KEY và DEMO_PASSWORD; restart backend.
2. Kết nối lại app: Decart “Chưa có key”, OpenAI “Đã cấu hình”.
3. Chọn **Thử bằng ảnh**, chụp/chọn **Ảnh của tôi** và **Trang phục**.
4. Kiểm tra hai thumbnail; chọn Áo/Váy và bấm **Tạo ảnh mặc thử** đúng một lần.
5. Đợi ảnh API; so trước/sau, kiểm tra mặt/tay, cổ áo, tay áo, ánh sáng và nền.
6. Bấm **Lưu ảnh mặc thử**, mở thư viện Pictures/GuongThuDo, kiểm tra đúng PNG kết quả.
7. Chuyển Live: phải báo cần cấu hình Decart, không hiển thị thành công bằng camera gốc.

Kết quả thực tế: **CHƯA THỬ API THẬT — thiếu OpenAI key**.

### 5. Lỗi và vòng đời

- Nhập mật khẩu sai: báo lỗi; endpoint có phí không cho gọi không có bearer.
- Ngắt mạng sau khi mở live: phải dừng, xóa video và cho mở lại, không đứng ở trạng thái thành công.
- Chặn video remote: sau 20 giây từ ACK phải dừng với lỗi không nhận khung hình.
- Video dừng giữa phiên: sau 8 giây phải dừng, không giữ khung hình cũ như kết quả còn chạy.
- Đổi ảnh bị ACK từ chối: dừng phiên và báo lỗi; không báo đã thay đúng đồ.
- Token hết hạn trước khi connect: lấy token mới ở lần bấm mở gương tiếp theo.
- Nhà cung cấp trả 401/403/429 hoặc thiếu số dư: lỗi có ý nghĩa, không fallback.
- Tạo ảnh timeout: thông báo khả năng request đã tính phí, không tự gửi lại.
- Xoay màn hình/khóa máy/ra nền: xác nhận live được ngắt, camera được giải phóng.
