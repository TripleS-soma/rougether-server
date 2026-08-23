package com.triples.rougether.userapi.recommendation.web;

import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUser;
import com.triples.rougether.userapi.recommendation.dto.RecommendationListResponse;
import com.triples.rougether.userapi.recommendation.service.RecommendationCommandService;
import com.triples.rougether.userapi.recommendation.service.RecommendationQueryService;
import com.triples.rougether.userapi.routine.dto.RoutineResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Recommendation", description = "AI 조정 추천 관련 API")
@RestController
@RequestMapping("/api/v1/recommendations")
@RequiredArgsConstructor
public class RecommendationController {

    private final RecommendationQueryService recommendationQueryService;
    private final RecommendationCommandService recommendationCommandService;

    @Operation(summary = "조정 추천 목록 조회",
            description = "내 활성 AI 조정 추천을 최신 생성순으로 반환합니다(사용자당 최대 3건). "
                    + "추천은 매주 일요일 배치가 최근 3주 루틴 수행 기록의 실패 패턴으로 생성하며, "
                    + "만료(생성 후 7일)·대상 루틴 삭제·추천 생성 뒤 스케줄을 직접 수정한 건은 목록에서 제외됩니다. "
                    + "추천이 없으면 빈 items 를 반환합니다.")
    @GetMapping
    public RecommendationListResponse getMyRecommendations(@CurrentUser AuthUser user) {
        return recommendationQueryService.getMyRecommendations(user.id());
    }

    @Operation(summary = "조정 추천 수락",
            description = "추천의 제안 스케줄(proposal)을 대상 루틴에 그대로 적용하고 적용된 루틴을 반환합니다. "
                    + "반복 스케줄 변경이라 루틴 수정(PUT /api/v1/routines/{id})과 동일하게 새 버전으로 분기되어 "
                    + "응답의 id 가 바뀝니다(originRoutineId 는 유지). 제안 스케줄 외의 필드(제목·카테고리·수행 시각·"
                    + "기간·단체미션 연동)는 그대로 보존됩니다. 아직 처리하지 않은 활성 추천만, 만료 전까지 수락할 수 "
                    + "있으며, 추천 생성 이후 스케줄이 그대로인 루틴에만 적용됩니다.")
    @PostMapping("/{recommendationId}/accept")
    public RoutineResponse accept(
            @CurrentUser AuthUser user,
            @Parameter(description = "추천 ID. 조정 추천 목록 조회 응답의 recommendationId 값")
            @PathVariable Long recommendationId) {
        return recommendationCommandService.accept(user.id(), recommendationId);
    }

    @Operation(summary = "조정 추천 무시",
            description = "추천을 무시 처리합니다. 루틴은 변경되지 않으며, 아직 처리하지 않은 활성 추천만 무시할 수 있습니다. "
                    + "무시한 계보의 루틴은 14일 동안 다시 추천되지 않습니다.")
    @PostMapping("/{recommendationId}/dismiss")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void dismiss(
            @CurrentUser AuthUser user,
            @Parameter(description = "추천 ID. 조정 추천 목록 조회 응답의 recommendationId 값")
            @PathVariable Long recommendationId) {
        recommendationCommandService.dismiss(user.id(), recommendationId);
    }
}
