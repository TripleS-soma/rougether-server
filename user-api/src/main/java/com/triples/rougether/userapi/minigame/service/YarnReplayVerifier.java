package com.triples.rougether.userapi.minigame.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.userapi.minigame.dto.MinigameFinishRequest;
import com.triples.rougether.userapi.minigame.error.MinigameErrorCode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

// 모바일 yarn-engine.ts의 정수 규칙을 그대로 재생해 점수를 검증함 (mobile #1563).
// 규칙 버전 3에서 처음 생긴 게임이라 3만 받음. 시계가 없는 게임이라 입력은 picks(집은 더미 번호 0..5 순서)이고
// ticks는 집은 횟수(최소 1). 한 번 집을 때의 순서: 바구니/대기열 → (바구니 완성 시) 자동 채우기 → 빈 더미 보충 → 2,000회 한도.
@Component
public class YarnReplayVerifier implements MinigameReplayVerifier {
    public static final int MAX_PICKS = 2_000;
    public static final int MAX_TICKS = MAX_PICKS;
    public static final int RULES_VERSION = 3;
    private static final int PILES = 6;
    private static final int BASKETS = 3;
    private static final int BASKET_CAP = 3;
    private static final int MAX_COLORS = 7;
    private static final int BASE_PALETTE = 4;
    private static final int CLEARED_PER_COLOR = 8;
    private static final int BASE_CAPACITY = 5;
    private static final int MAX_CAPACITY = 7;
    private static final int CLEARED_PER_SLOT = 15;
    private static final int PILE_BASE = 3;
    private static final int PILE_EXTRA = 2;
    private static final int BALL_SCORE = 1;
    private static final int BASKET_SCORE = 10;

    @Override
    public String gameCode() {
        return MinigameCatalog.YARN;
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
        if (request == null || request.jumpTicks() != null || request.actions() != null) {
            throw invalidReplay();
        }
        validateInput(request.ticks(), request.picks());
    }

    @Override
    public int verify(int seed, int rulesVersion, MinigameFinishRequest request) {
        validateInput(request);
        return verify(seed, rulesVersion, request.ticks(), request.picks()).score();
    }

    public void validateInput(Integer ticks, List<Integer> picks) {
        if (picks == null || picks.size() > MAX_PICKS) {
            throw invalidReplay();
        }
        // 틱은 시계가 아니라 집은 횟수. 한 번도 안 집고 마친 판도 ticks는 1임.
        if (ticks == null || ticks != Math.max(1, picks.size())) {
            throw invalidReplay();
        }
        for (Integer pile : picks) {
            if (pile == null || pile < 0 || pile >= PILES) {
                throw invalidReplay();
            }
        }
    }

    public Result verify(int seed, int rulesVersion, int ticks, List<Integer> picks) {
        validateInput(ticks, picks);
        if (seed < 1 || rulesVersion != RULES_VERSION) {
            throw invalidReplay();
        }
        Engine engine = new Engine(seed);
        for (int pile : picks) {
            // 엔진은 종료 뒤 입력을 기록하지 않으므로 끝난 뒤 덧붙인 집기는 조작된 기록임.
            if (engine.endReason != null) {
                throw invalidReplay();
            }
            engine.pick(pile);
        }
        // 입력을 다 썼는데 진행 중이면 사용자가 직접 마친 판(finish → 'saved').
        String endReason = engine.endReason != null ? engine.endReason : "saved";
        return new Result(engine.score, engine.cleared, endReason, engine.waiting.size(),
                engine.palette, engine.capacity);
    }

    private static BusinessException invalidReplay() {
        return new BusinessException(MinigameErrorCode.INVALID_REPLAY);
    }

    public record Result(int score, int cleared, String endReason, int waitingLength, int palette, int capacity) {
    }

    private static final class Engine {
        // JS의 (Math.imul(1664525, rng) + 1013904223) >>> 0 과 같도록 부호 없는 32비트를 long에 담음.
        private long rng;
        // 더미 하나의 길이는 최대 PILE_BASE + PILE_EXTRA - 1 = 4. 꼭대기 = 마지막 원소.
        private final int[][] piles = new int[PILES][PILE_BASE + PILE_EXTRA];
        private final int[] pileSizes = new int[PILES];
        private final int[] basketColors = new int[BASKETS];
        private final int[] basketCounts = new int[BASKETS];
        private final List<Integer> waiting = new ArrayList<>();
        private int preview;
        private int cleared;
        private int palette = BASE_PALETTE;
        private int capacity = BASE_CAPACITY;
        private int score;
        private int picks;
        private String endReason;

        // 초기 판 뽑기 순서: 더미 0..5(각각 길이 → 아래부터 공) → 바구니 0..2(앞 바구니와만 겹침 회피) → 미리보기(회피 없음).
        private Engine(int seed) {
            rng = seed & 0xffff_ffffL;
            for (int p = 0; p < PILES; p++) {
                newPile(p);
            }
            for (int s = 0; s < BASKETS; s++) {
                basketColors[s] = uniqueColor(draw(palette), s, s);
                basketCounts[s] = 0;
            }
            preview = draw(palette);
        }

        private long random() {
            rng = (1_664_525L * rng + 1_013_904_223L) & 0xffff_ffffL;
            return rng;
        }

        // 하위 비트는 쓰지 않음: (random() >>> 16) % n.
        private int draw(int n) {
            return (int) ((random() >>> 16) % n);
        }

        private void recompute() {
            palette = Math.min(MAX_COLORS, BASE_PALETTE + cleared / CLEARED_PER_COLOR);
            capacity = Math.min(MAX_CAPACITY, BASE_CAPACITY + cleared / CLEARED_PER_SLOT);
        }

        private void newPile(int index) {
            int length = PILE_BASE + draw(PILE_EXTRA);
            for (int i = 0; i < length; i++) {
                piles[index][i] = draw(palette);
            }
            pileSizes[index] = length;
        }

        // slot을 뺀 앞쪽 openCount개 바구니와 색이 겹치면 (color + 1) % palette로 밀어냄.
        // JS는 baskets.length만큼 보므로 초기 생성 중엔 이미 만든 바구니까지만, 그 뒤엔 세 바구니 전부 봄.
        private int uniqueColor(int color, int slot, int openCount) {
            while (true) {
                boolean clash = false;
                for (int b = 0; b < openCount; b++) {
                    if (b != slot && basketColors[b] == color) {
                        clash = true;
                    }
                }
                if (!clash) {
                    return color;
                }
                color = (color + 1) % palette;
            }
        }

        private int basketFor(int color) {
            for (int b = 0; b < BASKETS; b++) {
                if (basketColors[b] == color && basketCounts[b] < BASKET_CAP) {
                    return b;
                }
            }
            return -1;
        }

        // 바구니가 완성돼 교체됐으면 true. 교체 색은 새 palette로 다른 두 바구니와 겹침 회피 후 미리보기를 새로 뽑음.
        private boolean put(int b) {
            score += BALL_SCORE;
            basketCounts[b] += 1;
            if (basketCounts[b] < BASKET_CAP) {
                return false;
            }
            score += BASKET_SCORE;
            cleared += 1;
            recompute();
            basketColors[b] = uniqueColor(preview, b, BASKETS);
            basketCounts[b] = 0;
            preview = draw(palette);
            return true;
        }

        // 대기열을 앞에서부터 훑어 처음 맞는 공 하나를 옮기고 처음부터 다시 훑음. 한 바퀴 동안 못 옮기면 끝.
        private void autoFill() {
            boolean moved = true;
            while (moved) {
                moved = false;
                for (int w = 0; w < waiting.size(); w++) {
                    int b = basketFor(waiting.get(w));
                    if (b >= 0) {
                        waiting.remove(w);
                        put(b);
                        moved = true;
                        break;
                    }
                }
            }
        }

        private void pick(int i) {
            picks += 1;
            int color = piles[i][pileSizes[i] - 1];
            int b = basketFor(color);
            if (b >= 0) {
                pileSizes[i] -= 1;
                if (put(b)) {
                    autoFill();
                }
            } else if (waiting.size() >= capacity) {
                // 대기열이 가득 차면 공을 꺼내지 않고 종료. 집기는 기록되지만 보충·한도 검사는 하지 않음.
                endReason = "full";
                return;
            } else {
                pileSizes[i] -= 1;
                waiting.add(color);
            }
            if (pileSizes[i] == 0) {
                newPile(i);
            }
            if (picks >= MAX_PICKS) {
                endReason = "limit";
            }
        }
    }
}
