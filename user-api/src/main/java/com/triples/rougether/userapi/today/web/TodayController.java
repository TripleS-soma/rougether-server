package com.triples.rougether.userapi.today.web;

import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUser;
import com.triples.rougether.userapi.today.dto.TodayResponse;
import com.triples.rougether.userapi.today.service.TodayService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Today", description = "오늘 현황 API")
@RestController
@RequestMapping("/api/v1/today")
@RequiredArgsConstructor
public class TodayController {

    private final TodayService todayService;

    @Operation(summary = "오늘 현황 조회",
            description = "오늘(또는 지정일) 대상 루틴·투두를 카테고리별로 묶어 진행률·스트릭과 함께 반환합니다. "
                    + "date를 지정하지 않으면 KST 기준 오늘입니다.")
    @GetMapping
    public TodayResponse today(
            @CurrentUser AuthUser authUser,
            @Parameter(description = "조회 기준일(ISO-8601, 미지정이면 오늘 KST)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return todayService.today(authUser.id(), date);
    }
}
