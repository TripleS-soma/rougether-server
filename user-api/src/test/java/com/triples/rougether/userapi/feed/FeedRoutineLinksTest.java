package com.triples.rougether.userapi.feed;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.entity.RoutineLog;
import com.triples.rougether.domain.routine.entity.RoutineLogStatus;
import com.triples.rougether.domain.routine.repository.RoutineLogRepository;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import com.triples.rougether.userapi.feed.dto.FeedRoutineCompletionRequest;
import com.triples.rougether.userapi.feed.service.FeedRoutineLinks;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FeedRoutineLinksTest {
    // UTC 2026-10-03 15:00 = KST 2026-10-04 00:00. UTC 날짜로 판정하면 하루씩 밀림
    static final Clock KST_MIDNIGHT = Clock.fixed(Instant.parse("2026-10-03T15:00:00Z"), ZoneOffset.UTC);
    static final LocalDate KST_TODAY = LocalDate.of(2026, 10, 4);

    RoutineRepository routines = mock(RoutineRepository.class);
    RoutineLogRepository logs = mock(RoutineLogRepository.class);
    Routine routine = mock(Routine.class);
    FeedRoutineLinks links = new FeedRoutineLinks(routines, logs, KST_MIDNIGHT);

    @BeforeEach void setUp() {
        when(routine.getId()).thenReturn(30L);
        when(routine.getOriginRoutineId()).thenReturn(10L);
        when(routine.getTitle()).thenReturn("옛 제목");
        when(routines.findByIdAndUserId(30L, 7L)).thenReturn(Optional.of(routine));
        when(logs.findByLineageAndDateAndStatus(eq(10L), any(), eq(RoutineLogStatus.COMPLETED)))
                .thenReturn(List.of(mock(RoutineLog.class)));
        when(routines.findAliveByLineage(7L, 10L)).thenReturn(Optional.empty());
    }

    @Test void KST_오늘부터_6일전까지만_연결하고_미래와_7일전은_거절한다() {
        assertThat(links.verify(7L, new FeedRoutineCompletionRequest(30L, KST_TODAY)).date()).isEqualTo(KST_TODAY);
        assertThat(links.verify(7L, new FeedRoutineCompletionRequest(30L, KST_TODAY.minusDays(6))).date())
                .isEqualTo(KST_TODAY.minusDays(6));
        for (LocalDate outside : List.of(KST_TODAY.plusDays(1), KST_TODAY.minusDays(7))) {
            assertThatThrownBy(() -> links.verify(7L, new FeedRoutineCompletionRequest(30L, outside)))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode().code()).isEqualTo("FEED_ROUTINE_COMPLETION_INVALID"));
        }
    }

    @Test void 계보의_완료기록으로_판정하고_살아있는_버전_제목을_스냅샷한다() {
        Routine alive = mock(Routine.class);
        when(alive.getTitle()).thenReturn("현재 제목");
        when(routines.findAliveByLineage(7L, 10L)).thenReturn(Optional.of(alive));
        var link = links.verify(7L, new FeedRoutineCompletionRequest(30L, KST_TODAY));
        assertThat(link).isEqualTo(new FeedRoutineLinks.Link(30L, KST_TODAY, "현재 제목"));
        verify(logs).findByLineageAndDateAndStatus(10L, KST_TODAY, RoutineLogStatus.COMPLETED);
    }

    @Test void 완료기록이_없거나_본인루틴이_아니면_같은코드로_거절한다() {
        when(logs.findByLineageAndDateAndStatus(eq(10L), eq(KST_TODAY.minusDays(1)), any())).thenReturn(List.of());
        assertThatThrownBy(() -> links.verify(7L, new FeedRoutineCompletionRequest(30L, KST_TODAY.minusDays(1))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("루틴 완료 기록");
        assertThatThrownBy(() -> links.verify(8L, new FeedRoutineCompletionRequest(30L, KST_TODAY)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("루틴 완료 기록");
    }
}
