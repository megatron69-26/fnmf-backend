# 🤖 QUY TẮC TỰ ĐỘNG ĐÁNH GIÁ ĐỒNG BỘ DỰ ÁN (TEAM SYNCHRONIZATION AUDIT RULE)

## 📌 1. BỐI CẢNH DỰ ÁN & VAI TRÒ
* **Chủ sở hữu máy (User):** Đặng Đức Khôi (Member #3 - Phụ trách Backend, Data Pipeline, Database Oracle 21c, AI Gateway).
* **Dự án gốc trung tâm (Core Project):** `llm-gateway2` (Spring Boot 3, Oracle Database 21c, Alpha Vantage Gateway, Gemini AI Gateway, Paper Trading, Watchlist, Market Forecasting).
* **Các thành viên trong nhóm:**
  1. **Nguyễn Hữu Mạnh (Leader / PO / Prompt Engineer / Android Local Cache & Evaluation):** Phụ trách Prompt AI, đánh giá và cấu trúc Room DB trên Android.
  2. **Nguyễn Quang Hùng (Member #2 - Android Developer):** Phụ trách lập trình giao diện ứng dụng Android, kết nối Retrofit và vẽ biểu đồ nến thời gian thực `MPAndroidChart`.

---

## ⚡ 2. QUY ĐỊNH BẮT BUỘC CHO ANTIGRAVITY KHI MỞ / TẢI CODE CỦA THÀNH VIÊN KHÁC
Mỗi khi người dùng (Khôi) tải về, mở, hoặc nhắc đến bất kỳ thư mục, file, hoặc mã nguồn nào từ các thành viên khác (Mạnh, Hùng, hoặc đối tác bên ngoài), **ANTIGRAVITY PHẢI TỰ ĐỘNG THỰC HIỆN ĐÁNH GIÁ ĐỒNG BỘ TOÀN DIỆN** theo 4 bước sau mà không cần người dùng phải nhắc:

### 🔍 BƯỚC 1: KIỂM TRA ĐỒNG BỘ NGUỒN DỮ LIỆU & API (Data Source & API Alignment)
* Kiểm tra xem code của thành viên có đang trỏ đúng vào hệ thống Backend trung tâm của Khôi qua REST API (`/api/auth/*`, `/api/news/*`, `/api/market/*`, `/api/trade/*`, `/api/forecast/*`, `/api/watchlist/*`) hay không.
* **Cảnh báo ngay nếu:** Thành viên gọi trực tiếp API bên ngoài (như gọi thẳng Gemini AI hay Alpha Vantage từ Mobile) gây **lãng phí Token, lệch dữ liệu, hoặc lộ API Key**.

### 🔍 BƯỚC 2: KIỂM TRA ĐỒNG BỘ SCHEMA DỮ LIỆU (Schema & Model Consistency)
* So sánh chi tiết tên trường, kiểu dữ liệu, khóa chính/ngoại giữa Model của thành viên (ví dụ: Room Database, Retrofit DTO, Kotlin Data Class) với Database Oracle 21c và DTO của Backend Khôi.
* **Chỉ rõ các điểm lệch (nếu có):**
  * Kiểu dữ liệu (ví dụ: `String newsId` vs `Long id`).
  * Tên trường (ví dụ: `summary` vs `aiSummary`, `sentiment` vs `aiSentiment`).
  * Định dạng thời gian (Unix timestamp vs ISO-8601).

### 🔍 BƯỚC 3: KIỂM TRA XUNG ĐỘT KIẾN TRÚC & DỮ LIỆU GIẢ (Conflict & Dummy Data Detection)
* Phát hiện xem code của thành viên đang dùng dữ liệu test cứng (Hardcoded Dummy Data) hay đã sẵn sàng kết nối API thật.
* Kiểm tra các cấu hình nền tảng: Quyền truy cập mạng (`android.permission.INTERNET`), thư viện kết nối mạng (`Retrofit`, `OkHttp`), cấu hình Network Security Config / HTTPS.

### 🔍 BƯỚC 4: ĐƯA RA BẢN ĐÁNH GIÁ & HƯỚNG DẪN TÍCH HỢP (Integration Roadmap)
* Kết luận mức độ tương thích theo thang điểm (ví dụ: Tương thích 100%, hoặc Cần tinh chỉnh).
* Cung cấp sẵn các đoạn code sửa đổi (Adapter / Mapper / DTO / Service) để Khôi chỉ cần chuyển cho thành viên dán vào là ứng dụng chạy khớp 100% với Backend.
