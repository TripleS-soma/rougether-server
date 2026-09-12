package com.triples.rougether.userapi.minigame.service;

import com.triples.rougether.common.error.AuthErrorCode;
import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.minigame.entity.MinigameBestScore;
import com.triples.rougether.domain.minigame.entity.MinigameRun;
import com.triples.rougether.domain.minigame.repository.MinigameBestScoreRepository;
import com.triples.rougether.domain.minigame.repository.MinigameRunRepository;
import com.triples.rougether.userapi.minigame.dto.MinigameFinishRequest;
import com.triples.rougether.userapi.minigame.dto.MinigameFinishResponse;
import com.triples.rougether.userapi.minigame.dto.MinigameRunStartResponse;
import com.triples.rougether.userapi.minigame.error.MinigameErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class MinigameCommandService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration RUN_LIFETIME = Duration.ofMinutes(30);
    private final UserRepository userRepository;
    private final MinigameRunRepository runRepository;
    private final MinigameBestScoreRepository bestScoreRepository;
    private final MinigameCatalog catalog;
    private final MinigameReplayRegistry replayRegistry;
    private final Clock kstClock;

    public MinigameRunStartResponse start(Long userId, String gameCode) {
        catalog.requireGame(gameCode);
        MinigameReplayVerifier verifier = replayRegistry.get(gameCode);
        User user = lockActivePlayer(userId);
        Instant now = kstClock.instant();
        int seed = RANDOM.nextInt(Integer.MAX_VALUE) + 1;
        MinigameRun run = runRepository.save(MinigameRun.start(UUID.randomUUID().toString(), user,
                gameCode, verifier.rulesVersion(), seed, now, now.plus(RUN_LIFETIME)));
        return new MinigameRunStartResponse(run.getId(), gameCode, run.getRulesVersion(),
                seed, verifier.maxTicks(), run.getExpiresAt());
    }

    public MinigameFinishResponse finish(Long userId, String gameCode, String runId,
                                         MinigameFinishRequest request) {
        catalog.requireGame(gameCode);
        MinigameReplayVerifier verifier = replayRegistry.get(gameCode);
        // 사용자 락으로 첫 최고점 생성까지 직렬화하고 탈퇴 후 기록 재생성을 막음.
        User user = lockActivePlayer(userId);
        MinigameRun run = runRepository.findByIdAndUserIdAndGameCode(runId, userId, gameCode)
                .orElseThrow(() -> new BusinessException(MinigameErrorCode.RUN_NOT_FOUND));
        if (request == null) {
            throw new BusinessException(MinigameErrorCode.INVALID_REPLAY);
        }
        verifier.validateInput(request);
        String submissionHash = submissionHash(request);
        if (run.isFinished()) {
            if (!submissionHash.equals(run.getSubmissionHash())) {
                throw new BusinessException(MinigameErrorCode.RUN_ALREADY_FINISHED);
            }
            return receipt(run);
        }
        Instant now = kstClock.instant();
        if (!now.isBefore(run.getExpiresAt())) {
            throw new BusinessException(MinigameErrorCode.RUN_EXPIRED);
        }
        long allowedMillis = Duration.between(run.getStartedAt(), now).toMillis() + 2_000L;
        if (allowedMillis * MinigameReplayVerifier.TICKS_PER_SECOND < request.ticks() * 1_000L) {
            throw new BusinessException(MinigameErrorCode.RUN_TOO_EARLY);
        }
        int score = verifier.verify(run.getSeed(), run.getRulesVersion(), request);
        MinigameBestScore best = bestScoreRepository.findByUserIdAndGameCode(userId, gameCode).orElse(null);
        boolean personalBest = best == null || score > best.getScore();
        if (best == null) {
            best = bestScoreRepository.save(MinigameBestScore.create(user, gameCode, run.getRulesVersion(), score, now));
        } else if (personalBest) {
            best.improve(score, run.getRulesVersion(), now);
        }
        // 쓰기 결과를 쿼리 전에 반영해 같은 트랜잭션의 최고점과 순위가 맞도록 함.
        bestScoreRepository.flush();
        long rank = bestScoreRepository.countHigherScores(gameCode, best.getScore()) + 1;
        run.finish(request.ticks(), score, submissionHash, best.getScore(), personalBest, rank, now);
        return receipt(run);
    }

    private User lockActivePlayer(Long userId) {
        return userRepository.findByIdForUpdate(userId)
                .filter(user -> !user.isDeleted() && !user.isBot())
                .orElseThrow(() -> new BusinessException(AuthErrorCode.USER_NOT_FOUND));
    }

    private static MinigameFinishResponse receipt(MinigameRun run) {
        return new MinigameFinishResponse(run.getId(), run.getScore(), run.getFinishedBestScore(),
                run.getPersonalBest(), run.getFinishedRank());
    }

    private static String submissionHash(MinigameFinishRequest request) {
        StringBuilder canonical = new StringBuilder().append(request.ticks()).append(':');
        if (request.jumpTicks() != null) {
            // 러너의 기존 digest 형식을 유지해 업데이트 전 기록의 재전송도 동일하게 처리함.
            request.jumpTicks().forEach(tick -> canonical.append(tick).append(','));
        } else {
            canonical.append("actions:");
            request.actions().forEach(action -> canonical.append(action.tick()).append('=')
                    .append(action.direction().name()).append(','));
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
        }
    }
}
