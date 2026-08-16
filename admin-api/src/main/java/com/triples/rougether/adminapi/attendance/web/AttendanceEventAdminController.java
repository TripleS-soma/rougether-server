package com.triples.rougether.adminapi.attendance.web;

import com.triples.rougether.adminapi.attendance.dto.AttendanceEventCreateRequest;
import com.triples.rougether.adminapi.attendance.dto.AttendanceEventCreateResponse;
import com.triples.rougether.adminapi.attendance.error.AttendanceEventAdminException;
import com.triples.rougether.adminapi.attendance.service.AttendanceEventAdminService;
import com.triples.rougether.common.error.ErrorResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/attendance-events")
@RequiredArgsConstructor
public class AttendanceEventAdminController {

    private final AttendanceEventAdminService attendanceEventAdminService;

    @PostMapping
    public ResponseEntity<AttendanceEventCreateResponse> create(
            @Valid @RequestBody AttendanceEventCreateRequest request) {
        return ResponseEntity.status(201).body(attendanceEventAdminService.create(request));
    }

    @ExceptionHandler(AttendanceEventAdminException.class)
    public ResponseEntity<ErrorResponse> handleAttendanceEventAdmin(AttendanceEventAdminException exception) {
        return ResponseEntity.status(exception.status())
                .body(ErrorResponse.of(exception.code(), exception.getMessage()));
    }
}
