package com.triples.rougether.userapi.house.web;

import com.triples.rougether.userapi.house.dto.HouseCoverImageListResponse;
import com.triples.rougether.userapi.house.service.HouseCoverImageQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "House", description = "공동집 관련 API")
@RestController
@RequestMapping("/api/v1/houses/cover-images")
@RequiredArgsConstructor
public class HouseCoverImageController {

    private final HouseCoverImageQueryService houseCoverImageQueryService;

    @Operation(
            summary = "집 커버 이미지 목록 조회",
            description = "집 생성·설정 화면에서 선택할 수 있는 커버 이미지 asset key를 오름차순으로 반환합니다. "
                    + "클라이언트는 CDN base URL과 coverImageKey를 조합해 이미지를 표시합니다.")
    @GetMapping
    public HouseCoverImageListResponse getCoverImages() {
        return houseCoverImageQueryService.getCoverImages();
    }
}
