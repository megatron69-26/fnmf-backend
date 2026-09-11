# HUONG DAN CAI DAT, CAU HINH VA CHAY HE THONG BACKEND FNMF
Du an: FNMF - Financial News & Market Forecasting
Nguoi thuc hien: Dang Duc Khoi (Backend & Data Developer)

---

## 1. YEU CAU HE THONG VA PHAN MEM CAN CAI DAT

### 1. Java Development Kit (JDK 17)
* Phien ban: Eclipse Temurin JDK 17 (LTS) hoac OpenJDK 17.
* Kiem tra tren terminal:
  ```bash
  java -version
  ```
  Ket qua can xuat hien ban Java 17.x.

### 2. Apache Maven
* Du an da tich hop san Maven Wrapper (`mvnw` tren Linux/macOS va `mvnw.cmd` tren Windows). Khong bat buoc phai cai Maven rieng.
* Kiem tra:
  ```bash
  ./mvnw -v
  ```

### 3. Moi truong phat trien (IDE)
* Khuyen nghi: IntelliJ IDEA (Community hoac Ultimate) hoac Visual Studio Code voi Extension Pack for Java.

### 4. Co so du lieu
* **Local Development / Unit Tests:** Su dung H2 in-memory database voi che do tuong thich PostgreSQL (`MODE=PostgreSQL`). He thong tu dong tao va ap dung cac migration Flyway (V1 den V8) khi khoi dong, khong can cai dat PostgreSQL cuc bo.
* **Production Deployment:** PostgreSQL duoc cap phat tren Railway, tu dong ap dung migration Flyway khi container khoi dong.

---

## 2. CAU HINH BIEN MOI TRUONG VA FILE PROPERTIES

### 1. Cau hinh Local Development (`src/main/resources/application.properties`)
Moi truong cuc bo mac dinh khoi dong voi port `8083`:
```properties
server.port=8083

# Co so du lieu H2 in-memory tuong thich PostgreSQL
spring.datasource.url=jdbc:h2:mem:fnmf;DB_CLOSE_DELAY=-1;MODE=PostgreSQL
spring.datasource.driver-class-name=org.h2.Driver
spring.datasource.username=sa
spring.datasource.password=

# JPA & Hibernate
spring.jpa.database-platform=org.hibernate.dialect.H2Dialect
spring.jpa.hibernate.ddl-auto=validate
spring.jpa.show-sql=false

# Flyway Migrations
spring.flyway.enabled=true
spring.flyway.baseline-on-migrate=true
spring.flyway.locations=classpath:db/migration

# Model AI mac dinh
openai.default-model=gemini-3.6-flash
payment.provider=SANDBOX_INTERNAL
```

### 2. Cau hinh Production (`src/main/resources/application-prod.properties`)
Tren Railway, he thong doc cau hinh qua cac bien moi truong:
* `PORT`: Cong lang nghe (Railway tu dong cap).
* `SPRING_DATASOURCE_URL`: Duong dan JDBC PostgreSQL.
* `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`: Thong tin dang nhap CSDL.
* `JWT_SECRET`: Chuoi bi mat ky HMAC-SHA256.
* `OPENAI_API_KEY`: API key Google Gemini (qua endpoint tuong thich OpenAI).
* `OPENAI_DEFAULT_MODEL`: `gemini-3.6-flash`.
* `ALPHA_VANTAGE_API_KEY`: Key lay tin tuc thi truong Alpha Vantage.
* `PAYMENT_PROVIDER`: `SANDBOX_INTERNAL`.

---

## 3. BIEN DICH VA CHAY KIEM THU (TEST SUITE)

Truoc khi khoi dong hoac day code len kho luu tru, chay toan bo bo kiem thu:
```bash
mvn clean test
```
Ket qua mong doi: 184 test pass hoan toan, 0 failure, 0 error.

---

## 4. KHOI DONG SERVER BACKEND

### Chay bang Maven Wrapper tren Terminal:
```bash
mvn spring-boot:run
```
hoac tren Windows PowerShell:
```powershell
.\mvnw.cmd spring-boot:run
```

### Chay truc tiep tu IntelliJ IDEA:
1. Mo thu muc `llm-gateway3` trong IntelliJ.
2. Cho IntelliJ dong bo dependencies Maven.
3. Mo file `src/main/java/com/llmgateway/LlmGatewayApplication.java`.
4. Nhan nut Run de khoi chay Server.

Khi console xuat hien:
```text
Tomcat started on port 8083 (http) with context path '/'
Started LlmGatewayApplication in ... seconds
```
Server da san sang tai `http://localhost:8083`.

---

## 5. HUONG DAN KIEM THU CAC ENDPOINT CO BAN

### 1. Kiem tra tinh trang may chu (Health Check)
```bash
curl -X GET http://localhost:8083/api/market/prices
```

### 2. Dang ky tai khoan moi ($10,000 von khoi tao)
```bash
curl -X POST http://localhost:8083/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "email": "tester@fnmf.com",
    "password": "Password123!",
    "fullName": "Test User"
  }'
```

### 3. Dang nhap va nhan JWT
```bash
curl -X POST http://localhost:8083/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "email": "tester@fnmf.com",
    "password": "Password123!"
  }'
```
Luu tru chuoi `token` tra ve de gan vao header `Authorization: Bearer <token>` trong cac request tiep theo.

### 4. Xem thong tin vi va tai khoan
```bash
curl -X GET http://localhost:8083/api/auth/me \
  -H "Authorization: Bearer <TOKEN>"
```

### 5. Dat lenh Mua Paper Trading
```bash
curl -X POST http://localhost:8083/api/trade/order \
  -H "Authorization: Bearer <TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{
    "symbol": "BTCUSDT",
    "side": "BUY",
    "quantity": 0.05,
    "clientOrderId": "d8e3b7c9-4b2a-4c1e-9a8f-2e3b4c5d6e7f"
  }'
```

### 6. Tao don nap tien Sandbox ($500.00)
```bash
curl -X POST http://localhost:8083/api/payments/deposits \
  -H "Authorization: Bearer <TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{
    "amountUsd": 500.00,
    "clientRequestId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
  }'
```
Truy cap `checkoutUrl` tra ve tren trinh duyet de hoan tat xac nhan nap tien mo phong.
