package com.llmgateway.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Utility for VNPay 2.1.0 HMAC SHA-512 signing, URL encoding, and verification.
 * Adheres strictly to the VNPay standard specification:
 * - Natural alphabetical ASCII sort for parameters.
 * - Single-pass URL encoding.
 * - Constant-time HMAC SHA-512 verification via MessageDigest.isEqual.
 * - Excludes vnp_SecureHash and vnp_SecureHashType from checksum payload.
 */
public final class VnPayUtil {

    private VnPayUtil() {
    }

    public static String hmacSHA512(String key, String data) {
        try {
            if (key == null || data == null) {
                throw new IllegalArgumentException("Key and data cannot be null");
            }
            Mac hmac512 = Mac.getInstance("HmacSHA512");
            SecretKeySpec secretKey = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA512");
            hmac512.init(secretKey);
            byte[] result = hmac512.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(2 * result.length);
            for (byte b : result) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to calculate HMAC-SHA512", ex);
        }
    }

    public static String buildHashData(Map<String, String> fields) {
        List<String> fieldNames = new ArrayList<>(fields.keySet());
        Collections.sort(fieldNames);
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (String fieldName : fieldNames) {
            if ("vnp_SecureHash".equalsIgnoreCase(fieldName) || "vnp_SecureHashType".equalsIgnoreCase(fieldName)) {
                continue;
            }
            String fieldValue = fields.get(fieldName);
            if (fieldValue != null && !fieldValue.isEmpty()) {
                if (!first) {
                    sb.append('&');
                }
                sb.append(fieldName);
                sb.append('=');
                sb.append(URLEncoder.encode(fieldValue, StandardCharsets.US_ASCII));
                first = false;
            }
        }
        return sb.toString();
    }

    public static String buildQueryString(Map<String, String> fields) {
        List<String> fieldNames = new ArrayList<>(fields.keySet());
        Collections.sort(fieldNames);
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (String fieldName : fieldNames) {
            if ("vnp_SecureHash".equalsIgnoreCase(fieldName) || "vnp_SecureHashType".equalsIgnoreCase(fieldName)) {
                continue;
            }
            String fieldValue = fields.get(fieldName);
            if (fieldValue != null && !fieldValue.isEmpty()) {
                if (!first) {
                    sb.append('&');
                }
                sb.append(URLEncoder.encode(fieldName, StandardCharsets.US_ASCII));
                sb.append('=');
                sb.append(URLEncoder.encode(fieldValue, StandardCharsets.US_ASCII));
                first = false;
            }
        }
        return sb.toString();
    }

    public static boolean verifySignature(Map<String, String> fields, String secretKey) {
        if (fields == null || secretKey == null || secretKey.isBlank()) {
            return false;
        }
        String receivedHash = fields.get("vnp_SecureHash");
        if (receivedHash == null || receivedHash.isBlank()) {
            // Check case-insensitive
            for (Map.Entry<String, String> entry : fields.entrySet()) {
                if ("vnp_SecureHash".equalsIgnoreCase(entry.getKey())) {
                    receivedHash = entry.getValue();
                    break;
                }
            }
        }
        if (receivedHash == null || receivedHash.isBlank()) {
            return false;
        }

        String hashData = buildHashData(fields);
        String calculatedHash = hmacSHA512(secretKey, hashData);

        return MessageDigest.isEqual(
                calculatedHash.toLowerCase().getBytes(StandardCharsets.UTF_8),
                receivedHash.trim().toLowerCase().getBytes(StandardCharsets.UTF_8)
        );
    }
}
