package com.triples.rougether.userapi.feed.service;

import static com.triples.rougether.userapi.feed.error.FeedErrorCode.FEED_MINIGAME_RECORD_INVALID;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.minigame.entity.MinigameBestScore;
import com.triples.rougether.domain.minigame.entity.MinigameRun;
import com.triples.rougether.domain.minigame.repository.MinigameBestScoreRepository;
import com.triples.rougether.domain.minigame.repository.MinigameRunRepository;
import com.triples.rougether.userapi.feed.dto.FeedMinigameRecordRequest;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// 자유글에 연결할 미니게임 판을 검증하고 기록 카드 값을 서버 데이터로 고정함. 실패 사유(없는 판·남의 판·
// 끝나지 않은 판)는 판 존재 여부가 드러나지 않게 하나의 코드로 거절함.
@Component
@RequiredArgsConstructor
public class FeedMinigameRecords {
    private final MinigameRunRepository runs;
    private final MinigameBestScoreRepository bestScores;

    public record Snapshot(String runId, String gameCode, int score, Long rank, boolean personalBest, Instant achievedAt) { }

    public Snapshot snapshot(Long userId, FeedMinigameRecordRequest request) {
        // 완료된 판은 서버 재생 검증을 통과한 판만 존재함(검증 실패 제출은 완료 처리하지 않음)
        MinigameRun run = runs.findByIdAndUserId(request.runId(), userId)
                .filter(MinigameRun::isFinished)
                .orElseThrow(() -> new BusinessException(FEED_MINIGAME_RECORD_INVALID));
        MinigameBestScore best = bestScores.findByUserIdAndGameCode(userId, run.getGameCode()).orElse(null);
        // 순위는 랭킹 화면과 같은 기준(최고점보다 높은 활성 회원 수 + 1, 동점 공동 순위)
        Long rank = best == null ? null : bestScores.countHigherScores(run.getGameCode(), best.getScore()) + 1;
        boolean personalBest = best != null && best.getScore() == run.getScore();
        return new Snapshot(run.getId(), run.getGameCode(), run.getScore(), rank, personalBest, run.getFinishedAt());
    }
}
