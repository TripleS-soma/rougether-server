package com.triples.rougether.userapi.global.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.common.error.ErrorCode;
import com.triples.rougether.common.error.ErrorResponse;
import com.triples.rougether.userapi.auth.error.AuthErrorCode;
import com.triples.rougether.userapi.global.alert.OperationalAlertNotifier;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.resource.NoResourceFoundException;

// multipart 한도 초과는 MockMvc가 컨테이너 한도를 강제하지 않아 HTTP 경유로 재현이 안 됨 → 핸들러를 직접 검증함
class GlobalExceptionHandlerTest {

    private final RecordingOperationalAlertNotifier notifier = new RecordingOperationalAlertNotifier();
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(notifier);

    @Test
    void multipart_한도_초과는_500이_아니라_400_FILE_TOO_LARGE로_매핑된다() {
        ResponseEntity<ErrorResponse> response =
                handler.handleMaxUploadSize(new MaxUploadSizeExceededException(12L * 1024 * 1024));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo("FILE_TOO_LARGE");
    }

    @Test
    void 존재하지_않는_API는_500_알림_없이_404_RESOURCE_NOT_FOUND로_매핑된다() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/gachas");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/**");

        ResponseEntity<ErrorResponse> response = handler.handleNoResource(
                new NoResourceFoundException(HttpMethod.GET, "api/v1/gachas", "/api/v1/gachas"),
                request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().code()).isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(notifier.unexpectedCause).isNull();
    }

    @Test
    void 소셜_로그인_실패는_경로와_코드를_운영_알림으로_전달한다() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/google");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/auth/google");

        ResponseEntity<ErrorResponse> response = handler.handleBusiness(
                new BusinessException(AuthErrorCode.OAUTH_GOOGLE_TOKEN_INVALID), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(notifier.endpoint).isEqualTo("POST /api/v1/auth/google");
        assertThat(notifier.errorCode).isEqualTo(AuthErrorCode.OAUTH_GOOGLE_TOKEN_INVALID);
    }

    @Test
    void 운영_알림은_민감한_path_variable_값_대신_매핑_패턴을_전달한다() {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/api/v1/houses/by-code/SECRET12");
        request.setAttribute(
                HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
                "/api/v1/houses/by-code/{inviteCode}");

        handler.handleBusiness(new BusinessException(AuthErrorCode.OAUTH_KAKAO_UNAVAILABLE), request);

        assertThat(notifier.endpoint)
                .isEqualTo("GET /api/v1/houses/by-code/{inviteCode}")
                .doesNotContain("SECRET12");
    }

    @Test
    void 운영_알림_전송이_실패해도_API_오류_응답은_유지한다() {
        GlobalExceptionHandler failingAlertHandler = new GlobalExceptionHandler(new OperationalAlertNotifier() {
            @Override
            public void notifyBusiness(String endpoint, ErrorCode errorCode) {
                throw new IllegalStateException("alert unavailable");
            }

            @Override
            public void notifyUnexpected(String endpoint, Throwable cause) {
                throw new IllegalStateException("alert unavailable");
            }
        });
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/google");

        ResponseEntity<ErrorResponse> response = failingAlertHandler.handleBusiness(
                new BusinessException(AuthErrorCode.OAUTH_GOOGLE_TOKEN_INVALID), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().code()).isEqualTo("AUTH_OAUTH_GOOGLE_TOKEN_INVALID");
    }

    private static final class RecordingOperationalAlertNotifier implements OperationalAlertNotifier {

        private String endpoint;
        private ErrorCode errorCode;
        private Throwable unexpectedCause;

        @Override
        public void notifyBusiness(String endpoint, ErrorCode errorCode) {
            this.endpoint = endpoint;
            this.errorCode = errorCode;
        }

        @Override
        public void notifyUnexpected(String endpoint, Throwable cause) {
            this.endpoint = endpoint;
            this.unexpectedCause = cause;
        }
    }
}
