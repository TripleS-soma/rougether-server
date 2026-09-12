package com.triples.rougether.userapi.minigame.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.userapi.minigame.dto.MinigameFinishRequest;
import com.triples.rougether.userapi.minigame.error.MinigameErrorCode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

// 모바일의 60 Hz 정수 물리를 재생해 종료 시점과 점수를 검증함. 규칙 변경 시 버전을 올려야 함.
@Component
public class RunnerReplayVerifier implements MinigameReplayVerifier {
    public static final int MAX_TICKS = 18_000;
    public static final int TICKS_PER_SECOND = 60;
    public static final int MAX_JUMPS = 600;

    @Override
    public String gameCode() {
        return MinigameCatalog.RUNNER;
    }

    @Override
    public int maxTicks() {
        return MAX_TICKS;
    }

    @Override
    public void validateInput(MinigameFinishRequest request) {
        if (request == null || request.actions() != null) {
            throw invalidReplay();
        }
        validateInput(request.ticks(), request.jumpTicks());
    }

    @Override
    public int verify(int seed, int rulesVersion, MinigameFinishRequest request) {
        validateInput(request);
        return verify(seed, rulesVersion, request.ticks(), request.jumpTicks());
    }

    public void validateInput(Integer ticks, List<Integer> jumpTicks) {
        if (ticks == null || ticks < 1 || ticks > MAX_TICKS
                || jumpTicks == null || jumpTicks.size() > MAX_JUMPS) {
            throw invalidReplay();
        }
        int previous = 0;
        for (Integer jump : jumpTicks) {
            if (jump == null || jump <= previous || jump > ticks) {
                throw invalidReplay();
            }
            previous = jump;
        }
    }

    public int verify(int seed, int rulesVersion, int ticks, List<Integer> jumpTicks) {
        validateInput(ticks, jumpTicks);
        if (seed < 1 || rulesVersion != MinigameCatalog.RULES_VERSION) {
            throw invalidReplay();
        }
        RandomState random = new RandomState(seed);
        List<Obstacle> obstacles = new ArrayList<>();
        int y = 0;
        int velocity = 0;
        int countdown = 90;
        int nextJump = 0;
        for (int tick = 1; tick <= ticks; tick++) {
            if (nextJump < jumpTicks.size() && jumpTicks.get(nextJump) == tick) {
                if (y != 0) {
                    throw invalidReplay();
                }
                velocity = 16;
                nextJump++;
            }
            y += velocity;
            velocity--;
            if (y <= 0) {
                y = 0;
                velocity = 0;
            }
            if (--countdown == 0) {
                obstacles.add(new Obstacle(720, 24 + random.next(3) * 10,
                        28 + random.next(3) * 10));
                countdown = 75 + random.next(46);
            }
            int speed = 6 + Math.min(6, tick / 600);
            boolean collided = false;
            for (Obstacle obstacle : obstacles) {
                obstacle.x -= speed;
                if (130 > obstacle.x && 100 < obstacle.x + obstacle.width && y < obstacle.height) {
                    collided = true;
                }
            }
            if (collided) {
                if (tick != ticks) {
                    throw invalidReplay();
                }
                return ticks / 6;
            }
            obstacles.removeIf(obstacle -> obstacle.x + obstacle.width < 0);
        }
        if (ticks != MAX_TICKS) {
            throw new BusinessException(MinigameErrorCode.RUN_NOT_FINISHED);
        }
        return ticks / 6;
    }

    private static BusinessException invalidReplay() {
        return new BusinessException(MinigameErrorCode.INVALID_REPLAY);
    }

    private static final class RandomState {
        private long state;

        private RandomState(int seed) {
            state = seed;
        }

        private int next(int bound) {
            state = (1_664_525L * state + 1_013_904_223L) & 0xffff_ffffL;
            return (int) (state % bound);
        }
    }

    private static final class Obstacle {
        private int x;
        private final int width;
        private final int height;

        private Obstacle(int x, int width, int height) {
            this.x = x;
            this.width = width;
            this.height = height;
        }
    }
}
