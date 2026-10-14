package com.triples.rougether.userapi.feed;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.minigame.entity.MinigameBestScore;
import com.triples.rougether.domain.minigame.entity.MinigameRun;
import com.triples.rougether.domain.minigame.repository.MinigameBestScoreRepository;
import com.triples.rougether.domain.minigame.repository.MinigameRunRepository;
import com.triples.rougether.userapi.feed.dto.FeedMinigameRecordRequest;
import com.triples.rougether.userapi.feed.service.FeedMinigameRecords;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FeedMinigameRecordsTest {
    static final String RUN_ID = "3f1c2a9e-6f4b-4f0e-9b7a-2d1c0e8a5b11";
    static final Instant FINISHED_AT = Instant.parse("2026-10-14T03:00:00Z");

    MinigameRunRepository runs = mock(MinigameRunRepository.class);
    MinigameBestScoreRepository bests = mock(MinigameBestScoreRepository.class);
    MinigameRun run = mock(MinigameRun.class);
    MinigameBestScore best = mock(MinigameBestScore.class);
    FeedMinigameRecords records = new FeedMinigameRecords(runs, bests);

    @BeforeEach void setUp() {
        when(run.getId()).thenReturn(RUN_ID);
        when(run.getGameCode()).thenReturn("cat-tower");
        when(run.getScore()).thenReturn(42);
        when(run.getFinishedAt()).thenReturn(FINISHED_AT);
        when(run.isFinished()).thenReturn(true);
        when(runs.findByIdAndUserId(RUN_ID, 7L)).thenReturn(Optional.of(run));
        when(best.getScore()).thenReturn(42);
        when(bests.findByUserIdAndGameCode(7L, "cat-tower")).thenReturn(Optional.of(best));
        when(bests.countHigherScores("cat-tower", 42)).thenReturn(4L);
    }

    @Test void 판의_검증점수와_작성자_최고점_기준_순위를_스냅샷한다() {
        assertThat(records.snapshot(7L, new FeedMinigameRecordRequest(RUN_ID)))
                .isEqualTo(new FeedMinigameRecords.Snapshot(RUN_ID, "cat-tower", 42, 5L, true, FINISHED_AT));
    }

    @Test void 최고점보다_낮은_판은_최고기록이_아니고_순위는_최고점_기준이다() {
        when(best.getScore()).thenReturn(90);
        when(bests.countHigherScores("cat-tower", 90)).thenReturn(0L);
        var snapshot = records.snapshot(7L, new FeedMinigameRecordRequest(RUN_ID));
        assertThat(snapshot.personalBest()).isFalse();
        assertThat(snapshot.rank()).isEqualTo(1L);
        assertThat(snapshot.score()).isEqualTo(42);
    }

    @Test void 최고점_행이_없으면_순위는_null이다() {
        when(bests.findByUserIdAndGameCode(7L, "cat-tower")).thenReturn(Optional.empty());
        var snapshot = records.snapshot(7L, new FeedMinigameRecordRequest(RUN_ID));
        assertThat(snapshot.rank()).isNull();
        assertThat(snapshot.personalBest()).isFalse();
    }

    @Test void 남의판_없는판_끝나지않은판은_같은코드로_거절한다() {
        when(runs.findByIdAndUserId(RUN_ID, 8L)).thenReturn(Optional.empty());
        assertInvalid(8L, RUN_ID);
        assertInvalid(7L, "missing");
        when(run.isFinished()).thenReturn(false);
        assertInvalid(7L, RUN_ID);
        verify(bests, never()).countHigherScores(anyString(), anyInt());
    }

    private void assertInvalid(Long userId, String runId) {
        assertThatThrownBy(() -> records.snapshot(userId, new FeedMinigameRecordRequest(runId)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode().code()).isEqualTo("FEED_MINIGAME_RECORD_INVALID"));
    }
}
