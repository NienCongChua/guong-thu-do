# Gương Thử Đồ

Ứng dụng Kotlin + Jetpack Compose, giao diện tiếng Việt, Android 10+ (minSdk 29),
compile/target SDK 37. Video AI được xử lý trên Decart; ảnh tĩnh được chỉnh sửa trên
OpenAI hoặc FASHN qua backend. RAM 12 GB không phải điều kiện chạy model tại máy.

## Giao diện và thao tác

- **Khám phá**: lối vào phòng thử, hướng dẫn ngắn và kết quả gần đây.
- **Thử ảnh**: chọn hai ảnh, loại trang phục, chế độ Nhanh/Chi tiết và API dự phòng.
- **Gương**: camera trực tiếp, đổi trang phục, chuyển Camera gốc/Mặc thử AI; nút Dừng luôn hiện trong phiên.
- **Tủ đồ**: tối đa sáu trang phục và ba kết quả gần nhất trong bộ nhớ phiên.
- **Kết nối**: đăng nhập backend, kiểm tra thời gian phản hồi và cấu hình từng dịch vụ.
- **Kết quả**: kéo thanh so sánh trước/sau, xem nhà cung cấp và thời gian tạo, lưu PNG về thư viện.

Navigation 3 giữ back stack và trạng thái nhập/cuộn riêng cho từng tab; Material
Navigation Suite đổi thanh điều hướng dưới thành rail khi cửa sổ đủ rộng.
Chuyển cảnh dùng fade/slide ngắn; điều khiển có phản hồi chạm, trạng thái chờ và lỗi.
Ảnh tạm và kết quả trong app không tồn tại sau khi process bị đóng. Bấm Lưu để giữ kết quả lâu dài.
Không lưu mật khẩu vào saved instance state. Xoay máy không chủ động dừng phiên live;
đưa app vào nền hoặc rời màn Gương sẽ dừng phiên.

## Mã nguồn và luồng dữ liệu

- `MainActivity.kt`: Activity, edge-to-edge và vòng đời ứng dụng.
- `MirrorApp.kt`: điều hướng, Photo Picker, chụp ảnh và đồng ý mở phiên live.
- `HomeScreen.kt`, `PhotoScreen.kt`, `LiveScreen.kt`, `WardrobeScreen.kt`,
  `ConnectionScreen.kt`: các màn hình chính và màn kết quả.
- `MirrorTheme.kt`, `StudioComponents.kt`: màu sắc, typography, biểu tượng và thành phần dùng chung.
- `MirrorViewModel.kt`: phiên live, gửi ảnh, ACK, theo dõi khung hình, dừng và giải phóng.
  Camera/LiveKit Room do SDK tạo trong `connect()`; app nhận preview qua
  `localStreamUpdates`. Khi SDK thử lại kết nối ban đầu, SDK có thể hủy và tạo
  Room/track mới; không truyền Room do app sở hữu vào `connect()`. Dừng phiên
  gọi `release()` để SDK giải phóng camera/Room.
- `DecartCompatibility.kt`: xử lý lỗi metadata `session_id` của SDK 0.7.3 khi server bỏ `server_ip/server_port`; chỉ bỏ qua đúng hai trường cũ lúc bắt đầu kết nối. Kết nối vẫn chờ `livekit_room_info`, ACK và khung hình thật.
- `LiveFailureMessages.kt`: hiển thị nguyên nhân lỗi theo từng bước, che key/token trong thông báo và log.
- `LiveVideo.kt`: renderer LiveKit theo ví dụ Kotlin của Decart SDK 0.7.3.
- `PhotoAsset.kt`: giải mã EXIF đúng chiều, chuyển sRGB, PNG không mất dữ liệu sau giải mã,
  tối đa cạnh 1536 px và 12 MB; lưu kết quả bằng MediaStore.
- `BackendApi.kt`: đăng nhập và gọi backend; không có khóa dịch vụ trong APK.
- `backend/src/server.mjs`: xác thực, hạn mức, cấp token Decart và tuyến ảnh tĩnh.
- `backend/src/image-providers.mjs`: OpenAI Images Edits, FASHN Try-On v1.6, timeout, hủy và chuyển API có kiểm soát.
- `backend/test/server.test.mjs`: kiểm tra hợp đồng bằng provider giả lập, không gọi API tính phí.
- `docs/KIEM_THU.md`: kết quả thực tế và bảng kiểm thử trên điện thoại.

Live: chọn ảnh trang phục → đồng ý gửi Decart → backend cấp token 60 giây →
SDK gửi ảnh tham chiếu thật và prompt, chờ ACK → camera trước xuất bản video →
callback `onRemoteStream` cung cấp `stream.videoTrack` → renderer màn hình chính.

Chỉ remote track tạo ra trạng thái **Đang thử trực tiếp**. Camera gốc chỉ hiện khi
chuẩn bị hoặc khi chọn **Camera gốc**. Không có lớp áo PNG, sticker hay ảnh sản phẩm
nào chồng lên camera. Ảnh trang phục nằm trong ô thu nhỏ và tủ đồ.

Đổi áo/váy gọi `setImage` với bytes PNG của ảnh mới, prompt tiếng Anh và
`enhance = false`, chờ ACK rồi chờ khung hình mới. Trong lúc đó video cũ được ẩn,
nhãn **Đang đổi trang phục** hiển thị. ACK và khung hình mới xác nhận đường truyền;
việc model thay đúng cổ áo/tay áo/họa tiết vẫn phải đánh giá bằng mắt trên video thật.

Phiên dừng khi bấm **Dừng**, Back, ứng dụng vào nền, không có khung hình sau ACK
20 giây, video đứng 8 giây, mất kết nối hoặc hết thời lượng demo. Dừng sẽ ngắt SDK,
hủy công việc đang chờ và dispose camera/Room. Không tự mở lại phiên tính phí khi quay lại app.
Chỉ video; APK không yêu cầu RECORD_AUDIO.

Thử ảnh: bấm **Tạo ảnh mặc thử** mới upload ảnh người (thứ nhất) và ảnh trang phục
(thứ hai) đến backend. Nếu có key OpenAI, server dùng Images Edits; nếu chỉ có key FASHN,
server dùng Try-On v1.6. Chỉ hiển thị bytes PNG dịch vụ thực sự trả về.
Thanh so sánh dùng ảnh gốc của đúng lần tạo đó. Lưu chỉ lưu toàn bộ PNG kết quả.
Đổi đầu vào bỏ kết quả hiện tại; ba kết quả gần nhất vẫn có trong Tủ đồ trong phiên.

### Độ trễ và API dự phòng

- Đăng nhập/đọc capabilities: timeout 12 giây, kết nối tối đa 5 giây; cấp token: 25 giây.
  Tạo ảnh có ngân sách tổng 180 giây trên server và 195 giây trên client, kể cả fallback.
  Các client OkHttp dùng chung connection pool; không tự replay POST.
- Ảnh đầu vào được thu nhỏ cạnh dài 1536 px, giữ EXIF/sRGB và nén PNG ngoài main thread.
- Chế độ **Nhanh** dùng OpenAI quality=low hoặc FASHN mode=performance;
  **Chi tiết** dùng medium hoặc quality. Đây là đánh đổi tốc độ/chi tiết, không bảo đảm thời gian cố định.
- Khi có cả hai key, bật **Dùng API dự phòng** để cho phép OpenAI → FASHN.
  Mặc định tắt; ứng dụng hiển thị nơi nhận hai ảnh trước khi gửi.
- Chỉ chuyển tối đa một lần khi OpenAI trả HTTP 401/403/404/402/429/5xx.
  Không chuyển khi ảnh/yêu cầu bị từ chối, response thành công hỏng, mất mạng,
  hủy hoặc timeout vì trạng thái xử lý/tính phí có thể không rõ.
- Khi primary lỗi xác định, request đã cho phép fallback có thể bỏ qua OpenAI
  trong 30 giây để tránh chờ lỗi lặp. Request không cho phép fallback vẫn dùng OpenAI.
- FASHN tạo đúng một prediction, sau đó chỉ poll mã đó mỗi giây. Nhận base64 PNG,
  không tải ảnh từ URL không kiểm soát. Không có chuỗi tạo lại trả phí.
- Hủy sẽ ngắt HTTP/polling, không khẳng định nhà cung cấp đã hủy công việc hoặc hoàn tiền.
  Kết quả đến muộn sau hủy không thay đổi giao diện. Gương lỗi có nút chuyển sang thử ảnh;
  không giả lập FASHN như một dịch vụ video trực tiếp.

## Chạy backend trên laptop

Cần Node.js 24+. Node 24 có thể in cảnh báo experimental cho SQLite tích hợp;
cơ sở dữ liệu này chỉ giữ bộ đếm sử dụng, không lưu ảnh.

Mở PowerShell trong thư mục `backend`:

```powershell
npm ci
Copy-Item .env.example .env
```

Sửa `.env` bằng trình soạn thảo:

- Đặt `DEMO_PASSWORD` riêng, ít nhất 12 ký tự.
- Có thể chỉ điền `OPENAI_API_KEY` hoặc `FASHN_API_KEY` để chạy Thử bằng ảnh.
- Có thể chỉ điền `DECART_API_KEY` để chạy Gương trực tiếp.
- Có thể điền nhiều key. Có cả OpenAI và FASHN thì app hiện tùy chọn dự phòng.
- Thêm `FASHN_API_KEY` vào `backend/.env` để bật FASHN; mẫu có trong `.env.example`.
  Không cần thêm SDK hay key vào Android.
- Không đưa file `.env` vào Git hoặc APK. `.env.example` có các key để trống.
- `OPENAI_IMAGE_MODEL=gpt-image-1.5` là mặc định hỗ trợ nhiều ảnh và
  `input_fidelity=high`. Tài khoản phải có quyền sử dụng model.
  Cũng hỗ trợ `gpt-image-2`, `gpt-image-2.5-sunburst`, `gpt-image-2.5-flare`;
  server không gửi `input_fidelity` cho các model đó.
- Đặt số dư và giới hạn chi phí trong tài khoản nhà cung cấp cho buổi demo.

```powershell
npm start
```

Mặc định server nghe `127.0.0.1:8787`. Khởi động lại server sau khi đổi key.
Trong app, mở **Kết nối**, nhập địa chỉ backend và mật khẩu demo, bấm **Kết nối**.
Trạng thái “Đã cấu hình” chỉ nói server có biến môi trường; quyền và số dư chỉ xác định
được khi nhà cung cấp xử lý yêu cầu thật. App chỉ lưu địa chỉ, bearer token ở bộ nhớ;
sau khi process bị đóng cần đăng nhập lại.

## Kết nối HONOR WIN bằng USB (khuyến nghị cho demo)

1. Trên HONOR, bật Tùy chọn nhà phát triển → Gỡ lỗi USB, cắm USB và chấp nhận máy tính.
2. Trong terminal có Android SDK Platform Tools:

```powershell
adb devices -l
adb -s <SERIAL_HONOR> reverse tcp:8787 tcp:8787
adb -s <SERIAL_HONOR> install -r app\build\outputs\apk\debug\app-debug.apk
adb -s <SERIAL_HONOR> shell am start -n com.example.myapplication/.MainActivity
```

3. Chạy backend trên laptop. Trong app nhập `http://127.0.0.1:8787` và mật khẩu demo.
4. Cấp quyền camera khi chụp/mở gương. Chọn từ thư viện bằng Photo Picker không cần quyền toàn bộ thư viện.
5. Điện thoại cần Internet đến Decart qua WebSocket/WebRTC. Laptop cần Internet đến
   OpenAI/Decart để xử lý API và cấp token; `adb reverse` chỉ chuyển kết nối backend.
6. Nếu tháo/cắm lại USB, có thể cần chạy lại `adb reverse`.

Với Wi-Fi: cho điện thoại và laptop cùng LAN; đổi `HOST=0.0.0.0` trên server,
dùng `http://<IPv4-laptop>:8787` trong bản debug và cho phép cổng này qua firewall
chỉ trên mạng riêng. Chỉ dùng cách HTTP này cho demo trong mạng tin cậy.
Bản release yêu cầu backend HTTPS; cleartext chỉ được bật trong manifest debug.

## Build

Android Studio có SDK 37 và JDK phù hợp với Gradle/AGP của dự án (Gradle wrapper 9.6,
AGP 9.5.1). Compose compiler 2.2.10 khớp Kotlin tích hợp của cấu hình build này.
Sync Gradle rồi chạy:

```powershell
.\gradlew.bat :app:assembleDebug :app:lintDebug
```

Nếu terminal chưa có JAVA_HOME, trỏ đến JBR/JDK của Android Studio.
Trong môi trường phát triển đã dùng `D:\Android\jbr`.

APK: `app/build/outputs/apk/debug/app-debug.apk`.

Mặc định APK chỉ có backend URL `http://127.0.0.1:8787`, có thể sửa ở màn Kết nối
hoặc build với `-PbackendUrl=https://your-backend.example`. Không nhúng mật khẩu,
DECART_API_KEY hay OPENAI_API_KEY vào Gradle/BuildConfig.

SDK Android chính thức được ghim
`com.github.DecartAI:decart-android:0.7.3` từ JitPack. Đã đọc ví dụ Kotlin đúng tag
0.7.3; registry của tag đó chưa định nghĩa hằng cho 3.6, nên dùng
`RealtimeModel("lucy-vton-3.6", "/v1/stream", 30, 1280, 720)` với tên model chính xác.
Không dùng alias latest hoặc thay model khác. Camera không mirror đầu vào để giữ
hướng chữ/họa tiết; local và remote render cùng chiều, không lật ảnh trang phục.

## Xác thực và giới hạn sử dụng

Backend tối giản dành cho một người vận hành buổi demo:

- `POST /api/login`: mật khẩu demo, so sánh hash bằng timingSafeEqual; tối đa 5 lần/phút/IP.
- Bearer ngẫu nhiên 256 bit, hết hạn 8 giờ; cả hai endpoint tính phí và capabilities
  đều bắt buộc bearer. Tắt trust proxy; không tin X-Forwarded-For từ client.
- Giới hạn chung 30 request/phút/IP; cấp live token tối đa 3 lần/phút/IP;
  tạo ảnh tối đa 2 lần/phút/IP và một yêu cầu tạo ảnh đang xử lý.
- Bộ đếm SQLite bền qua restart: mặc định 6 lần tạo ảnh/ngày và 10 token live/ngày,
  tính theo UTC, dùng chung cho buổi demo; đếm cả yêu cầu đã gửi nhưng thất bại để hạn chế lặp trả phí.
- Token Decart hết hạn kết nối sau 60 giây, giới hạn model duy nhất `lucy-vton-3.6`,
  yêu cầu ràng buộc phía Decart `maxSessionDuration=120` giây mặc định.
  App cũng có timer tương ứng. Hết hạn token không được dùng thay cho việc ngắt phiên:
  phiên đang chạy luôn được ngắt bằng SDK khi Dừng/vào nền.
- `LIVE_MAX_SECONDS` tối đa 600, giới hạn ảnh/token tối đa 1000 trong cấu hình để tránh sai số nhập.
- Không tự lặp lại một POST tạo ảnh. Chỉ fallback tối đa một lần sang FASHN khi
  client chủ động cho phép; một thao tác có thể gửi tới hai nhà cung cấp và phát sinh
  phí tương ứng. Bộ đếm demo tính một thao tác, kể cả thất bại; không phải hóa đơn provider.
  Tổng ngân sách xử lý ảnh là 180 giây. Timeout/hủy có thể vẫn đã tính phí.
- Giới hạn upload 12 MB/ảnh, hai ảnh, kiểm tra MIME và chữ ký tệp; response không cache.
- Ảnh upload/kết quả chỉ đi qua RAM backend. Bộ đếm không chứa ảnh.
  Ảnh chụp tạm nằm trong cache riêng của app; ảnh kết quả chỉ ra thư viện khi bấm Lưu.
- Khi triển khai nhiều người dùng/nhiều server: cần danh tính và quota theo tài khoản,
  kho phiên/rate limit dùng chung, reverse proxy HTTPS và giới hạn request tại proxy.
  Không dùng mật khẩu demo chung làm hệ thống tài khoản sản xuất.

## Kiểm tra

Đợt cập nhật giao diện và API dự phòng (14/11/2026):

- `npm test`: 16/16 test backend qua, gồm hợp đồng HTTP, FASHN-only, fallback có
  đồng ý, cooldown, từ chối đầu vào, timeout và hủy; provider đều giả lập.
- `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest`:
  build/lint qua, 12 unit test qua.
- `:app:connectedDebugAndroidTest`: 5 test qua trên HONOR và 5 test qua trên
  emulator, gồm điều hướng và khôi phục bản nháp sau Activity recreation.
  Mỗi thiết bị bỏ qua 1 test đăng nhập backend thật vì không truyền thông tin đăng nhập.
- Espresso 3.7.0, AndroidX Test runner 1.7.0 và JUnit extension 1.3.0 thay bản cũ để
  tương thích API nhập liệu trên điện thoại.
- Chưa đo thời gian tạo ảnh thật hoặc đánh giá chất lượng kết quả từ FASHN.
  Không sử dụng API tính phí trong đợt kiểm tra này.

```powershell
cd backend
npm test
npm audit --omit=dev
```

Các test backend dùng provider giả lập trong bộ nhớ; không chứng minh chất lượng mặc thử.
`DecartReconnectTest` dùng WebSocket localhost: lần bắt tay đầu trả 503, lần thử lại
phải có Room/track mới và camera phát khung hình. Test này không cấp token, gửi ảnh
hay mở phiên Decart tính phí. Đã chạy thành công trên HONOR.
Đã chạy thêm 2 instrumentation test trên HONOR: kiểm tra APK không xin quyền microphone
và đăng nhập/đọc capabilities từ backend laptop thật qua USB. Test kết nối là tùy chọn:
khi không truyền `backendUrl` và `demoPassword`, nó được bỏ qua.
Có thể chạy lại khi backend demo đang mở (PowerShell phải đặt dấu nháy quanh tham số -P):

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.backendUrl=http://127.0.0.1:8787' '-Pandroid.testInstrumentationRunnerArguments.demoPassword=<MAT_KHAU_DEMO>'
```

Dùng `docs/KIEM_THU.md` cho các bước API thật, kiểm tra ảnh dán, đổi trang phục,
che khuất và chuyển động.

## Tài liệu nguồn đã đối chiếu

- [SDK Android chính thức](https://github.com/DecartAI/decart-android/tree/0.7.3)
- [Ví dụ renderer Kotlin 0.7.3](https://github.com/DecartAI/decart-android/blob/0.7.3/sample/src/main/java/ai/decart/sample/LiveKitVideoView.kt)
- [Lucy Virtual Try-On 3.6](https://platform.decart.ai/models/lucy-vton)
- [Ví dụ client token phía server](https://github.com/DecartAI/tryon-examples)
- [OpenAI Images Edits](https://developers.openai.com/api/reference/resources/images/methods/edit)
- [FASHN Try-On v1.6](https://docs.fashn.ai/api-reference/tryon-v1-6)
- [FASHN polling và base64](https://docs.fashn.ai/api-overview/api-fundamentals)

SDK JavaScript phía backend được ghim `@decartai/sdk@0.2.14`; đã đọc
`dist/tokens/client.d.ts` và `client.js` thực sự cài đặt để kiểm tra
`expiresIn`, `allowedModels` và `constraints.realtime.maxSessionDuration`.
