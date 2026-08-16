package com.triples.rougether.userapi.calendar.web;

import com.triples.rougether.userapi.calendar.dto.CalendarDayResponse;
import com.triples.rougether.userapi.calendar.dto.CalendarMonthResponse;
import com.triples.rougether.userapi.calendar.service.CalendarService;
import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.time.YearMonth;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Calendar", description = "캘린더 관련 API")
@RestController
@RequestMapping("/api/v1/calendar")
@RequiredArgsConstructor
public class CalendarController {

    private final CalendarService calendarService;

    @Operation(summary = "캘린더 날짜별 루틴·투두 조회",
            description = "지정한 날짜의 대상 루틴·투두를 카테고리별로 묶어 달성 여부·진행률과 함께 반환합니다. "
                    + "루틴은 그날 반복 대상(DAILY, 또는 WEEKLY의 해당 요일)이고 기간(startsOn~endsOn) 안인 것만, "
                    + "투두는 마감일(dueDate)이 그날인 것만 포함합니다. 루틴 달성 여부는 그날 완료 기록으로, "
                    + "투두 달성 여부는 상태(status)로 판정합니다. 본인 소유 루틴·투두만 조회됩니다.")
    @GetMapping
    public CalendarDayResponse day(
            @CurrentUser AuthUser authUser,
            @Parameter(description = "조회 기준일(YYYY-MM-DD)", required = true, example = "2026-06-30")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return calendarService.day(authUser.id(), date);
    }

    @Operation(summary = "캘린더 월별 루틴·투두 개수 조회",
            description = "지정한 달의 모든 날짜(1일~말일)에 대해 그날 대상 루틴 개수와 마감 투두 개수만 반환합니다. "
                    + "목록·완료 여부는 내려주지 않으며 달력 화면의 날짜별 표시(개수·점) 용도입니다. "
                    + "날짜별 소싱 규칙은 GET /api/v1/calendar와 동일합니다 — 오늘·미래는 현재 ACTIVE 루틴의 반복 대상, "
                    + "어제는 그날 유효했던 버전으로 재계산, 그제 이전은 그날 수행 기록(COMPLETED+FAILED) 건수. "
                    + "투두는 마감일(dueDate)이 그날인 것만 셉니다. 대상이 없는 날도 0으로 포함하며 본인 소유만 집계합니다.")
    @GetMapping("/month")
    public CalendarMonthResponse month(
            @CurrentUser AuthUser authUser,
            @Parameter(description = "조회 월(YYYY-MM)", required = true, example = "2026-08")
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth yearMonth) {
        return calendarService.month(authUser.id(), yearMonth);
    }
}
