package com.triples.rougether.userapi.global.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.common.error.ErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

// multipart 한도 초과는 MockMvc가 컨테이너 한도를 강제하지 않아 HTTP 경유로 재현이 안 됨 → 핸들러를 직접 검증함
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void multipart_한도_초과는_500이_아니라_400_FILE_TOO_LARGE로_매핑된다() {
        ResponseEntity<ErrorResponse> response =
                handler.handleMaxUploadSize(new MaxUploadSizeExceededException(12L * 1024 * 1024));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo("FILE_TOO_LARGE");
    }
}
