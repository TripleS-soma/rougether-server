package com.triples.rougether.adminapi.global.error;

import com.triples.rougether.common.error.ErrorResponse;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

// admin API 의 validation·본문 파싱 실패를 공통 ErrorResponse 계약(spec api.md — code/message/fieldErrors)으로
// 변환한다. user-api GlobalExceptionHandler 와 같은 코드·형태를 쓴다.
// Thymeleaf 페이지 컨트롤러의 에러 처리에 영향을 주지 않도록 이 네 예외 유형만 좁게 다루고,
// 도메인 예외는 기존처럼 각 컨트롤러의 로컬 @ExceptionHandler 가 우선 처리한다.
@RestControllerAdvice
public class AdminApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(AdminApiExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException exception) {
        List<ErrorResponse.FieldError> fieldErrors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new ErrorResponse.FieldError(error.getField(), error.getDefaultMessage()))
                .toList();

        log.warn("validation failed: fields={}", fieldErrors);
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of("VALIDATION_FAILED", "입력값이 올바르지 않습니다.", fieldErrors));
    }

    @ExceptionHandler({HandlerMethodValidationException.class, ConstraintViolationException.class})
    public ResponseEntity<ErrorResponse> handleParamValidation(Exception exception) {
        log.warn("param validation failed: {}", exception.getMessage());
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of("VALIDATION_FAILED", "입력값이 올바르지 않습니다."));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException exception) {
        log.warn("malformed request body: {}", exception.getMessage());
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of("MALFORMED_REQUEST", "요청 본문을 해석할 수 없습니다."));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        // 파라미터 원본 값은 사용자 입력이라 로그에 남기지 않음(name 만).
        log.warn("request parameter type mismatch: name={}", exception.getName());
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of("VALIDATION_FAILED", "입력값이 올바르지 않습니다."));
    }
}
