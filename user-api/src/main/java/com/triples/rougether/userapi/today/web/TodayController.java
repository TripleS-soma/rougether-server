package com.triples.rougether.userapi.today.web;

import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUser;
import com.triples.rougether.userapi.today.dto.TodayResponse;
import com.triples.rougether.userapi.today.service.TodayService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Today", description = "오늘 현황 API")
@RestController
@RequestMapping("/api/v1/today")
@RequiredArgsConstructor
public class TodayController {

    private final TodayService todayService;

    @Operation(summary = "오늘 현황 조회",
            description = "오늘(KST 기준) 대상 루틴·투두를 카테고리별로 묶어 진행률·스트릭과 함께 반환합니다. "
                    + "루틴은 ACTIVE 상태이면서 시작일~종료일 범위 안이고 반복 규칙에 해당하는 것만 포함합니다"
                    + "(DAILY는 매일, WEEKLY는 repeatDays.daysOfWeek에 오늘 요일이 포함될 때). "
                    + "투두는 마감일이 오늘과 정확히 같은 것만 포함하며(지난 마감·미래 마감 제외), 마감일이 없는 투두는 포함하지 않습니다. "
                    + "카테고리 그룹은 categoryId 오름차순으로 정렬하고 미분류 그룹은 맨 뒤에 둡니다. "
                    + "summary는 포함된 루틴·투두의 완료/남은 개수와 진행률, streak는 회원의 루틴 스트릭 요약입니다.")
    @GetMapping
    public TodayResponse today(@CurrentUser AuthUser authUser) {
        return todayService.today(authUser.id());
    }
}
