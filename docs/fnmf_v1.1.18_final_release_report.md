# BÁO CÁO TỔNG KẾT PHÁT HÀNH HỆ THỐNG FNMF PHIÊN BẢN v1.1.18
**Dự án:** FNMF - Nền Tảng Giao Dịch Tài Chính Mô Phỏng & Dự Báo AI  
**Người thực hiện:** Đặng Đức Khôi (Kỹ sư Backend, Data Pipeline & Tích Hợp Hệ Thống)  
**Thời gian hoàn tất:** 12/09/2026  
**Trạng thái phát hành:** ĐÃ PHÁT HÀNH (RELEASED)  

---

## 1. TỔNG QUAN KẾT QUẢ TRIỂN KHAI (EXECUTIVE SUMMARY)

Đợt 2 của quy trình phát hành hệ thống FNMF v1.1.18 đã được triển khai theo đúng kế hoạch kỹ thuật, đáp ứng các tiêu chuẩn về an toàn bảo mật, tính toàn vẹn dữ liệu, tài liệu kỹ thuật tiếng Việt, kiểm thử tự động, triển khai đám mây và phát hành tệp nhị phân Android đã ký số.

### Các kết quả đạt được:
1. **Chuẩn hóa tài liệu kỹ thuật:** Hệ thống tài liệu trên cả hai kho mã nguồn (`llm-gateway3` và `FNMF_Manh_Test`) được chuẩn hóa bằng tiếng Việt thuần túy, không sử dụng biểu tượng cảm xúc trang trí, mô tả chính xác kiến trúc cơ sở dữ liệu PostgreSQL 10 bảng, luồng dữ liệu và tài liệu kỹ thuật 12 phần dành cho hội đồng đánh giá.
2. **Kiểm thử tự động trước phát hành (Preflight Checks):**
   - Backend: 184/184 unit và integration tests đạt kết quả thành công (0 lỗi, 0 thất bại).
   - Android: Bộ kiểm thử đơn vị cục bộ đạt kết quả thành công; 10 bài kiểm thử có kết nối thiết bị thật (Connected Tests) trên điện thoại Samsung thực tế xác nhận hoạt động ổn định của AndroidKeyStore AES-GCM và quy trình di chuyển khóa phiên.
   - Tệp nhị phân phát hành: `apksigner` xác nhận APK Signature Scheme v2: true, APK Signature Scheme v3: false (v2 phù hợp với minSdk 24), `android:debuggable = false`, `minSdkVersion = 24`, `targetSdkVersion = 35`, chứng chỉ phát hành chính thức.
3. **Triển khai Backend lên Railway Production:**
   - Triển khai thành công commit `de8b66f` lên dịch vụ `fnmf-backend`.
   - Áp dụng migration `V8__add_forecast_source_and_metadata.sql` trên cơ sở dữ liệu PostgreSQL Railway.
   - Thiết lập cấu hình biến môi trường sản xuất: `PAYMENT_PROVIDER=SANDBOX_INTERNAL`, `OPENAI_DEFAULT_MODEL=gemini-3.6-flash`.
4. **Kiểm thử vận hành trên môi trường sản xuất (Production Smoke Tests):**
   - Dữ liệu giá thời gian thực: Binance API hoạt động ổn định, `stale = false`.
   - Dự báo AI Gemini: Trả về kết quả phân tích kỹ thuật 30 nến, nguồn `GEMINI`, khuyến nghị xu hướng.
   - Tin tức tài chính: Đồng bộ 5 bài viết với bản dịch tiếng Việt và các điểm tóm tắt.
   - Xác thực người dùng: Đăng ký, đăng nhập và cấp phát JWT token hợp lệ.
   - Danh mục theo dõi (Watchlist): Thao tác thêm mã và truy vấn danh mục hoạt động chính xác.
   - Khớp lệnh giả lập (Paper Trading): Khớp lệnh MUA 0.001 BTCUSDT với khóa chống trùng lặp (Idempotency Key), trừ số dư tiền mặt ví và ghi nhận vị thế vào danh mục tài sản.
   - Cổng nạp tiền thử nghiệm nội bộ: Tạo yêu cầu nạp USD, sinh liên kết thanh toán sandbox nội bộ.
   - An ninh điểm cuối: Điểm cuối tài liệu nội bộ (`/swagger-ui.html`) trả về HTTP 404 trên môi trường Production.
5. **Phát hành tệp APK và mã nguồn Android:**
   - Cập nhật commit lên nhánh `main` của kho mã nguồn `megatron69-26/fnmf-app`.
   - Phát hành GitHub Release `v1.1.18` đính kèm tệp nhị phân `FNMF-v1.1.18.apk` đã ký số.

---

## 2. CHI TIẾT KHO MÃ NGUỒN VÀ TÍNH TOÀN VẸN COMMIT

### 2.1. Backend Repository (`llm-gateway3`)
- **Kho lưu trữ từ xa:** `https://github.com/megatron69-26/fnmf-backend.git`
- **Nhánh triển khai:** `main`
- **Commit chính:** `de8b66f63643f8c1550c4c5a5cd9a24f102fd9c3`
- **Thông điệp commit:** `feat(forecast): enforce Gemini forecast integrity and secure production pipeline`
- **Kết quả kiểm thử trước commit:** 184/184 tests PASSED (BUILD SUCCESS).
- **Trạng thái tệp:** Không lưu khóa bí mật, không commit file cấu hình cục bộ hoặc `.env`.

### 2.2. Android Repository (`FNMF_Manh_Test`)
- **Kho lưu trữ từ xa:** `https://github.com/megatron69-26/fnmf-app.git`
- **Nhánh triển khai:** `main`
- **Commit chính:** `d2d00585c7a67ce99a63569b1aa5e4d4b5fa9dec`
- **Thông điệp commit:** `feat(android): release secure Vietnamese trading experience v1.1.18`
- **Kết quả kiểm thử trước commit:**
  - Unit tests: `gradlew testDebugUnitTest` BUILD SUCCESSFUL.
  - Connected tests: 10/10 tests PASSED trên Samsung vật lý (9 test an ninh và migration Token, 1 test ngữ cảnh ứng dụng).
- **An toàn bảo mật:** Keystore release và `local.properties` được loại trừ bởi `.gitignore`, không chứa mật khẩu trong lịch sử git.

---

## 3. THÔNG SỐ VÀ KẾT QUẢ TRIỂN KHAI RAILWAY CLOUD

- **Dịch vụ máy chủ:** `fnmf-backend` trên nền tảng Railway
- **Mã triển khai (Deployment ID):** `5ad4bf3c-091b-41c2-9ac9-3d2a9fe9891b`
- **Địa chỉ dịch vụ trực tuyến:** `https://fnmf-backend-production.up.railway.app`
- **Cơ sở dữ liệu:** PostgreSQL 16 tích hợp Flyway Migration (Phiên bản schema hiện tại: `V8`)
- **Các biến môi trường trọng yếu đã xác nhận:**
  - `PAYMENT_PROVIDER`: `SANDBOX_INTERNAL`
  - `OPENAI_DEFAULT_MODEL`: `gemini-3.6-flash`
  - `SPRING_PROFILES_ACTIVE`: `prod`

### Bảng tổng hợp kết quả kiểm thử môi trường sản xuất (Production Smoke Test Suite):
| STT | Điểm cuối (Endpoint) | Phương thức | Kết quả mong đợi | Kết quả thực tế | Trạng thái |
| :--- | :--- | :--- | :--- | :--- | :--- |
| 1 | `/api/market/prices` | GET | Trả về giá thời gian thực cho BTCUSDT, ETHUSDT, XAUUSD từ Binance, `stale = false` | HTTP 200, giá cập nhật từ Binance, `stale: false` | ĐẠT (PASS) |
| 2 | `/api/forecast/BTCUSDT` | GET | Dự báo kỹ thuật 30 nến qua Gemini AI | HTTP 200, `analysisSource: "GEMINI"`, `candleCount: 30`, `recommendation: "BUY"` | ĐẠT (PASS) |
| 3 | `/api/news/sync?limit=5` | GET | Đồng bộ tin tức tài chính có bản dịch tiếng Việt | HTTP 200, trạng thái `ok`, 5 tin tức kèm tóm tắt tiếng Việt | ĐẠT (PASS) |
| 4 | `/api/auth/register` & `/login` | POST | Cấp phát Bearer JWT token và khởi tạo ví ảo $10,000 | HTTP 200, cấp phát token hợp lệ (độ dài 164 ký tự), số dư ban đầu $10,000 | ĐẠT (PASS) |
| 5 | `/api/auth/me` | GET | Xác thực định danh người dùng qua Bearer Token | HTTP 200, trả về email và tên người dùng | ĐẠT (PASS) |
| 6 | `/api/watchlist` | GET/POST | Quản lý danh mục tài sản theo dõi của người dùng | HTTP 200, lưu trữ và phản hồi danh mục mã theo dõi | ĐẠT (PASS) |
| 7 | `/api/trade/order` | POST | Khớp lệnh Mua/Bán giả lập với Idempotency Key | HTTP 200, khớp lệnh MUA 0.001 BTCUSDT, trừ tiền ví chính xác | ĐẠT (PASS) |
| 8 | `/api/trade/portfolio` | GET | Tổng hợp tài sản, tiền mặt và các vị thế nắm giữ | HTTP 200, số dư tiền mặt cập nhật $9922.8240, 1 vị thế BTCUSDT | ĐẠT (PASS) |
| 9 | `/api/payments/deposits` | POST | Tạo phiên nạp tiền thử nghiệm nội bộ | HTTP 200, trạng thái `PENDING`, trả về đường dẫn checkout sandbox | ĐẠT (PASS) |
| 10 | `/swagger-ui.html` | GET | Chặn giao diện tài liệu nội bộ trên Production | HTTP 404 Not Found (Bảo vệ thông tin hệ thống) | ĐẠT (PASS) |

---

## 4. ĐẶC TẢ TỆP NHỊ PHÂN PHÁT HÀNH (RELEASE BINARY AUDIT)

Bản phát hành chính thức được ký số bằng Keystore Release riêng biệt, không sử dụng chứng chỉ gỡ lỗi mặc định (Android Debug Certificate).

- **Tên tệp lưu trữ:** `FNMF-v1.1.18.apk`
- **Đường dẫn phát hành GitHub:** `https://github.com/megatron69-26/fnmf-app/releases/tag/v1.1.18`
- **Thẻ phát hành (Tag):** `v1.1.18`
- **Tên gói ứng dụng (Package Name):** `com.example.nhumonglenh`
- **Mã phiên bản (Version Code):** `18`
- **Tên phiên bản (Version Name):** `1.1.18`
- **Cấu hình SDK:**
  - `minSdkVersion`: `24` (Hỗ trợ Android 7.0 trở lên)
  - `targetSdkVersion`: `35` (Tuân thủ chuẩn bảo mật Android 15 mới nhất)
- **Cờ gỡ lỗi (android:debuggable):** `false`
- **Lược đồ chữ ký (Signature Scheme):**
  - APK Signature Scheme v2: `true` (v2 phù hợp với minSdk 24)
  - APK Signature Scheme v3: `false`
- **Thông tin chứng chỉ ký phát hành:**
  - Thuật toán khóa: RSA 2048-bit
  - Mã băm chứng chỉ (SHA-256): `f19eddeb2acac5dcef5a5f710674cf0ea652fbb5da10233b736ceadc4d4648bc`
  - Chủ sở hữu chứng chỉ: Đặng Đức Khôi (`CN=Dang Duc Khoi, OU=FNMF, O=FNMF Team, L=Hanoi, ST=Hanoi, C=VN`)
- **Dung lượng tệp APK:** 5,810,069 bytes
- **Mã băm toàn vẹn tệp APK (SHA-256):** `D0164C5F05F03DD2C635CFC7B707CD7FB68F168A529F4DB6401C5BB3B23C0821`

---

## 5. HƯỚNG DẪN CÀI ĐẶT (INSTALLATION NOTE)

1. **Khác biệt chữ ký số:** Toàn bộ các bản APK trước phiên bản v1.1.18 được ký bằng Android Debug Certificate. Phiên bản v1.1.18 sử dụng Release Certificate mới.
2. **Cơ chế bảo vệ của Android:** Hệ điều hành Android ngăn chặn việc cài đè (upgrade install) giữa hai bản cài đặt có chữ ký số khác nhau để bảo vệ tính toàn vẹn ứng dụng.
3. **Quy trình cài đặt:** Người dùng hoặc kiểm thử viên cần **gỡ cài đặt (uninstall) bản debug cũ trên thiết bị một lần**, sau đó tiến hành cài đặt tệp `FNMF-v1.1.18.apk`.
4. **Bảo toàn dữ liệu:** Thao tác gỡ cài đặt chỉ xóa dữ liệu lưu tạm cục bộ trên thiết bị. Toàn bộ thông tin tài khoản, ví và lịch sử giao dịch trên hệ thống Backend vẫn được bảo toàn nguyên vẹn.
5. **Duy trì chứng chỉ:** Từ phiên bản v1.1.18 trở đi, mọi bản cập nhật tiếp theo bắt buộc phải sử dụng cố định Release Keystore hiện tại.

---

## 6. GHI CHÚ VẬN HÀNH (OPERATIONAL NOTES)

1. **Khởi động dịch vụ Cloud:** Máy chủ Backend trên nền tảng Railway có thể phát sinh độ trễ khởi động (cold start) ở yêu cầu mạng đầu tiên sau một khoảng thời gian nhàn rỗi.
2. **Khuyến nghị trước buổi trình bày:** Trước buổi demo hoặc báo cáo, nên thực hiện gọi trước các điểm cuối kiểm tra trạng thái hoặc giá thị trường (`/api/market/prices`) và mở ứng dụng trước khoảng một phút để đảm bảo các kết nối mạng đã sẵn sàng.
3. **Độ trễ mạng:** Thời gian phản hồi mạng phụ thuộc vào đường truyền thực tế và dịch vụ bên thứ ba, không ấn định thời gian phản hồi cố định nếu chưa có kết quả đo lường thực tế.

---

## 7. ĐÁNH GIÁ ĐỒNG BỘ VÀ BẢO MẬT (SECURITY & INTEGRATION AUDIT)

1. **Tuân thủ quy tắc nhóm (Agent Rule Alignment):**
   - 100% các cuộc gọi mạng từ ứng dụng Android đều thông qua hệ thống Backend trung tâm của Khôi trên Railway (`/api/auth/*`, `/api/market/*`, `/api/forecast/*`, `/api/news/*`, `/api/trade/*`, `/api/watchlist/*`, `/api/payments/*`).
   - Không có cuộc gọi trực tiếp từ thiết bị di động tới Gemini AI hay Alpha Vantage, loại bỏ nguy cơ lộ API Key và lãng phí hạn ngạch.
2. **Bảo mật phiên làm việc trên thiết bị Android:**
   - Hệ thống `AndroidKeyStore AES-GCM` hoạt động ổn định trên thiết bị Samsung thực tế.
   - Toàn bộ Bearer Token được mã hóa trước khi lưu trữ, tự động di chuyển dữ liệu phiên cũ từ SharedPreferences mà không làm gián đoạn người dùng.
3. **An toàn kiểm thử và bí mật hệ thống:**
   - Không lưu trữ mật khẩu Keystore, khóa API nội bộ hay Bearer Token trong mã nguồn, nhật ký kiểm thử hoặc tài liệu công khai.
   - Cổng thanh toán hoạt động an toàn ở chế độ `SANDBOX_INTERNAL` tuân thủ nguyên tắc ACID.

---

## 8. KẾT LUẬN

Hệ thống FNMF v1.1.18 đã hoàn tất các hạng mục kỹ thuật của Đợt 2, đáp ứng đầy đủ yêu cầu về kiểm thử, triển khai và tài liệu vận hành.
