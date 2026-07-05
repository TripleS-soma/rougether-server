package com.triples.rougether.userapi.routine.event;

import java.time.LocalDate;

// 루틴 완료 도메인 이벤트. 단체 미션 기여 적립 등 다른 도메인이 완료에 반응할 때 구독한다.
public record RoutineCompletedEvent(Long userId, LocalDate routineDate) {
}
