package com.triples.rougether.userapi.agenda.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

// Java 타입을 옮겨도 기존 OpenAPI 스키마 이름과 참조를 유지함
@Schema(name = "TodayCategoryGroup")
public record AgendaCategoryGroup(
        @Schema(description = "카테고리 ID(미분류면 null). 이름·색상은 내 카테고리 목록 조회(GET /api/v1/categories)에서 resolve", example = "3")
        Long categoryId,
        @Schema(description = "오늘 대상 루틴 목록. 수행 예정 시각 오름차순(미지정은 뒤), 같으면 id 오름차순")
        List<AgendaRoutineItem> routines,
        @Schema(description = "오늘 대상 투두 목록(기준일까지 마감인 투두, 지난 마감 포함)")
        List<AgendaTodoItem> todos
) {
}
