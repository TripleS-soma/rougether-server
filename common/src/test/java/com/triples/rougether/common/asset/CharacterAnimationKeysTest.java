package com.triples.rougether.common.asset;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class CharacterAnimationKeysTest {

    @Test
    void 세_파일의_이름과_검사_순서를_유지한다() {
        CharacterAnimationKeys keys = CharacterAnimationKeys.of("cat");

        assertThat(keys.idle()).isEqualTo("characters/cat/animations/idle.webp");
        assertThat(keys.poseCycle()).isEqualTo("characters/cat/animations/pose-cycle.webp");
        assertThat(keys.wave()).isEqualTo("characters/cat/animations/wave.webp");
        assertThat(keys.all()).containsExactly(
                "characters/cat/animations/idle.webp",
                "characters/cat/animations/pose-cycle.webp",
                "characters/cat/animations/wave.webp");
    }

    @Test
    void 세_파일에서_캐릭터_코드를_동일하게_추출한다() {
        for (String key : List.of(
                "characters/cat/animations/idle.webp",
                "characters/cat/animations/pose-cycle.webp",
                "characters/cat/animations/wave.webp")) {
            assertThat(CharacterAnimationKeys.matches(key)).as(key).isTrue();
            assertThat(CharacterAnimationKeys.characterCode(key)).as(key).contains("cat");
        }
    }

    @Test
    void 경로_생성은_코드를_검증하거나_보정하지_않는다() {
        assertThat(CharacterAnimationKeys.of(" Cat ").idle())
                .isEqualTo("characters/ Cat /animations/idle.webp");
        assertThat(CharacterAnimationKeys.of("").idle())
                .isEqualTo("characters//animations/idle.webp");
        assertThat(CharacterAnimationKeys.of(null).idle())
                .isEqualTo("characters/null/animations/idle.webp");
    }

    @Test
    void 이름_분류는_요청별_안전한_경로_검증을_대신하지_않는다() {
        assertThat(CharacterAnimationKeys.characterCode("characters/Upper_Cat/animations/idle.webp"))
                .contains("Upper_Cat");
        assertThat(CharacterAnimationKeys.characterCode("characters/space cat/animations/wave.webp"))
                .contains("space cat");
        assertThat(CharacterAnimationKeys.characterCode("characters/cat../animations/pose-cycle.webp"))
                .contains("cat..");
    }

    @Test
    void 다른_동작_확장자_계층_및_null은_표준_애니메이션이_아니다() {
        for (String key : List.of(
                "", "characters//animations/idle.webp",
                "characters/cat/animations/run.webp",
                "characters/cat/animations/idle.png",
                "characters/cat/animations/idleXwebp",
                "characters/cat/animations/idle.WEBP",
                "characters/cat/animations/Idle.webp",
                "characters/cat/nested/animations/idle.webp",
                "characters/cat/animations/wave.webp/extra",
                "characters/cat/animations/idle.webp?version=1",
                "prefix/characters/cat/animations/idle.webp")) {
            assertThat(CharacterAnimationKeys.matches(key)).as(key).isFalse();
            assertThat(CharacterAnimationKeys.characterCode(key)).as(key).isEmpty();
        }
        assertThat(CharacterAnimationKeys.matches(null)).isFalse();
        assertThat(CharacterAnimationKeys.characterCode(null)).isEmpty();
    }
}
