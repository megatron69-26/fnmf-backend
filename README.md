# FNMF Backend & AI Gateway
**Financial News & Market Forecasting (FNMF)**  
*Backend REST API, Database Management & AI Processing Layer*

- **Người thực hiện:** Đặng Đức Khôi (Backend / Data Developer)
- **Công nghệ cốt lõi:** Java 17, Spring Boot 3.3.5, Spring Data JPA, Oracle Database 21c, Spring Security (BCrypt), JJWT (JSON Web Token), Docker.

---

## 📌 1. Giới thiệu & Tính năng (Tuần 1)

Dự án Backend đóng vai trò làm trung tâm xử lý nghiệp vụ, quản lý dữ liệu và cầu nối AI cho toàn bộ ứng dụng di động FNMF:
- **Xác thực & Bảo mật (Auth Module):** Đăng ký, Đăng nhập bảo mật với mã hóa một chiều **BCrypt** và cấp vé định danh **JWT Token**.
- **Quản lý Ví vốn ảo (Paper Trading):** Tự động khởi tạo và cấp ví cá nhân với số dư mặc định **$10,000.00** khi người dùng đăng ký tài khoản thành công.
- **Cơ sở dữ liệu tập trung (Oracle DB):** Hệ thống 6 bảng CSDL chuẩn hóa phục vụ: Quản lý người dùng, Ví ảo, Danh mục nắm giữ (Holdings), Lịch sử giao dịch (Transactions), Watchlist và Bộ nhớ đệm AI News Cache.
- **Cổng AI Gateway:** Giấu an toàn API Key, tích hợp cơ chế Retry (Exponential Backoff) và Rate Limiting bảo vệ hệ thống.

---

## 🗄️ 2. Cấu trúc Cơ sở dữ liệu (Database Schema)

Hệ thống CSDL chạy trên **Oracle Database** (Schema: `KHOI2` / Connection: `khoi_mobile`) bao gồm 6 bảng:
1. `USERS`: Lưu trữ tài khoản và mật khẩu đã băm (`password_hash`).
2. `WALLETS`: Lưu trữ ví vốn ảo, số dư khả dụng và vốn khởi tạo.
3. `HOLDINGS`: Danh mục tài sản ảo đang nắm giữ (Vàng XAUUSD, Dầu USOIL, Bitcoin BTCUSDT...).
4. `TRANSACTIONS`: Lịch sử các lệnh Mua/Bán khớp lệnh thời gian thực.
5. `WATCHLISTS`: Danh mục theo dõi yêu thích của từng người dùng (CRUD).
6. `NEWS_AI_CACHE`: Bộ nhớ đệm lưu trữ bài báo và kết quả tóm tắt / phân tích tâm lý từ Gemini AI.

*File script tạo bảng: [`fnmf_schema_khoi_mobile.sql`](../fnmf_schema_khoi_mobile.sql)*

---

## 🚀 3. Hướng dẫn Chạy ứng dụng

### Yêu cầu môi trường:
- **JDK:** Java 17 (Eclipse Temurin hoặc OpenJDK 17+)
- **Database:** Oracle Database 21c (Lắng nghe tại cổng `1521`, SID: `orcl`)
- **Maven:** 3.9+

### Cách chạy:
#### Cách 1: Chạy bằng IntelliJ IDEA
1. Mở thư mục `llm-gateway2` trong IntelliJ IDEA.
2. Mở file `src/main/java/com/llmgateway/LlmGatewayApplication.java`.
3. Nhấn nút **Run ▶️** (Server sẽ lắng nghe tại cổng `http://localhost:8082`).

#### Cách 2: Chạy bằng dòng lệnh (Maven)
```bash
mvn clean spring-boot:run
```

---

## 📖 4. Tài liệu API (API Documentation)

### 🔹 1. Đăng ký tài khoản (`POST /api/auth/register`)
- **Endpoint:** `http://localhost:8082/api/auth/register`
- **Method:** `POST`
- **Header:** `Content-Type: application/json`
- **Request Body:**
```json
{
  "email": "user@fnmf.com",
  "password": "password123",
  "fullName": "Nguyen Van A"
}
```
- **Response thành công (200 OK):**
```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9...",
  "tokenType": "Bearer",
  "user": {
    "id": 1,
    "email": "user@fnmf.com",
    "fullName": "Nguyen Van A",
    "avatarUrl": null,
    "createdAt": "2026-08-19T01:34:18"
  },
  "wallet": {
    "id": 1,
    "userId": 1,
    "balanceUsd": 10000.0000,
    "initialBalance": 10000.0000
  },
  "message": "Đăng ký tài khoản và khởi tạo ví ảo $10,000 thành công!"
}
```

---

### 🔹 2. Đăng nhập (`POST /api/auth/login`)
- **Endpoint:** `http://localhost:8082/api/auth/login`
- **Method:** `POST`
- **Request Body:**
```json
{
  "email": "user@fnmf.com",
  "password": "password123"
}
```
- **Response thành công (200 OK):**
```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9...",
  "tokenType": "Bearer",
  "user": {
    "id": 1,
    "email": "user@fnmf.com",
    "fullName": "Nguyen Van A",
    "avatarUrl": null,
    "createdAt": "2026-08-19T01:34:18"
  },
  "wallet": {
    "id": 1,
    "userId": 1,
    "balanceUsd": 10000.0000,
    "initialBalance": 10000.0000
  },
  "message": "Đăng nhập thành công!"
}
```

---

### 🔹 3. Lấy thông tin tài khoản hiện tại (`GET /api/auth/me`)
- **Endpoint:** `http://localhost:8082/api/auth/me`
- **Method:** `GET`
- **Header:** `Authorization: Bearer <token>`
- **Response thành công (200 OK):**
```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9...",
  "tokenType": "Bearer",
  "user": {
    "id": 1,
    "email": "user@fnmf.com",
    "fullName": "Nguyen Van A",
    "avatarUrl": null,
    "createdAt": "2026-08-19T01:34:18"
  },
  "wallet": {
    "id": 1,
    "userId": 1,
    "balanceUsd": 10000.0000,
    "initialBalance": 10000.0000
  },
  "message": "Lấy thông tin tài khoản thành công!"
}
```

---

## 🔒 5. Bảo mật & Quy tắc nghiệp vụ
- Mọi mật khẩu người dùng đều được băm bằng thuật toán **BCrypt** trước khi lưu vào CSDL.
- Token JWT có thời hạn sử dụng 24 giờ.
- Cơ chế chặn trùng lặp Email và validate định dạng dữ liệu đầu vào chặt chẽ.
