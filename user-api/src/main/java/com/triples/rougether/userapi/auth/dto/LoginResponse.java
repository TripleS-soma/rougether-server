package com.triples.rougether.userapi.auth.dto;

public record LoginResponse(Long userId, String accessToken, String refreshToken, boolean isNewUser) {
}
