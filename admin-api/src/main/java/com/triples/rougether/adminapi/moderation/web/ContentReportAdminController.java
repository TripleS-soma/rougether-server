package com.triples.rougether.adminapi.moderation.web;

import com.triples.rougether.adminapi.asset.service.StoredAsset;
import com.triples.rougether.adminapi.moderation.dto.AdminContentReportPageResponse;
import com.triples.rougether.adminapi.moderation.dto.AdminContentReportResolveResponse;
import com.triples.rougether.adminapi.moderation.error.ContentReportAdminException;
import com.triples.rougether.adminapi.moderation.service.ContentReportAdminService;
import com.triples.rougether.adminapi.moderation.service.ContentReportAdminService.Action;
import com.triples.rougether.common.error.ErrorResponse;
import com.triples.rougether.domain.moderation.entity.ContentReportStatus;
import java.security.Principal;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// 콘텐츠 신고 운영자 대기열·처리(#399). 화면(/content-reports)에서 호출함. 세션 + CSRF 는 공통 보안 설정을 따름.
@RestController
@RequestMapping("/admin/reports")
public class ContentReportAdminController {

    private static final int MAX_SIZE = 100;

    private final ContentReportAdminService service;

    public ContentReportAdminController(ContentReportAdminService service) {
        this.service = service;
    }

    // status 생략 시 전체. 최신 신고순.
    @GetMapping
    public AdminContentReportPageResponse list(@RequestParam(value = "status", required = false) String status,
                                               @RequestParam(value = "page", defaultValue = "0") int page,
                                               @RequestParam(value = "size", defaultValue = "20") int size) {
        if (page < 0 || size < 1 || size > MAX_SIZE) {
            throw new ContentReportAdminException("VALIDATION_FAILED", "입력값이 올바르지 않습니다.", 400);
        }
        ContentReportStatus parsed = (status == null || status.isBlank()) ? null
                : parse(ContentReportStatus.class, status, "REPORT_STATUS_INVALID", "허용되지 않은 상태입니다: ");
        return service.list(parsed, page, size);
    }

    // action: HIDE(숨김·거래 정지 → ACTIONED) | DISMISS(조치 없음 → DISMISSED). 같은 대상의 대기 신고를 함께 닫음.
    @PostMapping("/{id}/resolve")
    public AdminContentReportResolveResponse resolve(@PathVariable Long id, @RequestBody Map<String, String> request,
                                                     Principal principal) {
        Action action = parse(Action.class, request.get("action"), "REPORT_ACTION_INVALID", "허용되지 않은 처리입니다: ");
        return service.resolve(id, action, principal.getName());
    }

    // 신고된 게시물의 비공개 피드 사진 열람. 캐시하지 않음.
    @GetMapping("/feed-images/{imageId}")
    public ResponseEntity<byte[]> feedImage(@PathVariable Long imageId) {
        StoredAsset image = service.feedImage(imageId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().mustRevalidate())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.IMAGE_JPEG)
                .body(image.content());
    }

    // enum 바인딩 실패·null 은 공통 에러 형식을 우회하므로 문자열로 받아 직접 파싱함
    private static <E extends Enum<E>> E parse(Class<E> type, String raw, String code, String message) {
        if (raw == null) {
            throw new ContentReportAdminException(code, message + "null", 400);
        }
        try {
            return Enum.valueOf(type, raw);
        } catch (IllegalArgumentException e) {
            throw new ContentReportAdminException(code, message + raw, 400);
        }
    }

    @ExceptionHandler(ContentReportAdminException.class)
    public ResponseEntity<ErrorResponse> handle(ContentReportAdminException exception) {
        return ResponseEntity.status(exception.getStatus())
                .body(ErrorResponse.of(exception.getCode(), exception.getMessage()));
    }
}
