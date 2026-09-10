package com.llmgateway.service.payment;

import com.llmgateway.config.VnPayConfig;
import com.llmgateway.entity.PaymentOrder;
import com.llmgateway.util.VnPayUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * PaymentProvider tích hợp VNPay 2.1.0 Sandbox dành cho chiều NẠP vốn mô phỏng.
 * Tuyệt đối không xử lý tiền thật, thẻ thật hoặc thông tin ngân hàng thật.
 */
@Component
public class VnPaySandboxPaymentProvider implements PaymentProvider {

    private static final Logger log = LoggerFactory.getLogger(VnPaySandboxPaymentProvider.class);
    public static final String PROVIDER_NAME = "VNPAY_SANDBOX";
    private static final DateTimeFormatter VNP_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final VnPayConfig vnPayConfig;

    public VnPaySandboxPaymentProvider(VnPayConfig vnPayConfig) {
        this.vnPayConfig = vnPayConfig;
    }

    @Override
    public String getProviderName() {
        return PROVIDER_NAME;
    }

    @Override
    public String buildCheckoutUrl(PaymentOrder order, String baseUrl) {
        return buildCheckoutUrl(order, baseUrl, null);
    }

    @Override
    public String buildCheckoutUrl(PaymentOrder order, String baseUrl, String clientIp) {
        if (!vnPayConfig.isConfigured()) {
            throw new IllegalStateException("Cấu hình VNPay Sandbox (TMN_CODE hoặc HASH_SECRET) chưa được thiết lập!");
        }

        BigDecimal exchangeRate = (order.getExchangeRateSnapshot() != null)
                ? order.getExchangeRateSnapshot()
                : vnPayConfig.getExchangeRate();

        BigDecimal amountVnd = (order.getAmountVnd() != null)
                ? order.getAmountVnd()
                : order.getAmountUsd().multiply(exchangeRate).setScale(0, RoundingMode.HALF_UP);

        long vnpAmount = amountVnd.multiply(new BigDecimal("100")).longValueExact();

        ZonedDateTime now = ZonedDateTime.now(VN_ZONE);
        String vnpCreateDate = now.format(VNP_DATE_FORMAT);
        String vnpExpireDate = now.plusMinutes(15).format(VNP_DATE_FORMAT);

        String txnRef = (order.getId() != null) ? String.valueOf(order.getId()) : order.getCheckoutToken();
        String returnUrl = resolveReturnUrl(baseUrl);
        String ipAddr = (clientIp != null && !clientIp.isBlank()) ? clientIp.trim() : "127.0.0.1";

        Map<String, String> vnpParams = new HashMap<>();
        vnpParams.put("vnp_Version", "2.1.0");
        vnpParams.put("vnp_Command", "pay");
        vnpParams.put("vnp_TmnCode", vnPayConfig.getTmnCode());
        vnpParams.put("vnp_Amount", String.valueOf(vnpAmount));
        vnpParams.put("vnp_CurrCode", "VND");
        vnpParams.put("vnp_TxnRef", txnRef);
        vnpParams.put("vnp_OrderInfo", "Nap tien FNMF #" + txnRef);
        vnpParams.put("vnp_OrderType", "other");
        vnpParams.put("vnp_Locale", "vn");
        vnpParams.put("vnp_ReturnUrl", returnUrl);
        vnpParams.put("vnp_IpAddr", ipAddr);
        vnpParams.put("vnp_CreateDate", vnpCreateDate);
        vnpParams.put("vnp_ExpireDate", vnpExpireDate);

        String hashData = VnPayUtil.buildHashData(vnpParams);
        String secureHash = VnPayUtil.hmacSHA512(vnPayConfig.getHashSecret(), hashData);
        String queryUrl = VnPayUtil.buildQueryString(vnpParams);

        log.info("BUILT VNPAY SANDBOX URL | orderId={} | txnRef={} | amountVnd={} | vnpAmount={} | ipAddr={}",
                order.getId(), txnRef, amountVnd, vnpAmount, ipAddr);

        return vnPayConfig.getPayUrl() + "?" + queryUrl + "&vnp_SecureHash=" + secureHash;
    }

    private String resolveReturnUrl(String baseUrl) {
        if (!vnPayConfig.getReturnUrl().isEmpty()) {
            return vnPayConfig.getReturnUrl();
        }
        String cleanBase = (baseUrl != null && !baseUrl.isBlank()) ? baseUrl.trim() : "";
        while (cleanBase.endsWith("/")) {
            cleanBase = cleanBase.substring(0, cleanBase.length() - 1);
        }
        return cleanBase + "/api/payments/vnpay/return";
    }

    @Override
    public boolean isSandbox() {
        return true;
    }
}
