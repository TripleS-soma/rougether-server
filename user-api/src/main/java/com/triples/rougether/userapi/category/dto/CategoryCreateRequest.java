package com.triples.rougether.userapi.category.dto;

import com.triples.rougether.domain.routine.entity.PrivacyScope;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CategoryCreateRequest(
        @Schema(description = "카테고리 이름(최대 100자)", example = "운동")
        @NotBlank @Size(max = 100) String name,
        @Schema(description = "표시 색상 hex", example = "#FF8800")
        @Size(max = 20) String colorHex,
        @Schema(description = "아이콘 asset key. CDN base URL과 조합해 이미지 URL로 사용", example = "icon_health")
        @Size(max = 100) String iconKey,
        @Schema(description = "정렬 순서(0 이상, 작을수록 먼저). 미지정 시 맨 뒤 순서(기존 최대값+1, 첫 카테고리는 0) 자동 부여", example = "0")
        @Min(0) Integer sortOrder,
        @Schema(description = "공개 범위. 허용값: PRIVATE(비공개), FRIENDS(친한친구), HOUSE(집), PUBLIC(공개). 미지정 시 PRIVATE", example = "PRIVATE")
        PrivacyScope visibility,
        @Schema(description = "연동할 집 ID(선택, 미지정이면 미연동). 집 단체미션용 루틴을 묶는 카테고리임을 표시. "
                + "내 집 목록(GET /api/v1/me/houses) 응답의 houseId 값을 사용하며, 해당 집의 활성 구성원만 지정할 수 있음", example = "5")
        Long houseId
) {

    // 기존 클라이언트/테스트 호환용 (houseId 미지정 = 미연동)
    public CategoryCreateRequest(String name, String colorHex, String iconKey,
                                 Integer sortOrder, PrivacyScope visibility) {
        this(name, colorHex, iconKey, sortOrder, visibility, null);
    }
}
