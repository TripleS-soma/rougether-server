package com.triples.rougether.adminapi.activity.web;

import com.triples.rougether.adminapi.activity.dto.AdminUserActivityResponse;
import com.triples.rougether.adminapi.activity.dto.UserActivityFeature;
import com.triples.rougether.adminapi.activity.service.AdminUserActivityService;
import com.triples.rougether.adminapi.activity.error.AdminActivityDateInvalidException;
import com.triples.rougether.adminapi.activity.error.AdminActivityBusyException;
import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.common.error.ErrorResponse;
import java.time.LocalDate;
import org.springframework.http.ResponseEntity;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/user-activities")
public class AdminUserActivityController {
    private final AdminUserActivityService service;

    public AdminUserActivityController(AdminUserActivityService service) { this.service = service; }

    @GetMapping
    public AdminUserActivityResponse getActivities(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "") String query,
            @RequestParam(required = false) UserActivityFeature feature,
            @RequestParam(defaultValue = "false") boolean actionsOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "30") int size) {
        return service.getActivities(date, query, feature, actionsOnly, page, size);
    }

    @ExceptionHandler({AdminActivityDateInvalidException.class, AdminActivityBusyException.class})
    public ResponseEntity<ErrorResponse> handleActivityError(BusinessException exception) {
        return ResponseEntity.status(exception.getErrorCode().status()).body(ErrorResponse.of(
                exception.getErrorCode().code(), exception.getErrorCode().message()));
    }
}
