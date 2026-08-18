# HƯỚNG DẪN CÀI ĐẶT, CẤU HÌNH VÀ CHẠY THỬ NGHIỆM BACKEND FNMF
> **Dự án:** FNMF - Financial News & Market Forecasting (Tuần 1)  
> **Người thực hiện:** Đặng Đức Khôi (Backend / Data Developer)

---

## 💻 PHẦN 1: CÁC PHẦN MỀM CẦN TẢI VÀ CÀI ĐẶT

### 1. **Java JDK 17 (Eclipse Temurin)**
* **Mục đích:** Môi trường chạy và biên dịch mã nguồn Java Spring Boot.
* **Link tải:** [https://adoptium.net/temurin/releases/?version=17](https://adoptium.net/temurin/releases/?version=17)
* **Cài đặt:** Tải file `.msi` cho Windows x64 $\rightarrow$ Bấm *Next* liên tục theo mặc định.

### 2. **IntelliJ IDEA (Bản Community - Miễn phí)**
* **Mục đích:** IDE lập trình để xem code, sửa code và bấm nút chạy server.
* **Link tải:** [https://www.jetbrains.com/idea/download/?section=windows](https://www.jetbrains.com/idea/download/?section=windows)
* **Cài đặt:** Tải bản Community Edition (`.exe`) $\rightarrow$ Cài đặt theo mặc định.

### 3. **Oracle Database 21c & Oracle SQL Developer**
* **Mục đích:** Hệ quản trị CSDL và công cụ giao diện xem bảng, quản lý dữ liệu.
* **Link tải:** [https://www.oracle.com/database/sqldeveloper/technologies/download/](https://www.oracle.com/database/sqldeveloper/technologies/download/)

### 4. **Postman (Công cụ gửi API Test)**
* **Mục đích:** Giả lập ứng dụng điện thoại để gửi yêu cầu (Đăng ký, Đăng nhập).
* **Link tải:** [https://www.postman.com/downloads/](https://www.postman.com/downloads/)
* **Cài đặt:** Tải về và mở lên sử dụng miễn phí.

### 5. **GitHub Desktop (hoặc Git for Windows)**
* **Mục đích:** Đồng bộ và đẩy toàn bộ mã nguồn lên GitHub của nhóm.
* **Link tải:** [https://desktop.github.com/](https://desktop.github.com/)

---

## 🗄️ PHẦN 2: THIẾT LẬP CƠ SỞ DỮ LIỆU ORACLE

* **Bước 1:** Mở phần mềm **Oracle SQL Developer**.
* **Bước 2:** Kết nối vào database:
  * **Tên kết nối:** `khoi_mobile`
  * **Username:** `khoi2`
* **Bước 3:** Mở file script tạo bảng:
  * **Tên file:** `fnmf_schema_khoi_mobile.sql` *(Nằm ngay trên Desktop)*.
* **Bước 4:** Nhấn phím **`F5`** *(hoặc nút Run Script hình tờ giấy tam giác xanh)* để chạy tạo toàn bộ 6 bảng và dữ liệu mẫu.
* **Bước 5:** Kiểm tra bảng đã tạo thành công bằng lệnh:
  ```sql
  SELECT * FROM users;
  SELECT * FROM wallets;
  ```

---

## ⚙️ PHẦN 3: MỞ VÀ CHẠY DỰ ÁN TRÊN INTELLIJ IDEA

* **Bước 1:** Mở **IntelliJ IDEA** $\rightarrow$ Chọn **Open** $\rightarrow$ Chọn thư mục **`llm-gateway2`** ngay trên màn hình Desktop.
* **Bước 2:** Cấu hình Java SDK *(Nếu thấy thanh màu vàng báo "Project JDK is not defined")*:
  * Bấm vào chữ xanh **Setup SDK** ở góc trên bên phải.
  * Chọn **17 (Eclipse Temurin 17...)**.
* **Bước 3:** Kiểm tra file cấu hình:
  * **Đường dẫn:** `src/main/resources/application.properties`
  * Đảm bảo các thông số kết nối đúng:
    ```properties
    server.port=8082
    spring.datasource.url=jdbc:oracle:thin:@localhost:1521:orcl
    spring.datasource.username=khoi2
    spring.datasource.password=khoi2
    ```
* **Bước 4:** Khởi động Server:
  * Mở file: `src/main/java/com/llmgateway/LlmGatewayApplication.java`
  * Bấm nút tam giác màu xanh lá cây (**Run ▶**) ở góc trên bên phải.
  * Khi cửa sổ Console bên dưới xuất hiện 2 dòng sau:
    * `Tomcat started on port 8082 (http) with context path '/'`
    * `Started LlmGatewayApplication in ... seconds`
    * $\rightarrow$ **Server đã chạy thành công 100%!**

---

## 📮 PHẦN 4: HƯỚNG DẪN TEST BẰNG POSTMAN (CHI TIẾT TỪNG NÚT BẤM)

> **ĐỊA CHỈ GỐC CỦA SERVER:** `http://localhost:8082`

### 🔹 A. TEST ĐĂNG KÝ TÀI KHOẢN MỚI (TỰ CẤP VÍ VỐN ẢO $10,000)
1. **Mở Postman** $\rightarrow$ Bấm dấu cộng (**`+`**) trên thanh tab để mở tab mới.
2. **Chọn phương thức:** Đổi chữ `GET` thành **`POST`** *(màu cam)*.
3. **Dán đường dẫn sau vào ô URL:**
   ```
   http://localhost:8082/api/auth/register
   ```
4. **Thiết lập nội dung gửi đi:**
   * Bấm vào tab **`Body`** *(ngay dưới thanh URL)*.
   * Tích chọn nút tròn **`raw`**.
   * Ở góc phải bấm chọn định dạng **`JSON`** *(thay vì Text)*.
   * Dán đoạn mã JSON sau vào khung trắng:
   ```json
   {
     "email": "khoi.pro@fnmf.com",
     "password": "mypassword123",
     "fullName": "Đặng Đức Khôi"
   }
   ```
5. **Bấm nút `Send`** *(màu xanh dương ở góc phải)*.
6. **Kết quả:** Trả về **Status: 200 OK** kèm mã Token, User Info và Ví ảo **$10,000.00**!

---

### 🔹 B. TEST ĐĂNG NHẬP (LOGIN)
1. Đổi đường dẫn trong ô URL thành:
   ```
   http://localhost:8082/api/auth/login
   ```
2. Phương thức vẫn giữ nguyên là: **`POST`**.
3. Tại tab **Body** $\rightarrow$ chọn **raw** $\rightarrow$ **JSON** $\rightarrow$ Dán nội dung:
   ```json
   {
     "email": "khoi.pro@fnmf.com",
     "password": "mypassword123"
   }
   ```
4. **Bấm nút `Send`**.
5. **Kết quả:** Server trả về *"Đăng nhập thành công!"* và một chuỗi `"token"`.  
   $\rightarrow$ **Hãy bôi đen và COPY chuỗi token này!**

---

### 🔹 C. TEST LẤY THÔNG TIN PROFILE QUA TOKEN (`GET /api/auth/me`)
1. Mở tab mới trên Postman.
2. Chọn phương thức: **`GET`** *(màu xanh lá)*.
3. Dán đường dẫn URL:
   ```
   http://localhost:8082/api/auth/me
   ```
4. **Gắn mã Token vào yêu cầu:**
   * Bấm vào tab **`Authorization`** *(nằm cạnh tab Params)*.
   * Tại mục **Type**, chọn: **`Bearer Token`**.
   * Tại ô **Token** bên phải: Dán chuỗi token vừa copy ở Bước B vào.
5. **Bấm nút `Send`**.
6. **Kết quả:** Server trả về đúng thông tin họ tên, email và số dư ví của bạn.

---

## 🛠️ PHẦN 5: CÁC LỖI THƯỜNG GẶP VÀ CÁCH XỬ LÝ NHANH

1. **Lỗi "405 Method Not Allowed":**
   * *Nguyên nhân:* Bạn quên chưa đổi chữ `GET` thành `POST` trên Postman.
   * *Khắc phục:* Bấm vào dropdown bên trái thanh URL đổi thành `POST` $\rightarrow$ Send lại.
2. **Lỗi "Required request body is missing" (Mã 500):**
   * *Nguyên nhân:* Bạn chưa chọn tab `Body` $\rightarrow$ `raw` $\rightarrow$ `JSON` để dán dữ liệu.
   * *Khắc phục:* Vào tab `Body` $\rightarrow$ chọn `raw` $\rightarrow$ chọn `JSON` và dán dữ liệu vào.
3. **Lỗi "Port 8082 was already in use":**
   * *Nguyên nhân:* Cổng 8082 đang bị tiến trình cũ chiếm dụng.
   * *Khắc phục:* Vào `application.properties` đổi thành `server.port=8083`.
4. **Lỗi "ORA-00903: invalid table name":**
   * *Nguyên nhân:* Gõ thiếu chữ `"s"` ở tên bảng CSDL (ví dụ: gõ `'user'` thay vì `'users'`).
   * *Khắc phục:* Luôn gõ đúng số nhiều: `SELECT * FROM users;` hoặc `SELECT * FROM wallets;`.
