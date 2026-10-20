package com.triples.rougether.userapi.agenda;

import com.triples.rougether.domain.routine.RoutineRecurrence;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.entity.RoutineLogStatus;
import com.triples.rougether.domain.routine.repository.RoutineDayLogRow;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

// 오늘·미래의 현재 ACTIVE 루틴에서 표시 대상과 완료 여부를 판정함
@Component
public class LiveRoutineSelector {

    public static final List<RoutineLogStatus> LOG_STATUSES =
            List.of(RoutineLogStatus.COMPLETED, RoutineLogStatus.SKIPPED);

    // 사용자 소유·ACTIVE·미삭제 조건으로 조회한 후보에서 기준일 반복 대상만 추림
    // 빈 대상이면 호출부에서 로그 조회를 생략할 수 있음
    public List<Routine> targetsOn(List<Routine> activeCandidates, LocalDate date) {
        return activeCandidates.stream()
                .filter(routine -> RoutineRecurrence.isTargetOn(routine, date))
                .toList();
    }

    // 같은 날짜의 대상과 로그를 받음. 건너뜀은 계보 전체에, 완료는 기록된 버전 ID에만 적용함
    public Selection applyDayLogs(List<Routine> targets, List<RoutineDayLogRow> dayLogs) {
        Set<Long> skippedLineages = new HashSet<>();
        Set<Long> completedRoutineIds = new HashSet<>();
        for (RoutineDayLogRow row : dayLogs) {
            if (row.getStatus() == RoutineLogStatus.SKIPPED) {
                skippedLineages.add(row.getLineageKey());
            } else if (row.getStatus() == RoutineLogStatus.COMPLETED) {
                completedRoutineIds.add(row.getRoutineId());
            }
        }
        List<Routine> routines = targets.stream()
                .filter(routine -> !skippedLineages.contains(lineageKey(routine)))
                .toList();
        return new Selection(routines, Set.copyOf(completedRoutineIds));
    }

    // origin 미백필 row는 자기 ID가 계보 키임. 조회 projection의 coalesce와 같은 기준임
    private static Long lineageKey(Routine routine) {
        return routine.getOriginRoutineId() != null ? routine.getOriginRoutineId() : routine.getId();
    }

    public record Selection(List<Routine> routines, Set<Long> completedRoutineIds) {
    }
}
