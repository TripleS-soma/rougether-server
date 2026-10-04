package com.triples.rougether.userapi.feed.service;

import static com.triples.rougether.userapi.feed.error.FeedErrorCode.FEED_ROUTINE_COMPLETION_INVALID;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.entity.RoutineLogStatus;
import com.triples.rougether.domain.routine.repository.RoutineLogRepository;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import com.triples.rougether.userapi.feed.dto.FeedRoutineCompletionRequest;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// 인증글에 연결할 루틴 완료 기록을 검증함. 실패 사유(남의 루틴·없는 루틴·미완료·기간 밖)는 루틴 존재 여부가
// 드러나지 않게 하나의 코드로 거절함.
@Component
@RequiredArgsConstructor
public class FeedRoutineLinks {
    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    // KST 오늘과 그 이전 6일(총 7일)의 완료 기록만 연결할 수 있음
    static final int WINDOW_DAYS = 7;

    private final RoutineRepository routines;
    private final RoutineLogRepository logs;
    private final Clock clock;

    public record Link(Long routineId, LocalDate date, String title) { }

    public Link verify(Long userId, FeedRoutineCompletionRequest request) {
        LocalDate today = LocalDate.now(clock.withZone(KST));
        LocalDate date = request.date();
        if (date.isAfter(today) || date.isBefore(today.minusDays(WINDOW_DAYS - 1))) throw invalid();
        // 수정으로 갈린 옛 버전·삭제된 루틴 id도 본인 것이면 계보 기준으로 판정함(완료 로그는 완료 당시 버전 id에 남음)
        Routine routine = routines.findByIdAndUserId(request.routineId(), userId).orElseThrow(FeedRoutineLinks::invalid);
        Long lineage = routine.getOriginRoutineId() != null ? routine.getOriginRoutineId() : routine.getId();
        if (logs.findByLineageAndDateAndStatus(lineage, date, RoutineLogStatus.COMPLETED).isEmpty()) throw invalid();
        // 제목은 현재 살아 있는 버전 기준 스냅샷. 계보가 모두 삭제됐으면 요청한 버전의 제목을 씀
        String title = routines.findAliveByLineage(userId, lineage).map(Routine::getTitle).orElse(routine.getTitle());
        return new Link(routine.getId(), date, title);
    }

    private static BusinessException invalid() {
        return new BusinessException(FEED_ROUTINE_COMPLETION_INVALID);
    }
}
