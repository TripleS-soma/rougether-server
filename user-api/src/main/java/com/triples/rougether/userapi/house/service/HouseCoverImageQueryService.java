package com.triples.rougether.userapi.house.service;

import com.triples.rougether.userapi.house.dto.HouseCoverImageListResponse;
import com.triples.rougether.userapi.house.dto.HouseCoverImageListResponse.HouseCoverImage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

// 집 생성·수정 화면에서 선택할 수 있는 게시 승인 이미지 key 목록을 조회함.
@Service
@RequiredArgsConstructor
public class HouseCoverImageQueryService {

    private final HouseCoverImageCatalog houseCoverImageCatalog;

    public HouseCoverImageListResponse getCoverImages() {
        return new HouseCoverImageListResponse(houseCoverImageCatalog.keys().stream()
                .map(HouseCoverImage::new)
                .toList());
    }
}
