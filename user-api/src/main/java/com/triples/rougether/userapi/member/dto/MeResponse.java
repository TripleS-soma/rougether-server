package com.triples.rougether.userapi.member.dto;

import java.time.OffsetDateTime;

public record MeResponse(Long userId, String nickname, OffsetDateTime lastLoginAt) {
}
