package com.llmgateway.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.GONE)
public class CheckoutExpiredException extends RuntimeException {

    public CheckoutExpiredException(String message) {
        super(message);
    }

    public CheckoutExpiredException(String message, Throwable cause) {
        super(message, cause);
    }
}
