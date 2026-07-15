package com.triples.rougether.adminapi.character.dto;

public record CharacterGrantResponse(
        Long userId,
        Long characterId,
        String characterCode,
        // 이미 보유 중이었으면 true (지급은 멱등 — 중복 지급되지 않음)
        boolean alreadyOwned,
        // 이번 지급으로 착용까지 됐는지 (첫 캐릭터일 때만 자동 착용)
        boolean selected) {
}
