package com.triples.rougether.userapi.minigame.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.userapi.minigame.dto.MinigameDirection;
import com.triples.rougether.userapi.minigame.dto.MinigameFinishRequest;
import com.triples.rougether.userapi.minigame.error.MinigameErrorCode;
import org.springframework.stereotype.Component;

// 모바일과 같은 60 Hz 타이머와 정수 난수로 계단 입력 및 종료 시점을 재생함.
@Component
public class StairsReplayVerifier implements MinigameReplayVerifier {
    public static final int MAX_TICKS = 7_200;
    public static final int MAX_INPUTS = 1_200;
    private static final int INPUT_INTERVAL = 6;

    @Override
    public String gameCode() {
        return MinigameCatalog.STAIRS;
    }

    @Override
    public int maxTicks() {
        return MAX_TICKS;
    }

    @Override
    public void validateInput(MinigameFinishRequest request) {
        if (request == null || request.ticks() == null || request.ticks() < 1
                || request.ticks() > MAX_TICKS || request.jumpTicks() != null
                || request.actions() == null || request.actions().size() > MAX_INPUTS) {
            throw invalidReplay();
        }
        int previous = 0;
        for (var action : request.actions()) {
            if (action == null || action.tick() == null || action.tick() <= previous
                    || action.tick() > request.ticks()
                    || (previous > 0 && action.tick() - previous < INPUT_INTERVAL)
                    || (action.direction() != MinigameDirection.LEFT
                        && action.direction() != MinigameDirection.RIGHT)) {
                throw invalidReplay();
            }
            previous = action.tick();
        }
    }

    @Override
    public int verify(int seed, int rulesVersion, MinigameFinishRequest request) {
        validateInput(request);
        if (seed < 1 || rulesVersion != rulesVersion()) {
            throw invalidReplay();
        }

        RandomState random = new RandomState(seed);
        int column = 0;
        MinigameDirection expected = random.nextDirection(column);
        int timer = 180;
        int score = 0;
        int nextAction = 0;

        for (int tick = 1; tick <= request.ticks(); tick++) {
            boolean hasAction = nextAction < request.actions().size()
                    && request.actions().get(nextAction).tick() == tick;

            // 만료 프레임에는 입력을 기록하지 않으므로 같은 프레임 입력도 거절함.
            if (--timer <= 0) {
                if (hasAction || tick != request.ticks()) {
                    throw invalidReplay();
                }
                return score;
            }

            if (hasAction) {
                MinigameDirection direction = request.actions().get(nextAction++).direction();
                if (direction != expected) {
                    if (tick != request.ticks()) {
                        throw invalidReplay();
                    }
                    return score;
                }
                column += direction == MinigameDirection.LEFT ? -1 : 1;
                score++;
                timer = Math.max(45, 180 - (score / 5) * 6);
                expected = random.nextDirection(column);
            }
            if (tick == MAX_TICKS) {
                return score;
            }
        }
        throw new BusinessException(MinigameErrorCode.RUN_NOT_FINISHED);
    }

    private static BusinessException invalidReplay() {
        return new BusinessException(MinigameErrorCode.INVALID_REPLAY);
    }

    private static final class RandomState {
        private long state;

        private RandomState(int seed) {
            state = seed;
        }

        private MinigameDirection nextDirection(int column) {
            // 경계 방향을 강제하더라도 난수를 한 번 소비해야 모바일과 수열이 일치함.
            state = (1_664_525L * state + 1_013_904_223L) & 0xffff_ffffL;
            if (column <= -3) {
                return MinigameDirection.RIGHT;
            }
            if (column >= 3) {
                return MinigameDirection.LEFT;
            }
            return ((state >>> 16) & 1) == 0 ? MinigameDirection.LEFT : MinigameDirection.RIGHT;
        }
    }
}
