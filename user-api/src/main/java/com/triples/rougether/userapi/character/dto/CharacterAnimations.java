package com.triples.rougether.userapi.character.dto;

import io.swagger.v3.oas.annotations.media.Schema;

// 캐릭터 애니메이션(APNG) asset key 묶음. S3 적재 규칙 characters/{code}/animations/ 에서 파생한다.
// DB 에 저장하지 않으므로, 새 캐릭터를 카탈로그에 등록할 때 애니메이션 3종(idle/pose-cycle/wave)
// 적재가 전제 조건이다 (docs/claude/domains/assets.md 구현 노트 참고).
public record CharacterAnimations(
        @Schema(description = "대기 동작 asset key (CDN base URL 과 조합해 사용)",
                example = "characters/cat/animations/idle.png")
        String idle,
        @Schema(description = "포즈 사이클 동작 asset key",
                example = "characters/cat/animations/pose-cycle.png")
        String poseCycle,
        @Schema(description = "손 흔들기 동작 asset key",
                example = "characters/cat/animations/wave.png")
        String wave) {

    public static CharacterAnimations of(String code) {
        String base = "characters/" + code + "/animations/";
        return new CharacterAnimations(base + "idle.png", base + "pose-cycle.png", base + "wave.png");
    }
}
