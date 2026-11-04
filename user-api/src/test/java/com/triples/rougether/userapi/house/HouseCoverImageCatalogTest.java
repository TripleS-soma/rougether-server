package com.triples.rougether.userapi.house;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.userapi.house.config.HouseCoverImageProperties;
import com.triples.rougether.userapi.house.config.HouseCoverImageProperties.Entry;
import com.triples.rougether.userapi.house.error.HouseErrorCode;
import com.triples.rougether.userapi.house.service.HouseCoverImageCatalog;
import java.util.List;
import org.junit.jupiter.api.Test;

class HouseCoverImageCatalogTest {

    @Test
    void 실제_게시_manifest에서_승인된_신규_테마와_기존_테마를_함께_허용한다() throws java.io.IOException {
        var source = new org.springframework.boot.env.YamlPropertySourceLoader()
                .load("application", new org.springframework.core.io.FileSystemResource("src/main/resources/application.yml"))
                .getFirst();
        var properties = new org.springframework.boot.context.properties.bind.Binder(
                org.springframework.boot.context.properties.source.ConfigurationPropertySources.from(source))
                .bind("house.cover-images", HouseCoverImageProperties.class).get();
        var catalog = new HouseCoverImageCatalog(properties);

        assertThat(catalog.items()).hasSize(6);
        catalog.validatePublished("house/forest-canopy/house-forest-canopy-frame-v1.png");
        catalog.validatePublished("house/evening-brick/house-evening-brick-frame-v1.png");
        catalog.validatePublished("house/cloud-balloon/house-unified-cloud-balloon-frame.png");
        catalog.validatePublished("house/coral-aquarium/house-unified-coral-aquarium-frame.png");
        catalog.validatePublished("house/mushroom-forest/house-unified-mushroom-forest-frame.png");
        catalog.validatePublished("house/night-observatory/house-unified-night-observatory-frame-v3.png");
    }

    private Entry entry(String code, String name, String key) {
        return new Entry(code, name, key);
    }

    @Test
    void 게시된_후보를_code_오름차순으로_제공한다() {
        HouseCoverImageCatalog catalog = new HouseCoverImageCatalog(new HouseCoverImageProperties(List.of(
                entry("morning", "아침 햇살 집", "house/morning.webp"),
                entry("forest", "버섯 숲 집", "house/forest.png"))));

        assertThat(catalog.items())
                .extracting(
                        HouseCoverImageCatalog.PublishedCoverImage::code,
                        HouseCoverImageCatalog.PublishedCoverImage::name,
                        HouseCoverImageCatalog.PublishedCoverImage::coverImageKey)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("forest", "버섯 숲 집", "house/forest.png"),
                        org.assertj.core.groups.Tuple.tuple("morning", "아침 햇살 집", "house/morning.webp"));
    }

    @Test
    void 게시되지_않은_key는_거부한다() {
        HouseCoverImageCatalog catalog = new HouseCoverImageCatalog(
                new HouseCoverImageProperties(List.of(
                        entry("forest", "버섯 숲 집", "house/forest.png"))));

        assertThatThrownBy(() -> catalog.validatePublished("house/not-published.png"))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(HouseErrorCode.HOUSE_COVER_IMAGE_INVALID));
    }

    @Test
    void 커버를_생략하면_허용한다() {
        HouseCoverImageCatalog catalog = new HouseCoverImageCatalog(
                new HouseCoverImageProperties(List.of(
                        entry("forest", "버섯 숲 집", "house/forest.png"))));

        catalog.validatePublished(null);
    }

    @Test
    void manifest에_집_이미지가_아닌_key가_있으면_기동_설정을_거부한다() {
        HouseCoverImageProperties properties =
                new HouseCoverImageProperties(List.of(
                        entry("cat", "고양이 집", "characters/cat.png")));

        assertThatThrownBy(() -> new HouseCoverImageCatalog(properties))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void manifest의_확장자는_저장_규칙과_같이_소문자만_허용한다() {
        HouseCoverImageProperties properties =
                new HouseCoverImageProperties(List.of(
                        entry("forest", "버섯 숲 집", "house/forest.PNG")));

        assertThatThrownBy(() -> new HouseCoverImageCatalog(properties))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void manifest_key가_DB_컬럼_길이를_넘으면_기동_설정을_거부한다() {
        String tooLongKey = "house/" + "a".repeat(246) + ".png";
        HouseCoverImageProperties properties =
                new HouseCoverImageProperties(List.of(
                        entry("forest", "버섯 숲 집", tooLongKey)));

        assertThat(tooLongKey).hasSize(256);
        assertThatThrownBy(() -> new HouseCoverImageCatalog(properties))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void code나_name이_비어_있으면_기동_설정을_거부한다() {
        assertThatThrownBy(() -> new HouseCoverImageCatalog(new HouseCoverImageProperties(List.of(
                entry("", "버섯 숲 집", "house/forest.png")))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new HouseCoverImageCatalog(new HouseCoverImageProperties(List.of(
                entry("forest", " ", "house/forest.png")))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void code나_key가_중복되면_기동_설정을_거부한다() {
        assertThatThrownBy(() -> new HouseCoverImageCatalog(new HouseCoverImageProperties(List.of(
                entry("forest", "버섯 숲 집", "house/forest.png"),
                entry("forest", "다른 숲 집", "house/other-forest.png")))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new HouseCoverImageCatalog(new HouseCoverImageProperties(List.of(
                entry("forest", "버섯 숲 집", "house/forest.png"),
                entry("other_forest", "다른 숲 집", "house/forest.png")))))
                .isInstanceOf(IllegalStateException.class);
    }
}
