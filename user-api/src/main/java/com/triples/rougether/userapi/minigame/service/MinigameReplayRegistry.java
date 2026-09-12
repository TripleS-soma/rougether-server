package com.triples.rougether.userapi.minigame.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.userapi.minigame.error.MinigameErrorCode;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class MinigameReplayRegistry {
    private final Map<String, MinigameReplayVerifier> verifiers;

    public MinigameReplayRegistry(List<MinigameReplayVerifier> verifiers) {
        this.verifiers = verifiers.stream().collect(Collectors.toUnmodifiableMap(
                MinigameReplayVerifier::gameCode, Function.identity()));
    }

    public MinigameReplayVerifier get(String gameCode) {
        MinigameReplayVerifier verifier = verifiers.get(gameCode);
        if (verifier == null) {
            throw new BusinessException(MinigameErrorCode.GAME_NOT_FOUND);
        }
        return verifier;
    }
}
