package com.triples.rougether.userapi.minigame.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.userapi.minigame.dto.MinigameAction;
import com.triples.rougether.userapi.minigame.dto.MinigameDirection;
import com.triples.rougether.userapi.minigame.dto.MinigameFinishRequest;
import com.triples.rougether.userapi.minigame.error.MinigameErrorCode;
import java.util.Arrays;
import org.springframework.stereotype.Component;

/** 모바일과 동일한 정수 난수와 2048 이동 규칙으로 합치기 점수를 재생함. */
@Component
public class MergeReplayVerifier implements MinigameReplayVerifier {
    public static final int MAX_TICKS = 18_000;
    public static final int MAX_ACTIONS = 2_000;
    private static final int SIZE = 4;

    @Override
    public String gameCode() {
        return MinigameCatalog.MERGE;
    }

    @Override
    public int maxTicks() {
        return MAX_TICKS;
    }

    @Override
    public void validateInput(MinigameFinishRequest request) {
        if (request == null || request.ticks() == null
                || request.ticks() < 1 || request.ticks() > MAX_TICKS
                || request.jumpTicks() != null || request.actions() == null
                || request.actions().size() > MAX_ACTIONS) {
            throw invalidReplay();
        }
        int previous = 0;
        for (MinigameAction action : request.actions()) {
            if (action == null || action.tick() == null || action.direction() == null
                    || action.tick() <= previous || action.tick() > request.ticks()) {
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
        int[] board = new int[SIZE * SIZE];
        spawn(board, random);
        spawn(board, random);
        int score = 0;
        for (MinigameAction action : request.actions()) {
            if (!hasMoves(board)) {
                throw invalidReplay();
            }
            int[] before = board.clone();
            score += move(board, action.direction());
            if (Arrays.equals(before, board)) {
                throw invalidReplay();
            }
            spawn(board, random);
        }
        return score;
    }

    private static int move(int[] board, MinigameDirection direction) {
        int score = 0;
        for (int line = 0; line < SIZE; line++) {
            int[] compacted = new int[SIZE];
            int count = 0;
            for (int offset = 0; offset < SIZE; offset++) {
                int value = board[index(line, offset, direction)];
                if (value != 0) {
                    compacted[count++] = value;
                }
            }
            int[] merged = new int[SIZE];
            int target = 0;
            for (int source = 0; source < count; source++) {
                int value = compacted[source];
                if (source + 1 < count && value == compacted[source + 1]) {
                    value *= 2;
                    score += value;
                    source++;
                }
                merged[target++] = value;
            }
            for (int offset = 0; offset < SIZE; offset++) {
                board[index(line, offset, direction)] = merged[offset];
            }
        }
        return score;
    }

    private static int index(int line, int offset, MinigameDirection direction) {
        return switch (direction) {
            case LEFT -> line * SIZE + offset;
            case RIGHT -> line * SIZE + SIZE - 1 - offset;
            case UP -> offset * SIZE + line;
            case DOWN -> (SIZE - 1 - offset) * SIZE + line;
        };
    }

    private static boolean hasMoves(int[] board) {
        for (int index = 0; index < board.length; index++) {
            if (board[index] == 0
                    || (index % SIZE < SIZE - 1 && board[index] == board[index + 1])
                    || (index / SIZE < SIZE - 1 && board[index] == board[index + SIZE])) {
                return true;
            }
        }
        return false;
    }

    private static void spawn(int[] board, RandomState random) {
        int emptyCount = 0;
        for (int value : board) {
            if (value == 0) {
                emptyCount++;
            }
        }
        int position = random.next(emptyCount);
        int value = random.next(10) == 0 ? 4 : 2;
        for (int index = 0; index < board.length; index++) {
            if (board[index] == 0 && position-- == 0) {
                board[index] = value;
                return;
            }
        }
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
            return (int) ((state >>> 16) % bound);
        }
    }
}
