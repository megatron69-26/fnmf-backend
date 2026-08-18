# ===== STAGE 1: BUILD (Xây dựng) =====
# Dùng một image có sẵn Maven + JDK 17 để compile code
FROM maven:3.9-eclipse-temurin-17 AS build

# Copy toàn bộ source code vào trong container
WORKDIR /app
COPY pom.xml .
# Tải dependencies trước (tận dụng Docker cache - nếu pom.xml không đổi thì không tải lại)
RUN mvn dependency:go-offline -B

# Copy source code và build
COPY src ./src
RUN mvn clean package -DskipTests -B

# ===== STAGE 2: RUN (Chạy) =====
# Chỉ dùng JRE nhẹ (không cần Maven, không cần compiler nữa)
FROM eclipse-temurin:17-jre

WORKDIR /app

# Copy file .jar đã build từ Stage 1 sang Stage 2
# Chỉ lấy đúng cái file cần thiết, bỏ lại toàn bộ source code + Maven
COPY --from=build /app/target/*.jar app.jar

# Mở cổng 8080 (cổng mà Spring Boot lắng nghe)
EXPOSE 8080

# Lệnh khởi động ứng dụng khi container được bật lên
ENTRYPOINT ["java", "-jar", "app.jar"]
