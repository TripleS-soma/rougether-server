package com.triples.rougether.userapi.attendance.web;

import com.triples.rougether.userapi.attendance.dto.AttendanceCheckInResponse;
import com.triples.rougether.userapi.attendance.dto.AttendanceEventStatusResponse;
import com.triples.rougether.userapi.attendance.service.AttendanceEventService;
import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Attendance Event", description = "연속 출석 이벤트 API")
@RestController
@RequestMapping("/api/v1/events/attendance")
@RequiredArgsConstructor
public class AttendanceEventController {

    private final AttendanceEventService attendanceEventService;

    @Operation(summary = "현재 연속 출석 이벤트 상태 조회")
    @GetMapping
    public AttendanceEventStatusResponse getStatus(@CurrentUser AuthUser user) {
        return attendanceEventService.getStatus(user.id());
    }

    @Operation(summary = "오늘 출석 체크")
    @PostMapping("/check-ins")
    public AttendanceCheckInResponse checkIn(@CurrentUser AuthUser user) {
        return attendanceEventService.checkIn(user.id());
    }
}
