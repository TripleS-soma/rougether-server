package com.triples.rougether.batch.moderation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.triples.rougether.batch.alert.BatchFailureAlertNotifier;
import com.triples.rougether.domain.moderation.repository.ScreeningCandidate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContentScreeningTriggerTest {

    private final ContentScreeningCandidateReader reader = mock(ContentScreeningCandidateReader.class);
    private final JevClient jev = mock(JevClient.class);
    private final ContentScreeningRecorder recorder = mock(ContentScreeningRecorder.class);
    private final BatchFailureAlertNotifier notifier = mock(BatchFailureAlertNotifier.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-04T10:00:00Z"), ZoneId.of("Asia/Seoul"));

    private ContentScreeningTrigger trigger(String apiKey) {
        ContentScreeningProperties properties = new ContentScreeningProperties(apiKey, "https://api.typesafe.ai",
                "jev-1.14.0", 0.6, 50, 7, Duration.ofSeconds(5), 2, Duration.ofSeconds(1));
        return new ContentScreeningTrigger(properties, reader, jev, recorder, notifier, clock);
    }

    private static ScreeningCandidate candidate(long id) {
        ScreeningCandidate candidate = mock(ScreeningCandidate.class);
        when(candidate.getTargetType()).thenReturn("FEED_POST");
        when(candidate.getTargetId()).thenReturn(id);
        when(candidate.getContent()).thenReturn("본문" + id);
        return candidate;
    }

    @Test
    void 키가_없으면_후보_조회도_외부_호출도_하지_않는다() {
        trigger("").screen();
        verifyNoInteractions(reader, jev, recorder, notifier);
    }

    @Test
    void 인증_실패면_남은_후보를_호출하지_않고_운영_알림을_한_번_보낸다() {
        List<ScreeningCandidate> candidates = List.of(candidate(1), candidate(2));
        when(reader.read(any())).thenReturn(candidates);
        when(jev.classify(anyString(), any())).thenThrow(new JevAuthException("Jev 인증 실패 status=401", null));

        trigger("ts-test").screen();

        verify(jev, times(1)).classify(anyString(), any());
        verify(notifier).notifyFailure(eq("content-screening-auth"), anyString(), anyString());
        verifyNoInteractions(recorder);
    }

    @Test
    void 재시도를_소진한_장애가_3건_연속이면_그_실행을_중단한다() {
        List<ScreeningCandidate> candidates = List.of(candidate(1), candidate(2), candidate(3), candidate(4));
        when(reader.read(any())).thenReturn(candidates);
        when(jev.classify(anyString(), any())).thenThrow(new JevException("Jev 오류 status=503", true));

        trigger("ts-test").screen();

        verify(jev, times(3)).classify(anyString(), any());
        verify(jev, never()).classify(eq("본문4"), any());
        verifyNoInteractions(recorder, notifier);
    }

    @Test
    void 일반_호출_실패는_그_건만_건너뛰고_다음_후보를_판정한다() {
        ScreeningCandidate first = candidate(1);
        ScreeningCandidate second = candidate(2);
        when(reader.read(any())).thenReturn(List.of(first, second));
        when(jev.classify(eq("본문1"), any())).thenThrow(new JevException("Jev 오류 status=400", false));
        when(jev.classify(eq("본문2"), any())).thenReturn(new JevVerdict("jev-1.14.0",
                java.util.Map.of("SPAM", 0.0, "ABUSE", 0.0, "SEXUAL", 0.0, "VIOLENCE", 0.0, "PERSONAL_INFO", 0.0,
                        "NONE", 1.0), 10));

        trigger("ts-test").screen();

        verify(recorder, never()).record(eq(first), anyString(), any(), any(), any());
        verify(recorder).record(eq(second), eq(ContentScreeningTrigger.sha256("본문2")), any(), any(), any());
        verifyNoInteractions(notifier);
    }
}
