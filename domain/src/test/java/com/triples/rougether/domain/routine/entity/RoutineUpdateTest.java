package com.triples.rougether.domain.routine.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.domain.member.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RoutineUpdateTest {

    private static Routine weekly() {
        return Routine.create(User.signUp(), null, "운동", AuthType.CHECK,
                "WEEKLY", "{\"daysOfWeek\":[\"MON\",\"WED\"]}", null, null, null);
    }

    @Test
    @DisplayName("WEEKLY→DAILY로 바꾸면 옛 repeat_days를 지운다 (mobile #373-④)")
    void dailyClearsRepeatDays() {
        Routine routine = weekly();

        routine.update(null, null, "DAILY", null, null, null, null);

        assertThat(routine.getRepeatType()).isEqualTo("DAILY");
        assertThat(routine.getRepeatDays()).isNull();
    }

    @Test
    @DisplayName("repeatType을 안 보내면(null) 기존 규칙과 repeat_days를 그대로 둔다")
    void keepsRepeatDaysWhenTypeUnchanged() {
        Routine routine = weekly();

        routine.update("운동 30분", null, null, null, null, null, null);

        assertThat(routine.getRepeatType()).isEqualTo("WEEKLY");
        assertThat(routine.getRepeatDays()).contains("MON");
    }

    @Test
    @DisplayName("DAILY→WEEKLY로 바꾸면 새 repeat_days가 들어간다")
    void weeklySetsRepeatDays() {
        Routine routine = Routine.create(User.signUp(), null, "물 마시기", AuthType.CHECK,
                "DAILY", null, null, null, null);

        routine.update(null, null, "WEEKLY", "{\"daysOfWeek\":[\"FRI\"]}", null, null, null);

        assertThat(routine.getRepeatDays()).contains("FRI");
    }
}
