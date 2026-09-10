package com.llmgateway.controller;

import com.llmgateway.service.PaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Controller xử lý các webhook IPN và Return URL từ cổng thanh toán VNPay Sandbox.
 * - IPN: Server-to-server endpoint, NGUỒN SỰ THẬT DUY NHẤT để hạch toán số dư.
 * - Return URL: Client browser redirect, CHỈ hiển thị kết quả cho người dùng, KHÔNG hạch toán số dư.
 */
@RestController
@RequestMapping("/api/payments/vnpay")
public class VnPayPaymentController {

    private static final Logger log = LoggerFactory.getLogger(VnPayPaymentController.class);

    private final PaymentService paymentService;

    public VnPayPaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    /**
     * GET /api/payments/vnpay/ipn
     * Nhận thông báo kết quả thanh toán từ VNPay Server-to-Server.
     * Không yêu cầu JWT (xác thực qua chữ ký số HMAC-SHA512).
     */
    @GetMapping("/ipn")
    public ResponseEntity<Map<String, String>> handleVnPayIpn(@RequestParam Map<String, String> allParams) {
        log.info("RECEIVED VNPAY IPN | txnRef={} | amount={} | responseCode={}",
                allParams.get("vnp_TxnRef"), allParams.get("vnp_Amount"), allParams.get("vnp_ResponseCode"));

        Map<String, String> result = paymentService.processVnPayIpn(allParams);

        log.info("RESPONDING TO VNPAY IPN | rspCode={} | message={}",
                result.get("RspCode"), result.get("Message"));

        return ResponseEntity.ok(result);
    }

    /**
     * GET /api/payments/vnpay/return
     * Nhận redirect từ trình duyệt người dùng sau khi hoàn tất giao diện VNPay Sandbox.
     * Chỉ hiển thị giao diện HTML xác nhận, TUYỆT ĐỐI KHÔNG CỘNG TIỀN VÀO VÍ.
     */
    @GetMapping("/return")
    public ResponseEntity<String> handleVnPayReturn(@RequestParam Map<String, String> allParams) {
        log.info("RECEIVED VNPAY RETURN REDIRECT | txnRef={} | responseCode={}",
                allParams.get("vnp_TxnRef"), allParams.get("vnp_ResponseCode"));

        String htmlContent = paymentService.renderVnPayReturnHtml(allParams);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "html", StandardCharsets.UTF_8));

        return ResponseEntity.ok().headers(headers).body(htmlContent);
    }
}
