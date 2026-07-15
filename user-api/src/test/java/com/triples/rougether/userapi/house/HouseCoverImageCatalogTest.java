package com.triples.rougether.userapi.house;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.userapi.house.config.HouseCoverImageProperties;
import com.triples.rougether.userapi.house.error.HouseErrorCode;
import com.triples.rougether.userapi.house.service.HouseCoverImageCatalog;
import java.util.List;
import org.junit.jupiter.api.Test;

class HouseCoverImageCatalogTest {

    @Test
    void 게시된_key를_중복_제거하고_오름차순으로_제공한다() {
        HouseCoverImageCatalog catalog = new HouseCoverImageCatalog(new HouseCoverImageProperties(List.of(
                "house/morning.webp",
                "house/forest.png",
                "house/morning.webp")));

        assertThat(catalog.keys()).containsExactly("house/forest.png", "house/morning.webp");
    }

    @Test
    void 게시되지_않은_key는_거부한다() {
        HouseCoverImageCatalog catalog = new HouseCoverImageCatalog(
                new HouseCoverImageProperties(List.of("house/forest.png")));

        assertThatThrownBy(() -> catalog.validatePublished("house/not-published.png"))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(HouseErrorCode.HOUSE_COVER_IMAGE_INVALID));
    }

    @Test
    void 커버를_생략하면_허용한다() {
        HouseCoverImageCatalog catalog = new HouseCoverImageCatalog(
                new HouseCoverImageProperties(List.of("house/forest.png")));

        catalog.validatePublished(null);
    }

    @Test
    void manifest에_집_이미지가_아닌_key가_있으면_기동_설정을_거부한다() {
        HouseCoverImageProperties properties =
                new HouseCoverImageProperties(List.of("characters/cat.png"));

        assertThatThrownBy(() -> new HouseCoverImageCatalog(properties))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void manifest의_확장자는_저장_규칙과_같이_소문자만_허용한다() {
        HouseCoverImageProperties properties =
                new HouseCoverImageProperties(List.of("house/forest.PNG"));

        assertThatThrownBy(() -> new HouseCoverImageCatalog(properties))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void manifest_key가_DB_컬럼_길이를_넘으면_기동_설정을_거부한다() {
        String tooLongKey = "house/" + "a".repeat(246) + ".png";
        HouseCoverImageProperties properties =
                new HouseCoverImageProperties(List.of(tooLongKey));

        assertThat(tooLongKey).hasSize(256);
        assertThatThrownBy(() -> new HouseCoverImageCatalog(properties))
                .isInstanceOf(IllegalStateException.class);
    }
}
