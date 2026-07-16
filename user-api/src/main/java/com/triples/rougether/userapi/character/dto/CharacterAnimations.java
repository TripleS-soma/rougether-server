package com.triples.rougether.userapi.character.dto;

import io.swagger.v3.oas.annotations.media.Schema;

// 캐릭터 애니메이션(애니메이션 WebP) asset key 묶음. S3 적재 규칙 characters/{code}/animations/ 에서 파생한다.
// WebP 인 이유: APNG 는 RN Android 에서 재생되지 않고, WebP 는 양 플랫폼 재생 + 용량 ~75% 절감.
// DB 에 저장하지 않으므로, 새 캐릭터를 카탈로그에 등록할 때 애니메이션 3종(idle/pose-cycle/wave)
// 적재가 전제 조건이다 (docs/claude/domains/assets.md 구현 노트 참고).
public record CharacterAnimations(
        @Schema(description = "대기 동작 asset key (CDN base URL 과 조합해 사용)",
                example = "characters/cat/animations/idle.webp")
        String idle,
        @Schema(description = "포즈 사이클 동작 asset key",
                example = "characters/cat/animations/pose-cycle.webp")
        String poseCycle,
        @Schema(description = "손 흔들기 동작 asset key",
                example = "characters/cat/animations/wave.webp")
        String wave) {

    public static CharacterAnimations of(String code) {
        String base = "characters/" + code + "/animations/";
        return new CharacterAnimations(base + "idle.webp", base + "pose-cycle.webp", base + "wave.webp");
    }
}
