package com.triples.rougether.userapi.minigame.dto;

public record MinigameFinishResponse(String runId, int score, int bestScore,
                                     boolean personalBest, long rank) {}
