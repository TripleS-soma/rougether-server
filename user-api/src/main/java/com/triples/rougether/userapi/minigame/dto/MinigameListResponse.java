package com.triples.rougether.userapi.minigame.dto;

import java.util.List;

public record MinigameListResponse(List<Item> items) {
    public record Item(String gameCode, String name, String description, int rulesVersion) {}
}
