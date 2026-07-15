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

    @Test
    void repeat_type이_BIWEEKLY면_starts_on_기준_2주_간격의_해당_요일만_대상이다() {
        // starts_on 2026-07-13(월)을 1주차로 삼음
        Routine routine = routine("BIWEEKLY", "{\"daysOfWeek\":[\"MON\"]}",
                LocalDate.of(2026, 7, 13), null);

        assertThat(RoutineRecurrence.isTargetOn(routine, LocalDate.of(2026, 7, 13))).isTrue(); // 1주차(0)
        assertThat(RoutineRecurrence.isTargetOn(routine, LocalDate.of(2026, 7, 20))).isFalse(); // 2주차(1, 홀수)
        assertThat(RoutineRecurrence.isTargetOn(routine, LocalDate.of(2026, 7, 27))).isTrue(); // 3주차(2)
    }

    @Test
    void repeat_type이_BIWEEKLY면_요일이_맞아도_2주_간격이_아니면_대상이_아니다() {
        Routine routine = routine("BIWEEKLY", "{\"daysOfWeek\":[\"WED\"]}",
                LocalDate.of(2026, 7, 15), null);

        assertThat(RoutineRecurrence.isTargetOn(routine, LocalDate.of(2026, 7, 22))).isFalse();
    }

    @Test
    void repeat_type이_BIWEEKLY인데_starts_on이_없으면_대상이_아니다() {
        Routine routine = routine("BIWEEKLY", "{\"daysOfWeek\":[\"MON\"]}", null, null);

        assertThat(RoutineRecurrence.isTargetOn(routine, LocalDate.of(2026, 7, 13))).isFalse();
    }

    @Test
    void repeat_type이_MONTHLY면_repeat_days의_dayOfMonth와_일치하는_날만_대상이다() {
        Routine routine = routine("MONTHLY", "{\"dayOfMonth\":15}", null, null);

        assertThat(RoutineRecurrence.isTargetOn(routine, LocalDate.of(2026, 7, 15))).isTrue();
        assertThat(RoutineRecurrence.isTargetOn(routine, LocalDate.of(2026, 7, 16))).isFalse();
    }

    @Test
    void repeat_type이_MONTHLY면_해당_월에_없는_날짜는_그_달에_대상이_없다() {
        Routine routine = routine("MONTHLY", "{\"dayOfMonth\":31}", null, null);

        assertThat(RoutineRecurrence.isTargetOn(routine, LocalDate.of(2026, 1, 31))).isTrue();
        assertThat(RoutineRecurrence.isTargetOn(routine, LocalDate.of(2026, 2, 28))).isFalse(); // 2월엔 31일 없음
    }

    @Test
    void repeat_type이_YEARLY면_repeat_days의_month_day와_일치하는_날만_대상이다() {
        Routine routine = routine("YEARLY", "{\"month\":7,\"day\":12}", null, null);

        assertThat(RoutineRecurrence.isTargetOn(routine, LocalDate.of(2026, 7, 12))).isTrue();
        assertThat(RoutineRecurrence.isTargetOn(routine, LocalDate.of(2027, 7, 12))).isTrue();
        assertThat(RoutineRecurrence.isTargetOn(routine, LocalDate.of(2026, 7, 13))).isFalse();
    }

    @Test
    void repeat_type이_YEARLY면_평년의_2월29일_지정은_그_해에_대상이_없다() {
        Routine routine = routine("YEARLY", "{\"month\":2,\"day\":29}", null, null);

        // 2026년은 평년(2월 28일까지)
        assertThat(RoutineRecurrence.isTargetOn(routine, LocalDate.of(2026, 2, 28))).isFalse();
        assertThat(RoutineRecurrence.isTargetOn(routine, LocalDate.of(2026, 3, 1))).isFalse();
        // 2028년은 윤년
        assertThat(RoutineRecurrence.isTargetOn(routine, LocalDate.of(2028, 2, 29))).isTrue();
    }
}
