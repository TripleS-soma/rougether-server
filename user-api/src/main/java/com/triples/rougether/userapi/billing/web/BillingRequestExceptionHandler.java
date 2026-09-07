package com.triples.rougether.userapi.billing.web;

import com.triples.rougether.common.error.ErrorResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

// 결제 JSON 파싱 예외에 포함될 수 있는 purchaseToken·JWS 원문을 로그에 남기지 않음.
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {FurnitureBillingController.class, BillingNotificationController.class})
public class BillingRequestExceptionHandler {
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> unreadable() {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of("MALFORMED_REQUEST", "요청 본문을 해석할 수 없습니다."));
    }
}
