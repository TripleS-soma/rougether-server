package com.triples.rougether.batch.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.triples.rougether.domain.moderation.repository.ContentReportRepository;
import com.triples.rougether.domain.moderation.repository.ContentScreeningRepository;
import com.triples.rougether.domain.moderation.repository.ScreeningCandidate;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContentScreeningCandidateReaderTest {

    private final ContentScreeningRepository screenings = mock(ContentScreeningRepository.class);
    private final ContentReportRepository reports = mock(ContentReportRepository.class);
    private final Instant now = Instant.parse("2026-11-04T10:00:00Z");
    private final ContentScreeningCandidateReader reader = new ContentScreeningCandidateReader(screenings, reports,
            new ContentScreeningProperties("ts-test", "https://api.typesafe.ai", "jev-1.14.0", 0.6, 3, 7,
                    Duration.ofSeconds(5), 2, Duration.ofSeconds(1)));

    private static ScreeningCandidate candidate(String type, long id) {
        ScreeningCandidate candidate = mock(ScreeningCandidate.class);
        when(candidate.getTargetType()).thenReturn(type);
        when(candidate.getTargetId()).thenReturn(id);
        return candidate;
    }

    @Test
    void 신고된_대상을_먼저_채우고_상한에_닿으면_새_글은_조회하지_않는다() {
        List<ScreeningCandidate> reportedPosts = List.of(candidate("FEED_POST", 1), candidate("FEED_POST", 2));
        List<ScreeningCandidate> reportedComments = List.of(candidate("FEED_COMMENT", 9));
        when(reports.findReportedPostCandidates(anyInt())).thenReturn(reportedPosts);
        when(reports.findReportedCommentCandidates(anyInt())).thenReturn(reportedComments);

        List<ScreeningTarget> targets = reader.read(now);

        assertThat(targets).hasSize(3).allMatch(ScreeningTarget::reported);
        verify(screenings, never()).findPostCandidates(any(), anyInt());
        verify(screenings, never()).findCommentCandidates(any(), anyInt());
    }

    @Test
    void 신고된_대상과_겹치는_새_글은_한_번만_신고된_대상으로_넣고_남은_자리를_채운다() {
        List<ScreeningCandidate> reportedPosts = List.of(candidate("FEED_POST", 1));
        List<ScreeningCandidate> recentPosts = List.of(candidate("FEED_POST", 1), candidate("FEED_POST", 5));
        List<ScreeningCandidate> recentComments = List.of(candidate("FEED_COMMENT", 7), candidate("FEED_COMMENT", 8));
        when(reports.findReportedPostCandidates(anyInt())).thenReturn(reportedPosts);
        when(reports.findReportedCommentCandidates(anyInt())).thenReturn(List.of());
        when(screenings.findPostCandidates(any(), anyInt())).thenReturn(recentPosts);
        when(screenings.findCommentCandidates(any(), anyInt())).thenReturn(recentComments);

        List<ScreeningTarget> targets = reader.read(now);

        assertThat(targets).extracting(ScreeningTarget::key)
                .containsExactly("FEED_POST:1", "FEED_POST:5", "FEED_COMMENT:7");
        assertThat(targets).extracting(ScreeningTarget::reported).containsExactly(true, false, false);
        verify(screenings).findPostCandidates(now.minus(Duration.ofDays(7)), 3);
    }

    @Test
    void 직전_실행_간격동안_창_밖으로_밀린_건을_센다() {
        Instant windowStart = now.minus(Duration.ofDays(7));
        when(screenings.countUnscreenedCreatedBetween(windowStart.minus(Duration.ofMinutes(5)), windowStart))
                .thenReturn(2L);
        assertThat(reader.countDroppedSince(now, Duration.ofMinutes(5))).isEqualTo(2L);
    }
}
