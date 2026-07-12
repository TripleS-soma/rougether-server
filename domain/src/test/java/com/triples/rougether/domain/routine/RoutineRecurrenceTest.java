package com.triples.rougether.domain.routine;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.routine.entity.AuthType;
import com.triples.rougether.domain.routine.entity.Routine;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class RoutineRecurrenceTest {

    private final User user = User.signUp();

    private Routine routine(String repeatType, String repeatDays, LocalDate startsOn, LocalDate endsOn) {
        return Routine.create(user, null, "title", AuthType.PHOTO,
                repeatType, repeatDays, null, startsOn, endsOn);
    }

    @Test
    void repeat_type이_DAILY면_기간_안이면_항상_대상이다() {
        Routine routine = routine("DAILY", null, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));

        assertThat(RoutineRecurrence.isTargetOn(routine, LocalDate.of(2026, 7, 12))).isTrue();
    }

    @Test
    void starts_on_이전이면_대상이_아니다() {
        Routine routine = routine("DAILY", null, LocalDate.of(2026, 7, 13), null);

        assertThat(RoutineRecurrence.isTargetOn(routine, LocalDate.of(2026, 7, 12))).isFalse();
    }

    @Test
    void ends_on_이후면_대상이_아니다() {
        Routine routine = routine("DAILY", null, null, LocalDate.of(2026, 7, 11));

        assertThat(RoutineRecurrence.isTargetOn(routine, LocalDate.of(2026, 7, 12))).isFalse();
    }

    @Test
    void repeat_type이_WEEKLY면_repeat_days에_포함된_요일만_대상이다() {
        Routine routine = routine("WEEKLY", "{\"daysOfWeek\":[\"MON\",\"WED\"]}", null, null);

        // 2026-07-12는 일요일, 2026-07-13은 월요일
        assertThat(RoutineRecurrence.isTargetOn(routine, LocalDate.of(2026, 7, 12))).isFalse();
        assertThat(RoutineRecurrence.isTargetOn(routine, LocalDate.of(2026, 7, 13))).isTrue();
    }

    @Test
    void repeat_days가_깨진_JSON이면_대상이_아니다() {
        Routine routine = routine("WEEKLY", "not-json", null, null);

        assertThat(RoutineRecurrence.isTargetOn(routine, LocalDate.of(2026, 7, 13))).isFalse();
    }

    @Test
    void repeat_type이_null이면_대상이_아니다() {
        Routine routine = routine(null, null, null, null);

        assertThat(RoutineRecurrence.isTargetOn(routine, LocalDate.of(2026, 7, 12))).isFalse();
    }
}
