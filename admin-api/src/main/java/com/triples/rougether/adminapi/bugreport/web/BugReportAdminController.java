package com.triples.rougether.adminapi.bugreport.web;

import com.triples.rougether.adminapi.asset.service.StoredAsset;
import com.triples.rougether.adminapi.bugreport.dto.AdminBugReportResponse;
import com.triples.rougether.adminapi.bugreport.error.BugReportAdminException;
import com.triples.rougether.adminapi.bugreport.service.BugReportAdminService;
import com.triples.rougether.common.error.ErrorResponse;
import com.triples.rougether.domain.bugreport.entity.BugReportStatus;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// 어드민 버그 제보 관리 (#213) + 답장 (#348). 화면(/bug-reports)에서 목록·상태 변경·답장을 호출한다.
@RestController
@RequestMapping("/admin/bug-reports")
public class BugReportAdminController {

    private final BugReportAdminService bugReportAdminService;

    public BugReportAdminController(BugReportAdminService bugReportAdminService) {
        this.bugReportAdminService = bugReportAdminService;
    }

    @GetMapping
    public Map<String, List<AdminBugReportResponse>> getReports(
            @RequestParam(value = "status", required = false) String status) {
        // enum 바인딩 실패는 공통 에러 형식을 우회하므로 문자열로 받아 직접 파싱한다
        BugReportStatus parsed = (status == null || status.isBlank()) ? null : parseStatus(status);
        return Map.of("items", bugReportAdminService.getReports(parsed));
    }

    @GetMapping("/screenshots")
    public ResponseEntity<byte[]> getScreenshot(@RequestParam("key") String key) {
        StoredAsset screenshot = bugReportAdminService.getScreenshot(key);
        MediaType contentType = screenshot.contentType() == null
                ? MediaType.APPLICATION_OCTET_STREAM
                : MediaType.parseMediaType(screenshot.contentType());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().mustRevalidate())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(contentType)
                .body(screenshot.content());
    }

    @PatchMapping("/{id}/status")
    public AdminBugReportResponse changeStatus(@PathVariable Long id,
                                               @RequestBody Map<String, String> request) {
        return bugReportAdminService.changeStatus(id, parseStatus(request.get("status")));
    }

    // 답장 등록 (#348). status 를 함께 주면 답장과 같은 트랜잭션에서 처리 상태도 바꾼다.
    @PostMapping("/{id}/replies")
    public AdminBugReportResponse reply(@PathVariable Long id,
                                        @RequestBody Map<String, String> request,
                                        Principal principal) {
        String content = parseReplyContent(request.get("content"));
        String rawStatus = request.get("status");
        BugReportStatus status = (rawStatus == null || rawStatus.isBlank()) ? null : parseStatus(rawStatus);
        return bugReportAdminService.reply(id, content, status, principal.getName());
    }

    private static String parseReplyContent(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BugReportAdminException("BUG_REPORT_REPLY_INVALID", "답장 내용이 필요합니다.", 400);
        }
        String content = raw.trim();
        if (content.length() > 2000) {
            throw new BugReportAdminException("BUG_REPORT_REPLY_INVALID", "답장은 2000자 이하여야 합니다.", 400);
        }
        return content;
    }

    // {"status": null}·미지원 값 모두 400 - valueOf(null) 은 NPE 라 직접 방어한다
    private static BugReportStatus parseStatus(String raw) {
        if (raw == null) {
            throw new BugReportAdminException("BUG_REPORT_STATUS_INVALID", "상태 값이 필요합니다.", 400);
        }
        try {
            return BugReportStatus.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new BugReportAdminException("BUG_REPORT_STATUS_INVALID", "허용되지 않은 상태입니다: " + raw, 400);
        }
    }

    @ExceptionHandler(BugReportAdminException.class)
    public ResponseEntity<ErrorResponse> handleInvalid(BugReportAdminException exception) {
        return ResponseEntity.status(exception.getStatus())
                .body(ErrorResponse.of(exception.getCode(), exception.getMessage()));
    }
}
