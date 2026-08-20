# 📱 HƯỚNG DẪN TÍCH HỢP ANDROID & ÁNH XẠ DỮ LIỆU BIỂU ĐỒ NẾN (ANDROID INTEGRATION GUIDE FOR HÙNG & AGENT)

> **Tài liệu bàn giao từ Kỹ sư Backend (Đặng Đức Khôi) gửi Lập trình viên Android (Nguyễn Quang Hùng & AI Agent):**  
> File này cung cấp toàn bộ hướng dẫn cấu hình mạng, mã nguồn Kotlin Retrofit mẫu, và đặc biệt là **kỹ thuật ánh xạ dữ liệu (Data Mapping) từ API sang thư viện vẽ biểu đồ nến Candlestick Chart (MPAndroidChart)** thời gian thực.

---

## 🌐 1. CẤU HÌNH KẾT NỐI MẠNG (NETWORK CONFIGURATION)

### A. Địa chỉ Base URL:
* **Chạy trên Máy ảo Android (Android Emulator):**  
  👉 `http://10.0.2.2:8082/` *(10.0.2.2 là địa chỉ trỏ về máy tính host)*
* **Chạy trên Điện thoại thật (Cùng mạng Wi-Fi với máy Khôi):**  
  👉 `http://10.225.76.109:8082/`
* **Chạy qua mạng Internet công khai (Localtunnel / Cloudflare):**  
  👉 `https://<ten-mien-localtunnel>.loca.lt/`

### B. Cấu hình `AndroidManifest.xml`:
Cho phép Android gửi request HTTP (không bắt buộc HTTPS trong môi trường Dev):
```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />

    <application
        android:usesCleartextTraffic="true"
        ...>
        ...
    </application>
</manifest>
```

---

## 🕯️ 2. TRỌNG TÂM: ÁNH XẠ DỮ LIỆU NẾN (CANDLESTICK DATA MAPPING)

Thư viện chuẩn nhất và mạnh mẽ nhất cho Android là **`MPAndroidChart`** (`com.github.PhilJay:MPAndroidChart:v3.1.0`).

### Bước 1: Thêm thư viện vào `build.gradle.kts` (Module :app)
```kotlin
dependencies {
    // Thư viện vẽ biểu đồ nến & kỹ thuật
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")

    // Retrofit & Gson chuyển đổi JSON
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
}
```

---

### Bước 2: Cấu trúc DTO nhận từ Backend (`CandleDto.kt`)
API Endpoint: **`GET /api/market/candles?symbol=BTCUSDT&interval=daily`**

```kotlin
package com.fnmf.app.data.model

import com.google.gson.annotations.SerializedName

data class CandleDto(
    @SerializedName("time") val time: String,      // Ví dụ: "2026-08-20"
    @SerializedName("open") val open: Float,        // Giá mở cửa
    @SerializedName("high") val high: Float,        // Giá cao nhất trong phiên
    @SerializedName("low") val low: Float,          // Giá thấp nhất trong phiên
    @SerializedName("close") val close: Float,      // Giá đóng cửa
    @SerializedName("volume") val volume: Long      // Khối lượng giao dịch
)
```

---

### Bước 3: Hàm Ánh xạ (Data Mapping) sang `CandleEntry` của MPAndroidChart

> **Quy tắc quan trọng của `CandleEntry`:**  
> Thứ tự truyền tham số trong hàm khởi tạo `CandleEntry` của MPAndroidChart là:  
> 👉 `CandleEntry(x, shadowHigh, shadowLow, open, close)`  
> *(Lưu ý: High và Low là 2 đầu bóng nến, Open và Close là thân nến).*

```kotlin
package com.fnmf.app.utils

import android.graphics.Color
import android.graphics.Paint
import com.fnmf.app.data.model.CandleDto
import com.github.mikephil.charting.charts.CandleStickChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.CandleData
import com.github.mikephil.charting.data.CandleDataSet
import com.github.mikephil.charting.data.CandleEntry
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter

object ChartDataMapper {

    /**
     * Chuyển đổi danh sách CandleDto từ Backend thành CandleData để hiển thị lên CandleStickChart
     */
    fun mapAndRenderCandles(
        chart: CandleStickChart,
        candles: List<CandleDto>,
        symbolLabel: String = "BTC/USDT"
    ) {
        val entries = ArrayList<CandleEntry>()
        val dateLabels = ArrayList<String>()

        // 1. DUYỆT QUA TỪNG CÂY NẾN VÀ ÁNH XẠ VÀO CANDLE ENTRY
        candles.forEachIndexed { index, candle ->
            entries.add(
                CandleEntry(
                    index.toFloat(),    // Trục X: Vị trí thứ tự cây nến (0, 1, 2,...)
                    candle.high,        // Trục Y1: Giá cao nhất (Shadow High)
                    candle.low,         // Trục Y2: Giá thấp nhất (Shadow Low)
                    candle.open,        // Trục Y3: Giá mở cửa (Open)
                    candle.close        // Trục Y4: Giá đóng cửa (Close)
                )
            )
            dateLabels.add(candle.time.substring(5)) // Lấy "MM-dd" để hiển thị trục hoành
        }

        // 2. THIẾT LẬP MÀU SẮC & STYLE CHUẨN TÀI CHÍNH QUỐC TẾ
        val dataSet = CandleDataSet(entries, symbolLabel).apply {
            shadowColor = Color.DKGRAY
            shadowWidth = 1.0f
            
            // Nến GIẢM (Bearish): Màu ĐỎ, tô đặc
            decreasingColor = Color.parseColor("#E74C3C")
            decreasingPaintStyle = Paint.Style.FILL
            
            // Nến TĂNG (Bullish): Màu XANH LÁ, tô đặc
            increasingColor = Color.parseColor("#2ECC71")
            increasingPaintStyle = Paint.Style.FILL
            
            // Nến đi ngang: Màu Xanh Dương
            neutralColor = Color.parseColor("#3498DB")
            
            setDrawValues(false) // Ẩn số trên từng nến để biểu đồ thoáng đẹp
            setHighlightEnabled(true)
            highLightColor = Color.WHITE
        }

        // 3. TỐI ƯU GIAO DIỆN TRỤC TỌA ĐỘ
        chart.apply {
            data = CandleData(dataSet)
            
            // Trục X (Thời gian dưới đáy)
            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                textColor = Color.WHITE
                setDrawGridLines(false)
                valueFormatter = IndexAxisValueFormatter(dateLabels)
                granularity = 1f
            }
            
            // Trục Y bên trái (Thước đo giá $)
            axisLeft.apply {
                textColor = Color.WHITE
                setDrawGridLines(true)
                gridColor = Color.parseColor("#33FFFFFF")
            }
            
            // Ẩn trục Y bên phải và đường viền thừa
            axisRight.isEnabled = false
            description.isEnabled = false
            legend.textColor = Color.WHITE
            
            // Làm mới biểu đồ và tạo hiệu ứng mượt mà
            animateX(800)
            invalidate()
        }
    }
}
```

---

## ⚡ 3. CẬP NHẬT GIÁ THỜI GIAN THỰC (REAL-TIME LIVE UPDATE)

Để biểu đồ nến nhảy giá thời gian thực mà không cần tải lại toàn bộ 30 nến:

```kotlin
// Trong ViewModel hoặc Coroutine Polling (5 giây/lần)
fun updateLivePrice(currentPrice: Float, chart: CandleStickChart) {
    val data = chart.data ?: return
    val set = data.getDataSetByIndex(0) as? CandleDataSet ?: return
    
    if (set.entryCount > 0) {
        val lastIndex = set.entryCount - 1
        val lastEntry = set.getEntryForIndex(lastIndex)
        
        // Cập nhật giá đóng cửa của cây nến hiện tại
        lastEntry.close = currentPrice
        if (currentPrice > lastEntry.high) lastEntry.high = currentPrice
        if (currentPrice < lastEntry.low) lastEntry.low = currentPrice
        
        data.notifyDataChanged()
        chart.notifyDataSetChanged()
        chart.invalidate() // Vẽ lại ngay tức thì
    }
}
```

---

## 📡 4. MẪU RETROFIT API SERVICE CHO ANDROID (`ApiService.kt`)

```kotlin
package com.fnmf.app.data.network

import com.fnmf.app.data.model.*
import retrofit2.Response
import retrofit2.http.*

interface ApiService {

    // === 1. AUTHENTICATION ===
    @POST("api/auth/register")
    suspend fun register(@Body request: RegisterRequest): Response<AuthResponse>

    @POST("api/auth/login")
    suspend fun login(@Body request: LoginRequest): Response<AuthResponse>

    @GET("api/auth/me")
    suspend fun getProfile(@Header("Authorization") token: String): Response<UserProfileResponse>

    // === 2. MARKET DATA & CANDLESTICKS ===
    @GET("api/market/prices")
    suspend fun getAllPrices(): Response<List<MarketPriceDto>>

    @GET("api/market/candles")
    suspend fun getCandles(
        @Query("symbol") symbol: String,
        @Query("interval") interval: String = "daily"
    ): Response<List<CandleDto>>

    // === 3. AI NEWS SENTIMENT ===
    @GET("api/news/feed")
    suspend fun getAiNewsFeed(@Query("limit") limit: Int = 5): Response<List<NewsFeedItemDto>>

    // === 4. WATCHLIST ===
    @GET("api/watchlist")
    suspend fun getWatchlist(@Header("Authorization") token: String): Response<List<WatchlistItemDto>>

    @GET("api/watchlist/ai-insights")
    suspend fun getWatchlistAiInsights(@Header("Authorization") token: String): Response<List<WatchlistAiInsightDto>>

    @POST("api/watchlist")
    suspend fun addToWatchlist(
        @Header("Authorization") token: String,
        @Body body: Map<String, String>
    ): Response<WatchlistItemDto>

    // === 5. PAPER TRADING ===
    @POST("api/trade/order")
    suspend fun executeTrade(
        @Header("Authorization") token: String,
        @Body order: TradeOrderRequest
    ): Response<TradeOrderResponse>

    @GET("api/trade/portfolio")
    suspend fun getPortfolio(@Header("Authorization") token: String): Response<PortfolioSummaryDto>

    // === 6. AI MARKET FORECASTING ===
    @GET("api/forecast/{symbol}")
    suspend fun getForecastBySymbol(@Path("symbol") symbol: String): Response<ForecastResponse>
}
```

---

## 📋 5. TỔNG HỢP CÁC ENDPOINT CHO ANDROID DEV

| Tên Màn hình trên App | API Endpoint cần gọi | Phương thức | Dữ liệu trả về |
| :--- | :--- | :---: | :--- |
| **Màn hình Đăng nhập / Đăng ký** | `/api/auth/login`, `/api/auth/register` | `POST` | `token`, `fullName`, `balance ($10k)` |
| **Màn hình Trang chủ (Bảng giá)** | `/api/market/prices` | `GET` | Giá BTC, ETH, Vàng, Dầu (0 Token AI) |
| **Màn hình Biểu đồ Nến** | `/api/market/candles?symbol=BTCUSDT` | `GET` | 30 nến OHLCV để vẽ MPAndroidChart |
| **Màn hình Tin tức AI** | `/api/news/feed?limit=5` | `GET` | Tin thật + Tóm tắt AI + Nhãn `BULLISH/BEARISH` |
| **Màn hình Watchlist Cá nhân** | `/api/watchlist/ai-insights` | `GET` | Dự báo AI + Tin tức riêng cho các mã đã bookmark |
| **Màn hình Dự báo Chi tiết** | `/api/forecast/{symbol}` | `GET` | Vùng Hỗ trợ/Kháng cự + Khuyến nghị Mua/Bán |
| **Màn hình Đặt lệnh Mua/Bán** | `/api/trade/order` | `POST` | Khớp lệnh ví ảo + Tính giá trung bình DCA |
| **Màn hình Danh mục Tài sản** | `/api/trade/portfolio` | `GET` | Net Worth + Lời/Lỗ (PnL) thời gian thực |

---

> 🚀 **Gợi ý dành cho Agent của Hùng:** Bạn chỉ cần đọc file này, copy các hàm ánh xạ `ChartDataMapper` và `ApiService` vào project Android Studio là ứng dụng sẽ chạy mượt mà 100% với Backend!
