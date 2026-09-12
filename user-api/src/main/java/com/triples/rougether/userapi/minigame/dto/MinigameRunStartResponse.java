package com.triples.rougether.userapi.minigame.dto;

import java.time.Instant;

public record MinigameRunStartResponse(String runId, String gameCode, int rulesVersion,
                                       int seed, int maxTicks, Instant expiresAt) {}
