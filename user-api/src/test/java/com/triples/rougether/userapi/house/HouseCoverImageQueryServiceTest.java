package com.triples.rougether.userapi.house;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.triples.rougether.userapi.house.dto.HouseCoverImageListResponse;
import com.triples.rougether.userapi.house.service.HouseCoverImageCatalog;
import com.triples.rougether.userapi.house.service.HouseCoverImageCatalog.PublishedCoverImage;
import com.triples.rougether.userapi.house.service.HouseCoverImageQueryService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HouseCoverImageQueryServiceTest {

    @Mock
    private HouseCoverImageCatalog houseCoverImageCatalog;

    @InjectMocks
    private HouseCoverImageQueryService houseCoverImageQueryService;

    @Test
    void 게시된_이미지의_code_name_key를_반환한다() {
        when(houseCoverImageCatalog.items()).thenReturn(List.of(
                new PublishedCoverImage("forest", "버섯 숲 집", "house/forest.png"),
                new PublishedCoverImage("morning", "아침 햇살 집", "house/morning.png")));

        HouseCoverImageListResponse response = houseCoverImageQueryService.getCoverImages();

        assertThat(response.items())
                .extracting(
                        HouseCoverImageListResponse.HouseCoverImage::code,
                        HouseCoverImageListResponse.HouseCoverImage::name,
                        HouseCoverImageListResponse.HouseCoverImage::coverImageKey)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("forest", "버섯 숲 집", "house/forest.png"),
                        org.assertj.core.groups.Tuple.tuple("morning", "아침 햇살 집", "house/morning.png"));
    }

    @Test
    void 이미지가_없으면_빈_목록을_반환한다() {
        when(houseCoverImageCatalog.items()).thenReturn(List.of());

        HouseCoverImageListResponse response = houseCoverImageQueryService.getCoverImages();

        assertThat(response.items()).isEmpty();
    }
}
