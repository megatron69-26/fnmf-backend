package com.llmgateway.dto.payment;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public class CreatePaymentRequest {

    @NotNull(message = "Số tiền USD không được để trống")
    @Positive(message = "Số tiền nạp/rút phải lớn hơn 0")
    @Digits(integer = 12, fraction = 2, message = "Số tiền USD tối đa 2 chữ số thập phân")
    private BigDecimal amountUsd;

    @NotBlank(message = "clientRequestId không được để trống")
    @Size(max = 100, message = "clientRequestId tối đa 100 ký tự")
    private String clientRequestId;

    public CreatePaymentRequest() {
    }

    public CreatePaymentRequest(BigDecimal amountUsd, String clientRequestId) {
        this.amountUsd = amountUsd;
        this.clientRequestId = clientRequestId;
    }

    public BigDecimal getAmountUsd() {
        return amountUsd;
    }

    public void setAmountUsd(BigDecimal amountUsd) {
        this.amountUsd = amountUsd;
    }

    public String getClientRequestId() {
        return clientRequestId;
    }

    public void setClientRequestId(String clientRequestId) {
        this.clientRequestId = clientRequestId;
    }
}