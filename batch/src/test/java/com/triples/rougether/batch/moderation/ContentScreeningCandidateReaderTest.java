package com.triples.rougether.batch.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.triples.rougether.domain.moderation.repository.ContentScreeningRepository;
import com.triples.rougether.domain.moderation.repository.ScreeningCandidate;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContentScreeningCandidateReaderTest {

    private final ContentScreeningRepository repository = mock(ContentScreeningRepository.class);
    private final Instant now = Instant.parse("2026-11-04T10:00:00Z");
    private final ContentScreeningCandidateReader reader = new ContentScreeningCandidateReader(repository,
            new ContentScreeningProperties("ts-test", "https://api.typesafe.ai", "jev-1.14.0", 0.6, 3, 7,
                    Duration.ofSeconds(5), 2, Duration.ofSeconds(1)));

    @Test
    void 글이_상한을_채우면_댓글은_조회하지_않는다() {
        when(repository.findPostCandidates(any(), eq(3))).thenReturn(List.of(
                mock(ScreeningCandidate.class), mock(ScreeningCandidate.class), mock(ScreeningCandidate.class)));

        assertThat(reader.read(now)).hasSize(3);
        verify(repository).findPostCandidates(now.minus(Duration.ofDays(7)), 3);
        verify(repository, never()).findCommentCandidates(any(), anyInt());
    }

    @Test
    void 남은_자리만큼만_댓글을_채운다() {
        when(repository.findPostCandidates(any(), eq(3))).thenReturn(List.of(mock(ScreeningCandidate.class)));
        when(repository.findCommentCandidates(any(), eq(2))).thenReturn(List.of(
                mock(ScreeningCandidate.class), mock(ScreeningCandidate.class)));

        assertThat(reader.read(now)).hasSize(3);
        verify(repository).findCommentCandidates(now.minus(Duration.ofDays(7)), 2);
    }

    @Test
    void 직전_실행_간격동안_창_밖으로_밀린_건을_센다() {
        Instant windowStart = now.minus(Duration.ofDays(7));
        when(repository.countUnscreenedCreatedBetween(windowStart.minus(Duration.ofMinutes(5)), windowStart))
                .thenReturn(2L);
        assertThat(reader.countDroppedSince(now, Duration.ofMinutes(5))).isEqualTo(2L);
    }
}
