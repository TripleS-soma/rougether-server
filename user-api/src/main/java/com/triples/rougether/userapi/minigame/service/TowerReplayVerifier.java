package com.triples.rougether.userapi.minigame.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.userapi.minigame.dto.MinigameFinishRequest;
import com.triples.rougether.userapi.minigame.error.MinigameErrorCode;
import java.util.List;
import org.springframework.stereotype.Component;

// 모바일 tower-engine.ts의 60 Hz 정수 규칙을 재생해 종료 시점과 점수를 검증함 (mobile #1529).
// 규칙 버전 3에서 처음 생긴 게임이라 3만 받음. 입력은 기존 jumpTicks 필드에 떨어뜨린 틱을 담음.
// 한 틱의 순서: 틱 +1 → 움직이는 상자 이동(벽 반사) → 떨어뜨리기(6틱 쿨다운) → 시간 한도 검사.
@Component
public class TowerReplayVerifier implements MinigameReplayVerifier {
    public static final int MAX_TICKS = 18_000;
    public static final int MAX_DROPS = 3_000;
    public static final int RULES_VERSION = 3;
    private static final int STAGE = 1_000;
    private static final int BASE = 600;
    private static final int PERFECT = 8;
    private static final int GROW = 20;
    private static final int GROW_AFTER = 3;
    private static final int SPEED0 = 6;
    private static final int SPEED_MAX = 18;
    private static final int SPEED_EVERY = 5;
    private static final int COOLDOWN = 6;

    @Override
    public String gameCode() {
        return MinigameCatalog.TOWER;
    }

    @Override
    public int maxTicks() {
        return MAX_TICKS;
    }

    @Override
    public int rulesVersion() {
        return RULES_VERSION;
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
        return verify(seed, rulesVersion, request.ticks(), request.jumpTicks()).score();
    }

    public void validateInput(Integer ticks, List<Integer> dropTicks) {
        if (ticks == null || ticks < 1 || ticks > MAX_TICKS
                || dropTicks == null || dropTicks.size() > MAX_DROPS) {
            throw invalidReplay();
        }
        // 엔진은 쿨다운 안의 입력을 기록하지 않으므로 간격 6 미만은 조작된 기록임.
        int previous = 0;
        for (Integer drop : dropTicks) {
            if (drop == null || drop <= previous || drop > ticks
                    || (previous > 0 && drop - previous < COOLDOWN)) {
                throw invalidReplay();
            }
            previous = drop;
        }
    }

    public Result verify(int seed, int rulesVersion, int ticks, List<Integer> dropTicks) {
        validateInput(ticks, dropTicks);
        if (seed < 1 || rulesVersion != RULES_VERSION) {
            throw invalidReplay();
        }
        Engine engine = new Engine(seed);
        int nextDrop = 0;
        for (int tick = 1; tick <= ticks; tick++) {
            boolean press = nextDrop < dropTicks.size() && dropTicks.get(nextDrop) == tick;
            if (press) {
                nextDrop++;
            }
            engine.step(press);
            if (engine.endReason != null) {
                // 종료 뒤에 틱이나 입력을 덧붙인 기록은 거부함.
                if (tick != ticks) {
                    throw invalidReplay();
                }
                return new Result(engine.score, engine.layers, engine.endReason);
            }
        }
        throw new BusinessException(MinigameErrorCode.RUN_NOT_FINISHED);
    }

    private static BusinessException invalidReplay() {
        return new BusinessException(MinigameErrorCode.INVALID_REPLAY);
    }

    public record Result(int score, int layers, String endReason) {
    }

    private static final class Engine {
        // JS의 (Math.imul(1664525, rng) + 1013904223) >>> 0 과 같도록 부호 없는 32비트를 long에 담음.
        private long rng;
        private int tick;
        private int score;
        private int layers;
        private int streak;
        private int speed = SPEED0;
        private int lastDropTick = -COOLDOWN;
        private int topX = (STAGE - BASE) / 2;
        private int topWidth = BASE;
        private int movingX;
        private int movingWidth;
        private int movingDir;
        private String endReason;

        private Engine(int seed) {
            rng = seed & 0xffff_ffffL;
            spawn();
        }

        private long random() {
            rng = (1_664_525L * rng + 1_013_904_223L) & 0xffff_ffffL;
            return rng;
        }

        // 위치·방향 순서로 난수를 두 번 소비함. 위치는 상위 24비트, 방향은 상위 16비트의 마지막 비트.
        private void spawn() {
            movingWidth = topWidth;
            movingX = (int) ((random() >>> 8) % (STAGE - movingWidth + 1));
            movingDir = ((random() >>> 16) & 1) == 0 ? 1 : -1;
        }

        private void step(boolean press) {
            tick += 1;
            int maxX = STAGE - movingWidth;
            movingX += movingDir * speed;
            if (movingX < 0) {
                movingX = -movingX;
                movingDir = 1;
            }
            if (movingX > maxX) {
                movingX = 2 * maxX - movingX;
                movingDir = -1;
            }
            if (press && tick - lastDropTick >= COOLDOWN) {
                lastDropTick = tick;
                drop();
            }
            if (endReason == null && tick >= MAX_TICKS) {
                endReason = "limit";
            }
        }

        private void drop() {
            int offset = movingX - topX;
            int placedX;
            int placedWidth;
            if (offset <= PERFECT && offset >= -PERFECT) {
                placedX = topX;
                placedWidth = topWidth;
                streak += 1;
                score += 2;
                if (streak >= GROW_AFTER && placedWidth < BASE) {
                    int grown = Math.min(BASE, placedWidth + GROW);
                    // grown >= placedWidth라 피제수가 음수가 아니므로 정수 나눗셈 = Math.floor.
                    placedX -= (grown - placedWidth) / 2;
                    placedWidth = grown;
                    if (placedX < 0) {
                        placedX = 0;
                    }
                    if (placedX > STAGE - grown) {
                        placedX = STAGE - grown;
                    }
                }
            } else {
                int left = Math.max(movingX, topX);
                int right = Math.min(movingX + movingWidth, topX + topWidth);
                if (right - left <= 0) {
                    endReason = "miss";
                    return;
                }
                placedX = left;
                placedWidth = right - left;
                streak = 0;
                score += 1;
            }
            topX = placedX;
            topWidth = placedWidth;
            layers += 1;
            speed = Math.min(SPEED_MAX, SPEED0 + layers / SPEED_EVERY);
            spawn();
        }
    }
}
