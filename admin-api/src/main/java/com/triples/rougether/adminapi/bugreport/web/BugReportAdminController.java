package com.triples.rougether.adminapi.bugreport.web;

import com.triples.rougether.adminapi.bugreport.dto.AdminBugReportResponse;
import com.triples.rougether.adminapi.bugreport.error.BugReportAdminException;
import com.triples.rougether.adminapi.bugreport.service.BugReportAdminService;
import com.triples.rougether.common.error.ErrorResponse;
import com.triples.rougether.domain.bugreport.entity.BugReportStatus;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// 어드민 버그 제보 관리 (#213). 화면(/bug-reports)에서 목록·상태 변경을 호출한다.
@RestController
@RequestMapping("/admin/bug-reports")
public class BugReportAdminController {

    private final BugReportAdminService bugReportAdminService;

    public BugReportAdminController(BugReportAdminService bugReportAdminService) {
        this.bugReportAdminService = bugReportAdminService;
    }

    @GetMapping
    public Map<String, List<AdminBugReportResponse>> getReports(
            @RequestParam(value = "status", required = false) BugReportStatus status) {
        return Map.of("items", bugReportAdminService.getReports(status));
    }

    @PatchMapping("/{id}/status")
    public AdminBugReportResponse changeStatus(@PathVariable Long id,
                                               @RequestBody Map<String, String> request) {
        BugReportStatus status;
        try {
            status = BugReportStatus.valueOf(request.getOrDefault("status", ""));
        } catch (IllegalArgumentException e) {
            throw new BugReportAdminException("BUG_REPORT_STATUS_INVALID",
                    "허용되지 않은 상태입니다: " + request.get("status"), 400);
        }
        return bugReportAdminService.changeStatus(id, status);
    }

    @ExceptionHandler(BugReportAdminException.class)
    public ResponseEntity<ErrorResponse> handleInvalid(BugReportAdminException exception) {
        return ResponseEntity.status(exception.getStatus())
                .body(ErrorResponse.of(exception.getCode(), exception.getMessage()));
    }
}
