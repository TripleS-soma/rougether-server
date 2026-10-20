package com.triples.rougether.adminapi.asset;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class AssetKeyClassifierTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "characters/Upper_Cat/animations/idle.webp",
            "characters/space cat/animations/wave.webp",
            "characters/ /animations/idle.webp"
    })
    void 일반_분류기는_code의_대문자와_공백을_제한하지_않는다(String key) {
        assertThat(AssetKeyClassifier.isAnimated(key)).isTrue();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "characters/cat/nested/animations/idle.webp",
            "characters/cat/animations/nested/wave.webp"
    })
    void null이나_중첩된_캐릭터_경로는_애니메이션으로_분류하지_않는다(String key) {
        assertThat(AssetKeyClassifier.isAnimated(key)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "chair-animated-v12.webp",
            "house/decor/chair-animated-v1.webp"
    })
    void 가구_애니메이션_파일명_규칙은_items_접두사에_한정하지_않는다(String key) {
        assertThat(AssetKeyClassifier.isAnimated(key)).isTrue();
    }
}
