package com.triples.rougether.userapi.minigame.dto;

import java.util.List;

public record MinigameLeaderboardResponse(List<Entry> items, Entry myEntry, long totalPlayers) {
    public record Entry(long rank, long userId, String nickname, int score) {}
}
