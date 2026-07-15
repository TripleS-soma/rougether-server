package com.triples.rougether.adminapi.character.dto;

import jakarta.validation.constraints.NotBlank;

// POST /admin/users/{userId}/characters/grant 요청. 개발/QA용 캐릭터 지급.
public record CharacterGrantRequest(@NotBlank String characterCode) {
}
