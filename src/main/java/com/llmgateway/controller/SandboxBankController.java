package com.llmgateway.controller;

import com.llmgateway.entity.PaymentOrder;
import com.llmgateway.entity.PaymentStatus;
import com.llmgateway.exception.CheckoutExpiredException;
import com.llmgateway.service.PaymentService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.time.format.DateTimeFormatter;

/**
 * Controller phục vụ trang Hosted Sandbox Checkout.
 * Đảm bảo hiển thị cảnh báo Sandbox rõ ràng, không nhận userId/email từ query string.
 * Tất cả thông tin giao dịch được truy vấn bảo mật qua checkoutToken phía server.
 * Tuân thủ CSP production: tách biệt hoàn toàn CSS (/sandbox-checkout.css) và JS (/sandbox-checkout.js).
 */
@Controller
@RequestMapping("/sandbox-bank")
public class SandboxBankController {

    private final PaymentService paymentService;
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    public SandboxBankController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    /**
     * GET /sandbox-bank/checkout/{checkoutToken}
     * Trả về giao diện thanh toán mô phỏng cho người dùng.
     */
    @GetMapping(value = "/checkout/{checkoutToken}", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public ResponseEntity<String> getCheckoutPage(@PathVariable String checkoutToken) {
        try {
            PaymentOrder order = paymentService.getOrderByCheckoutToken(checkoutToken);
            String html = renderCheckoutHtml(order, null);
            return ResponseEntity.ok(html);
        } catch (CheckoutExpiredException e) {
            String errorHtml = renderErrorHtml("Phiên giao dịch đã hết hạn (410 GONE)", e.getMessage());
            return ResponseEntity.status(HttpStatus.GONE).body(errorHtml);
        } catch (IllegalArgumentException e) {
            String errorHtml = renderErrorHtml("Giao dịch không tồn tại", e.getMessage());
            return ResponseEntity.badRequest().body(errorHtml);
        }
    }

    /**
     * POST /sandbox-bank/checkout/{checkoutToken}/process
     * Nhận lệnh mô phỏng: SUCCESS, FAIL, CANCEL.
     * Chống double-click và cập nhật số dư idempotent.
     */
    @PostMapping(value = "/checkout/{checkoutToken}/process", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public ResponseEntity<String> processCheckout(
            @PathVariable String checkoutToken,
            @RequestParam(defaultValue = "SUCCESS") String action,
            @RequestParam(required = false) String failureReason) {
        try {
            PaymentOrder updated = paymentService.processCheckoutAction(checkoutToken, action, failureReason);
            String resultHtml = renderCheckoutHtml(updated, "Thao tác mô phỏng đã được xử lý thành công!");
            return ResponseEntity.ok(resultHtml);
        } catch (CheckoutExpiredException e) {
            String errorHtml = renderErrorHtml("Phiên giao dịch đã hết hạn (410 GONE)", e.getMessage());
            return ResponseEntity.status(HttpStatus.GONE).body(errorHtml);
        } catch (IllegalArgumentException e) {
            String errorHtml = renderErrorHtml("Lỗi xử lý giao dịch", e.getMessage());
            return ResponseEntity.badRequest().body(errorHtml);
        } catch (Exception e) {
            String errorHtml = renderErrorHtml("Lỗi hệ thống", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorHtml);
        }
    }

    private String renderCheckoutHtml(PaymentOrder order, String bannerMessage) {
        String typeDisplay = order.getType().name().equals("DEPOSIT") ? "NẠP TIỀN VÀO VÍ (DEPOSIT)" : "RÚT TIỀN KHỎI VÍ (WITHDRAWAL)";
        String typeBadgeClass = order.getType().name().equals("DEPOSIT") ? "badge-deposit" : "badge-withdrawal";

        String statusBadgeClass;
        String statusText;
        if (order.getStatus() == PaymentStatus.SUCCEEDED) {
            statusBadgeClass = "badge-success";
            statusText = "THÀNH CÔNG (SUCCEEDED)";
        } else if (order.getStatus() == PaymentStatus.FAILED) {
            statusBadgeClass = "badge-failed";
            statusText = "THẤT BẠI (FAILED)";
        } else if (order.getStatus() == PaymentStatus.CANCELLED) {
            statusBadgeClass = "badge-cancelled";
            statusText = "ĐÃ HỦY (CANCELLED)";
        } else {
            statusBadgeClass = "badge-pending";
            statusText = "CHỜ XỬ LÝ (PENDING)";
        }

        boolean isTerminal = (order.getStatus() == PaymentStatus.SUCCEEDED
                || order.getStatus() == PaymentStatus.FAILED
                || order.getStatus() == PaymentStatus.CANCELLED);

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>\n")
            .append("<html lang=\"vi\">\n")
            .append("<head>\n")
            .append("  <meta charset=\"UTF-8\">\n")
            .append("  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n")
            .append("  <title>FNMF Sandbox Banking Checkout</title>\n")
            .append("  <link rel=\"stylesheet\" href=\"/sandbox-checkout.css\">\n")
            .append("</head>\n")
            .append("<body>\n")
            .append("  <div class=\"card\">\n")
            .append("    <div class=\"warning-box\">\n")
            .append("      ⚠️ MÔI TRƯỜNG SANDBOX — KHÔNG PHẢI TIỀN THẬT<br>\n")
            .append("      <span class=\"warning-subtitle\">Dành riêng cho đồ án học thuật & mô phỏng kiến trúc Banking</span>\n")
            .append("    </div>\n");

        if (bannerMessage != null && !bannerMessage.isBlank()) {
            html.append("    <div class=\"alert-banner\">").append(escapeHtml(bannerMessage)).append("</div>\n");
        }

        html.append("    <div class=\"header\">\n")
            .append("      <h2>Cổng Giao Dịch Sandbox</h2>\n")
            .append("      <p class=\"header-desc\">Hệ thống thanh toán ảo nội bộ FNMF</p>\n")
            .append("    </div>\n")
            .append("    <div class=\"amount-box\">\n")
            .append("      <div class=\"amount-label\">SỐ TIỀN MÔ PHỎNG</div>\n")
            .append("      <div class=\"amount-val\">$").append(order.getAmountUsd().toPlainString()).append(" USD</div>\n")
            .append("    </div>\n")
            .append("    <div class=\"row\"><span class=\"label\">Mã giao dịch:</span><span class=\"value\">#").append(order.getId()).append("</span></div>\n")
            .append("    <div class=\"row\"><span class=\"label\">Loại giao dịch:</span><span class=\"badge ").append(typeBadgeClass).append("\">").append(typeDisplay).append("</span></div>\n")
            .append("    <div class=\"row\"><span class=\"label\">Trạng thái:</span><span class=\"badge ").append(statusBadgeClass).append("\">").append(statusText).append("</span></div>\n")
            .append("    <div class=\"row\"><span class=\"label\">Nhà cung cấp:</span><span class=\"value\">").append(escapeHtml(order.getProvider())).append("</span></div>\n")
            .append("    <div class=\"row\"><span class=\"label\">Thời gian tạo:</span><span class=\"value\">").append(order.getCreatedAt() != null ? order.getCreatedAt().format(FORMATTER) : "-").append("</span></div>\n");

        if (order.getFailureReason() != null && !order.getFailureReason().isBlank()) {
            html.append("    <div class=\"row\"><span class=\"label\">Lý do:</span><span class=\"value-fail\">").append(escapeHtml(order.getFailureReason())).append("</span></div>\n");
        }

        if (!isTerminal) {
            html.append("    <div class=\"actions\">\n")
                .append("      <form method=\"POST\" action=\"/sandbox-bank/checkout/").append(order.getCheckoutToken()).append("/process\">\n")
                .append("        <input type=\"hidden\" name=\"action\" value=\"SUCCESS\">\n")
                .append("        <button type=\"submit\" class=\"btn-success\">✅ Xác nhận giao dịch Sandbox</button>\n")
                .append("      </form>\n")
                .append("      <form method=\"POST\" action=\"/sandbox-bank/checkout/").append(order.getCheckoutToken()).append("/process\">\n")
                .append("        <input type=\"hidden\" name=\"action\" value=\"FAIL\">\n")
                .append("        <input type=\"hidden\" name=\"failureReason\" value=\"Người dùng mô phỏng lỗi ngân hàng từ chối\">\n")
                .append("        <button type=\"submit\" class=\"btn-fail\">❌ Mô phỏng thất bại</button>\n")
                .append("      </form>\n")
                .append("      <form method=\"POST\" action=\"/sandbox-bank/checkout/").append(order.getCheckoutToken()).append("/process\">\n")
                .append("        <input type=\"hidden\" name=\"action\" value=\"CANCEL\">\n")
                .append("        <button type=\"submit\" class=\"btn-cancel\">⏹️ Hủy giao dịch</button>\n")
                .append("      </form>\n")
                .append("    </div>\n");
        } else {
            html.append("    <div class=\"completed-notice\">\n")
                .append("      🎉 Giao dịch đã kết thúc với trạng thái: <b>").append(statusText).append("</b><br><br>\n")
                .append("      Bạn có thể an tâm đóng cửa sổ này và quay lại ứng dụng <b>FNMF Mobile</b>. Số dư ví đã được đồng bộ tự động.\n")
                .append("    </div>\n");
        }

        html.append("  </div>\n")
            .append("  <script src=\"/sandbox-checkout.js\"></script>\n")
            .append("</body>\n")
            .append("</html>\n");

        return html.toString();
    }

    private String renderErrorHtml(String title, String message) {
        return "<!DOCTYPE html>\n<html lang=\"vi\">\n<head>\n  <meta charset=\"UTF-8\">\n" +
                "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
                "  <title>" + escapeHtml(title) + "</title>\n" +
                "  <link rel=\"stylesheet\" href=\"/sandbox-checkout.css\">\n" +
                "</head>\n<body>\n  <div class=\"error-card\">\n" +
                "    <div class=\"error-title\">" + escapeHtml(title) + "</div>\n" +
                "    <p class=\"error-msg\">" + escapeHtml(message) + "</p>\n" +
                "    <p class=\"error-hint\">Vui lòng quay lại ứng dụng FNMF và thử lại.</p>\n" +
                "  </div>\n</body>\n</html>";
    }

    private String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}