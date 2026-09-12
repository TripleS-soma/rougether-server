package com.triples.rougether.userapi.minigame.service;

import com.triples.rougether.userapi.minigame.dto.MinigameFinishRequest;

public interface MinigameReplayVerifier {
    int TICKS_PER_SECOND = 60;

    String gameCode();

    int maxTicks();

    default int rulesVersion() {
        return 1;
    }

    void validateInput(MinigameFinishRequest request);

    int verify(int seed, int rulesVersion, MinigameFinishRequest request);
}
