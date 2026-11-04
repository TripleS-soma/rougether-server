package com.triples.rougether.batch.moderation;

import com.triples.rougether.batch.alert.BatchFailureAlertNotifier;
import com.triples.rougether.domain.moderation.repository.ScreeningCandidate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 피드 글·댓글 자동 유해 판정(#478). 5분마다 후보를 Jev 로 판정하고 기준 이상이면 시스템 신고를 남김.
// 작성 경로와 분리된 비동기 처리라 실패는 다음 실행이 다시 집음. 글을 숨기거나 지우지 않음(운영자가 처리).
// 키가 비면 아무것도 하지 않음. 401·403 은 그 실행을 중단하고 운영 알림(쿨다운)을 보냄.
@Slf4j
@Component
@RequiredArgsConstructor
public class ContentScreeningTrigger {

    static final Duration INTERVAL = Duration.ofMinutes(5);
    static final int MAX_CONSECUTIVE_OUTAGES = 3;
    private static final String AUTH_ALERT_KEY = "content-screening-auth";

    private final ContentScreeningProperties properties;
    private final ContentScreeningCandidateReader reader;
    private final JevClient jev;
    private final ContentScreeningRecorder recorder;
    private final BatchFailureAlertNotifier alertNotifier;
    private final Clock clock;

    // 5분 주기의 나머지 1 분(6,11,…,56). 매시 1분(저녁 미완료 알림)은 피함 — SchedulingConfig 배정표 참고.
    @Scheduled(cron = "0 6/5 * * * *", zone = "Asia/Seoul")
    public void screen() {
        if (!properties.enabled()) {
            return;
        }
        Instant now = clock.instant();
        List<ScreeningTarget> candidates = reader.read(now);
        int flagged = 0;
        int failed = 0;
        int consecutiveOutages = 0;
        for (ScreeningTarget target : candidates) {
            ScreeningCandidate candidate = target.candidate();
            JevVerdict verdict;
            try {
                verdict = jev.classify(candidate.getContent(), candidate.getContext());
            } catch (JevAuthException e) {
                log.error("자동 유해 판정 중단 — Jev 인증 실패: {}", e.getMessage());
                alertNotifier.notifyFailure(AUTH_ALERT_KEY, "자동 유해 판정 인증 실패",
                        "TYPESAFE_API_KEY 확인 필요: " + e.getMessage());
                return;
            } catch (JevException e) {
                failed++;
                log.warn("자동 유해 판정 건너뜀 - {}:{} — {}", candidate.getTargetType(), candidate.getTargetId(),
                        e.getMessage());
                // 재시도까지 소진한 장애가 연속되면 남은 후보도 실패할 가능성이 높음 — 스케줄러 스레드를 오래 잡지 않게 중단
                consecutiveOutages = e.isRetryable() ? consecutiveOutages + 1 : 0;
                if (consecutiveOutages >= MAX_CONSECUTIVE_OUTAGES) {
                    log.warn("자동 유해 판정 중단 — Jev 장애가 {}건 연속, 다음 실행에서 재시도", consecutiveOutages);
                    break;
                }
                continue;
            }
            consecutiveOutages = 0;
            try {
                ScreeningJudgement judgement = ScreeningJudgement.of(verdict.probabilities(),
                        properties.flagThreshold());
                recorder.record(target, sha256(candidate.getContent()), verdict, judgement, now);
                if (judgement.flagged()) {
                    flagged++;
                }
            } catch (RuntimeException e) {
                failed++;
                log.warn("자동 유해 판정 저장 실패 - {}:{}", candidate.getTargetType(), candidate.getTargetId(), e);
            }
        }
        long dropped = reader.countDroppedSince(now, INTERVAL);
        if (dropped > 0) {
            log.warn("자동 유해 판정 창({}일)을 지나 판정되지 못한 글·댓글 {}건", properties.windowDays(), dropped);
        }
        if (!candidates.isEmpty()) {
            log.info("자동 유해 판정 완료 - 후보 {}건, 기준 이상 {}건, 실패 {}건", candidates.size(), flagged, failed);
        }
    }

    // MySQL SHA2(content, 256)(utf8mb4 바이트)와 같은 값이 되도록 UTF-8 바이트로 계산함.
    static String sha256(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 미지원", e);
        }
    }
}
