package com.triples.rougether.userapi.auth.service;

import java.time.Instant;

public record GeneratedRefreshToken(String raw, String tokenHash, Instant expiresAt) {
}
